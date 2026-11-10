# BocBang Backend

Backend Spring Boot chạy trên board MediaTek G520 (ARMv8.2-A CPU), tích hợp trực tiếp **C++ JNI** (`native/`) để nạp và chạy 4 model GGML/GGUF ngay trong bộ nhớ mà không cần gọi tiến trình CLI bên ngoài. Nhận âm thanh qua WebSocket và REST, trả văn bản về client, phục vụ nhiều người dùng cùng lúc trên một bộ thread dùng chung có giới hạn tải, kèm health probe, metrics và trang web demo.

Model AI cắm vào qua interface `SpeechToTextEngine` (xem mục "Cắm model AI khác"). Engine mặc định là `jni`; engine `mock` chạy được khi chưa có model.

---

## Kiến trúc C++ JNI & 4 Model

Mỗi thư viện C++ (`transcribe.cpp`, `whisper.cpp`, `llama.cpp`) đi kèm phiên bản `ggml` riêng, nên module `native/` biên dịch thành **3 file `.so` độc lập** có ẩn toàn bộ symbol nội bộ (`exports.map` + `-fvisibility=hidden`) để nạp chung vào cùng một JVM không xung đột:

| Thư viện JNI | Engine C++ | Model phụ trách | Vai trò |
|---|---|---|---|
| `libbocbang_whisper.so` | `whisper.cpp` | `ggml-silero-vad.bin` + `whisper-small-en.bin` | Tách đoạn tiếng nói (Silero VAD) & ASR tiếng Anh (`whisper-en`) |
| `libbocbang_transcribe.so` | `transcribe.cpp` | `qwen3-asr-1.7b-q4_k_m.gguf` | ASR đa ngôn ngữ / tiếng Việt (`qwen3-asr`) |
| `libbocbang_llama.so` | `llama.cpp` | `qwen3-4b-instruct-2507-q4_k_m.gguf` | Thêm dấu câu, viết hoa, sửa lỗi nhận dạng (Refiner) |

Tất cả model nhận âm thanh **16 kHz, 16-bit, mono** (chuyển sang `float[]` chuẩn hóa `[-1.0, 1.0)` ngay trong Java trước khi truyền qua JNI).

---

## Luồng xử lý file dài (5 phút – 1 giờ)

1. Client gửi file (WAV, MP3, M4A, OGG, FLAC...) vào `POST /api/v1/stt/jobs`. File ghi thẳng xuống đĩa, trả ngay `202 Accepted` kèm `jobId`.
2. Worker chạy nền:
   - `CONVERTING`: `ffmpeg` chuyển mọi định dạng về WAV 16 kHz 16-bit mono (nếu đã đúng chuẩn thì bỏ qua `ffmpeg`).
   - `DETECTING_SPEECH`: quét Silero VAD qua JNI theo từng cửa sổ 5 phút (~19 MB heap) để tìm các đoạn có tiếng nói, gộp các đoạn gần nhau và cắt tối đa `30s`/đoạn.
   - `TRANSCRIBING`: nạp model ASR (`qwen3-asr` hoặc `whisper-en`) **một lần duy nhất** vào RAM native, đọc từng đoạn `float[]` trực tiếp từ file WAV và gọi JNI. Cập nhật `progress` và `segments` sau mỗi đoạn.
   - `REFINING`: nếu `stt.unload-before-refine: true` (mặc định), giải phóng ~1.3 GB của model ASR trước khi nạp ~2.5 GB của `qwen3-4b-instruct`, chia văn bản thành từng khúc (`max-batch-chars`) để sửa dấu câu & lỗi từ, sau đó giải phóng RAM.
3. Nếu client gọi `DELETE /api/v1/stt/jobs/{jobId}` hoặc quá `timeout`, `CpuExecutionGate` bật cờ `abort` nguyên tử trong C++ để dừng ngay đồ thị `ggml` đang chạy.

---

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

---

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

---

## Chạy trên PC

```bash
./scripts/build-native.sh host
STT_MODEL_DIR=/workspace/MeetingNotes-models ./gradlew bootRun
```

