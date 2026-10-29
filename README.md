# BocBang Backend

Backend Spring Boot chạy trên board MediaTek G520 (ARMv8.2-A CPU), tích hợp trực tiếp **C++ JNI** (`native/`) để nạp và chạy 4 model GGML/GGUF ngay trong bộ nhớ mà không cần gọi tiến trình CLI bên ngoài. Nhận âm thanh qua WebSocket và REST, trả văn bản về client, phục vụ nhiều người dùng cùng lúc trên một bộ thread dùng chung có giới hạn tải, kèm health probe, metrics và trang web demo.

Model AI cắm vào qua interface `SpeechToTextEngine` (xem mục "Cắm model AI khác"). Engine mặc định là `jni`; engine `mock` chạy được khi chưa có model.

## Kiến trúc C++ JNI & 4 Model

Mỗi thư viện C++ (`transcribe.cpp`, `whisper.cpp`, `llama.cpp`) đi kèm phiên bản `ggml` riêng, nên module `native/` biên dịch thành **3 file `.so` độc lập** có ẩn toàn bộ symbol nội bộ (`exports.map` + `-fvisibility=hidden`) để nạp chung vào cùng một JVM không xung đột:

| Thư viện JNI | Engine C++ | Model phụ trách | Vai trò |
|---|---|---|---|
| `libbocbang_whisper.so` | `whisper.cpp` | `ggml-silero-vad.bin` + `whisper-small-en.bin` | Tách đoạn tiếng nói (Silero VAD) & ASR tiếng Anh (`whisper-en`) |
| `libbocbang_transcribe.so` | `transcribe.cpp` | `qwen3-asr-1.7b-q4_k_m.gguf` | ASR đa ngôn ngữ / tiếng Việt (`qwen3-asr`) |
| `libbocbang_llama.so` | `llama.cpp` | `qwen3-4b-instruct-2507-q4_k_m.gguf` | Thêm dấu câu, viết hoa, sửa lỗi nhận dạng (Refiner) |

Tất cả model nhận âm thanh **16 kHz, 16-bit, mono** (chuyển sang `float[]` chuẩn hóa `[-1.0, 1.0)` ngay trong Java trước khi truyền qua JNI).

## Luồng xử lý file dài (5 phút – 1 giờ)

1. Client gửi file (WAV, MP3, M4A, OGG, FLAC...) vào `POST /api/v1/stt/jobs`. File ghi thẳng xuống đĩa, trả ngay `202 Accepted` kèm `jobId`.
2. Worker chạy nền:
   - `CONVERTING`: `ffmpeg` chuyển mọi định dạng về WAV 16 kHz 16-bit mono (nếu đã đúng chuẩn thì bỏ qua `ffmpeg`).
   - `DETECTING_SPEECH`: quét Silero VAD qua JNI theo từng cửa sổ 5 phút (~19 MB heap) để tìm các đoạn có tiếng nói, gộp các đoạn gần nhau và cắt tối đa `30s`/đoạn.
   - `TRANSCRIBING`: nạp model ASR (`qwen3-asr` hoặc `whisper-en`) **một lần duy nhất** vào RAM native, đọc từng đoạn `float[]` trực tiếp từ file WAV và gọi JNI. Cập nhật `progress` và `segments` sau mỗi đoạn.
   - `REFINING`: nếu `stt.unload-before-refine: true` (mặc định), giải phóng ~1.3 GB của model ASR trước khi nạp ~2.5 GB của `qwen3-4b-instruct`, chia văn bản thành từng khúc (`max-batch-chars`) để sửa dấu câu & lỗi từ, sau đó giải phóng RAM.
3. Nếu client gọi `DELETE /api/v1/stt/jobs/{jobId}` hoặc quá `timeout`, `CpuExecutionGate` bật cờ `abort` nguyên tử trong C++ để dừng ngay đồ thị `ggml` đang chạy.

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

## Chạy trên PC

```bash
./scripts/build-native.sh host
STT_MODEL_DIR=/workspace/MeetingNotes-models ./gradlew bootRun
```

