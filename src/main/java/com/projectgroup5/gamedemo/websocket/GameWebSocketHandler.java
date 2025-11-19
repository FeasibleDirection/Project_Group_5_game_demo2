package com.projectgroup5.gamedemo.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projectgroup5.gamedemo.event.EventBus;
import com.projectgroup5.gamedemo.event.InputReceivedEvent;
import com.projectgroup5.gamedemo.game.*;
import com.projectgroup5.gamedemo.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket核心处理器 - Architecture A 的网络层
 * 职责：
 * 1. 管理WebSocket连接
 * 2. 接收客户端输入
 * 3. 广播服务器状态
 * 4. 房间权限验证
 */
@Component
public class GameWebSocketHandler extends TextWebSocketHandler {
    private static final Logger logger = LoggerFactory.getLogger(GameWebSocketHandler.class);
    
    private final GameRoomManager roomManager;
    private final PhysicsEngine physicsEngine;
    private final AuthService authService;
    private final EventBus eventBus;
    private final ObjectMapper objectMapper;
    
    // sessionId -> WebSocketSession
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    
    // sessionId -> PlayerConnection
    private final Map<String, PlayerConnection> connections = new ConcurrentHashMap<>();
    
    // roomId -> Set<sessionId>
    private final Map<Long, Set<String>> roomSessions = new ConcurrentHashMap<>();
    
    public GameWebSocketHandler(GameRoomManager roomManager,
                               PhysicsEngine physicsEngine,
                               AuthService authService,
                               EventBus eventBus,
                               ObjectMapper objectMapper) {
        this.roomManager = roomManager;
        this.physicsEngine = physicsEngine;
        this.authService = authService;
        this.eventBus = eventBus;
        this.objectMapper = objectMapper;
    }
    
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = session.getId();
        sessions.put(sessionId, session);
        logger.info("WebSocket connected: {}", sessionId);
        