Chưa có `.so` hay model? Chạy engine giả (mỗi giây âm thanh sinh ra vài từ tiếng Việt có sẵn) để xem demo và thử tải:

```bash
set STT_ENGINE=mock && gradlew.bat bootRun           # Windows cmd
STT_ENGINE=mock ./gradlew bootRun                    # Linux/macOS
```

Server chạy ở `http://localhost:8080` (hoặc `http://localhost:8099` tùy cấu hình `SERVER_PORT`).

---

## Web Demo

Mở `http://localhost:8080/` (hoặc cổng hiện tại của server).

- **Micro**: nói vào micro, chữ hiện ngay. Trình duyệt chỉ cho dùng micro trên `localhost` hoặc `https`.
- **File âm thanh**: phát lại file ở tốc độ thật. Đặt "Số kết nối song song" lớn hơn 1 để thử nhiều người dùng cùng lúc; ô nào bị máy chủ từ chối vì đầy sẽ hiện đỏ.
- **Gửi cả file qua REST**: thử `POST /api/v1/stt/transcriptions` với cùng file.
- Góc trên phải hiện engine và mức tải hiện tại của máy chủ.

Demo chỉ gửi âm thanh khi có người nói (kèm 0,3 giây đệm phía trước để không cắt đầu từ), nên im lặng kéo dài không làm đầy giới hạn 60 giây của máy chủ. Engine `jni` chỉ trả văn bản sau khi nhận `end`, nên demo tự gửi `end` khi bạn ngừng nói khoảng 0,8 giây (và sau 25 giây nếu nói liền), rồi chữ của câu đó hiện ra. Bấm Dừng thì demo chờ tối đa 30 giây cho câu cuối; quá hạn nó báo chưa nhận được thay vì im lặng đóng kết nối. Engine `mock` có chữ chạy liên tục.

---

## Chi tiết API & WebSocket Specification

### 1. Thông Tin Chung & Versioning

- **Base URL**: `http://<host>:<port>` (Mặc định: `8099` hoặc `8080`)
- **Current API Path**: `/api/v1/stt`
- **Legacy API Path**: `/api/stt` *(Deprecated: Trả về RFC 9745 header `Deprecation: @1793232000` và `Link: </api/v1/stt/...>; rel="successor-version"`)*

### 2. Xác Thực (Authentication & Security)

Xác thực được điều khiển qua cấu hình `stt.security.api-keys` (biến môi trường `STT_API_KEYS`).

- **Mặc định**: Tắt (rỗng), dành cho board trong mạng nội bộ trusted.
- **Bật xác thực**: Đặt danh sách key ngăn cách bởi dấu phẩy:
  ```bash
  STT_API_KEYS=key-android-client,key-ai-service ./gradlew bootRun
  ```

Khi bật xác thực, client gửi key bằng một trong các hình thức:
- **REST Header**: `Authorization: Bearer <API_KEY>` hoặc `X-Api-Key: <API_KEY>`
- **WebSocket Query Param**: `ws://<host>:<port>/ws/stt?apiKey=<API_KEY>` *(dành cho browser/client không đặt được custom header khi bắt tay WebSocket)*

> **Lưu ý**: Endpoint probe sức khỏe (`/actuator/health/**`) và trang demo UI (`/`, `/index.html`) luôn **không đòi key** để liveness/readiness probe hoạt động bình thường. Endpoints metrics (`/actuator/metrics`, `/actuator/prometheus`) mặc định mở; đặt `STT_PROTECT_ACTUATOR=true` để đòi key cho cả metrics.

---

### 3. Endpoints REST (`/api/v1/stt`)

#### 3.1 `GET /api/v1/stt/status`

Kiểm tra thông tin engine, danh sách model, tần số lấy mẫu, đường dẫn WebSocket & Jobs, cùng sức chứa và tải hiện tại của server trước khi kết nối.

