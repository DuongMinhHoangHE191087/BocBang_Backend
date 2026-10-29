package com.bocbang.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Proves the {@code stt.security.protect-actuator} property reaches the filter as configured. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "stt.engine=test",
            "stt.pool.workers=2",
            "stt.pool.max-sessions=3",
            "stt.security.api-keys=secret-one",
            "stt.security.protect-actuator=true"
        })
@Import(SttEndToEndTest.FakeEngineConfig.class)
class SttActuatorProtectionEndToEndTest {

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void metricsNeedTheKeyWhenActuatorProtectionIsOn() throws Exception {
        assertThat(get("/actuator/metrics/stt.sessions.active", null).statusCode()).isEqualTo(401);
        assertThat(get("/actuator/metrics/stt.sessions.active", "Bearer secret-one").statusCode()).isEqualTo(200);
    }

    @Test
    void theHealthProbesAndTheDemoPageStayOpen() throws Exception {
        assertThat(get("/actuator/health", null).statusCode()).isEqualTo(200);
        assertThat(get("/actuator/health/liveness", null).statusCode()).isEqualTo(200);
        assertThat(get("/actuator/health/readiness", null).statusCode()).isEqualTo(200);
        assertThat(get("/", null).statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> get(String path, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