        // 发送连接确认
        sendMessage(session, Map.of("type", "CONNECTED", "sessionId", sessionId));
    }
    
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String sessionId = session.getId();
        String payload = message.getPayload();

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> msg = objectMapper.readValue(payload, Map.class);
            String type = (String) msg.get("type");

            switch (type) {
                case "JOIN_GAME":
                    handleJoinGame(session, msg);
                    break;

                case "PLAYER_INPUT":
                    handlePlayerInput(session, msg);
                    break;

                case "LEAVE_GAME":
                    handleLeaveGame(session);
                    break;

                default:
                    logger.warn("Unknown message type: {}", type);
            }

        } catch (Exception e) {
            logger.error("Error handling message from {}", sessionId, e);
            sendMessage(session, Map.of("type", "ERROR", "message", e.getMessage()));
        }
    }

    
    /**
     * 处理玩家加入游戏（Architecture A）
     */
    private void handleJoinGame(WebSocketSession session, Map<String, Object> msg) throws IOException {
        String username = (String) msg.get("username");
        String token = (String) msg.get("token");
        Long roomId = ((Number) msg.get("roomId")).longValue();

        // 1. 验证Token
        boolean validToken = authService.getUserByToken(token)
            .map(u -> u.getUsername().equals(username))
            .orElse(false);

        if (!validToken) {
            sendMessage(session, Map.of("type", "ERROR", "message", "Invalid token"));
            session.close();
            return;
        }

        // 2. 检查玩家是否在房间中
        if (!roomManager.isPlayerInRoom(roomId, username)) {
            sendMessage(session, Map.of(
                "type", "NOT_IN_ROOM",
                "message", "你不在这个房间中，即将返回大厅"
            ));
            session.close();
            return;
        }

        // 3. 注册连接
        String sessionId = session.getId();
        connections.put(sessionId, new PlayerConnection(roomId, username));
        roomSessions.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(sessionId);

        logger.info("Player {} joined game room {} (Architecture A)", username, roomId);

        // 4. 发送加入成功消息
        sendMessage(session, Map.of(
            "type", "JOINED",
            "roomId", roomId,
            "username", username,
            "architecture", "A"
        ));
    }
    
    /**
     * 处理玩家输入（Architecture A核心）
     * 客户端只发送输入，不发送位置/状态
     */
    private void handlePlayerInput(WebSocketSession session, Map<String, Object> msg) {
        PlayerConnection conn = connections.get(session.getId());
        if (conn == null) return;
        
        Optional<GameWorld> worldOpt = roomManager.getGameRoom(conn.roomId);
        if (worldOpt.isEmpty()) return;
        
        GameWorld world = worldOpt.get();
        if (world.getPhase() != GameWorld.GamePhase.IN_PROGRESS) return;
        
        PlayerEntity player = world.getPlayers().get(conn.username);
        if (player == null || !player.alive) return;
        
        // 解析输入
        PlayerInput input = new PlayerInput();
        input.setUsername(conn.username);
        input.setMoveUp((Boolean) msg.getOrDefault("moveUp", false));
        input.setMoveDown((Boolean) msg.getOrDefault("moveDown", false));
        input.setMoveLeft((Boolean) msg.getOrDefault("moveLeft", false));
        input.setMoveRight((Boolean) msg.getOrDefault("moveRight", false));
        input.setFire((Boolean) msg.getOrDefault("fire", false));
        input.setTimestamp(System.currentTimeMillis());
        
        // 发布输入事件
        eventBus.publish(new InputReceivedEvent(conn.roomId, conn.username, input));
        
        // 应用移动（服务器权威）
        physicsEngine.applyPlayerInput(player, input);
        
        // 处理射击（服务器权威 + 反作弊）
        if (input.isFire()) {
            long now = System.currentTimeMillis();
            if (physicsEngine.canFire(player, now)) {
                BulletEntity bullet = physicsEngine.createBullet(
                    player.username,
                    player.x,
                    player.y - PlayerEntity.HEIGHT / 2
                );
                world.getBullets().put(bullet.id, bullet);
                player.lastFireTime = now;
            }
        }
    }
    
    /**
     * 处理玩家离开
     */
    private void handleLeaveGame(WebSocketSession session) {
        cleanupConnection(session.getId());
    }
    
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        cleanupConnection(session.getId());
        logger.info("WebSocket disconnected: {}, status: {}", session.getId(), status);
    }
    
    private void cleanupConnection(String sessionId) {
        PlayerConnection conn = connections.remove(sessionId);
        if (conn != null) {
            Set<String> roomSessionSet = roomSessions.get(conn.roomId);
            if (roomSessionSet != null) {
                roomSessionSet.remove(sessionId);
            }
            logger.info("Player {} left game room {}", conn.username, conn.roomId);
        }
        sessions.remove(sessionId);
    }
    
    /**
     * 广播消息到指定房间的所有玩家
     */
    public void broadcastToRoom(long roomId, String message) {
        Set<String> roomSessionIds = roomSessions.get(roomId);
        if (roomSessionIds == null) return;
        
        roomSessionIds.forEach(sessionId -> {
            WebSocketSession session = sessions.get(sessionId);
            if (session != null && session.isOpen()) {
                try {
                    session.sendMessage(new TextMessage(message));
                } catch (IOException e) {
                    logger.error("Failed to send message to session {}", sessionId, e);
                }
            }
        });
    }
    
    private void sendMessage(WebSocketSession session, Map<String, Object> data) throws IOException {
        String json = objectMapper.writeValueAsString(data);
        session.sendMessage(new TextMessage(json));
    }
    
    /**
     * 玩家连接信息
     */
    private static class PlayerConnection {
        final long roomId;
        final String username;
        
        PlayerConnection(long roomId, String username) {
            this.roomId = roomId;
            this.username = username;
        }
    }

    public void broadcastGameState(GameWorld world) {
        try {
            Map<String, Object> data = Map.of(
                    "type", "GAME_STATE",
                    "players", world.getPlayers().values(),
                    "bullets", world.getBullets().values(),
                    "asteroids", world.getAsteroids().values(),
                    "phase", world.getPhase().name(),
                    "frame", world.getCurrentFrameNumber()
            );

            String json = objectMapper.writeValueAsString(data);
            broadcastToRoom(world.getRoomId(), json);

        } catch (Exception e) {
            logger.error("Failed to broadcast game state", e);
        }
    }

}