- **Method**: `GET`
- **Request Headers**: `Authorization: Bearer <API_KEY>` *(nếu bật auth)*
- **Response `200 OK`**:
  ```json
  {
    "engine": "jni",
    "defaultModel": "qwen3-asr",
    "models": [
      "qwen3-asr",
      "whisper-en"
    ],
    "refinerEnabled": true,
    "sampleRate": 16000,
    "maxUtteranceSeconds": 60,
    "websocketPath": "/ws/stt",
    "jobsPath": "/api/v1/stt/jobs",
    "capacity": {
      "workers": 4,
      "maxSessions": 4,
      "activeSessions": 1,
      "queuedTasks": 0
    },
    "ready": true
  }
  ```

---

#### 3.2 `POST /api/v1/stt/transcriptions`

Nhận dạng đồng bộ cho câu ngắn (tối đa `maxUtteranceSeconds`, mặc định 60 giây). File bắt buộc là định dạng **WAV 16 kHz, 16-bit, mono**.

- **Method**: `POST`
- **Content-Type**: `multipart/form-data`
- **Form Data**:
  - `file`: File âm thanh WAV (bắt buộc)
- **Response `200 OK`**:
  ```json
  {
    "text": "Xin chào đây là bài thử nghiệm nhận dạng",
    "sampleRate": 16000,
    "durationMs": 3200
  }
  ```
- **Ví dụ `curl`**:
  ```bash
  curl -X POST "http://localhost:8099/api/v1/stt/transcriptions" \
    -H "Authorization: Bearer my-secret-key" \
    -F "file=@sample_16k_mono.wav"
  ```

---

#### 3.3 `POST /api/v1/stt/jobs`

Gửi file âm thanh dài (WAV, MP3, M4A, OGG, FLAC...) để xử lý bất đồng bộ trong background worker (FFmpeg -> Silero VAD -> Qwen3-ASR -> LLM Refiner).

- **Method**: `POST`
- **Content-Type**: `multipart/form-data`
- **Form Data**:
  - `file`: File âm thanh (bắt buộc, hỗ trợ tối đa 500 MB)
- **Query Parameters (Tùy chọn)**:
  - `model`: Tên model ASR (`qwen3-asr` hoặc `whisper-en`)
  - `refine`: `true` | `false` (Bật/tắt bước LLM sửa dấu câu & lỗi từ)
- **Response `202 Accepted`**:
  - **Headers**: `Location: /api/v1/stt/jobs/{jobId}`
  - **Body**:
  ```json
  {
    "jobId": "c8f1e290-6712-4a0b-891d-9171f114a849",
    "model": "qwen3-asr",
    "refine": true,
    "status": "RUNNING",
    "stage": "CONVERTING",
    "progress": 0.1,
    "durationSeconds": null,
    "completedSegments": 0,
    "totalSegments": 0,
    "rawText": null,
    "text": null,
    "segments": [],
    "error": null,
    "createdAt": "2026-11-03T07:20:00Z",
    "updatedAt": "2026-11-03T07:20:01Z"
  }
  ```
- **Ví dụ `curl`**:
  ```bash
  curl -i -X POST "http://localhost:8099/api/v1/stt/jobs?model=qwen3-asr&refine=true" \
    -H "Authorization: Bearer my-secret-key" \
    -F "file=@meeting_1hour.m4a"
  ```

---

#### 3.4 `GET /api/v1/stt/jobs/{jobId}`

Poll thông tin tiến độ (`progress` từ `0.0` đến `1.0`), các đoạn tiếng nói (`segments`), văn bản thô (`rawText`) và văn bản đã qua LLM (`text`).

- **Method**: `GET`
- **Response `200 OK`**:
  ```json
  {
    "jobId": "c8f1e290-6712-4a0b-891d-9171f114a849",
    "model": "qwen3-asr",
    "refine": true,
    "status": "DONE",
    "stage": "DONE",
    "progress": 1.0,
    "durationSeconds": 125.4,
    "completedSegments": 2,
    "totalSegments": 2,
    "rawText": "xin chao moi nguoi hom nay chung ta hop ban ve du an",
    "text": "Xin chào mọi người. Hôm nay chúng ta họp bàn về dự án.",
    "segments": [
      {
        "index": 0,
        "start": 0.5,
        "end": 4.2,
        "text": "Xin chào mọi người."
      },
      {
        "index": 1,
        "start": 5.0,
        "end": 9.8,
        "text": "Hôm nay chúng ta họp bàn về dự án."
      }
    ],
    "error": null,
    "createdAt": "2026-11-03T07:20:00Z",
    "updatedAt": "2026-11-03T07:20:30Z"
  }
  ```
