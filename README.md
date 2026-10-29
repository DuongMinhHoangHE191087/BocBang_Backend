# BocBang Backend

Backend Spring Boot chạy trên board MediaTek G520 (ARMv8.2-A CPU), tích hợp trực tiếp **C++ JNI** (`native/`) để nạp và chạy 4 model GGML/GGUF ngay trong bộ nhớ mà không cần gọi tiến trình CLI bên ngoài.

## Kiến trúc C++ JNI & 4 Model

Mỗi thư viện C++ (`transcribe.cpp`, `whisper.cpp`, `llama.cpp`) đi kèm phiên bản `ggml` riêng, nên module `native/` biên dịch thành **3 file `.so` độc lập** có ẩn toàn bộ symbol nội bộ (`exports.map` + `-fvisibility=hidden`) để nạp chung vào cùng một JVM không xung đột:

| Thư viện JNI | Engine C++ | Model phụ trách | Vai trò |
|---|---|---|---|
| `libbocbang_whisper.so` | `whisper.cpp` | `ggml-silero-vad.bin` + `whisper-small-en.bin` | Tách đoạn tiếng nói (Silero VAD) & ASR tiếng Anh (`whisper-en`) |
| `libbocbang_transcribe.so` | `transcribe.cpp` | `qwen3-asr-1.7b-q4_k_m.gguf` | ASR đa ngôn ngữ / tiếng Việt (`qwen3-asr`) |
| `libbocbang_llama.so` | `llama.cpp` | `qwen3-4b-instruct-2507-q4_k_m.gguf` | Thêm dấu câu, viết hoa, sửa lỗi nhận dạng (Refiner) |

Tất cả model nhận âm thanh **16 kHz, 16-bit, mono** (chuyển sang `float[]` chuẩn hóa `[-1.0, 1.0)` ngay trong Java trước khi truyền qua JNI).

## Luồng xử lý file dài (5 phút – 1 giờ)

1. Client gửi file (WAV, MP3, M4A, OGG, FLAC...) vào `POST /api/stt/jobs`. File ghi thẳng xuống đĩa, trả ngay `202 Accepted` kèm `jobId`.
2. Worker chạy nền:
   - `CONVERTING`: `ffmpeg` chuyển mọi định dạng về WAV 16 kHz 16-bit mono (nếu đã đúng chuẩn thì bỏ qua `ffmpeg`).
   - `DETECTING_SPEECH`: quét Silero VAD qua JNI theo từng cửa sổ 5 phút (~19 MB heap) để tìm các đoạn có tiếng nói, gộp các đoạn gần nhau và cắt tối đa `30s`/đoạn.
   - `TRANSCRIBING`: nạp model ASR (`qwen3-asr` hoặc `whisper-en`) **một lần duy nhất** vào RAM native, đọc từng đoạn `float[]` trực tiếp từ file WAV và gọi JNI. Cập nhật `progress` và `segments` sau mỗi đoạn.
   - `REFINING`: nếu `stt.unload-before-refine: true` (mặc định), giải phóng ~1.3 GB của model ASR trước khi nạp ~2.5 GB của `qwen3-4b-instruct`, chia văn bản thành từng khúc (`max-batch-chars`) để sửa dấu câu & lỗi từ, sau đó giải phóng RAM.
3. Nếu client gọi `DELETE /api/stt/jobs/{jobId}` hoặc quá `timeout`, `CpuExecutionGate` bật cờ `abort` nguyên tử trong C++ để dừng ngay đồ thị `ggml` đang chạy.

## Biên dịch thư viện C++ JNI (`native/`)

```bash
# Biên dịch cho Android ARM64 (MediaTek G520 / Termux openjdk-17, dùng Android NDK armv8.2-a+dotprod+fp16)
./scripts/build-native.sh android-arm64

# Biên dịch cho Linux ARM64 glibc (nếu chạy trong Debian/Ubuntu chroot trên board)
./scripts/build-native.sh linux-arm64

# Biên dịch cho máy PC hiện tại (Linux x86_64) để test trực tiếp trên máy phát triển
./scripts/build-native.sh host
```

Kết quả `.so` nằm tại `native/dist/<target>/`. Có thể truyền biến môi trường `TRANSCRIBE_CPP_DIR`, `WHISPER_CPP_DIR`, `LLAMA_CPP_DIR`, `ANDROID_NDK_HOME` nếu đặt mã nguồn C++ ở đường dẫn khác.

## Đẩy lên board MediaTek G520 và chạy

1. Cài OpenJDK 17 và `ffmpeg` trên board (ví dụ trong Termux: `pkg install openjdk-17 ffmpeg`).
2. Cắm cáp ADB và chạy script đóng gói + đẩy tự động:

```bash
# Đẩy JAR + 3 file .so (thêm PUSH_MODELS=/workspace/MeetingNotes-models nếu muốn đẩy cả 4 model)
PUSH_MODELS=/workspace/MeetingNotes-models ./scripts/deploy-board.sh

# Chạy server trên board
adb shell /data/local/tmp/bocbang/run.sh
```

Lưu ý trên Android: các file `.so` và script thực thi phải đặt trong `/data/local/tmp/...` hoặc `$HOME` của Termux (không đặt trên `/sdcard` vì phân vùng đó mount `noexec`).

## API

### REST

| Method | Đường dẫn | Mô tả |
|---|---|---|
| GET | `/api/health` | Kiểm tra server còn sống |
| GET | `/api/stt/status` | Model mặc định, danh sách model, trạng thái refiner, sample rate |
| POST | `/api/stt/jobs` | Tạo job cho file âm thanh dài (`?model=qwen3-asr\|whisper-en&refine=true`), trả `202` |
| GET | `/api/stt/jobs/{jobId}` | Xem tiến độ (`0.0..1.0`), các đoạn đã xong và văn bản cuối cùng |
| DELETE | `/api/stt/jobs/{jobId}` | Hủy job đang chạy (bật cờ abort trong C++) và xóa file tạm |
| POST | `/api/stt/transcriptions` | Nhận dạng đồng bộ một file WAV 16 kHz 16-bit mono ngắn |

```bash
curl -F "file=@meeting.m4a" "http://localhost:8080/api/stt/jobs?model=qwen3-asr&refine=true"
curl http://localhost:8080/api/stt/jobs/<jobId>
```

### WebSocket: `ws://<host>:8080/ws/stt`

- **Binary frame**: PCM 16-bit little-endian, mono, 16 kHz.
- **Text frame** `{"type":"end"}`: kết thúc câu, chạy JNI trên model mặc định và trả `{"type":"final","text":"..."}`.

## Kiểm tra trên PC

```bash
./scripts/build-native.sh host
STT_MODEL_DIR=/workspace/MeetingNotes-models ./gradlew bootRun
./gradlew test
```