Chưa có `.so` hay model? Chạy engine giả (mỗi giây âm thanh sinh ra vài từ tiếng Việt có sẵn) để xem demo và thử tải:

```
set STT_ENGINE=mock && gradlew.bat bootRun           # Windows cmd
STT_ENGINE=mock ./gradlew bootRun                    # Linux/macOS
```

Server chạy ở `http://localhost:8080`.

## Web demo

Mở `http://localhost:8080/`.

- **Micro**: nói vào micro, chữ hiện ngay. Trình duyệt chỉ cho dùng micro trên `localhost` hoặc `https`.
- **File âm thanh**: phát lại file ở tốc độ thật. Đặt "Số kết nối song song" lớn hơn 1 để thử nhiều người dùng cùng lúc; ô nào bị máy chủ từ chối vì đầy sẽ hiện đỏ.
- **Gửi cả file qua REST**: thử `POST /api/v1/stt/transcriptions` với cùng file.
- Góc trên phải hiện engine và mức tải hiện tại của máy chủ.

Engine `jni` chỉ trả văn bản sau khi nhận `end`, nên khung "đang nói" chỉ hiện chữ khi bạn dừng; engine `mock` có chữ chạy liên tục.

## API

### REST (`/api/v1`)

| Method | Đường dẫn | Mô tả |
|---|---|---|
| GET | `/api/v1/stt/status` | Engine, model mặc định, danh sách model, trạng thái refiner, sample rate, đường dẫn WebSocket, sức chứa và tải hiện tại, `ready` |
| POST | `/api/v1/stt/jobs` | Tạo job cho file âm thanh dài (`?model=qwen3-asr\|whisper-en&refine=true`), trả `202` |
| GET | `/api/v1/stt/jobs/{jobId}` | Xem tiến độ (`0.0..1.0`), các đoạn đã xong và văn bản cuối cùng |
| DELETE | `/api/v1/stt/jobs/{jobId}` | Hủy job đang chạy (bật cờ abort trong C++) và xóa file tạm |
| POST | `/api/v1/stt/transcriptions` | Nhận dạng đồng bộ một file WAV 16 kHz 16-bit mono ngắn, trả `{"text","sampleRate","durationMs"}` |

Đường dẫn cũ `/api/stt/...` vẫn dùng được như bí danh cho client viết trước khi có `/api/v1`; client mới nên dùng `/api/v1`. `/api/health` không còn: dùng `/actuator/health`.

```bash
curl -F "file=@meeting.m4a" "http://localhost:8080/api/v1/stt/jobs?model=qwen3-asr&refine=true"
curl http://localhost:8080/api/v1/stt/jobs/<jobId>
curl -F "file=@sample.wav" http://localhost:8080/api/v1/stt/transcriptions
```

Lỗi trả theo RFC 9457 (`application/problem+json`): `400` file sai định dạng hoặc sai sample rate, `413` file quá lớn, `415` không phải multipart, `503` khi model lỗi, hoặc kèm `Retry-After` khi mọi slot nhận dạng đang bận.

### Vận hành (Actuator)

| Đường dẫn | Dùng cho |
|---|---|
| `/actuator/health/liveness` | Liveness probe. Luôn UP khi tiến trình còn sống, không phụ thuộc tải |
| `/actuator/health/readiness` | Readiness probe. `503` (trạng thái `BUSY`) khi đã đầy slot, để load balancer chuyển người dùng mới sang node khác |
| `/actuator/health` | Tổng hợp. Node đầy slot vẫn trả `200`, vì đầy là bận chứ không phải hỏng |
| `/actuator/metrics`, `/actuator/prometheus` | Metrics (Prometheus scrape được) |

Metrics riêng: `stt.sessions.active`, `stt.sessions.max`, `stt.sessions.rejected`, `stt.inference.workers`, `stt.inference.active`, `stt.inference.queued`, `stt.inference.duration`, `stt.queue.wait`. Chỉ số cần theo dõi nhất là `stt.queue.wait`: tăng nghĩa là máy đang quá tải.

### WebSocket: `ws://<host>:8080/ws/stt?sampleRate=16000`

