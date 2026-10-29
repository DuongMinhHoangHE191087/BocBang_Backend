---
name: stt-backend
description: Kiến trúc và protocol của BocBang Backend (Spring Boot 4 + engine nhận dạng cắm được, mặc định C++ JNI trên board G520, có engine mock). Dùng khi sửa WebSocket /ws/stt, REST /api/v1/stt, runtime đa luồng, engine nhận dạng giọng nói, job file dài, web demo, hoặc viết client Android gửi âm thanh.
---

# BocBang STT backend

## Luồng dữ liệu

1. Client (Android `AudioRecord`, PCM 16-bit mono 16 kHz) mở `ws://<host>:8080/ws/stt?sampleRate=16000`. Model chỉ nhận đúng `stt.sample-rate`.
2. Server gửi `{"type":"ready",...,"maxFrameBytes":65536}`. Hết chỗ thì gửi `{"type":"error","code":"capacity_exceeded"}` rồi đóng mã 1013.
3. Client gửi binary frame PCM mỗi 100 ms. Engine streaming (mock) trả `partial` khi văn bản thay đổi; engine `jni` chỉ gom âm thanh. Mỗi tin transcript có `lagMs`.
4. Client gửi text `{"type":"end"}` để chốt câu. Server chạy model trên cả câu (tối đa `stt.max-utterance-seconds`) và trả `final`.

Lỗi luôn có `code` (`invalid_sample_rate`, `capacity_exceeded`, `overloaded`, `invalid_frame`, `invalid_command`, `invalid_audio`, `recognition_failed`, `engine_failure`). `invalid_audio` và `recognition_failed` giữ kết nối. Bảng hành vi đầy đủ trong README.md.

## Đường đi của một frame

`SpeechWebSocketHandler` (thread web) → `SessionPipeline.offer` (hàng đợi riêng của kết nối) → `InferenceExecutor` (bộ thread cố định) → `SpeechSession.accept` / `finish` → `Listener` gửi `partial/final` qua `ConcurrentWebSocketSessionDecorator`. Với engine `jni`, `finish` gọi `JniSpeechEngine.transcribe` qua `CpuExecutionGate` (mọi lời gọi native xếp hàng tuần tự). REST WAV ngắn đi qua cùng `SessionLimiter` và `InferenceExecutor` (`TranscriptionService`); file dài chạy nền trong `TranscriptionJobService`.

## Chỗ cần sửa khi làm việc với từng phần

- Thêm model khác: implement `SpeechToTextEngine` và `SpeechSession` trong package `stt/<tên>/`, `@Component` + `@ConditionalOnProperty(prefix = "stt", name = "engine", havingValue = "<tên>")`, thêm test kế thừa `SpeechToTextEngineContract`. Không sửa `websocket/` hay `api/`.
- Đổi protocol WebSocket: `websocket/SpeechWebSocketHandler.java`, cập nhật README.md, `SttEndToEndTest`, `tools/load-test.mjs` nếu đổi tin nhắn, và `static/app.js`.
- Đổi hành vi đa luồng, hàng đợi, backpressure, phân loại lỗi engine: `runtime/SessionPipeline.java` (có `SessionPipelineTest`).
- Giới hạn tải, chọn engine, WebSocket: `runtime/RuntimeProperties.java`, `application.yml`. Model, thư viện native, VAD, refiner, job: `stt/SttProperties.java`. Hai record cùng tiền tố `stt`; test dựng bằng `TestSettings.runtime(...)` và `TestSettings.properties(...)`.
- Đổi REST: `api/SttController.java` (phục vụ cả `/api/v1/stt` và `/api/stt` cũ), lỗi trong `api/ApiExceptionHandler.java` (RFC 9457).
- Đổi cách đọc file WAV ngắn: `stt/TranscriptionService.java` (chỉ nhận WAV 16-bit PCM mono đúng `stt.sample-rate`). File dài: `stt/TranscriptionJobService.java`.
- Khóa API: `security/ApiKeyFilter.java` và `SecurityProperties` (`stt.security.api-keys` hay `STT_API_KEYS`, rỗng là tắt). Khóa gửi bằng `Authorization: Bearer` hoặc `X-Api-Key`; riêng `/ws/**` nhận thêm `?apiKey=` vì trình duyệt không đặt được header cho WebSocket. Đổi thì cập nhật `ApiKeyFilterTest`, `SttSecurityEndToEndTest`, ô "Khóa API" trong `static/app.js` và README.md.
- Phần C++ và JNI: `native/`, `stt/jni/`; cách biên dịch trong README.md.
- Web demo: `src/main/resources/static/` (`index.html`, `app.css`, `app.js`, `pcm-worklet.js`), không dùng CDN hay bước build.
- Chọn engine: `STT_ENGINE=jni|mock` (mặc định `jni`). Model: `stt.models.*`, thư mục model `STT_MODEL_DIR`.

