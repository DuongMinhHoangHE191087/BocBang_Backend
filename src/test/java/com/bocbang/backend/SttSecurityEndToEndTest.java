package com.bocbang.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** The same server as {@link SttEndToEndTest} with API keys switched on. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "stt.engine=test",
            "stt.pool.workers=2",
            "stt.pool.max-sessions=3",
            "stt.security.api-keys=secret-one,secret-two"
        })
@Import(SttEndToEndTest.FakeEngineConfig.class)
class SttSecurityEndToEndTest {

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void refusesRestRequestsWithoutAValidKey() throws Exception {
        HttpResponse<String> none = get("/api/v1/stt/status", null);
        HttpResponse<String> older = get("/api/stt/status", null);
        HttpResponse<String> wrong = get("/api/v1/stt/status", "Bearer nope");

        assertThat(none.statusCode()).isEqualTo(401);
        assertThat(none.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
        assertThat(none.headers().firstValue("WWW-Authenticate")).isPresent();
        assertThat(older.statusCode()).isEqualTo(401);
        assertThat(wrong.statusCode()).isEqualTo(401);
    }

    @Test
    void servesRestRequestsThatCarryAValidKey() throws Exception {
        HttpResponse<String> first = get("/api/v1/stt/status", "Bearer secret-one");
        HttpResponse<String> second = get("/api/stt/status", "Bearer secret-two");

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.body()).contains("\"engine\":\"fake\"");
        assertThat(second.statusCode()).isEqualTo(200);
    }

    @Test
    void refusesTheWebSocketHandshakeWithoutAValidKey() {
        assertThatThrownBy(() -> firstMessage("/ws/stt", null, null).get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .satisfies(error -> {
                    assertThat(error.getCause()).isInstanceOf(WebSocketHandshakeException.class);
                    WebSocketHandshakeException handshake = (WebSocketHandshakeException) error.getCause();
                    assertThat(handshake.getResponse().statusCode()).isEqualTo(401);
                });
    }

    @Test
    void acceptsTheWebSocketWithTheKeyInAHeaderOrInTheQuery() throws Exception {
        String viaHeader = firstMessage("/ws/stt", "X-Api-Key", "secret-one").get(5, TimeUnit.SECONDS);
        String viaQuery = firstMessage("/ws/stt?apiKey=secret-two", null, null).get(5, TimeUnit.SECONDS);

        assertThat(viaHeader).contains("\"type\":\"ready\"");
        assertThat(viaQuery).contains("\"type\":\"ready\"");
    }

    @Test
    void keepsProbesAndTheDemoPageOpen() throws Exception {
        assertThat(get("/actuator/health/readiness", null).statusCode()).isEqualTo(200);
        assertThat(get("/actuator/health", null).statusCode()).isEqualTo(200);
        assertThat(get("/", null).statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> get(String path, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Completes with the first text message, or exceptionally when the handshake is refused. */
    private CompletableFuture<String> firstMessage(String path, String headerName, String headerValue) {
        CompletableFuture<String> first = new CompletableFuture<>();
        WebSocket.Builder builder = http.newWebSocketBuilder();
        if (headerName != null) {
            builder.header(headerName, headerValue);
        }
        builder.buildAsync(URI.create("ws://localhost:" + port + path), new WebSocket.Listener() {
            @Override
            public void onOpen(WebSocket webSocket) {
                webSocket.request(1);
            }

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                first.complete(data.toString());
                webSocket.abort();
                return null;
            }
        }).whenComplete((webSocket, error) -> {
            if (error != null) {
                first.completeExceptionally(error.getCause() != null ? error.getCause() : error);
            }
        });
        return first;
    }
}
