# Quyết định kiến trúc và việc còn nợ

Ghi lại vì sao code đang như vậy, để người sau không phải đoán. Mỗi mục: quyết định, lý do, hệ quả. Chi tiết dùng và cấu hình nằm trong [README.md](../README.md).

## Quyết định

**1. Hai record cấu hình cùng tiền tố `stt`.** `SttProperties` giữ cấu hình model, thư viện native, VAD, refiner, ffmpeg và job. `RuntimeProperties` giữ chọn engine, `pool`, `session`, `websocket`, `mock`. Lý do: test của phần JNI dựng `SttProperties` bằng constructor đủ tham số, nhét thêm trường vào đó làm vỡ chúng và gây xung đột mỗi lần hai bên cùng sửa. Hệ quả: mỗi record bỏ qua khóa của record kia; test dùng `TestSettings.properties(...)` và `TestSettings.runtime(...)`.

**2. Mọi lần chạy model realtime đi qua `runtime/`.** `InferenceExecutor` (bộ thread cố định), `SessionPipeline` (mỗi kết nối một hàng đợi, một phiên chỉ chạy trên một thread, công bằng giữa các phiên), `SessionLimiter` (từ chối ngay khi đầy). Lý do: thread web không được chạy model, và tổng CPU phải bị chặn dù có bao nhiêu client. Hệ quả: engine `jni` còn có `CpuExecutionGate` chạy native từng cái một, nên thêm worker không tăng thông lượng.

**3. Lỗi từ engine chia hai loại.** `IllegalArgumentException` và `SpeechRecognitionException` là "từ chối một đầu vào": báo `invalid_audio` hoặc `recognition_failed`, giữ kết nối. Mọi lỗi khác, kể cả `Error` như `UnsatisfiedLinkError`, đóng phiên với `engine_failure`. Lý do: trước khi gộp, handler của phần JNI đã coi hai loại đầu là không ngắt kết nối; một lần hết thời gian ở một câu không nên đuổi cả người dùng. `Error` từ native thì phiên không còn đáng tin. Hai giới hạn kèm theo: engine từ chối liên tiếp 50 lần mà không nhận gì thì phiên bị đóng (khi đó engine hỏng chứ không phải đầu vào xấu, và phiên đang giữ slot vô ích); và lỗi của một `end` luôn là `recognition_failed`, để client đếm mỗi `end` đúng một câu trả lời.

**4. Slot chỉ được trả khi recognizer đã đóng thật** (`Listener.onClosed`), không phải lúc ngắt kết nối. Lý do: lúc quá tải, lệnh đóng xếp sau các tác vụ khác; trả slot sớm làm số recognizer sống vượt giới hạn.

**5. `max-buffered-audio` mặc định 30 giây.** Engine `jni` chỉ trả văn bản sau `end` và một lần `end` có thể mất nhiều giây trên board; trong lúc đó client vẫn gửi câu kế tiếp. Với 5 giây, client bình thường bị ngắt oan. Cần xác nhận lại bằng số đo trên board.

**6. Trạng thái `BUSY` cho "đầy slot".** `/actuator/health` xếp `BUSY` dưới `UP` (vẫn 200), nhóm readiness xếp trên `UP` và trả 503. Lý do: load balancer cần ngừng gửi người mới tới node đầy, nhưng monitor trên URL mặc định không được coi node bận là hỏng rồi khởi động lại.

**7. REST `/api/v1/stt`, giữ `/api/stt` làm bí danh deprecated.** Đồng đội và client đã dùng đường dẫn cũ, nên không gỡ ngay. Phản hồi cũ kèm `Deprecation` và `Link: rel="successor-version"`, chưa hứa ngày gỡ. `/api/health` bỏ, dùng `/actuator/health`.

**8. Khóa API: tắt mặc định, chỉ bảo vệ `/api/**` và `/ws/**`.** Header `Authorization: Bearer` hoặc `X-Api-Key`; riêng WebSocket nhận `?apiKey=` vì trình duyệt không đặt được header. `/actuator` và trang demo luôn mở vì probe không mang khóa. Bộ lọc xét `getServletPath()` chứ không phải URI thô, để path parameter (`/api;x=1/...`) không qua mặt được. Tùy chọn `stt.security.protect-actuator` (mặc định tắt) khóa luôn metrics và info, trừ probe health.

