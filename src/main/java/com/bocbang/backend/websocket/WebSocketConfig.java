package com.bocbang.backend.websocket;

import com.bocbang.backend.stt.SttProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    /** Text frames only carry short JSON commands. */
    private static final int MAX_TEXT_FRAME_BYTES = 4096;

    private final SpeechWebSocketHandler speechHandler;
    private final SttProperties.WebSocket settings;

    public WebSocketConfig(SpeechWebSocketHandler speechHandler, SttProperties properties) {
        this.speechHandler = speechHandler;
        this.settings = properties.websocket();
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Android clients send no Origin header; set stt.websocket.allowed-origins before exposing the server publicly.
        registry.addHandler(speechHandler, "/ws/stt")
                .setAllowedOriginPatterns(settings.allowedOrigins().toArray(String[]::new));
    }

    /** The container default of 8 KB would close the connection on any audio frame longer than ~250 ms at 16 kHz. */
    @Bean
    ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxBinaryMessageBufferSize(settings.maxFrameBytes());
        container.setMaxTextMessageBufferSize(MAX_TEXT_FRAME_BYTES);
        container.setMaxSessionIdleTimeout(settings.idleTimeout().toMillis());
        return container;
    }
}
