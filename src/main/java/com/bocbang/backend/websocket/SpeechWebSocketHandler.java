package com.bocbang.backend.websocket;

import com.bocbang.backend.stt.SpeechSession;
import com.bocbang.backend.stt.SpeechToTextEngine;
import com.bocbang.backend.stt.SttProperties;
import com.bocbang.backend.stt.TranscriptEvent;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Streaming speech-to-text over WebSocket at {@code /ws/stt?sampleRate=16000}.
 *
 * <p>Client to server: binary frames of 16-bit little-endian mono PCM; text frame {@code {"type":"end"}}
 * to finish the current utterance.
 * <p>Server to client (JSON text): {@code ready} once on connect, then {@code partial} and {@code final}
 * transcripts, or {@code error}.
 */
@Component
public class SpeechWebSocketHandler extends AbstractWebSocketHandler {

    private static final String SESSION_ATTRIBUTE = "speechSession";
    private static final int MIN_SAMPLE_RATE = 8000;
    private static final int MAX_SAMPLE_RATE = 48000;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SpeechToTextEngine engine;
    private final SttProperties properties;

    public SpeechWebSocketHandler(SpeechToTextEngine engine, SttProperties properties) {
        this.engine = engine;
        this.properties = properties;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession webSocket) throws IOException {
        int sampleRate;
        try {
            sampleRate = requestedSampleRate(webSocket);
        } catch (IllegalArgumentException e) {
            sendError(webSocket, e.getMessage());
            webSocket.close(CloseStatus.POLICY_VIOLATION.withReason(e.getMessage()));
            return;
        }
        webSocket.getAttributes().put(SESSION_ATTRIBUTE, engine.openSession(sampleRate));
        send(webSocket, Map.of(
                "type", "ready",
                "sampleRate", sampleRate,
                "encoding", "pcm_s16le",
                "channels", 1));
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession webSocket, BinaryMessage message) throws IOException {
        ByteBuffer payload = message.getPayload();
        byte[] pcm = new byte[payload.remaining()];
        payload.get(pcm);
        if (pcm.length % 2 != 0) {
            sendError(webSocket, "PCM 16-bit frames must have an even number of bytes");
            return;
        }
        speechSession(webSocket).accept(pcm, pcm.length)
                .ifPresent(event -> sendTranscript(webSocket, event));
    }

    @Override
    protected void handleTextMessage(WebSocketSession webSocket, TextMessage message) throws IOException {
        JsonNode command;
        try {
            command = JSON.readTree(message.getPayload());
        } catch (JacksonException e) {
            sendError(webSocket, "Text frame must be a JSON object");
            return;
        }
        if ("end".equals(command.path("type").asString(""))) {
            sendTranscript(webSocket, speechSession(webSocket).finish());
        } else {
            sendError(webSocket, "Unknown command, expected {\"type\":\"end\"}");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession webSocket, CloseStatus status) {
        if (webSocket.getAttributes().remove(SESSION_ATTRIBUTE) instanceof SpeechSession session) {
            session.close();
        }
    }

    private int requestedSampleRate(WebSocketSession webSocket) {
        String value = UriComponentsBuilder.fromUri(webSocket.getUri())
                .build()
                .getQueryParams()
                .getFirst("sampleRate");
        if (value == null) {
            return properties.defaultSampleRate();
        }
        int sampleRate = Integer.parseInt(value);
        if (sampleRate < MIN_SAMPLE_RATE || sampleRate > MAX_SAMPLE_RATE) {
            throw new IllegalArgumentException(
                    "sampleRate must be between " + MIN_SAMPLE_RATE + " and " + MAX_SAMPLE_RATE);
        }
        return sampleRate;
    }

    private static SpeechSession speechSession(WebSocketSession webSocket) {
        return (SpeechSession) webSocket.getAttributes().get(SESSION_ATTRIBUTE);
    }

    private static void sendTranscript(WebSocketSession webSocket, TranscriptEvent event) {
        send(webSocket, Map.of(
                "type", event.type().name().toLowerCase(Locale.ROOT),
                "text", event.text()));
    }

    private static void sendError(WebSocketSession webSocket, String message) {
        send(webSocket, Map.of("type", "error", "message", message));
    }

    private static void send(WebSocketSession webSocket, Map<String, ?> body) {
        try {
            webSocket.sendMessage(new TextMessage(JSON.writeValueAsString(body)));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to send WebSocket message", e);
        }
    }
}
