package com.bocbang.backend.websocket;

import com.bocbang.backend.runtime.InferenceExecutor;
import com.bocbang.backend.runtime.RuntimeProperties;
import com.bocbang.backend.runtime.SessionLimiter;
import com.bocbang.backend.runtime.SessionPipeline;
import com.bocbang.backend.stt.SpeechRecognitionException;
import com.bocbang.backend.stt.SpeechSession;
import com.bocbang.backend.stt.SpeechToTextEngine;
import com.bocbang.backend.stt.SttProperties;
import com.bocbang.backend.stt.TranscriptEvent;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Speech-to-text over WebSocket at {@code /ws/stt?sampleRate=16000}.
 *
 * <p>Client to server: binary frames of 16-bit little-endian mono PCM at the model's sample rate;
 * text frame {@code {"type":"end"}} to finish the current utterance.
 * <p>Server to client (JSON text): {@code ready} once on connect, {@code final} after each {@code end}, or
 * {@code error} with a machine-readable {@code code}. Engines that support streaming may also send
 * {@code partial}. Every transcript carries the server-side {@code lagMs}.
 *
 * <p>This class only moves messages. Recognition runs on the shared inference threads through a
 * {@link SessionPipeline}, so a connection never blocks a web server thread.
 */
@Component
public class SpeechWebSocketHandler extends AbstractWebSocketHandler implements DisposableBean {

    private static final Logger LOG = LoggerFactory.getLogger(SpeechWebSocketHandler.class);
    private static final String CONNECTION_ATTRIBUTE = "speechConnection";
    private static final int BYTES_PER_SAMPLE = 2;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SpeechToTextEngine engine;
    private final SttProperties properties;
    private final RuntimeProperties runtime;
    private final SessionLimiter limiter;
    private final InferenceExecutor executor;
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();