- **Các giá trị `status`**: `QUEUED`, `RUNNING`, `DONE`, `FAILED`, `CANCELLED`
- **Các giá trị `stage`**: `QUEUED`, `CONVERTING`, `DETECTING_SPEECH`, `TRANSCRIBING`, `REFINING`, `DONE`, `FAILED`, `CANCELLED`

---

#### 3.5 `DELETE /api/v1/stt/jobs/{jobId}`

Hủy ngay lập tức job đang chạy (bật cờ abort nguyên tử trong C++ JNI) và xóa file tạm.

- **Method**: `DELETE`
- **Response**: `204 No Content` (Thành công) hoặc `404 Not Found`

---

### 4. WebSocket Streaming (`/ws/stt`)

Truyền nhận âm thanh thời gian thực qua WebSocket full-duplex.

- **URL**: `ws://<host>:<port>/ws/stt`
- **Handshake Query Parameters**:
  - `sampleRate`: Tần số lấy mẫu âm thanh (Mặc định `16000`). Phải khớp chính xác với tần số của model.
  - `apiKey`: Khóa API (Tùy chọn, dùng khi server bật auth và client không gửi được header).
- **Handshake Headers (Khuyến nghị)**:
  - `Authorization: Bearer <API_KEY>` hoặc `X-Api-Key: <API_KEY>`

#### 4.1 Client -> Server Protocol

1. **Binary Frames (Dữ liệu âm thanh PCM)**:
   - **Định dạng**: PCM 16-bit little-endian, mono, 16 kHz (`pcm_s16le`).
   - **Yêu cầu**: Độ dài byte trong mỗi frame phải là **số chẵn** (`pcm.length % 2 == 0`).
   - **Kích thước khuyến nghị**: 100 ms âm thanh mỗi frame (3,200 bytes ở 16 kHz). Tối đa `65536` bytes/frame (`maxFrameBytes`).

2. **Text Frames (Lệnh điều khiển JSON)**:
   - **Kết thúc câu hiện tại (`end`)**:
     Client gửi JSON text frame khi ngắt câu hoặc ngừng nói:
     ```json
     {"type": "end"}
     ```

#### 4.2 Server -> Client Protocol (JSON Text Messages)

1. **`ready`** (Gửi ngay sau khi kết nối WebSocket thành công):
   ```json
   {
     "type": "ready",
     "sampleRate": 16000,
     "encoding": "pcm_s16le",
     "channels": 1,
     "maxFrameBytes": 65536
   }
   ```

2. **`partial`** (Văn bản tạm thời - chỉ có ở engine hỗ trợ streaming như `mock`):
   ```json
   {
     "type": "partial",
     "text": "xin chào",
     "lagMs": 24
   }
   ```

3. **`final`** (Văn bản hoàn chỉnh sau khi nhận lệnh `{"type":"end"}`):
   ```json
   {
     "type": "final",
     "text": "xin chào các bạn",
     "lagMs": 31
   }
   ```

4. **`error`** (Thông báo lỗi ứng dụng):
   ```json
   {
     "type": "error",
     "code": "capacity_exceeded",
     "message": "Server is at capacity, retry shortly"
   }
   ```

#### Chi Tiết Mã Lỗi & Hành Vi WebSocket Server

