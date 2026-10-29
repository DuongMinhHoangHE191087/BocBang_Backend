# BocBang Backend

Backend Spring Boot nhận âm thanh qua WebSocket, chuyển thành văn bản bằng model nhận dạng giọng nói chạy trên CPU (Vosk, offline), và trả kết quả realtime về client. Phục vụ nhiều người dùng cùng lúc trên một bộ thread dùng chung, có giới hạn tải, health probe và metrics. Kèm trang web demo và REST `/api/v1`.

Backend chỉ là cầu nối: model AI cắm vào qua interface `SpeechToTextEngine` (xem mục "Cắm model AI khác").

## Yêu cầu

- JDK 17 trở lên. Gradle wrapper đi kèm, không cần cài Gradle.
- Model Vosk (ví dụ `vosk-model-small-vn-0.4`, tiếng Việt), không nằm trong repo. Nguồn: https://alphacephei.com/vosk/models

## Chạy

```
gradlew.bat bootRun                      # Windows, engine Vosk
./gradlew bootRun                        # Linux/macOS
```

Chưa có model? Chạy engine giả để xem demo và thử tải:

```
set STT_ENGINE=mock && gradlew.bat bootRun           # Windows cmd
STT_ENGINE=mock ./gradlew bootRun                    # Linux/macOS
```

Đặt model đã giải nén vào `models/vosk-model-small-vn-0.4` (hoặc biến `STT_VOSK_MODEL_PATH`). Thiếu model thì server dừng ngay và báo đường dẫn đang tìm.

Server chạy ở `http://localhost:8080`.

## Web demo

Mở `http://localhost:8080/`.

- **Micro**: nói vào micro, chữ hiện ngay. Trình duyệt chỉ cho dùng micro trên `localhost` hoặc `https`.
- **File âm thanh**: phát lại file ở tốc độ thật. Đặt "Số kết nối song song" lớn hơn 1 để thử nhiều người dùng cùng lúc; ô nào bị máy chủ từ chối vì đầy sẽ hiện đỏ.
- **Gửi cả file qua REST**: thử `POST /api/v1/stt/transcriptions` với cùng file.
- Góc trên phải hiện engine và mức tải hiện tại của máy chủ.

## API

### REST (`/api/v1`)

| Method | Đường dẫn | Mô tả |
|---|---|---|
| GET | `/api/v1/stt/status` | Engine, đường dẫn WebSocket, sức chứa và tải hiện tại, `ready` |
| POST | `/api/v1/stt/transcriptions` | Multipart `file` là WAV 16-bit PCM mono, trả `{"text","sampleRate","durationMs"}` |

```
curl -F "file=@sample.wav" http://localhost:8080/api/v1/stt/transcriptions
```

Lỗi trả theo RFC 9457 (`application/problem+json`): `400` file sai định dạng, `413` file quá lớn (tối đa 20 MB), `415` không phải multipart, `503` kèm `Retry-After` khi mọi slot nhận dạng đang bận.

### Vận hành (Actuator)

| Đường dẫn | Dùng cho |
|---|---|
| `/actuator/health/liveness` | Liveness probe. Luôn UP khi tiến trình còn sống, không phụ thuộc tải |
| `/actuator/health/readiness` | Readiness probe. `503` khi đã đầy slot, để load balancer chuyển người dùng mới sang node khác |
| `/actuator/metrics`, `/actuator/prometheus` | Metrics (Prometheus scrape được) |

Metrics riêng: `stt.sessions.active`, `stt.sessions.max`, `stt.sessions.rejected`, `stt.inference.workers`, `stt.inference.active`, `stt.inference.queued`, `stt.inference.duration`, `stt.queue.wait`. Chỉ số cần theo dõi nhất là `stt.queue.wait`: tăng nghĩa là máy đang quá tải.

### WebSocket: `ws://<host>:8080/ws/stt?sampleRate=16000`

`sampleRate` tùy chọn, mặc định 16000, chấp nhận 8000–48000.

Client gửi:

- **Binary frame**: PCM 16-bit little-endian, mono, số byte chẵn. Nên gửi mỗi 100 ms (3200 byte ở 16 kHz). Tối đa `maxFrameBytes` (mặc định 65536) mỗi frame, lớn hơn bị đóng với mã 1009.
- **Text frame** `{"type":"end"}`: kết thúc câu hiện tại, server trả `final`.

Server trả (JSON text):

```json
{"type":"ready","sampleRate":16000,"encoding":"pcm_s16le","channels":1,"maxFrameBytes":65536}
{"type":"partial","text":"xin chào","lagMs":24}
{"type":"final","text":"xin chào các bạn","lagMs":31}
{"type":"error","code":"capacity_exceeded","message":"..."}
```

`lagMs` là thời gian audio mới nhất chờ trong server (hàng đợi và model) trước khi ra kết quả. Mã lỗi (`code`) và hành vi:

| `code` | Khi nào | Server làm gì |
|---|---|---|
| `invalid_sample_rate` | `sampleRate` ngoài khoảng | Đóng, mã 1008 |
| `capacity_exceeded` | Đã đủ số luồng tối đa | Đóng, mã 1013. Client thử lại sau vài giây |
| `overloaded` | Client có hơn `max-buffered-audio` âm thanh chưa xử lý xong | Đóng, mã 1013 |
| `invalid_frame` | Frame có số byte lẻ | Giữ kết nối, bỏ frame |
| `invalid_command` | Text frame không hợp lệ | Giữ kết nối |
| `engine_failure` | Model lỗi | Đóng, mã 1011 |

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
                 SpeechToTextEngine ──► model AI
