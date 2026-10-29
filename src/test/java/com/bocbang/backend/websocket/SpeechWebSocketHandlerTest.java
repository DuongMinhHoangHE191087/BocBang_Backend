package com.bocbang.backend.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bocbang.backend.TestSettings;
import com.bocbang.backend.runtime.InferenceExecutor;
import com.bocbang.backend.runtime.SessionLimiter;
import com.bocbang.backend.stt.FakeSpeechToTextEngine;
import com.bocbang.backend.stt.SttProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class SpeechWebSocketHandlerTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final FakeSpeechToTextEngine engine = new FakeSpeechToTextEngine("xin chào");
    private final InferenceExecutor executor = new InferenceExecutor(2, meters);
    private final SessionLimiter limiter = new SessionLimiter(1, meters);

    @AfterEach
    void tearDown() {
        executor.close();
    }

    @Test
    void sendsReadyThenPartialAndFinalTranscripts() throws Exception {
        SpeechWebSocketHandler handler = handler(TestSettings.properties());
        WebSocketSession webSocket = webSocket("ws://localhost/ws/stt?sampleRate=16000");

        handler.afterConnectionEstablished(webSocket);
        handler.handleBinaryMessage(webSocket, new BinaryMessage(new byte[] {1, 0}));
        handler.handleTextMessage(webSocket, new TextMessage("{\"type\":\"end\"}"));

        List<String> sent = sentPayloads(webSocket, 3);
        assertThat(sent.get(0)).contains("\"type\":\"ready\"").contains("\"sampleRate\":16000");
        assertThat(sent.get(1)).contains("\"type\":\"partial\"").contains("\"text\":\"xin chào\"").contains("\"lagMs\":");
        assertThat(sent.get(2)).contains("\"type\":\"final\"").contains("\"text\":\"xin chào\"");
        assertThat(engine.openedSampleRates).containsExactly(16000);
    }

    @Test
    void closesTheRecognizerAndFreesTheSlotWhenTheConnectionCloses() throws Exception {
        SpeechWebSocketHandler handler = handler(TestSettings.properties());
        WebSocketSession webSocket = webSocket("ws://localhost/ws/stt");
        handler.afterConnectionEstablished(webSocket);
        assertThat(limiter.active()).isEqualTo(1);

        handler.afterConnectionClosed(webSocket, CloseStatus.NORMAL);

        await().atMost(5, TimeUnit.SECONDS).until(() -> engine.closedSessions.get() == 1);
        assertThat(limiter.active()).isZero();
    }

    @Test
    void rejectsSampleRateOutsideSupportedRange() throws Exception {
        SpeechWebSocketHandler handler = handler(TestSettings.properties());
        WebSocketSession webSocket = webSocket("ws://localhost/ws/stt?sampleRate=100");

        handler.afterConnectionEstablished(webSocket);

        assertThat(sentPayloads(webSocket, 1).get(0))
                .contains("\"type\":\"error\"")
                .contains("\"code\":\"invalid_sample_rate\"");
        verify(webSocket).close(any(CloseStatus.class));
        assertThat(engine.openedSampleRates).isEmpty();
        assertThat(limiter.active()).isZero();
    }

    @Test
    void reportsOddLengthPcmFrameAsError() throws Exception {
        SpeechWebSocketHandler handler = handler(TestSettings.properties());
        WebSocketSession webSocket = webSocket("ws://localhost/ws/stt");
        handler.afterConnectionEstablished(webSocket);

        handler.handleBinaryMessage(webSocket, new BinaryMessage(new byte[] {1, 2, 3}));

        assertThat(sentPayloads(webSocket, 2).get(1))
                .contains("\"type\":\"error\"")
                .contains("\"code\":\"invalid_frame\"");
        assertThat(engine.receivedBytes.get()).isZero();
    }

    @Test
    void refusesAConnectionOverCapacityWithServiceOverload() throws Exception {
        SpeechWebSocketHandler handler = handler(TestSettings.properties());
        handler.afterConnectionEstablished(webSocket("ws://localhost/ws/stt"));
        WebSocketSession second = webSocket("ws://localhost/ws/stt");

        handler.afterConnectionEstablished(second);

        assertThat(sentPayloads(second, 1).get(0)).contains("\"code\":\"capacity_exceeded\"");
        ArgumentCaptor<CloseStatus> status = ArgumentCaptor.forClass(CloseStatus.class);
        verify(second).close(status.capture());
        assertThat(status.getValue().getCode()).isEqualTo(CloseStatus.SERVICE_OVERLOAD.getCode());
        assertThat(engine.openedSampleRates).hasSize(1);
        assertThat(limiter.active()).isEqualTo(1);
    }

    @Test
    void closesTheConnectionWhenTheClientOutrunsTheServer() throws Exception {
        SpeechWebSocketHandler handler = handler(TestSettings.properties("session.max-buffered-audio", "1ms"));
        WebSocketSession webSocket = webSocket("ws://localhost/ws/stt?sampleRate=16000");
        handler.afterConnectionEstablished(webSocket);

        handler.handleBinaryMessage(webSocket, new BinaryMessage(new byte[3200]));

        assertThat(sentPayloads(webSocket, 2).get(1)).contains("\"code\":\"overloaded\"");
        ArgumentCaptor<CloseStatus> status = ArgumentCaptor.forClass(CloseStatus.class);
        verify(webSocket).close(status.capture());
        assertThat(status.getValue().getCode()).isEqualTo(CloseStatus.SERVICE_OVERLOAD.getCode());
    }

    private SpeechWebSocketHandler handler(SttProperties properties) {
        return new SpeechWebSocketHandler(engine, properties, limiter, executor);
    }

    private static WebSocketSession webSocket(String uri) {
        WebSocketSession webSocket = mock(WebSocketSession.class);
        when(webSocket.getUri()).thenReturn(URI.create(uri));
        when(webSocket.getAttributes()).thenReturn(new HashMap<>());
        return webSocket;
    }

    private static List<String> sentPayloads(WebSocketSession webSocket, int expectedCount) throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(webSocket, timeout(3000).times(expectedCount)).sendMessage(captor.capture());
        return captor.getAllValues().stream()
                .map(TextMessage::getPayload)
                .toList();
    }
}