`sampleRate` tùy chọn, mặc định `stt.sample-rate` (16000). Model chỉ nhận đúng tần số này, tần số khác bị từ chối.

Client gửi:

- **Binary frame**: PCM 16-bit little-endian, mono, 16 kHz, số byte chẵn. Nên gửi mỗi 100 ms (3200 byte). Tối đa `maxFrameBytes` (mặc định 65536) mỗi frame, lớn hơn bị đóng với mã 1009.
- **Text frame** `{"type":"end"}`: kết thúc câu hiện tại, chạy model trên toàn bộ âm thanh của câu và trả `final`. Nhiều `end` liên tiếp mà không có âm thanh xen giữa chỉ nhận một `final`. Một câu dài tối đa `stt.max-utterance-seconds` (60 giây).

Server trả (JSON text):

```json
{"type":"ready","sampleRate":16000,"encoding":"pcm_s16le","channels":1,"maxFrameBytes":65536}
{"type":"partial","text":"xin chào","lagMs":24}
{"type":"final","text":"xin chào các bạn","lagMs":31}
{"type":"error","code":"capacity_exceeded","message":"..."}
```

`partial` chỉ có ở engine hỗ trợ streaming (như `mock`); engine `jni` chỉ trả `final`. `lagMs` là thời gian audio mới nhất chờ trong server (hàng đợi và model) trước khi ra kết quả. Mã lỗi (`code`) và hành vi:

| `code` | Khi nào | Server làm gì |
|---|---|---|
| `invalid_sample_rate` | `sampleRate` khác tần số của model | Đóng, mã 1008 |
| `capacity_exceeded` | Đã đủ số luồng tối đa | Đóng, mã 1013. Client thử lại sau vài giây |
| `overloaded` | Client có hơn `max-buffered-audio` âm thanh chưa xử lý xong | Đóng, mã 1013 |
| `invalid_frame` | Frame có số byte lẻ | Giữ kết nối, bỏ frame |
| `invalid_command` | Text frame không hợp lệ | Giữ kết nối |
| `invalid_audio` | Engine từ chối âm thanh, ví dụ câu dài quá giới hạn | Giữ kết nối. Gửi `end` thường xuyên hơn |
| `recognition_failed` | Model không ra được văn bản cho câu này (hết thời gian, bị hủy) | Giữ kết nối, câu đó bị bỏ, câu sau chạy bình thường |
| `engine_failure` | Model hỏng nặng (lỗi native) | Đóng, mã 1011 |

Kết nối không gửi gì trong `idle-timeout` (60 giây) bị đóng với mã 1001; Tomcat quét định kỳ nên có thể trễ vài giây. Client nhận kết quả quá chậm (hơn `send-timeout`) bị ngắt.

Với Android, `AudioRecord` ở `ENCODING_PCM_16BIT`, `CHANNEL_IN_MONO`, 16000 Hz đúng định dạng này, chỉ cần đẩy buffer vào frame nhị phân.

## Đa luồng và sức chứa

```
WebSocket / REST ──► SessionLimiter (giới hạn số luồng)
                          │
        mỗi kết nối: SessionPipeline (hàng đợi riêng, một phiên chỉ chạy trên một thread)
                          │
                 InferenceExecutor (bộ thread cố định, dùng chung)
                          │
                 SpeechToTextEngine ──► (jni) CpuExecutionGate ──► model C++
```

- Thread web server không bao giờ chạy model. Tổng CPU dùng cho nhận dạng bị chặn trên bởi số worker, dù có bao nhiêu client.
- Mỗi phiên nhận một lượt xử lý rồi xếp lại cuối hàng, nên một client nói liên tục không làm đói các client khác. Âm thanh xếp hàng được gộp thành một lần gọi model (tối đa `max-batch-audio`) để bắt kịp khi bị chậm.
- Vượt `max-sessions` thì từ chối ngay (WebSocket mã 1013, REST `503`), để người đang kết nối vẫn nhanh thay vì tất cả cùng chậm.
- File WAV tải lên qua REST đồng bộ dùng chung slot và bộ thread này. Job file dài chạy nền trong `TranscriptionJobService`.
- Engine `jni` còn thêm `CpuExecutionGate`: mọi lời gọi native (VAD, ASR, refiner) xếp hàng công bằng và chạy từng cái một, nên số thread nhận dạng nhiều hơn 1 không tăng thông lượng, chỉ giúp các phiên khác vẫn nhận kịp âm thanh trong lúc một phiên đang chờ model. Một lần `end` có thể mất nhiều giây trên board, nên `max-buffered-audio` mặc định 30s để client nói tiếp câu sau không bị ngắt oan.

