package com.bocbang.backend.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bocbang.backend.stt.FakeSpeechToTextEngine;
import com.bocbang.backend.stt.SttProperties;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class SpeechWebSocketHandlerTest {

    private final FakeSpeechToTextEngine engine = new FakeSpeechToTextEngine("xin chào");
    private final SpeechWebSocketHandler handler =
            new SpeechWebSocketHandler(engine, new SttProperties(16000, new SttProperties.Vosk("unused")));

    @Test
    void sendsReadyThenPartialAndFinalTranscripts() throws Exception {
        WebSocketSession webSocket = webSocket("ws://localhost/ws/stt?sampleRate=16000");

        handler.afterConnectionEstablished(webSocket);
        handler.handleBinaryMessage(webSocket, new BinaryMessage(new byte[] {1, 0}));
        handler.handleTextMessage(webSocket, new TextMessage("{\"type\":\"end\"}"));

        List<String> sent = sentPayloads(webSocket, 3);
        assertThat(sent.get(0)).contains("\"type\":\"ready\"").contains("\"sampleRate\":16000");
        assertThat(sent.get(1)).contains("\"type\":\"partial\"").contains("\"text\":\"xin chào\"");
        assertThat(sent.get(2)).contains("\"type\":\"final\"").contains("\"text\":\"xin chào\"");
        assertThat(engine.openedSampleRates).containsExactly(16000);
    }

    @Test
    void closesRecognizerWhenConnectionCloses() throws Exception {
        WebSocketSession webSocket = webSocket("ws://localhost/ws/stt");
        handler.afterConnectionEstablished(webSocket);

        handler.afterConnectionClosed(webSocket, CloseStatus.NORMAL);

        assertThat(engine.closedSessions.get()).isEqualTo(1);
    }

    @Test
    void rejectsSampleRateOutsideSupportedRange() throws Exception {
        WebSocketSession webSocket = webSocket("ws://localhost/ws/stt?sampleRate=100");

        handler.afterConnectionEstablished(webSocket);

        assertThat(sentPayloads(webSocket, 1).get(0)).contains("\"type\":\"error\"");
        verify(webSocket).close(any(CloseStatus.class));
        assertThat(engine.openedSampleRates).isEmpty();
    }

    @Test
    void reportsOddLengthPcmFrameAsError() throws Exception {
        WebSocketSession webSocket = webSocket("ws://localhost/ws/stt");
        handler.afterConnectionEstablished(webSocket);

        handler.handleBinaryMessage(webSocket, new BinaryMessage(new byte[] {1, 2, 3}));

        assertThat(sentPayloads(webSocket, 2).get(1)).contains("\"type\":\"error\"");
        assertThat(engine.receivedBytes.get()).isZero();
    }

    private static WebSocketSession webSocket(String uri) {
        WebSocketSession webSocket = mock(WebSocketSession.class);
        when(webSocket.getUri()).thenReturn(URI.create(uri));
        when(webSocket.getAttributes()).thenReturn(new HashMap<>());
        return webSocket;
    }

    private static List<String> sentPayloads(WebSocketSession webSocket, int expectedCount) throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(webSocket, times(expectedCount)).sendMessage(captor.capture());
        return captor.getAllValues().stream()
                .map(TextMessage::getPayload)
                .toList();
    }
}