    public SpeechWebSocketHandler(SpeechToTextEngine engine, SttProperties properties, RuntimeProperties runtime,
            SessionLimiter limiter, InferenceExecutor executor) {
        this.engine = engine;
        this.properties = properties;
        this.runtime = runtime;
        this.limiter = limiter;
        this.executor = executor;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession webSocket) throws IOException {
        int sampleRate;
        try {
            sampleRate = requestedSampleRate(webSocket);
        } catch (IllegalArgumentException e) {
            reject(webSocket, "invalid_sample_rate", e.getMessage(), CloseStatus.POLICY_VIOLATION);
            return;
        }
        Optional<SessionLimiter.Permit> permit = limiter.tryAcquire();
        if (permit.isEmpty()) {
            reject(webSocket, "capacity_exceeded", "Server is at capacity, retry shortly", CloseStatus.SERVICE_OVERLOAD);
            return;
        }
        SpeechSession speech;
        try {
            speech = engine.openSession(sampleRate);
        } catch (RuntimeException e) {
            permit.get().close();
            LOG.error("Cannot open a {} session", engine.name(), e);
            reject(webSocket, "engine_failure", "Cannot start recognition", CloseStatus.SERVER_ERROR);
            return;
        }

        RuntimeProperties.WebSocket settings = runtime.websocket();
        WebSocketSession socket = new ConcurrentWebSocketSessionDecorator(
                webSocket, (int) settings.sendTimeout().toMillis(), settings.sendBufferBytes());
        SessionPipeline pipeline = new SessionPipeline(speech, executor, transcriptListener(socket, permit.get()),
                audioBytes(sampleRate, runtime.session().maxBufferedAudio().toMillis()),
                (int) audioBytes(sampleRate, runtime.session().maxBatchAudio().toMillis()));
        Connection connection = new Connection(socket, pipeline);
        webSocket.getAttributes().put(CONNECTION_ATTRIBUTE, connection);
        connections.add(connection);

        send(socket, Map.of(
                "type", "ready",
                "sampleRate", sampleRate,
                "encoding", "pcm_s16le",
                "channels", 1,
                "maxFrameBytes", settings.maxFrameBytes()));
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession webSocket, BinaryMessage message) {
        Connection connection = connection(webSocket);
        if (connection == null) {
            return;
        }
        ByteBuffer payload = message.getPayload();
        byte[] pcm = new byte[payload.remaining()];
        payload.get(pcm);
        if (pcm.length % BYTES_PER_SAMPLE != 0) {
            sendError(connection.socket(), "invalid_frame", "PCM 16-bit frames must have an even number of bytes");
            return;
        }
        if (pcm.length > 0 && !connection.pipeline().offer(pcm)) {
            sendError(connection.socket(), "overloaded", "Server cannot keep up with the audio stream");
            close(connection.socket(), CloseStatus.SERVICE_OVERLOAD);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession webSocket, TextMessage message) {
        Connection connection = connection(webSocket);
        if (connection == null) {
            return;
        }
        JsonNode command;
        try {
            command = JSON.readTree(message.getPayload());
        } catch (JacksonException e) {
            sendError(connection.socket(), "invalid_command", "Text frame must be a JSON object");
            return;
        }
        if ("end".equals(command.path("type").asString(""))) {
            connection.pipeline().finish();
        } else {
            sendError(connection.socket(), "invalid_command", "Unknown command, expected {\"type\":\"end\"}");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession webSocket, CloseStatus status) {
        if (webSocket.getAttributes().remove(CONNECTION_ATTRIBUTE) instanceof Connection connection) {
            release(connection);
        }
    }

    @Override
    public void destroy() {
        for (Connection connection : connections) {
            release(connection);
            close(connection.socket(), CloseStatus.GOING_AWAY);
        }
    }

    /** The stream's slot is given back by the pipeline once its recognizer is really closed, not before. */
    private void release(Connection connection) {
        connections.remove(connection);
        connection.pipeline().close();
    }

    private SessionPipeline.Listener transcriptListener(WebSocketSession socket, SessionLimiter.Permit permit) {
        return new SessionPipeline.Listener() {
            @Override
            public void onTranscript(TranscriptEvent event, long lagMillis) {
                send(socket, Map.of(
                        "type", event.type().name().toLowerCase(Locale.ROOT),
                        "text", event.text(),
                        "lagMs", lagMillis));
            }

            @Override
            public void onRejected(RuntimeException cause) {
                if (cause instanceof SpeechRecognitionException) {
                    // The log keeps the cause; the client gets no model paths or native messages.
                    LOG.warn("Recognition failed on session {}: {}", socket.getId(), cause.getMessage());
                    sendError(socket, "recognition_failed", "Recognition failed for this utterance");
                } else {
                    sendError(socket, "invalid_audio", cause.getMessage() + ", send {\"type\":\"end\"} more often");
                }
            }

            @Override
            public void onFailure(Throwable cause) {
                LOG.error("Recognition failed on session {}", socket.getId(), cause);
                sendError(socket, "engine_failure", "Recognition failed");
                close(socket, CloseStatus.SERVER_ERROR);
            }

            @Override
            public void onClosed() {
                permit.close();
            }
        };
    }

    private int requestedSampleRate(WebSocketSession webSocket) {
        String value = UriComponentsBuilder.fromUri(webSocket.getUri())
                .build()
                .getQueryParams()
                .getFirst("sampleRate");
        if (value == null) {
            return properties.sampleRate();
        }
        int sampleRate;
        try {
            sampleRate = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("sampleRate must be a whole number");
        }
        if (sampleRate != properties.sampleRate()) {
            throw new IllegalArgumentException("sampleRate must be " + properties.sampleRate());
        }
        return sampleRate;
    }

    /** Bytes of 16-bit mono audio in the given time, rounded down to whole samples (at least one). */
    private static long audioBytes(int sampleRate, long millis) {
        return Math.max(BYTES_PER_SAMPLE, (sampleRate * (long) BYTES_PER_SAMPLE * millis / 1000) & ~1L);
    }

    private static Connection connection(WebSocketSession webSocket) {
        return webSocket.getAttributes().get(CONNECTION_ATTRIBUTE) instanceof Connection connection ? connection : null;
    }

    private static void reject(WebSocketSession webSocket, String code, String message, CloseStatus status) {
        sendError(webSocket, code, message);
        close(webSocket, status);
    }

    private static void sendError(WebSocketSession webSocket, String code, String message) {
        send(webSocket, Map.of("type", "error", "code", code, "message", message));
    }

    /** Sending can fail when the client is already gone; that is the close handler's business, not an error here. */
    private static void send(WebSocketSession webSocket, Map<String, ?> body) {
        try {
            webSocket.sendMessage(new TextMessage(JSON.writeValueAsString(body)));
        } catch (IOException | RuntimeException e) {
            LOG.debug("Could not send to session {}: {}", webSocket.getId(), e.toString());
        }
    }

    private static void close(WebSocketSession webSocket, CloseStatus status) {
        try {
            webSocket.close(status);
        } catch (IOException | RuntimeException e) {
            LOG.debug("Could not close session {}: {}", webSocket.getId(), e.toString());
        }
    }

    private record Connection(WebSocketSession socket, SessionPipeline pipeline) {
    }
}