Cấu hình trong `application.yml` (hoặc biến môi trường dạng `STT_POOL_WORKERS`). Cấu hình model, thư viện native và job nằm ở `SttProperties`; cấu hình đa luồng và WebSocket ở `RuntimeProperties`, cùng tiền tố `stt`:

| Khóa | Mặc định | Ý nghĩa |
|---|---|---|
| `stt.engine` | `jni` | `jni` hoặc `mock` hoặc tên engine bạn thêm |
| `stt.pool.workers` | 0 (số CPU trừ 1) | Số thread nhận dạng |
| `stt.pool.max-sessions` | 0 (số worker nhân `sessionsPerWorker` của engine) | Số luồng tối đa cùng lúc. `jni` khai báo 1 mỗi worker, `mock` khai báo 4. Đây là mức thận trọng, hãy đo với dữ liệu thật rồi đặt lại (xem "Đo sức chứa thật") |
| `stt.session.max-buffered-audio` | 30s | Âm thanh chờ tối đa của một client |
| `stt.session.max-batch-audio` | 500ms | Âm thanh gộp tối đa mỗi lần gọi model |
| `stt.websocket.allowed-origins` | `*` | Origin được phép. Giới hạn lại trước khi public |
| `stt.websocket.max-frame-bytes` | 65536 | Kích thước frame nhị phân tối đa |
| `stt.websocket.idle-timeout` | 60s | Ngắt kết nối im lặng |

### Đo sức chứa thật

`tools/load-test.mjs` (cần Node 22 trở lên) mở N kết nối, mỗi kết nối phát âm thanh ở tốc độ thật và in phân vị `lagMs`:

```
node tools/load-test.mjs localhost:8080 6 25 tiengnoi.wav
```

Đối số: `host:port`, số kết nối, số giây, và file WAV 16 kHz mono 16-bit (tùy chọn). **Luôn đo bằng file tiếng nói thật.** Không có file thì tool phát im lặng, và im lặng giải mã nhanh hơn nhiều lần tiếng nói nên cho con số quá lạc quan.

Tăng dần số kết nối đến khi p95 của `lagMs` vượt mức bạn chấp nhận được (ví dụ 2 giây), rồi đặt `stt.pool.max-sessions` thấp hơn mức đó một chút. Quá mức đó, server vẫn tự bảo vệ: client tụt quá `max-buffered-audio` bị ngắt, nhưng đặt đúng `max-sessions` thì người dùng thừa được từ chối ngay từ cửa (`capacity_exceeded`) thay vì bị ngắt giữa chừng. Khi đo, tắt hết tiến trình nền nặng, nếu không số đo sai nhiều lần. Chưa có số đo cho engine `jni` trên board G520; số đo cũ với Vosk không còn áp dụng.

## Cắm model AI khác

Mô hình được viết thêm chỉ cần:

1. Tạo package mới, ví dụ `stt/<tên>/`, với một class `@Component` implement `SpeechToTextEngine` (xem `stt/mock/MockSpeechEngine` làm mẫu ngắn nhất, `stt/jni/JniSpeechEngine` làm mẫu đầy đủ).
2. Gắn `@ConditionalOnProperty(prefix = "stt", name = "engine", havingValue = "<tên>")` để chọn bằng `STT_ENGINE=<tên>`.
3. Tạo test kế thừa `SpeechToTextEngineContract` (trong `src/test/.../stt/`) và trả engine của bạn từ `engine()` (xem `MockSpeechEngineTest`). Bộ test này kiểm tra những điều runtime dùng chung đòi hỏi: nhiều phiên mở song song, `finish()` luôn trả `FINAL`, `close()` gọi hai lần không lỗi.

