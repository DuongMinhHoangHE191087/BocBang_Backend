# BocBang Backend — hướng dẫn cho Claude

Spring Boot 4.1 + Java 17 + Gradle (Kotlin DSL). Nhận PCM qua WebSocket `/ws/stt`, nhận dạng bằng Vosk (CPU, offline), trả JSON realtime. REST nằm dưới `/api`. Chi tiết API và cách chạy xem [README.md](README.md).

## Cấu trúc và quy ước

- Package gốc: `com.bocbang.backend`. Mỗi tính năng một package: `api/`, `websocket/`, `stt/`, `stt/vosk/`.
- Model nhận dạng luôn đi qua interface `SpeechToTextEngine` / `SpeechSession` trong `stt/`. Không gọi trực tiếp class Vosk từ `api/` hoặc `websocket/`.
- Một `SpeechSession` chỉ được dùng từ một thread tại một thời điểm. Engine (model) thì dùng chung.
- Cấu hình đọc qua `@ConfigurationProperties` (`SttProperties`), không hard-code đường dẫn model.
- Thông báo lỗi trong code viết bằng tiếng Anh. Tài liệu và hướng dẫn cho người dùng viết bằng tiếng Việt.
- Giữ comment ở mức tối thiểu, chỉ ghi lý do không hiển nhiên.

## Gradle và JVM (đọc trước khi chạy build)

Máy 16 GB, dùng script chung `~/.claude/scripts/jvm-ondemand.ps1` (xem `~/.claude/CLAUDE.md`):

- Lúc viết và sửa code: không chạy Gradle.
- Khi cần kiểm tra: `powershell -ExecutionPolicy Bypass -File "$HOME/.claude/scripts/jvm-ondemand.ps1" run "test --console=plain"`. Script tự chọn JDK 17 và tắt daemon sau khi xong.
- Sau mỗi lệnh Gradle chạy tay: `... jvm-ondemand.ps1 stop`.
- Dự án chưa có script build riêng. Dependency lần đầu cần mạng; sau đó có thể thêm `--offline`.

## Kiểm tra nhanh không cần model

`./gradlew test` chạy các test dùng `FakeSpeechToTextEngine`. `VoskSpeechEngineTest` tự bỏ qua khi `models/vosk-model-small-vn-0.4` chưa có.