```

- Thread web server không bao giờ chạy model. Tổng CPU dùng cho nhận dạng bị chặn trên bởi số worker, dù có bao nhiêu client.
- Mỗi phiên nhận một lượt xử lý rồi xếp lại cuối hàng, nên một client nói liên tục không làm đói các client khác. Âm thanh xếp hàng được gộp thành một lần gọi model (tối đa `max-batch-audio`) để bắt kịp khi bị chậm.
- Vượt `max-sessions` thì từ chối ngay (WebSocket mã 1013, REST `503`), để người đang kết nối vẫn nhanh thay vì tất cả cùng chậm.
- File WAV tải lên qua REST dùng chung slot và bộ thread này.

Cấu hình trong `application.yml` (hoặc biến môi trường dạng `STT_POOL_WORKERS`):

| Khóa | Mặc định | Ý nghĩa |
|---|---|---|
| `stt.engine` | `vosk` | `vosk` hoặc `mock` hoặc tên engine bạn thêm |
| `stt.pool.workers` | 0 (số CPU trừ 1) | Số thread nhận dạng |
| `stt.pool.max-sessions` | 0 (4 mỗi worker) | Số luồng tối đa cùng lúc. Con số mặc định là ước lượng thận trọng, hãy đo với model thật rồi đặt lại |
| `stt.session.max-buffered-audio` | 5s | Âm thanh chờ tối đa của một client |
| `stt.session.max-batch-audio` | 500ms | Âm thanh gộp tối đa mỗi lần gọi model |
| `stt.websocket.allowed-origins` | `*` | Origin được phép. Giới hạn lại trước khi public |
| `stt.websocket.max-frame-bytes` | 65536 | Kích thước frame nhị phân tối đa |
| `stt.websocket.idle-timeout` | 60s | Ngắt kết nối im lặng |

### Đo sức chứa thật

`tools/load-test.mjs` (cần Node 22 trở lên) mở N kết nối, mỗi kết nối phát âm thanh im lặng ở tốc độ thật và in phân vị `lagMs`:

```
node tools/load-test.mjs localhost:8080 24 10
```

Tăng số kết nối đến khi p95 của `lagMs` vượt mức chấp nhận được (ví dụ 300 ms), lấy mức đó làm cơ sở cho `stt.pool.max-sessions`. Im lặng chỉ là cận dưới của tải thật; hãy đo lại bằng file tiếng nói thật ở trang demo.

## Cắm model AI khác

Mô hình được viết thêm chỉ cần:

1. Tạo package mới, ví dụ `stt/whisper/`, với một class `@Component` implement `SpeechToTextEngine` (xem `stt/mock/MockSpeechEngine` làm mẫu ngắn nhất).
2. Gắn `@ConditionalOnProperty(prefix = "stt", name = "engine", havingValue = "whisper")` để chọn bằng `STT_ENGINE=whisper`.
3. Tạo test kế thừa `SpeechToTextEngineContract` (trong `src/test/.../stt/`) và trả engine của bạn từ `engine()`. Bộ test này kiểm tra những điều runtime dùng chung đòi hỏi: nhiều phiên mở song song, `finish()` luôn trả `FINAL`, `close()` gọi hai lần không lỗi.

Quy ước engine phải giữ:

- `openSession` được gọi đồng thời từ nhiều thread. Model nạp một lần và dùng chung.
- `SpeechSession` chỉ bị một thread dùng tại một thời điểm (runtime bảo đảm), nhưng các lần gọi có thể đến từ các thread khác nhau. Không dùng `ThreadLocal`.
- `accept` nên trả về nhanh trong vài chục mili giây cho 100–500 ms âm thanh; nó chiếm một worker trong lúc chạy.
- Không sửa `websocket/` hay `api/` khi thêm engine.

## Kiến trúc

```
com.bocbang.backend
├── api/              REST /api/v1 và xử lý lỗi RFC 9457
├── websocket/        WebSocket /ws/stt: chỉ chuyển tin nhắn, không chạy model
├── runtime/          InferenceExecutor, SessionLimiter, SessionPipeline, health indicator
└── stt/              Abstraction cho model nhận dạng
    ├── SpeechToTextEngine   engine dùng chung (một model nạp một lần)
    ├── SpeechSession        một phiên streaming (mỗi kết nối một phiên)
    ├── TranscriptionService xử lý file WAV qua cùng engine và bộ thread
    ├── vosk/                cài đặt Vosk
    └── mock/                engine giả để demo và thử tải
```

## Test

```
./gradlew test --no-daemon
```

Test chạy ngay không cần model: đơn vị cho runtime đa luồng, engine giả, và `SttEndToEndTest` khởi động cả server trên cổng ngẫu nhiên rồi nói chuyện qua WebSocket và HTTP thật. `VoskSpeechEngineTest` nạp model thật và chỉ chạy khi có `models/vosk-model-small-vn-0.4`.

## Lưu ý khi triển khai

- Spring Boot không chạy trực tiếp trong APK Android. Mô hình đúng là: app Android thu âm và gửi qua WebSocket tới server (PC/server/Raspberry Pi...).
- `allowed-origins` đang là `*` để dev dễ. Trước khi public, đặt `stt.websocket.allowed-origins`.
- Sau reverse proxy (nginx...), bật nâng cấp WebSocket (`Upgrade`/`Connection`) và đặt `proxy_read_timeout` lớn hơn `idle-timeout`. Trỏ readiness probe vào `/actuator/health/readiness`.
- `/actuator` và trang demo chưa có xác thực. Đừng mở ra Internet khi chưa đặt sau proxy có kiểm soát truy cập.
- Vosk chạy trên CPU, mỗi phiên tốn một `Recognizer` (vài chục MB với model nhỏ). Đặt `-Xmx` và `max-sessions` theo RAM thực tế.
