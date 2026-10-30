package com.bocbang.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.bocbang.backend.runtime.SessionLimiter;
import com.bocbang.backend.stt.FakeSpeechToTextEngine;
import com.bocbang.backend.stt.SpeechToTextEngine;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/** Boots the whole server on a random port with a fake engine and talks to it over real sockets. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"stt.engine=test", "stt.pool.workers=2", "stt.pool.max-sessions=3"})
@Import(SttEndToEndTest.FakeEngineConfig.class)
class SttEndToEndTest {

    @TestConfiguration
    static class FakeEngineConfig {
        @Bean
        SpeechToTextEngine fakeEngine() {
            return new FakeSpeechToTextEngine("xin chào");
        }
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    SessionLimiter limiter;

    @Autowired
    ConfigurableApplicationContext context;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void waitUntilEarlierTestsHaveReleasedTheirSlots() {
        await().atMost(5, TimeUnit.SECONDS).until(() -> limiter.active() == 0);
    }

    @Test
    void servesSeveralWebSocketClientsAtTheSameTimeWithFramesLargerThanTheContainerDefault() throws Exception {
        List<Client> clients = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                clients.add(connect("/ws/stt?sampleRate=16000"));
            }
            for (Client client : clients) {
                client.awaitMessage("\"type\":\"ready\"");
            }

            byte[] oneSecondFrame = new byte[16000 * 2];
            for (Client client : clients) {
                client.sendBinary(oneSecondFrame);
                client.sendText("{\"type\":\"end\"}");
            }

            for (Client client : clients) {
                client.awaitMessage("\"type\":\"partial\"");
                client.awaitMessage("\"type\":\"final\"");
                assertThat(client.closeCode.get()).as("connection stays open").isZero();
            }
        } finally {
            clients.forEach(Client::close);
        }
    }

    @Test
    void refusesTheConnectionOverCapacityAndReportsNotReadyWhileFull() throws Exception {
        List<Client> clients = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                Client client = connect("/ws/stt");
                client.awaitMessage("\"type\":\"ready\"");
                clients.add(client);
            }
            assertThat(get("/actuator/health/readiness").statusCode()).isEqualTo(503);
            assertThat(get("/actuator/health/liveness").statusCode()).isEqualTo(200);
            assertThat(get("/actuator/health").statusCode()).as("a busy node is not a broken one").isEqualTo(200);

            Client overflow = connect("/ws/stt");
            overflow.awaitMessage("\"code\":\"capacity_exceeded\"");
            await().atMost(5, TimeUnit.SECONDS).until(() -> overflow.closeCode.get() == 1013);
        } finally {
            clients.forEach(Client::close);
        }

        await().atMost(5, TimeUnit.SECONDS).until(() -> get("/actuator/health/readiness").statusCode() == 200);
    }

    @Test
    void reportsEngineAndLiveCapacityOnTheStatusEndpoint() throws Exception {
        HttpResponse<String> response = get("/api/v1/stt/status");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"engine\":\"fake\"")
                .contains("\"websocketPath\":\"/ws/stt\"")
                .contains("\"workers\":2")
                .contains("\"maxSessions\":3")
                .contains("\"ready\":true");
    }

    @Test
    void keepsTheUnversionedPathsForClientsBuiltBeforeV1() throws Exception {
        HttpResponse<String> legacy = get("/api/stt/status");
        HttpResponse<String> current = get("/api/v1/stt/status");

        assertThat(legacy.statusCode()).isEqualTo(200);
        assertThat(legacy.body()).contains("\"engine\":\"fake\"");
        assertThat(current.body())
                .contains("\"defaultModel\":\"qwen3-asr\"")
                .contains("\"sampleRate\":16000")
                .contains("\"jobsPath\":\"/api/v1/stt/jobs\"");
    }

    @Test
    void marksTheOlderPathAsDeprecatedAndPointsToTheVersionedOne() throws Exception {
        HttpResponse<String> older = get("/api/stt/status");
        HttpResponse<String> current = get("/api/v1/stt/status");

        assertThat(older.headers().firstValue("Deprecation")).isPresent();
        assertThat(older.headers().firstValue("Link").orElse(""))
                .contains("</api/v1/stt/status>")
                .contains("rel=\"successor-version\"");
        assertThat(current.headers().firstValue("Deprecation")).isEmpty();
        assertThat(current.headers().firstValue("Link")).isEmpty();
    }

    @Test
    void transcribesAnUploadedWavFile() throws Exception {
        HttpResponse<String> response = postWav("/api/v1/stt/transcriptions", wav(new byte[16000 * 2]));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"text\":\"xin chào\"")
                .contains("\"sampleRate\":16000")
                .contains("\"durationMs\":1000");
    }

    @Test
    void runsALongAudioJobAndServesItOnTheVersionedAndTheOlderPaths() throws Exception {
        HttpResponse<String> submitted = postWav("/api/v1/stt/jobs?refine=false", wav(new byte[16000 * 2 * 2]));

        assertThat(submitted.statusCode()).isEqualTo(202);
        assertThat(submitted.headers().firstValue("Location").orElse("")).startsWith("/api/v1/stt/jobs/");
        String jobId = jobId(submitted.body());

        await().atMost(15, TimeUnit.SECONDS)
                .until(() -> get("/api/stt/jobs/" + jobId).body().contains("\"status\":\"DONE\""));
        assertThat(get("/api/v1/stt/jobs/" + jobId).body()).contains("\"text\":\"xin chào\"");
        assertThat(delete("/api/v1/stt/jobs/" + jobId).statusCode()).isEqualTo(204);
        assertThat(get("/api/v1/stt/jobs/" + jobId).statusCode()).isEqualTo(404);
    }

    @Test
    void answers404ForAJobThatDoesNotExist() throws Exception {
        assertThat(get("/api/v1/stt/jobs/does-not-exist").statusCode()).isEqualTo(404);
        assertThat(delete("/api/v1/stt/jobs/does-not-exist").statusCode()).isEqualTo(404);
    }

    @Test
    void refusesAnEmptyJobUploadAsAClientMistake() throws Exception {
        HttpResponse<String> response = postWav("/api/v1/stt/jobs", new byte[0]);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
    }

    @Test
    void answers503WithRetryAfterWhenNoRecognitionSlotIsFree() throws Exception {
        List<SessionLimiter.Permit> taken = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            taken.add(limiter.tryAcquire().orElseThrow());
        }
        try {
            HttpResponse<String> response = postWav("/api/v1/stt/transcriptions", wav(new byte[3200]));

            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.headers().firstValue("Retry-After")).isPresent();
            assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
        } finally {
            taken.forEach(SessionLimiter.Permit::close);
        }
    }

    @Test
    void reportsClientMistakesAsProblemDetails() throws Exception {
        HttpResponse<String> notWav = postWav("/api/v1/stt/transcriptions", "hello".getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> notMultipart = http.send(
                HttpRequest.newBuilder(uri("/api/v1/stt/transcriptions"))
                        .header("Content-Type", "text/plain")
                        .POST(HttpRequest.BodyPublishers.ofString("hello"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(notWav.statusCode()).isEqualTo(400);
        assertThat(notWav.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
        assertThat(notMultipart.statusCode()).isEqualTo(415);
        assertThat(notMultipart.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
    }

    @Test
    void shutsTheInferenceExecutorDownBeforeTheEngineItRuns() {
        assertThat(context.getBeanFactory().getDependentBeans("fakeEngine")).contains("inferenceExecutor");
    }

    @Test
    void servesTheDemoPageAndExposesMetrics() throws Exception {
        HttpResponse<String> page = get("/");
        HttpResponse<String> monitor = get("/monitor.html");
        HttpResponse<String> sessions = get("/actuator/metrics/stt.sessions.active");
        HttpResponse<String> workers = get("/actuator/metrics/stt.inference.workers");

        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("BocBang");
        assertThat(monitor.statusCode()).isEqualTo(200);
        assertThat(monitor.body()).contains("BocBang Monitor");
        assertThat(sessions.statusCode()).isEqualTo(200);
        assertThat(workers.body()).contains("\"name\":\"stt.inference.workers\"").contains("\"value\":2.0");
    }

    @Test
    void exposesPrometheusHistogramsAndPerStageMetricsAcrossHttpWebSocketAndJobs() throws Exception {
        assertThat(get("/api/v1/stt/status").statusCode()).isEqualTo(200);
        assertThat(postWav("/api/v1/stt/transcriptions", wav(new byte[16000 * 2])).statusCode()).isEqualTo(200);

        Client client = connect("/ws/stt?sampleRate=16000");
        try {
            client.awaitMessage("\"type\":\"ready\"");
            client.sendBinary(new byte[3200]);
            client.sendText("{\"type\":\"end\"}");
            client.awaitMessage("\"type\":\"final\"");
        } finally {
            client.close();
        }

        HttpResponse<String> submitted = postWav("/api/v1/stt/jobs?refine=false", wav(new byte[16000 * 2]));
        String jobId = jobId(submitted.body());
        await().atMost(15, TimeUnit.SECONDS)
                .until(() -> get("/api/v1/stt/jobs/" + jobId).body().contains("\"status\":\"DONE\""));

        HttpResponse<String> prometheus = get("/actuator/prometheus");
        assertThat(prometheus.statusCode()).isEqualTo(200);
        assertThat(prometheus.body())
                .contains("application=\"bocbang-backend\"")
                .contains("http_server_requests_seconds_bucket{")
                .contains("uri=\"/api/v1/stt/transcriptions\"")
                .contains("le=\"0.1\"")
                .contains("le=\"+Inf\"")
                .contains("stt_queue_wait_seconds_count")
                .contains("stt_inference_duration_seconds_bucket{")
                .contains("stt_ws_connections_total{")
                .contains("stt_ws_audio_bytes_total")
                .contains("stt_ws_utterances_total{")
                .contains("stt_ws_utterance_lag_seconds_bucket{")
                .contains("stt_jobs_submitted_total{")
                .contains("stt_jobs_stage_duration_seconds_count{")
                .contains("stage=\"CONVERTING\"")
                .contains("stage=\"DETECTING_SPEECH\"")
                .contains("stage=\"TRANSCRIBING\"")
                .contains("stt_jobs_duration_seconds_count{")
                .contains("stt_native_active")
                .contains("process_memory_rss_bytes")
                .contains("system_memory_available_bytes");
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).DELETE().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String jobId(String body) {
        Matcher matcher = Pattern.compile("\"jobId\":\"([^\"]+)\"").matcher(body);
        assertThat(matcher.find()).as("a jobId in " + body).isTrue();
        return matcher.group(1);
    }

    private HttpResponse<String> postWav(String path, byte[] file) throws Exception {
        String boundary = "----bocbang" + System.nanoTime();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(file);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return http.send(
                HttpRequest.newBuilder(uri(path))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static byte[] wav(byte[] pcm) throws Exception {
        AudioFormat format = new AudioFormat(16000f, 16, 1, true, false);
        AudioInputStream stream = new AudioInputStream(new ByteArrayInputStream(pcm), format, pcm.length / 2);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AudioSystem.write(stream, AudioFileFormat.Type.WAVE, out);
        return out.toByteArray();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private Client connect(String path) {
        Client client = new Client();
        client.socket = http.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + path), client)
                .join();
        return client;
    }

    /** JDK WebSocket client that keeps every text message and the close code. */
    private static final class Client implements WebSocket.Listener {

        final List<String> messages = new CopyOnWriteArrayList<>();
        final AtomicInteger closeCode = new AtomicInteger();
        volatile WebSocket socket;
        private final StringBuilder partial = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(Long.MAX_VALUE);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                messages.add(partial.toString());
                partial.setLength(0);
            }
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeCode.set(statusCode);
            return null;
        }

        void awaitMessage(String fragment) {
            await().atMost(5, TimeUnit.SECONDS)
                    .until(() -> messages.stream().anyMatch(message -> message.contains(fragment)));
        }

        void sendBinary(byte[] payload) {
            socket.sendBinary(ByteBuffer.wrap(payload), true).join();
        }

        void sendText(String text) {
            socket.sendText(text, true).join();
        }

        void close() {
            socket.abort();
        }
    }
}