| `code` | Nguyên nhân | Hành vi Server |
|---|---|---|
| `invalid_sample_rate` | Query `sampleRate` khác tần số model (16000 Hz) | Ngắt kết nối, Close Code `1008` |
| `capacity_exceeded` | Số phiên vượt quá `stt.pool.max-sessions` | Ngắt kết nối, Close Code `1013` |
| `overloaded` | Client gửi âm thanh chưa xử lý vượt `max-buffered-audio` | Ngắt kết nối, Close Code `1013` |
| `invalid_frame` | Frame binary có số byte lẻ | Trả lỗi `error`, giữ kết nối |
| `invalid_command` | Text frame không đúng định dạng JSON `{"type":"end"}` | Trả lỗi `error`, giữ kết nối |
| `invalid_audio` | Âm thanh câu hiện tại vượt quá `max-utterance-seconds` | Trả lỗi `error`, giữ kết nối |
| `recognition_failed` | Model JNI không giải mã được câu này | Trả lỗi `error`, giữ kết nối, câu sau chạy bình thường |
| `engine_failure` | Lỗi C++ JNI nghiêm trọng | Ngắt kết nối, Close Code `1011` |

---

### 5. Định Dạng Lỗi HTTP (RFC 9457 Problem Details)

Tất cả các lỗi HTTP REST từ server đều tuân theo chuẩn RFC 9457 `application/problem+json`:

```json
{
  "type": "about:blank",
  "title": "Server at capacity",
  "status": 503,
  "detail": "Server is currently full, please try again",
  "instance": "/api/v1/stt/transcriptions"
}
```

- **HTTP `400 Bad Request`**: File rỗng, không đúng chuẩn WAV 16k 16-bit mono hoặc quá dài cho `/transcriptions`.
- **HTTP `401 Unauthorized`**: Thiếu hoặc sai API Key (`WWW-Authenticate: Bearer realm="bocbang"`).
- **HTTP `413 Payload Too Large`**: File tải lên vượt quá 500 MB.
- **HTTP `503 Service Unavailable`**: Server đầy tải. Trả về kèm HTTP Header `Retry-After: 1`.

---

### 6. Actuator & Probes Vận Hành

| Endpoint | Method | Mặc định Key | Mô tả |
|---|---|---|---|
| `/actuator/health` | GET | Không đòi key | Tổng quan trạng thái (`UP` / `DOWN`) |
| `/actuator/health/liveness` | GET | Không đòi key | Liveness probe (Luôn `200 OK` khi JVM đang chạy) |
| `/actuator/health/readiness` | GET | Không đòi key | Readiness probe (Trả `200 OK` khi sẵn sàng, `503 Service Unavailable` trạng thái `BUSY` khi đầy slot để load balancer ngắt tải) |
| `/actuator/metrics` | GET | Mở (Đòi key nếu `STT_PROTECT_ACTUATOR=true`) | Metric hệ thống Micrometer |
| `/actuator/prometheus` | GET | Mở (Đòi key nếu `STT_PROTECT_ACTUATOR=true`) | Prometheus metrics endpoint |

---

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
| `stt.security.api-keys` | rỗng (tắt) | Danh sách khóa API, biến môi trường `STT_API_KEYS`. Xem "Xác thực (API key)" |
| `stt.security.protect-actuator` | `false` | `true` thì metrics và info cũng đòi khóa (probe health vẫn mở). Biến `STT_PROTECT_ACTUATOR`. Không có tác dụng khi chưa đặt khóa |
| `stt.websocket.allowed-origins` | `*` | Origin được phép. Giới hạn lại trước khi public |
| `stt.websocket.max-frame-bytes` | 65536 | Kích thước frame nhị phân tối đa |
| `stt.websocket.idle-timeout` | 60s | Ngắt kết nối im lặng |

### Đo sức chứa thật

`tools/load-test.mjs` (cần Node 22 trở lên) mở N kết nối, mỗi kết nối phát âm thanh ở tốc độ thật và in phân vị `lagMs`:

```bash
node tools/load-test.mjs localhost:8080 6 25 tiengnoi.wav
node tools/load-test.mjs localhost:8080 6 40 tiengnoi.wav --end-every=8 --api-key=khoa-cua-ban
```

