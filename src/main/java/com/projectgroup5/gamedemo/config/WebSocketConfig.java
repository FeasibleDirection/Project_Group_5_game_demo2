package com.projectgroup5.gamedemo.config;

import com.projectgroup5.gamedemo.websocket.GameWebSocketHandler;
import com.projectgroup5.gamedemo.websocket.GameWebSocketHandlerB;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;

/**
 * WebSocket配置 - Architecture A 的实时通信层
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final GameWebSocketHandler gameWebSocketHandlerA;
    private final GameWebSocketHandlerB gameWebSocketHandlerB;

    public WebSocketConfig(GameWebSocketHandler gameWebSocketHandlerA,
                           GameWebSocketHandlerB gameWebSocketHandlerB) {
        this.gameWebSocketHandlerA = gameWebSocketHandlerA;
        this.gameWebSocketHandlerB = gameWebSocketHandlerB;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Architecture A (服务器权威)
        registry.addHandler(gameWebSocketHandlerA, "/ws/game")
                .setAllowedOrigins("*");

        // Architecture B (P2P Host，经服务器中转)
        registry.addHandler(gameWebSocketHandlerB, "/ws/game-b")
                .setAllowedOrigins("*");
    }
}

