---
name: stt-backend
description: Kiến trúc và protocol của BocBang Backend (Spring Boot 4 + engine nhận dạng cắm được, mặc định Vosk). Dùng khi sửa WebSocket /ws/stt, REST /api/v1/stt, runtime đa luồng, engine nhận dạng giọng nói, web demo, hoặc viết client Android gửi âm thanh.
---

# BocBang STT backend

## Luồng dữ liệu

1. Client (Android `AudioRecord`, PCM 16-bit mono) mở `ws://<host>:8080/ws/stt?sampleRate=16000`.
2. Server gửi `{"type":"ready",...,"maxFrameBytes":65536}`. Hết chỗ thì gửi `{"type":"error","code":"capacity_exceeded"}` rồi đóng mã 1013.
3. Client gửi binary frame PCM mỗi 100 ms. Server trả `partial` khi văn bản thay đổi và `final` khi model kết thúc một câu, mỗi tin có `lagMs`.
4. Client gửi text `{"type":"end"}` để chốt câu cuối. Server trả `final`.

Lỗi luôn có `code` (`invalid_sample_rate`, `capacity_exceeded`, `overloaded`, `invalid_frame`, `invalid_command`, `engine_failure`). Bảng hành vi đầy đủ trong README.md.

## Đường đi của một frame

`SpeechWebSocketHandler` (thread web) → `SessionPipeline.offer` (hàng đợi riêng của kết nối) → `InferenceExecutor` (bộ thread cố định) → `SpeechSession.accept` → `Listener` gửi `partial/final` qua `ConcurrentWebSocketSessionDecorator`. REST upload file đi qua cùng `SessionLimiter` và `InferenceExecutor` (`TranscriptionService`).

## Chỗ cần sửa khi làm việc với từng phần

- Thêm model khác: implement `SpeechToTextEngine` và `SpeechSession` trong package `stt/<tên>/`, `@Component` + `@ConditionalOnProperty(prefix = "stt", name = "engine", havingValue = "<tên>")`, thêm test kế thừa `SpeechToTextEngineContract`. Không sửa `websocket/` hay `api/`.
- Đổi protocol WebSocket: `websocket/SpeechWebSocketHandler.java`, cập nhật README.md, `SttEndToEndTest`, `tools/load-test.mjs` nếu đổi tin nhắn, và `static/app.js`.
- Đổi hành vi đa luồng, hàng đợi, backpressure: `runtime/SessionPipeline.java` (có `SessionPipelineTest`).
- Giới hạn tải và cấu hình: `stt/SttProperties.java`, `application.yml`.
- Đổi REST: `api/SttController.java`, lỗi trong `api/ApiExceptionHandler.java` (RFC 9457). Đường dẫn có tiền tố `/api/v1`.
- Đổi cách đọc file: `stt/TranscriptionService.java` (chỉ nhận WAV 16-bit PCM mono).
- Web demo: `src/main/resources/static/` (`index.html`, `app.css`, `app.js`, `pcm-worklet.js`), không dùng CDN hay bước build.
- Chọn engine: `STT_ENGINE=vosk|mock`. Đường dẫn model Vosk: `stt.vosk.model-path` hoặc `STT_VOSK_MODEL_PATH`.

## Ràng buộc đã biết

- `SpeechSession` không thread-safe. `SessionPipeline` bảo đảm một thread tại một thời điểm, theo đúng thứ tự đến, nhưng các lần gọi có thể đến từ các worker khác nhau (không dùng `ThreadLocal`).
- Model Vosk được nạp một lần khi khởi động. Nếu thiếu thư mục model, server dừng ngay với thông báo đường dẫn (dùng `STT_ENGINE=mock` để chạy khi chưa có model).
- Số luồng tối đa mặc định = số worker nhân `SpeechToTextEngine.sessionsPerWorker()` (Vosk 1, mock 4). Vosk thật tốn khoảng 0.6 đến 1.1 nhân mỗi luồng với tiếng nói liên tục, nên phải đo bằng `tools/load-test.mjs` với file tiếng nói thật (im lặng nhanh gấp ~10 lần, cho số sai) và chỉ số `stt.queue.wait`, rồi đặt `stt.pool.max-sessions`.
- Container WebSocket mặc định chỉ nhận frame nhị phân 8 KB; `WebSocketConfig` nâng lên `stt.websocket.max-frame-bytes`.
- Máy chủ chỉ chạy trên PC/server. App Android là client, không chạy Spring Boot trong APK.
- `AudioSystem.getAudioInputStream` cần luồng hỗ trợ mark/reset, nên `TranscriptionService` bọc `BufferedInputStream`.

## Kiểm tra

- Test không cần model: `test --no-daemon` qua `~/.claude/scripts/jvm-ondemand.ps1` (xem CLAUDE.md của dự án). Gồm `SttEndToEndTest` chạy cả server thật trên cổng ngẫu nhiên.
- Test với model thật tự bật khi có thư mục `models/vosk-model-small-vn-0.4`.
- Thử tay: chạy server với `STT_ENGINE=mock`, mở `http://localhost:8080/`, hoặc `node tools/load-test.mjs`.
