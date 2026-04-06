package com.chat.p2p.config;

import com.chat.p2p.service.P2PNetworkService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final P2PNetworkService networkService;

    public WebSocketConfig(P2PNetworkService networkService) {
        this.networkService = networkService;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new ChatWebSocketHandler(networkService), "/ws")
            .setAllowedOrigins("*");
    }
}
