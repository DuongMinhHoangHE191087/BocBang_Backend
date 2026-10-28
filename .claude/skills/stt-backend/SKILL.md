---
name: stt-backend
description: Kiến trúc và protocol của BocBang Backend (Spring Boot 4 + Vosk). Dùng khi sửa WebSocket /ws/stt, REST /api/stt, engine nhận dạng giọng nói, hoặc viết client Android gửi âm thanh.
---

# BocBang STT backend

## Luồng dữ liệu

1. Client (Android `AudioRecord`, PCM 16-bit mono) mở `ws://<host>:8080/ws/stt?sampleRate=16000`.
2. Server gửi `{"type":"ready",...}`.
3. Client gửi binary frame PCM mỗi 100–250 ms. Server trả `partial` khi văn bản thay đổi và `final` khi model kết thúc một câu.
4. Client gửi text `{"type":"end"}` để chốt câu cuối. Server trả `final`.

## Chỗ cần sửa khi làm việc với từng phần

- Thêm model khác: implement `SpeechToTextEngine` và `SpeechSession` trong `stt/`, đăng ký làm `@Component`. Không sửa `websocket/` hay `api/`.
- Đổi protocol: `websocket/SpeechWebSocketHandler.java`. Cập nhật luôn bảng trong README.md.
- Đổi cách đọc file: `stt/TranscriptionService.java` (chỉ nhận WAV 16-bit PCM mono).
- Cấu hình model: `stt.vosk.model-path` trong `application.yml`, hoặc biến `STT_VOSK_MODEL_PATH`.

## Ràng buộc đã biết

- `SpeechSession` không thread-safe. Handler WebSocket gọi tuần tự cho từng kết nối; đừng gọi từ thread khác.
- Model Vosk được nạp một lần khi khởi động. Nếu thiếu thư mục model, server dừng ngay với thông báo đường dẫn.
- Vosk chạy trên CPU ngay trong thread của WebSocket. Với nhiều kết nối đồng thời, cần đo lại trước khi đẩy sang executor riêng.
- Server không xử lý Android APK. Backend chạy trên PC/server, app Android là client.

## Kiểm tra

- Test không cần model: `./gradlew test` (dùng `FakeSpeechToTextEngine`).
- Test với model thật tự bật khi có thư mục `models/vosk-model-small-vn-0.4`.
- Trên máy dev, Gradle phải chạy qua `~/.claude/scripts/jvm-ondemand.ps1` (xem CLAUDE.md của dự án).
