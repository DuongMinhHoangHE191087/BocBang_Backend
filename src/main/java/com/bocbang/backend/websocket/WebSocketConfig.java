package com.bocbang.backend.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final SpeechWebSocketHandler speechHandler;

    public WebSocketConfig(SpeechWebSocketHandler speechHandler) {
        this.speechHandler = speechHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Android clients send no Origin header; restrict this before exposing the server publicly.
        registry.addHandler(speechHandler, "/ws/stt").setAllowedOriginPatterns("*");
    }
}