Quy ước engine phải giữ:

- `openSession` được gọi đồng thời từ nhiều thread. Model nạp một lần và dùng chung.
- `SpeechSession` chỉ bị một thread dùng tại một thời điểm (runtime bảo đảm), nhưng các lần gọi có thể đến từ các thread khác nhau. Không dùng `ThreadLocal`.
- `accept` nên trả về nhanh trong vài chục mili giây cho 100–500 ms âm thanh; nó chiếm một worker trong lúc chạy. Engine chỉ nhận dạng khi hết câu (như `jni`) thì `accept` chỉ gom âm thanh và `finish()` mới chạy model.
- Ném `IllegalArgumentException` từ `accept`/`finish` khi từ chối một đầu vào mà phiên vẫn dùng tiếp được, và `SpeechRecognitionException` khi không ra được văn bản cho một câu: runtime báo lỗi cho client mà không ngắt kết nối. Mọi lỗi khác (kể cả `Error` từ native) đóng phiên với `engine_failure`.
- Ghi đè `sessionsPerWorker()` (mặc định 1) để khai báo mỗi worker chịu được bao nhiêu luồng realtime. Chỉ tăng khi đã đo bằng `tools/load-test.mjs` với tiếng nói thật. Giá trị này quyết định mặc định của `stt.pool.max-sessions`.
- Không sửa `websocket/` hay `api/` khi thêm engine.

## Kiến trúc

```
com.bocbang.backend
├── api/              REST /api/v1 (và /api/stt cũ) và xử lý lỗi RFC 9457
├── websocket/        WebSocket /ws/stt: chỉ chuyển tin nhắn, không chạy model
├── runtime/          InferenceExecutor, SessionLimiter, SessionPipeline, RuntimeProperties, health indicator
└── stt/              Abstraction cho model nhận dạng
    ├── SpeechToTextEngine       engine dùng chung (model nạp một lần)
    ├── SpeechSession            một phiên realtime (mỗi kết nối một phiên)
    ├── TranscriptionService     xử lý file WAV ngắn qua cùng engine và bộ thread
    ├── TranscriptionJobService  job nền cho file dài (ffmpeg, VAD, ASR, refiner)
    ├── jni/                     engine C++ JNI, CpuExecutionGate, VAD, refiner
    └── mock/                    engine giả để demo và thử tải
```

## Test

```
./gradlew test --no-daemon
```

Test chạy ngay không cần model: đơn vị cho runtime đa luồng, engine giả, job nền, và `SttEndToEndTest` khởi động cả server trên cổng ngẫu nhiên rồi nói chuyện qua WebSocket và HTTP thật. `JniSpeechEngineTest` chỉ chạy khi đã biên dịch `.so` cho máy host (`./scripts/build-native.sh host`) và có model trong thư mục model; thiếu thì tự bỏ qua. Chạy `./gradlew --stop` sau khi xong nếu không dùng `--no-daemon`.

## Lưu ý khi triển khai

- Spring Boot không chạy trực tiếp trong APK Android. Mô hình đúng là: app Android thu âm và gửi qua WebSocket tới server (board, PC, Raspberry Pi...).
- `allowed-origins` đang là `*` để dev dễ. Trước khi public, đặt `stt.websocket.allowed-origins`.
- Sau reverse proxy (nginx...), bật nâng cấp WebSocket (`Upgrade`/`Connection`) và đặt `proxy_read_timeout` lớn hơn `idle-timeout`. Trỏ readiness probe vào `/actuator/health/readiness`.
- `/actuator` và trang demo chưa có xác thực. Đừng mở ra Internet khi chưa đặt sau proxy có kiểm soát truy cập.
- RAM của board là giới hạn thật: model ASR ~1.3 GB, refiner ~2.5 GB, mỗi câu realtime giữ âm thanh trong heap tối đa `max-utterance-seconds`. Đặt `-Xmx` và `max-sessions` theo RAM thực tế.
