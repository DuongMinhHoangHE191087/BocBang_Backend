# BocBang Backend — hướng dẫn cho Claude

Spring Boot 4.1 + Java 17 + Gradle (Kotlin DSL) + C++ JNI (`native/`). Chạy trên board MediaTek G520 (ARMv8.2-A CPU). Gọi trực tiếp 4 model GGML/GGUF qua JNI:
- `libbocbang_whisper.so` (`whisper.cpp`): `ggml-silero-vad.bin` + `whisper-small-en.bin`
- `libbocbang_transcribe.so` (`transcribe.cpp`): `qwen3-asr-1.7b-q4_k_m.gguf`
- `libbocbang_llama.so` (`llama.cpp`): `qwen3-4b-instruct-2507-q4_k_m.gguf`

Nhận PCM qua WebSocket `/ws/stt` và trả JSON realtime. REST nằm dưới `/api/v1` (đường dẫn cũ `/api/stt` là bí danh), health và metrics dưới `/actuator`, web demo ở `/`. Chi tiết API, cấu hình, cách biên dịch `.so` và đẩy lên board xem [README.md](README.md).

## Cấu trúc và quy ước

- Package gốc: `com.bocbang.backend`. Các package: `api/`, `websocket/`, `runtime/`, `security/`, `stt/`, `stt/jni/`, `stt/mock/`.
- Mã nguồn C++ JNI nằm trong `native/` (`CMakeLists.txt`, `transcribe_jni.cpp`, `whisper_jni.cpp`, `llama_jni.cpp`, `exports.map`). Mỗi thư viện `.so` ẩn toàn bộ symbol `ggml_*` nội bộ qua `exports.map` để không đụng nhau khi nạp chung trong một JVM.
- Model nhận dạng luôn đi qua interface `SpeechToTextEngine` / `SpeechSession` trong `stt/`. Không gọi trực tiếp class `stt/jni` từ `api/` hoặc `websocket/`. Engine mới chọn bằng `stt.engine` (`@ConditionalOnProperty`, `jni` là mặc định) và phải qua `SpeechToTextEngineContract` trong test.
- Mọi lời gọi JNI nặng CPU đều đi qua `CpuExecutionGate` để xếp hàng tuần tự và hỗ trợ hủy tức thì (`abort`) khi hết thời gian hoặc khi job bị xóa.
- Mọi lần chạy model realtime và REST đồng bộ đi qua `runtime/`: `InferenceExecutor` (bộ thread cố định), `SessionPipeline` (mỗi kết nối một hàng đợi, một phiên chỉ chạy trên một thread tại một thời điểm), `SessionLimiter` (giới hạn số luồng). Không chạy model trên thread web server và không tạo thread riêng cho engine. Handler WebSocket chỉ chuyển tin nhắn. Slot của luồng chỉ được trả khi `Listener.onClosed` chạy (recognizer đã đóng thật), đừng trả ở handler.
- Phân loại lỗi từ engine trong `SessionPipeline`: `IllegalArgumentException` và `SpeechRecognitionException` là "từ chối một đầu vào", báo cho client (`invalid_audio`, `recognition_failed`) và giữ kết nối; mọi lỗi khác, kể cả `Error` từ native, đóng phiên với `engine_failure`.
- Gửi tin nhắn WebSocket từ thread khác thread của container chỉ qua `ConcurrentWebSocketSessionDecorator` (đã làm trong handler).
- Cấu hình đọc qua `@ConfigurationProperties`, không hard-code đường dẫn model hay giới hạn. Hai record cùng tiền tố `stt`: `SttProperties` (model, native, VAD, refiner, ffmpeg, job; chỉ sửa khi đổi phần JNI) và `RuntimeProperties` (chọn engine, `pool`, `session`, `websocket`, `mock`). Test dựng cấu hình bằng `TestSettings.properties(...)` và `TestSettings.runtime(...)`.
- Xác thực chỉ ở `security/ApiKeyFilter` (khóa trong `stt.security.api-keys`, rỗng là tắt), áp cho `/api/**` và `/ws/**`. Không rải kiểm tra khóa vào controller hay handler. Bộ lọc xét đường dẫn theo `getServletPath()`, không phải `getRequestURI()`, để path parameter (`/api;x=1/...`) không qua mặt được.
- Giao thức WebSocket và REST là hợp đồng với client Android: đổi thì cập nhật README, `.claude/skills/stt-backend/SKILL.md` và `SttEndToEndTest` cùng lúc. `/ws/stt` giữ nguyên đường dẫn.
- File âm thanh dài luôn đọc theo cửa sổ trên đĩa qua `Wav`, không nạp toàn bộ PCM của file 1 giờ vào Java heap.
- Thông báo lỗi trong code viết bằng tiếng Anh. Tài liệu và hướng dẫn cho người dùng viết bằng tiếng Việt.
- Giữ comment ở mức tối thiểu, chỉ ghi lý do không hiển nhiên.

## Kiểm tra

- Biên dịch JNI cho máy host: `./scripts/build-native.sh host`
- Biên dịch JNI cho board G520: `./scripts/build-native.sh android-arm64`
- Chạy test Java + JNI: `./gradlew test` (nhớ chạy `./gradlew --stop` sau khi xong). `SttEndToEndTest` khởi động cả server trên cổng ngẫu nhiên với `FakeSpeechToTextEngine` (`stt.engine=test`), không cần `.so` hay model. `JniSpeechEngineTest` tự bỏ qua khi chưa có `.so` host hoặc model.
- Thử tải: `node tools/load-test.mjs host:port số-kết-nối số-giây file.wav` (Node 22+) với server đang chạy, dùng `STT_ENGINE=mock` nếu chưa có model. Đo sức chứa luôn dùng tiếng nói thật, không dùng im lặng. Khi đo, tắt hết tiến trình nền nặng, nếu không số đo sai (từng gấp 3 lần).

## Gradle và JVM trên máy dev Windows 16 GB

Dùng script chung `~/.claude/scripts/jvm-ondemand.ps1` (xem `~/.claude/CLAUDE.md`):

- Lúc viết và sửa code: không chạy Gradle.
- Khi cần kiểm tra: `powershell -ExecutionPolicy Bypass -File "$HOME/.claude/scripts/jvm-ondemand.ps1" run "test --no-daemon --console=plain"`. Script tự chọn JDK 17 và tắt daemon sau khi xong.
- Luôn thêm `--no-daemon`: trên máy này Gradle daemon từng tự biến mất giữa chừng (build lỗi "daemon disappeared"), chạy `--no-daemon` thì ổn định.
- Sau mỗi lệnh Gradle chạy tay: `... jvm-ondemand.ps1 stop`.
- Dependency lần đầu cần mạng; sau đó có thể thêm `--offline`.
- Dependency Spring thêm mới không cần ghi version (plugin `io.spring.dependency-management` áp BOM Spring Boot).
- Chạy thử server thật: `bootJar` rồi `java -jar build/libs/*.jar` bằng JDK 17 (đường dẫn trong script), với `STT_ENGINE=mock` nếu chưa có model. Tắt tiến trình sau khi thử, đừng để chạy nền.