**9. `/transcriptions` đồng bộ chặn ở `max-utterance-seconds`.** Nó giữ cả file trong RAM, trong khi giới hạn upload là 500 MB dành cho `/jobs`. Đọc dư đúng một byte để phân biệt "đúng giới hạn" với "quá dài" mà không nạp phần còn lại.

**10. Demo chỉ gửi âm thanh khi có người nói, tự gửi `end` khi người nói ngừng, và chờ câu cuối sau Dừng.** Không gửi im lặng ở đầu vì một phút im lặng sẽ dùng hết giới hạn 60 giây của máy chủ trước khi ai nói gì. Engine chỉ trả sau `end` thì "chờ 1,2 giây rồi đóng" làm mất chữ. Client cũng đếm `end` như server: nhiều `end` không có âm thanh xen giữa chỉ nhận một kết quả, nên đếm theo từng lệnh thật sự được tính.

**11. CI chạy `./gradlew test` trên Ubuntu, JDK 17.** Test JNI tự bỏ qua khi không có `.so` và model, nên CI không cần chúng.

**12. Client không nhận thông báo lỗi nội bộ.** Đường dẫn model, đầu ra ffmpeg và thông báo native chỉ vào log; WebSocket, REST và trường `error` của job trả mô tả chung. Một lần thử kết nối thiếu model đã làm lộ đường dẫn tuyệt đối trên máy chủ.

**13. Job: từ chối sớm, dọn sạch, kiểm tra định dạng.** Hàng đợi đầy bị từ chối trước khi chép file (tránh bắt máy chủ ghi 500 MB rồi mới từ chối). Thư mục job được xóa khi tắt máy chủ và các thư mục tên UUID còn sót được quét khi khởi động. Audio sau chuyển đổi phải đúng 16-bit mono ở `stt.sample-rate`, nếu không job báo lỗi thay vì nhận dạng ra rác. Cắt văn bản cho refiner không còn làm mất ký tự ở chỗ cắt.

**14. Dependabot hằng tuần** cho Gradle và GitHub Actions, tối đa 3 PR mở cùng lúc. Bỏ bằng cách xóa `.github/dependabot.yml`.

## Việc còn nợ

Chưa làm vì cần phần cứng, số đo thật, hoặc một quyết định của người phụ trách:

- **Đo sức chứa engine `jni` trên board G520** rồi đặt `stt.pool.max-sessions`. Mặc định (số worker nhân 1) có thể cao hơn mức board chịu được.
- **Xác nhận `max-buffered-audio: 30s`** với thời gian một lần `end` thật.
- **Thử khóa API sau proxy HTTPS.** Chưa kiểm chứng ngoài mạng cục bộ.
- **Bật `STT_PROTECT_ACTUATOR`** ở môi trường thật và cấp khóa cho scraper Prometheus. Mặc định vẫn mở để không làm vỡ cấu hình đang chạy.
- **Job có refine gỡ model ASR dùng chung với realtime** (`releaseModels`). Không gây lỗi (mọi lời gọi native đi qua `CpuExecutionGate` và model tự nạp lại), nhưng câu `end` kế tiếp của phiên realtime phải đợi nạp lại, mất nhiều giây. Là đánh đổi RAM của board có chủ ý; cần số đo để chọn `unload-before-refine`.
- **Upload job ghi đĩa hai lần**: Spring đã ghi file tạm, rồi `submit` chép tiếp. Dùng `MultipartFile.transferTo` cần đổi chữ ký `submit` của phần job.
- **Ngày gỡ `/api/stt`.** Cần biết client Android đã chuyển sang `/api/v1` chưa.
- **Sức khỏe theo model.** Readiness chưa phản ánh việc thiếu file model: engine `jni` chỉ báo lỗi lúc nhận dạng, không phải lúc khởi động.
