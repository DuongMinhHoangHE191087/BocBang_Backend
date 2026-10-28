# BocBang Backend

Backend Spring Boot nhận âm thanh qua WebSocket, chuyển thành văn bản bằng model nhận dạng giọng nói chạy trên CPU (Vosk, offline), và trả kết quả realtime về client. Kèm REST `/api` để kiểm tra và chuyển một file WAV thành văn bản.

## Yêu cầu

- JDK 17 trở lên (Spring Boot 4 yêu cầu Java 17+). Gradle wrapper đi kèm, không cần cài Gradle.
- Model Vosk (ví dụ `vosk-model-small-vn-0.4`, tiếng Việt) đã được chuẩn bị riêng, không nằm trong repo. Nguồn model: https://alphacephei.com/vosk/models

## Đặt model

Đặt thư mục model đã giải nén vào `models/vosk-model-small-vn-0.4` ở thư mục gốc dự án, hoặc chỉ định đường dẫn khác bằng biến môi trường `STT_VOSK_MODEL_PATH`. Thư mục `models/` đã nằm trong `.gitignore`. Nếu model không tồn tại, server dừng ngay khi khởi động và báo đường dẫn đang tìm.

## Chạy

```
./gradlew bootRun          # Linux/macOS
gradlew.bat bootRun        # Windows
```

Server chạy ở `http://localhost:8080`.

## API

### REST

| Method | Đường dẫn | Mô tả |
|---|---|---|
| GET | `/api/health` | Kiểm tra server còn sống |
| GET | `/api/stt/status` | Tên engine, sample rate mặc định, đường dẫn WebSocket |
| POST | `/api/stt/transcriptions` | Multipart `file` là WAV 16-bit PCM mono, trả `{"text": "...", "sampleRate": 16000}` |

```
curl -F "file=@sample.wav" http://localhost:8080/api/stt/transcriptions
```

### WebSocket: `ws://<host>:8080/ws/stt?sampleRate=16000`

`sampleRate` tùy chọn, mặc định 16000, chấp nhận 8000–48000.

Client gửi:

- **Binary frame**: PCM 16-bit little-endian, mono. Nên gửi mỗi 100–250 ms (ví dụ 3200–8000 byte ở 16 kHz).
- **Text frame** `{"type":"end"}`: kết thúc câu hiện tại, server trả `final`.

Server trả (JSON text):

```json
{"type":"ready","sampleRate":16000,"encoding":"pcm_s16le","channels":1}
{"type":"partial","text":"xin chào"}
{"type":"final","text":"xin chào các bạn"}
{"type":"error","message":"..."}
```

Với Android, `AudioRecord` ở `ENCODING_PCM_16BIT`, `CHANNEL_IN_MONO`, 16000 Hz đúng định dạng này, chỉ cần đẩy buffer vào frame nhị phân.

## Kiến trúc

```
com.bocbang.backend
├── api/              REST /api (health, stt status, transcriptions) + xử lý lỗi
├── websocket/        WebSocket /ws/stt, đọc PCM và trả transcript
└── stt/              Abstraction cho model nhận dạng
    ├── SpeechToTextEngine   engine dùng chung (một model load một lần)
    ├── SpeechSession        một phiên streaming (mỗi kết nối một phiên)
    ├── TranscriptionService xử lý file WAV qua cùng engine
    └── vosk/                cài đặt Vosk (VoskSpeechEngine, VoskSpeechSession)
```

Để đổi model (Whisper, Sherpa-ONNX...), chỉ cần thêm một class implement `SpeechToTextEngine` trong `stt/`. WebSocket và REST không cần sửa.

## Test

```
./gradlew test
```

Test engine giả chạy ngay. `VoskSpeechEngineTest` nạp model thật và chỉ chạy khi có `models/vosk-model-small-vn-0.4`.

## Lưu ý khi triển khai

- Spring Boot không chạy trực tiếp trong APK Android. Mô hình đúng là: app Android thu âm và gửi qua WebSocket tới server (PC/server/Raspberry Pi...).
- `allowedOriginPatterns` đang là `*` để dev dễ. Trước khi public, giới hạn lại trong `WebSocketConfig`.
- Vosk xử lý trên CPU, mỗi phiên tốn một `Recognizer`. Với nhiều kết nối đồng thời, cần đo lại tải CPU trên máy thực tế.
