package com.projectgroup5.gamedemo.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projectgroup5.gamedemo.service.AuthService;
import com.projectgroup5.gamedemo.service.LobbyService;
import com.projectgroup5.gamedemo.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Architecture B: P2P Lockstep via Server Relay
 *
 * - 不做物理计算，只负责：
 *   1) 认证 / 房间校验
 *   2) 管理 WebSocket 连接
 *   3) 维护每个房间的 host（房主）/ peers
 *   4) 转发 LOCKSTEP_INPUT / GAME_STATE / HOST_CHANGED 等消息
 *
 * 真正的游戏模拟在 Host 浏览器里运行。
 */
@Component
public class GameWebSocketHandlerB extends TextWebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(GameWebSocketHandlerB.class);

    private final AuthService authService;
    private final LobbyService lobbyService;
    private final ObjectMapper objectMapper;

    // sessionId -> WebSocketSession
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    // sessionId -> PlayerConnection
    private final Map<String, PlayerConnection> connections = new ConcurrentHashMap<>();

    // roomId -> Set<sessionId>
    private final Map<Long, Set<String>> roomSessions = new ConcurrentHashMap<>();

    // roomId -> hostSessionId  (Architecture B: 哪个 peer 当前是 Host)
    private final Map<Long, String> roomHosts = new ConcurrentHashMap<>();

    public GameWebSocketHandlerB(AuthService authService,
                                 LobbyService lobbyService,
                                 ObjectMapper objectMapper) {
        this.authService = authService;
        this.lobbyService = lobbyService;
        this.objectMapper = objectMapper;
    }

    // --- WebSocket 生命周期 ---

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = session.getId();
        sessions.put(sessionId, session);
        logger.info("[ArchB] WebSocket connected: {}", sessionId);

        sendJson(session, Map.of(
                "type", "CONNECTED",
                "arch", "B",
                "sessionId", sessionId
        ));
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
                case "JOIN_GAME_B":
                    handleJoinGame(session, msg);
                    break;

                case "LOCKSTEP_INPUT":
                    handleLockstepInput(session, msg);
                    break;

                case "GAME_STATE":
                    handleGameStateFromHost(session, msg);
                    break;

                case "LEAVE_GAME":
                    handleLeaveGame(session);
                    break;

                default:
                    logger.warn("[ArchB] Unknown message type: {}", type);
            }

        } catch (Exception e) {
            logger.error("[ArchB] Error handling message from {}", sessionId, e);
            sendJson(session, Map.of("type", "ERROR", "message", e.getMessage()));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        cleanupConnection(session.getId());
        logger.info("[ArchB] WebSocket disconnected: {}, status={}", session.getId(), status);
    }

    // --- 业务处理 ---

    /**
     * JOIN_GAME_B:
     * {
     *   type: "JOIN_GAME_B",
     *   roomId, username, token
     * }
     */
    private void handleJoinGame(WebSocketSession session, Map<String, Object> msg) throws IOException {
        String username = (String) msg.get("username");
        String token = (String) msg.get("token");
        Long roomId = ((Number) msg.get("roomId")).longValue();
        String sessionId = session.getId();

        // 1. Token 校验
        boolean validToken = authService.getUserByToken(token)
                .map(User::getUsername)
                .map(name -> name.equals(username))
                .orElse(false);

        if (!validToken) {
            sendJson(session, Map.of("type", "ERROR", "message", "Invalid token"));
            session.close();
            return;
        }

        // 2. 检查玩家是否在房间 (LobbyService / GameRoomManager 已维护)
        if (!lobbyService.isPlayerInRoom(roomId, username)) {
            sendJson(session, Map.of(
                    "type", "NOT_IN_ROOM",
                    "message", "Not in room (Arch B)"
            ));
            session.close();
            return;
        }

        // 3. 注册连接
        connections.put(sessionId, new PlayerConnection(roomId, username));
        roomSessions.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet())
                .add(sessionId);

        // 4. 如果房间没有 host，则当前玩家成为 host
        roomHosts.computeIfAbsent(roomId, rid -> {
            logger.info("[ArchB] Room {} host set to {}", rid, username);
            return sessionId;
        });

        boolean isHost = roomHosts.get(roomId).equals(sessionId);

        logger.info("[ArchB] Player {} joined room {} (sessionId={}, isHost={})",
                username, roomId, sessionId, isHost);

        // 5. 告诉这个客户端它是否是 host
        sendJson(session, Map.of(
                "type", "JOINED_B",
                "roomId", roomId,
                "username", username,
                "isHost", isHost,
                "architecture", "B"
        ));

        // 6. 通知房间其它玩家：新玩家进入 & host是谁
        broadcastToRoom(roomId, Map.of(
                "type", "ROOM_STATE_B",
                "roomId", roomId,
                "hostUsername", getHostUsername(roomId),
                "players", getRoomPlayerUsernames(roomId)
        ));
    }

    /**
     * LOCKSTEP_INPUT:
     * 普通玩家 / host 都可以发输入。
     * 服务器只负责转发给房间内其他 peer，特别是 Host。
     */
    private void handleLockstepInput(WebSocketSession session, Map<String, Object> msg) {
        PlayerConnection conn = connections.get(session.getId());
        if (conn == null) return;

        Long roomId = conn.roomId;

        Map<String, Object> forward = new HashMap<>(msg);
        forward.put("from", conn.username);  // 标记是谁发的

        // 转发给房间所有人（含 host，含自己也可以，前端自己忽略）
        broadcastToRoom(roomId, forward);
    }

    /**
     * GAME_STATE:
     * 只有当前房间的 host 可以发 GAME_STATE，服务器负责转发给其他 peer。
     * （相当于 host 做服务器那一套 GameWorld + Physics）
     */
    private void handleGameStateFromHost(WebSocketSession session, Map<String, Object> msg) {
        PlayerConnection conn = connections.get(session.getId());
        if (conn == null) return;

        Long roomId = conn.roomId;
        String hostSessionId = roomHosts.get(roomId);

        // 必须是 host 才能发送 GAME_STATE
        if (!session.getId().equals(hostSessionId)) {
            logger.warn("[ArchB] Non-host {} tried to send GAME_STATE for room {}", conn.username, roomId);
            return;
        }

        // 给整个房间广播（包括 host 自己）
        broadcastToRoom(roomId, msg);
    }

    private void handleLeaveGame(WebSocketSession session) {
        cleanupConnection(session.getId());
    }

    // --- 工具方法 ---

    private void cleanupConnection(String sessionId) {
        PlayerConnection conn = connections.remove(sessionId);
        if (conn == null) return;

        long roomId = conn.roomId;

        Set<String> set = roomSessions.get(roomId);
        if (set != null) {
            set.remove(sessionId);
            if (set.isEmpty()) {
                roomSessions.remove(roomId);
                roomHosts.remove(roomId);
                logger.info("[ArchB] Room {} all players left, cleared.", roomId);
            }
        }

        // 如果当前断开的是 host，需要重新选 host
        String hostSessionId = roomHosts.get(roomId);
        if (hostSessionId != null && hostSessionId.equals(sessionId)) {
            String newHost = null;
            if (set != null && !set.isEmpty()) {
                newHost = set.iterator().next();
                roomHosts.put(roomId, newHost);
            } else {
                roomHosts.remove(roomId);
            }

            logger.info("[ArchB] Room {} host {} left. New host sessionId={}",
                    roomId, conn.username, newHost);

            // 通知房间剩余玩家 host 变更
            if (newHost != null) {
                String hostName = connections.get(newHost).username;
                broadcastToRoom(roomId, Map.of(
                        "type", "HOST_CHANGED_B",
                        "roomId", roomId,
                        "hostUsername", hostName
                ));
            }
        }
    }

    private void sendJson(WebSocketSession session, Map<String, Object> data) throws IOException {
        String json = objectMapper.writeValueAsString(data);
        session.sendMessage(new TextMessage(json));
    }

    private void broadcastToRoom(long roomId, Map<String, Object> data) {
        String json;
        try {
            json = objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            logger.error("[ArchB] Failed to serialize broadcast json", e);
            return;
        }

        Set<String> set = roomSessions.get(roomId);
        if (set == null) return;

        for (String sid : set) {
            WebSocketSession session = sessions.get(sid);
            if (session != null && session.isOpen()) {
                try {
                    session.sendMessage(new TextMessage(json));
                } catch (IOException e) {
                    logger.error("[ArchB] Failed to send msg to session {}", sid, e);
                }
            }
        }
    }

    private List<String> getRoomPlayerUsernames(long roomId) {
        Set<String> set = roomSessions.get(roomId);
        if (set == null) return Collections.emptyList();

        List<String> users = new ArrayList<>();
        for (String sid : set) {
            PlayerConnection conn = connections.get(sid);
            if (conn != null) {
                users.add(conn.username);
            }
        }
        return users;
    }

    private String getHostUsername(long roomId) {
        String hostSessionId = roomHosts.get(roomId);
        if (hostSessionId == null) return null;
        PlayerConnection conn = connections.get(hostSessionId);
        return conn == null ? null : conn.username;
    }

    /** 房间内 WebSocket 连接信息 */
    private static class PlayerConnection {
        final long roomId;
        final String username;
        PlayerConnection(long roomId, String username) {
            this.roomId = roomId;
            this.username = username;
        }
    }
}