## Ràng buộc đã biết

- `SpeechSession` không thread-safe. `SessionPipeline` bảo đảm một thread tại một thời điểm, theo đúng thứ tự đến, nhưng các lần gọi có thể đến từ các worker khác nhau (không dùng `ThreadLocal`).
- `JniSpeechEngine` giữ tối đa một model ASR trong RAM native, nạp khi cần; thiếu file model thì lỗi lúc nhận dạng, không phải lúc khởi động. Thiếu cấu hình `stt.models` thì server không khởi động (dùng `STT_ENGINE=mock` khi chưa có model).
- Số luồng tối đa mặc định = số worker nhân `SpeechToTextEngine.sessionsPerWorker()` (jni 1, mock 4). `CpuExecutionGate` chạy native từng cái một nên throughput không tăng theo số worker; phải đo bằng `tools/load-test.mjs` với file tiếng nói thật (im lặng nhanh hơn nhiều, cho số sai) và chỉ số `stt.queue.wait`, rồi đặt `stt.pool.max-sessions`.
- Slot của luồng WebSocket (`SessionLimiter.Permit`) chỉ được trả khi `SessionPipeline.Listener.onClosed` chạy, tức recognizer đã đóng thật, không phải lúc ngắt kết nối. Đừng trả permit ở handler.
- `SessionPipeline` phân loại lỗi engine: `IllegalArgumentException` và `SpeechRecognitionException` gọi `onRejected` (giữ phiên); mọi lỗi khác, kể cả `Error` như `UnsatisfiedLinkError`, gọi `onFailure` (đóng phiên). Nhiều `end` liên tiếp không có audio xen giữa gộp thành một `final`.
- `max-buffered-audio` mặc định 30s vì `finish()` của engine `jni` có thể mất nhiều giây, trong lúc đó client vẫn gửi câu kế tiếp.
- `POST /transcriptions` giữ cả file trong RAM nên chặn ở `stt.max-utterance-seconds` (đọc dư đúng một byte rồi từ chối), trong khi giới hạn upload của Spring là 500 MB cho `/jobs`. Đừng nâng giới hạn này mà không bỏ việc nạp cả file.
- `/api/stt` cũ phục vụ cùng controller, `LegacyApiDeprecationFilter` gắn `Deprecation` và `Link: rel="successor-version"`. Demo (`static/app.js`) tự gửi `end` khi ngừng nói và chờ câu cuối sau Dừng; `tools/load-test.mjs` cần `--end-every` với engine chỉ trả sau `end`.
- Job: hàng đợi đầy bị từ chối trước khi chép file; `TranscriptionJobService.close()` xóa thư mục của job chưa chạy xong và lúc khởi động quét các thư mục tên UUID còn sót; audio sau chuyển đổi phải là 16-bit mono đúng `stt.sample-rate`. Lỗi job gửi client chỉ qua `JobFailure` (thông điệp viết cho client), còn lại là câu chung.
- `SessionPipeline` đóng phiên sau `MAX_CONSECUTIVE_REJECTIONS` (50) lần engine từ chối liên tiếp, và gói `IllegalArgumentException` của `finish()` thành `SpeechRecognitionException` để mỗi `end` luôn có một `final` hoặc `recognition_failed`.
- REST upload bọc session trong `GuardedSession`: thread request bị ngắt không được đóng session khi một thread suy luận còn đang ở trong engine. Tần số lấy mẫu của WAV phải bằng `stt.sample-rate`.
- Health: indicator `stt` báo `BUSY` khi đầy slot. `application.yml` xếp `BUSY` dưới `UP` cho `/actuator/health` (vẫn 200) và trên `UP` cho nhóm readiness (503). `InferenceExecutor` nhận `SpeechToTextEngine` làm tham số để bị hủy trước engine.
- Container WebSocket mặc định chỉ nhận frame nhị phân 8 KB; `WebSocketConfig` nâng lên `stt.websocket.max-frame-bytes`.
- Máy chủ chạy trên board/PC/server. App Android là client, không chạy Spring Boot trong APK.
- `AudioSystem.getAudioInputStream` cần luồng hỗ trợ mark/reset, nên `TranscriptionService` bọc `BufferedInputStream`.

## Kiểm tra

- Test không cần model: `test --no-daemon` qua `~/.claude/scripts/jvm-ondemand.ps1` (xem CLAUDE.md của dự án). Gồm `SttEndToEndTest` chạy cả server thật trên cổng ngẫu nhiên với engine giả.
- `JniSpeechEngineTest` tự bật khi đã `./scripts/build-native.sh host` và có model.
- Thử tay: chạy server với `STT_ENGINE=mock`, mở `http://localhost:8080/`, hoặc `node tools/load-test.mjs`.