Đối số: `host:port`, số kết nối, số giây, và file WAV 16 kHz mono 16-bit (tùy chọn). `--end-every=<giây>` gửi `end` định kỳ: **bắt buộc với engine `jni`**, vì nó chỉ trả văn bản sau `end`; thiếu cờ này công cụ chỉ nhận một kết quả ở cuối và phân vị `lagMs` không có ý nghĩa (công cụ sẽ nhắc). `--api-key=<khóa>` dùng khi máy chủ bật xác thực. **Luôn đo bằng file tiếng nói thật.** Không có file thì tool phát im lặng, và im lặng giải mã nhanh hơn nhiều lần tiếng nói nên cho con số quá lạc quan.

Tăng dần số kết nối đến khi p95 của `lagMs` vượt mức bạn chấp nhận được (ví dụ 2 giây), rồi đặt `stt.pool.max-sessions` thấp hơn mức đó một chút. Quá mức đó, server vẫn tự bảo vệ: client tụt quá `max-buffered-audio` bị ngắt, nhưng đặt đúng `max-sessions` thì người dùng thừa được từ chối ngay từ cửa (`capacity_exceeded`) thay vì bị ngắt giữa chừng. Khi đo, tắt hết tiến trình nền nặng, nếu không số đo sai nhiều lần. Chưa có số đo cho engine `jni` trên board G520; số đo cũ với Vosk không còn áp dụng.

---

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

---

## Kiến trúc

Lý do của từng quyết định và những việc còn nợ: [docs/quyet-dinh-kien-truc.md](docs/quyet-dinh-kien-truc.md).

```
com.bocbang.backend
├── api/              REST /api/v1 (và /api/stt cũ) và xử lý lỗi RFC 9457
├── websocket/        WebSocket /ws/stt: chỉ chuyển tin nhắn, không chạy model
├── runtime/          InferenceExecutor, SessionLimiter, SessionPipeline, RuntimeProperties, health indicator
├── security/         ApiKeyFilter: khóa API cho /api và /ws (tắt khi không cấu hình khóa)
└── stt/              Abstraction cho model nhận dạng
    ├── SpeechToTextEngine       engine dùng chung (model nạp một lần)
    ├── SpeechSession            một phiên realtime (mỗi kết nối một phiên)
    ├── TranscriptionService     xử lý file WAV ngắn qua cùng engine và bộ thread
    ├── TranscriptionJobService  job nền cho file dài (ffmpeg, VAD, ASR, refiner)
    ├── jni/                     engine C++ JNI, CpuExecutionGate, VAD, refiner
    └── mock/                    engine giả để demo và thử tải
```

---

## Test

```bash
./gradlew test --no-daemon
```

Test chạy ngay không cần model: đơn vị cho runtime đa luồng, engine giả, job nền, và `SttEndToEndTest` khởi động cả server trên cổng ngẫu nhiên rồi nói chuyện qua WebSocket và HTTP thật. `JniSpeechEngineTest` chỉ chạy khi đã biên dịch `.so` cho máy host (`./scripts/build-native.sh host`) và có model trong thư mục model; thiếu thì tự bỏ qua. Chạy `./gradlew --stop` sau khi xong nếu không dùng `--no-daemon`.

---

## Lưu ý khi triển khai

- Spring Boot không chạy trực tiếp trong APK Android. Mô hình đúng là: app Android thu âm và gửi qua WebSocket tới server (board, PC, Raspberry Pi...).
- `allowed-origins` đang là `*` để dev dễ. Trước khi public, đặt `stt.websocket.allowed-origins`.
- Sau reverse proxy (nginx...), bật nâng cấp WebSocket (`Upgrade`/`Connection`) và đặt `proxy_read_timeout` lớn hơn `idle-timeout`. Trỏ readiness probe vào `/actuator/health/readiness`.
- Khóa API (`STT_API_KEYS`) bảo vệ `/api` và `/ws`; metrics chỉ được bảo vệ khi bật `STT_PROTECT_ACTUATOR`. Trang demo và probe health luôn mở. Đừng mở máy chủ ra Internet khi chưa đặt sau proxy có kiểm soát truy cập và HTTPS.
- RAM của board là giới hạn thật: model ASR ~1.3 GB, refiner ~2.5 GB, mỗi câu realtime giữ âm thanh trong heap tối đa `max-utterance-seconds`. Đặt `-Xmx` và `max-sessions` theo RAM thực tế.
