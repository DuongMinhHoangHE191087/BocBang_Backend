# BocBang Backend — hướng dẫn cho Claude

Spring Boot 4.1 + Java 17 + Gradle (Kotlin DSL). Nhận PCM qua WebSocket `/ws/stt`, nhận dạng bằng engine cắm được (Vosk CPU offline, hoặc `mock`), trả JSON realtime. REST nằm dưới `/api/v1`, health và metrics dưới `/actuator`, web demo ở `/`. Chi tiết API, cấu hình và cách chạy xem [README.md](README.md).

## Cấu trúc và quy ước

- Package gốc: `com.bocbang.backend`. Mỗi tính năng một package: `api/`, `websocket/`, `runtime/`, `stt/`, `stt/vosk/`, `stt/mock/`.
- Model nhận dạng luôn đi qua interface `SpeechToTextEngine` / `SpeechSession` trong `stt/`. Không gọi trực tiếp class Vosk từ `api/` hoặc `websocket/`. Engine mới chọn bằng `stt.engine` (`@ConditionalOnProperty`) và phải qua `SpeechToTextEngineContract` trong test.
- Mọi lần chạy model đi qua `runtime/`: `InferenceExecutor` (bộ thread cố định), `SessionPipeline` (mỗi kết nối một hàng đợi, một phiên chỉ chạy trên một thread tại một thời điểm), `SessionLimiter` (giới hạn số luồng). Không chạy model trên thread web server và không tạo thread riêng cho engine. Handler WebSocket chỉ chuyển tin nhắn.
- Gửi tin nhắn WebSocket từ thread khác thread của container chỉ qua `ConcurrentWebSocketSessionDecorator` (đã làm trong handler).
- Cấu hình đọc qua `@ConfigurationProperties` (`SttProperties`), không hard-code đường dẫn model hay giới hạn. Test dựng cấu hình bằng `TestSettings.properties(...)`.
- Giao thức WebSocket và REST là hợp đồng với client Android: đổi thì cập nhật README, `.claude/skills/stt-backend/SKILL.md` và `SttEndToEndTest` cùng lúc. `/ws/stt` giữ nguyên đường dẫn.
- Thông báo lỗi trong code viết bằng tiếng Anh. Tài liệu và hướng dẫn cho người dùng viết bằng tiếng Việt.
- Giữ comment ở mức tối thiểu, chỉ ghi lý do không hiển nhiên.

## Gradle và JVM (đọc trước khi chạy build)

Máy 16 GB, dùng script chung `~/.claude/scripts/jvm-ondemand.ps1` (xem `~/.claude/CLAUDE.md`):

- Lúc viết và sửa code: không chạy Gradle.
- Khi cần kiểm tra: `powershell -ExecutionPolicy Bypass -File "$HOME/.claude/scripts/jvm-ondemand.ps1" run "test --no-daemon --console=plain"`. Script tự chọn JDK 17 và tắt daemon sau khi xong.
- Luôn thêm `--no-daemon`: trên máy này Gradle daemon từng tự biến mất giữa chừng (build lỗi "daemon disappeared"), chạy `--no-daemon` thì ổn định.
- Sau mỗi lệnh Gradle chạy tay: `... jvm-ondemand.ps1 stop`.
- Dự án chưa có script build riêng. Dependency lần đầu cần mạng; sau đó có thể thêm `--offline`.
- `build.gradle.kts` áp BOM Spring Boot bằng `platform(SpringBootPlugin.BOM_COORDINATES)` vì không dùng plugin dependency-management. Dependency Spring thêm mới không cần ghi version.
- Chạy thử server thật: `bootJar` rồi `java -jar build/libs/*.jar` bằng JDK 17 (đường dẫn trong script), với `STT_ENGINE=mock` nếu chưa có model. Tắt tiến trình sau khi thử, đừng để chạy nền.

## Kiểm tra nhanh không cần model

`./gradlew test` chạy test đơn vị cho runtime đa luồng, engine giả và `SttEndToEndTest` (khởi động cả server trên cổng ngẫu nhiên với `FakeSpeechToTextEngine`). `VoskSpeechEngineTest` tự bỏ qua khi `models/vosk-model-small-vn-0.4` chưa có. Thử tải: `node tools/load-test.mjs host:port số-kết-nối số-giây file.wav` (Node 22+) với server đang chạy. Model Vosk nằm ở `models/` (đã ignore); máy dev đã có sẵn, tải lại từ alphacephei.com/vosk/models nếu thiếu. Đo sức chứa luôn dùng tiếng nói thật, không dùng im lặng. Khi đo, tắt hết tiến trình nền nặng, nếu không số đo sai (từng gấp 3 lần).
