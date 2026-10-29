# BocBang Backend — hướng dẫn cho Claude

Spring Boot 4.1 + Java 17 + Gradle (Kotlin DSL) + C++ JNI (`native/`). Chạy trên board MediaTek G520 (ARMv8.2-A CPU). Gọi trực tiếp 4 model GGML/GGUF qua JNI:
- `libbocbang_whisper.so` (`whisper.cpp`): `ggml-silero-vad.bin` + `whisper-small-en.bin`
- `libbocbang_transcribe.so` (`transcribe.cpp`): `qwen3-asr-1.7b-q4_k_m.gguf`
- `libbocbang_llama.so` (`llama.cpp`): `qwen3-4b-instruct-2507-q4_k_m.gguf`

Chi tiết API, cách biên dịch `.so` và đẩy lên board xem [README.md](README.md).

## Cấu trúc và quy ước

- Package gốc: `com.bocbang.backend`. Các package: `api/`, `websocket/`, `stt/`, `stt/jni/`.
- Mã nguồn C++ JNI nằm trong `native/` (`CMakeLists.txt`, `transcribe_jni.cpp`, `whisper_jni.cpp`, `llama_jni.cpp`, `exports.map`). Mỗi thư viện `.so` ẩn toàn bộ symbol `ggml_*` nội bộ qua `exports.map` để không đụng nhau khi nạp chung trong một JVM.
- Mọi lời gọi JNI nặng CPU đều đi qua `CpuExecutionGate` để xếp hàng tuần tự và hỗ trợ hủy tức thì (`abort`) khi hết thời gian hoặc khi job bị xóa.
- File âm thanh dài luôn đọc theo cửa sổ trên đĩa qua `Wav`, không nạp toàn bộ PCM của file 1 giờ vào Java heap.
- Thông báo lỗi trong code viết bằng tiếng Anh. Tài liệu và hướng dẫn cho người dùng viết bằng tiếng Việt.
- Giữ comment ở mức tối thiểu, chỉ ghi lý do không hiển nhiên.

## Kiểm tra

- Biên dịch JNI cho máy host: `./scripts/build-native.sh host`
- Biên dịch JNI cho board G520: `./scripts/build-native.sh android-arm64`
- Chạy test Java + JNI: `./gradlew test` (nhớ chạy `./gradlew --stop` sau khi xong).
