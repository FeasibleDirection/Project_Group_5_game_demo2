package com.projectgroup5.gamedemo.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projectgroup5.gamedemo.dao.GameLogRepository;
import com.projectgroup5.gamedemo.entity.GameLog;
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
 * Architecture B: P2P Gossip via Server Relay
 *
 * 完全去中心化的P2P架构：
 *   1) 认证 / 房间校验
 *   2) 管理 WebSocket 连接
 *   3) 转发所有消息给房间其他玩家
 *   4) 打印所有消息到控制台（日志）
 *
 * 每个用户平等：
 *   - 每个用户生成自己的石头（username_asteroidId）
 *   - 每个用户本地计算碰撞
 *   - 每个用户广播自己的状态
 *   - 服务器只做消息中转，不做任何游戏逻辑
 */
@Component
public class GameWebSocketHandlerB extends TextWebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(GameWebSocketHandlerB.class);

    private final AuthService authService;
    private final LobbyService lobbyService;
    private final ObjectMapper objectMapper;
    GameLogRepository gameLogRepository;

    // sessionId -> WebSocketSession
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    // sessionId -> PlayerConnection
    private final Map<String, PlayerConnection> connections = new ConcurrentHashMap<>();

    // roomId -> Set<sessionId>
    private final Map<Long, Set<String>> roomSessions = new ConcurrentHashMap<>();

    // 🔥 游戏结束投票：roomId -> Map<username, VoteInfo>
    private final Map<Long, Map<String, GameEndVote>> gameEndVotes = new ConcurrentHashMap<>();

    // 🔥 房间游戏开始时间：roomId -> startTime
    private final Map<Long, Long> roomStartTimes = new ConcurrentHashMap<>();

    public GameWebSocketHandlerB(AuthService authService,
                                LobbyService lobbyService,
                                GameLogRepository gameLogRepository,
                                ObjectMapper objectMapper) {
        this.authService = authService;
        this.lobbyService = lobbyService;
        this.gameLogRepository = gameLogRepository;
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

                case "LEAVE_GAME":
                    handleLeaveGame(session);
                    break;

                case "GAME_END_VOTE":
                    handleGameEndVote(session, msg);
                    break;

                // 🔥 P2P Gossip：所有游戏消息都直接转发+打印日志
                case "PLAYER_POSITION":
                case "ASTEROID_SPAWN":
                case "ASTEROID_POSITION":
                case "BULLET_FIRED":
                case "BULLET_POSITION":
                case "BULLET_HIT_ASTEROID":
                case "PLAYER_HIT":
                case "PLAYER_DEAD":
                case "SCORE_UPDATE":
                    handleGossipMessage(session, msg, type);
                    break;

                default:
                    logger.warn("[ArchB-Gossip] Unknown message type: {}", type);
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
     * JOIN_GAME_B: 玩家加入游戏
     * P2P Gossip 模式：无Host概念，所有玩家平等
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

        // 2. 检查玩家是否在房间
        if (!lobbyService.isPlayerInRoom(roomId, username)) {
            sendJson(session, Map.of(
                    "type", "NOT_IN_ROOM",
                    "message", "Not in room (Arch B - Gossip)"
            ));
            session.close();
            return;
        }

        // 3. 注册连接
        connections.put(sessionId, new PlayerConnection(roomId, username));
        roomSessions.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet())
                .add(sessionId);

        // 🔥 记录游戏开始时间（第一个玩家加入时）
        roomStartTimes.putIfAbsent(roomId, System.currentTimeMillis());

        logger.info("[ArchB-Gossip] Player {} joined room {} (peer-to-peer)", username, roomId);

        // 4. 告诉客户端加入成功（所有玩家平等，无Host）
        sendJson(session, Map.of(
                "type", "JOINED_B",
                "roomId", roomId,
                "username", username,
                "architecture", "B-Gossip",
                "players", getRoomPlayerUsernames(roomId)
        ));

        // 5. 通知房间其他玩家：新玩家进入
        Map<String, Object> joinEvent = new HashMap<>();
        joinEvent.put("type", "PLAYER_JOINED");
        joinEvent.put("username", username);
        joinEvent.put("players", getRoomPlayerUsernames(roomId));
        broadcastToRoomExcept(roomId, joinEvent, sessionId);
    }

    /**
     * 🔥 P2P Gossip消息处理：接收、打印日志、转发
     * 
     * 消息类型：
     * - PLAYER_POSITION: 玩家位置
     * - ASTEROID_SPAWN: 石头生成
     * - ASTEROID_POSITION: 石头位置
     * - BULLET_FIRED: 子弹发射
     * - BULLET_POSITION: 子弹位置
     * - BULLET_HIT_ASTEROID: 子弹命中石头
     * - PLAYER_HIT: 玩家被撞
     * - PLAYER_DEAD: 玩家死亡
     * - SCORE_UPDATE: 分数更新
     */
    private void handleGossipMessage(WebSocketSession session, Map<String, Object> msg, String type) {
        PlayerConnection conn = connections.get(session.getId());
        if (conn == null) return;

        String username = conn.username;
        long roomId = conn.roomId;

        // 🔥 打印详细日志到控制台
        logGossipMessage(type, username, msg);

        // 转发给房间其他玩家（不包括发送者自己）
        broadcastToRoomExcept(roomId, msg, session.getId());
    }

    /**
     * 打印P2P Gossip消息到控制台
     */
    private void logGossipMessage(String type, String username, Map<String, Object> msg) {
        switch (type) {
            case "PLAYER_POSITION":
                logger.info("[ArchB-Gossip] [{}] PLAYER_POSITION: x={}, y={}",
                        username, msg.get("x"), msg.get("y"));
                break;

            case "ASTEROID_SPAWN":
                logger.info("[ArchB-Gossip] [{}] ASTEROID_SPAWN: id={} at ({}, {}), radius={}, hp={}",
                        username, msg.get("asteroidId"), msg.get("x"), msg.get("y"),
                        msg.get("radius"), msg.get("hp"));
                break;

            case "ASTEROID_POSITION":
                logger.info("[ArchB-Gossip] [{}] ASTEROID_POSITION: id={} at ({}, {})",
                        username, msg.get("asteroidId"), msg.get("x"), msg.get("y"));
                break;

            case "BULLET_FIRED":
                logger.info("[ArchB-Gossip] [{}] BULLET_FIRED: id={} at ({}, {})",
                        username, msg.get("bulletId"), msg.get("x"), msg.get("y"));
                break;

            case "BULLET_POSITION":
                logger.info("[ArchB-Gossip] [{}] BULLET_POSITION: id={} at ({}, {})",
                        username, msg.get("bulletId"), msg.get("x"), msg.get("y"));
                break;

            case "BULLET_HIT_ASTEROID":
                logger.info("[ArchB-Gossip] [{}] BULLET_HIT_ASTEROID: bullet={} hit asteroid={} (owner={})",
                        username, msg.get("bulletId"), msg.get("asteroidId"), msg.get("asteroidOwner"));
                break;

            case "PLAYER_HIT":
                logger.info("[ArchB-Gossip] [{}] PLAYER_HIT: by asteroid={}, hp={}",
                        username, msg.get("asteroidId"), msg.get("hp"));
                break;

            case "PLAYER_DEAD":
                logger.info("[ArchB-Gossip] [{}] PLAYER_DEAD", username);
                break;

            case "SCORE_UPDATE":
                logger.info("[ArchB-Gossip] [{}] SCORE_UPDATE: score={}, reason={}",
                        username, msg.get("score"), msg.get("reason"));
                break;

            case "ASTEROID_DESTROYED":
                logger.info("[ArchB-Gossip] [{}] ASTEROID_DESTROYED: id={}, reason={}",
                        username, msg.get("asteroidId"), msg.get("reason"));
                break;

            case "BULLET_DESTROYED":
                logger.info("[ArchB-Gossip] [{}] BULLET_DESTROYED: id={}, reason={}",
                        username, msg.get("bulletId"), msg.get("reason"));
                break;

            default:
                logger.info("[ArchB-Gossip] [{}] {}: {}", username, type, msg);
        }
    }

    private void handleLeaveGame(WebSocketSession session) {
        cleanupConnection(session.getId());
    }

    /**
     * 🔥 处理游戏结束投票
     */
    private void handleGameEndVote(WebSocketSession session, Map<String, Object> msg) {
        PlayerConnection conn = connections.get(session.getId());
        if (conn == null) return;

        String username = conn.username;
        long roomId = conn.roomId;
        String reason = (String) msg.get("reason");
        Object timestampObj = msg.get("timestamp");
        long timestamp = timestampObj instanceof Number ? 
            ((Number) timestampObj).longValue() : System.currentTimeMillis();
        
        // 🔥 获取玩家最终游戏数据
        int score = msg.get("score") instanceof Number ? ((Number) msg.get("score")).intValue() : 0;
        int hp = msg.get("hp") instanceof Number ? ((Number) msg.get("hp")).intValue() : 0;
        boolean alive = msg.get("alive") instanceof Boolean ? (Boolean) msg.get("alive") : false;

        logger.info("[ArchB-Gossip] [{}] GAME_END_VOTE: reason={}, score={}, hp={}, alive={}, timestamp={}", 
                username, reason, score, hp, alive, timestamp);

        // 记录投票（包含玩家数据）
        gameEndVotes.computeIfAbsent(roomId, k -> new ConcurrentHashMap<>())
                .put(username, new GameEndVote(username, reason, timestamp, score, hp, alive));

        // 检查是否所有玩家都投票了
        checkAndFinalizeGameEnd(roomId);
    }

    /**
     * 检查是否所有玩家都投票，如果是则结束游戏
     */
    private void checkAndFinalizeGameEnd(long roomId) {
        List<String> allPlayers = getRoomPlayerUsernames(roomId);
        Map<String, GameEndVote> votes = gameEndVotes.get(roomId);
        
        if (votes == null || allPlayers.isEmpty()) return;

        // 检查是否所有玩家都投票
        boolean allVoted = allPlayers.stream().allMatch(votes::containsKey);
        
        if (allVoted) {
            logger.info("[ArchB-Gossip] Room {} all players voted, ending game", roomId);
            
            // 统计投票结果
            Map<String, Long> reasonCounts = new HashMap<>();
            for (GameEndVote vote : votes.values()) {
                reasonCounts.merge(vote.reason, 1L, Long::sum);
            }
            
            // 找到最多票的原因
            String finalReason = reasonCounts.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse("UNKNOWN");
            
            logger.info("[ArchB-Gossip] Room {} game end reason: {}, votes: {}", 
                    roomId, finalReason, reasonCounts);

            // 保存游戏记录到数据库
            saveGameLog(roomId, votes, finalReason);

            // 🔥 重置房间状态（让玩家可以重新开始）
            lobbyService.resetRoomAfterGame(roomId);

            // 通知所有玩家游戏结束
            Map<String, Object> endMsg = new HashMap<>();
            endMsg.put("type", "GAME_ENDED");
            endMsg.put("reason", finalReason);
            endMsg.put("votes", votes);
            broadcastToRoom(roomId, endMsg);

            // 清理投票记录
            gameEndVotes.remove(roomId);
            roomStartTimes.remove(roomId);
        }
    }

    /**
     * 保存游戏记录到数据库（格式与架构A类似）
     */
    private void saveGameLog(long roomId, Map<String, GameEndVote> votes, String finalReason) {
        try {
            Long startTime = roomStartTimes.get(roomId);
            if (startTime == null) {
                startTime = System.currentTimeMillis() - 60000; // 默认1分钟前
            }
            long endTime = System.currentTimeMillis();
            long elapsedMs = endTime - startTime;
            
            // 🔥 构建players列表（与架构A格式一致）
            List<Map<String, Object>> players = new ArrayList<>();
            for (GameEndVote vote : votes.values()) {
                Map<String, Object> playerData = new LinkedHashMap<>();
                playerData.put("username", vote.username);
                playerData.put("score", vote.score);
                playerData.put("hp", vote.hp);
                playerData.put("alive", vote.alive);
                playerData.put("elapsedMillis", elapsedMs);
                players.add(playerData);
            }
            
            // 🔥 找出获胜者（分数最高的玩家）
            String winner = votes.values().stream()
                    .max(Comparator.comparingInt(v -> v.score))
                    .map(v -> v.username)
                    .orElse("NONE");
            
            // 🔥 构建metadata
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("winner", winner);
            metadata.put("mapName", "Unknown"); // 架构B没有地图信息
            metadata.put("winMode", "Unknown"); // 架构B没有胜利模式信息
            metadata.put("maxPlayers", votes.size());
            metadata.put("architecture", "B");
            metadata.put("finalReason", finalReason);
            
            // 🔥 构建events（记录每个玩家的投票原因）
            Map<String, String> events = new LinkedHashMap<>();
            for (GameEndVote vote : votes.values()) {
                events.put(vote.username, vote.reason);
            }
            metadata.put("events", events);
            
            // 🔥 构建result_json（与架构A格式一致）
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("players", players);
            root.put("metadata", metadata);
            
            String resultJson = objectMapper.writeValueAsString(root);
            
            // 🔥 保存到数据库
            GameLog log = new GameLog();
            log.setRoomId(roomId);
            log.setStartedAt(startTime);
            log.setEndedAt(endTime);
            log.setResultJson(resultJson);
            gameLogRepository.insert(log);
            
            logger.info("[ArchB-Gossip] Room {} game log saved to database", roomId);
            logger.info("[ArchB-Gossip] Room {} duration: {}ms, winner: {}, players: {}", 
                    roomId, elapsedMs, winner, votes.size());
            
        } catch (Exception e) {
            logger.error("[ArchB-Gossip] Failed to save game log for room {}", roomId, e);
        }
    }

    // --- 工具方法 ---

    /**
     * 清理玩家连接（P2P Gossip模式：无Host概念）
     */
    private void cleanupConnection(String sessionId) {
        PlayerConnection conn = connections.remove(sessionId);
        if (conn == null) return;

        long roomId = conn.roomId;
        String username = conn.username;

        Set<String> set = roomSessions.get(roomId);
        if (set != null) {
            set.remove(sessionId);
            if (set.isEmpty()) {
                roomSessions.remove(roomId);
                logger.info("[ArchB-Gossip] Room {} all players left, cleared.", roomId);
            } else {
                // 通知其他玩家：有人离开了
                Map<String, Object> leaveEvent = new HashMap<>();
                leaveEvent.put("type", "PLAYER_LEFT");
                leaveEvent.put("username", username);
                leaveEvent.put("players", getRoomPlayerUsernames(roomId));
                broadcastToRoom(roomId, leaveEvent);
                
                logger.info("[ArchB-Gossip] Player {} left room {}, {} players remaining",
                        username, roomId, set.size());
            }
        }
    }

    private void sendJson(WebSocketSession session, Map<String, Object> data) throws IOException {
        String json = objectMapper.writeValueAsString(data);
        session.sendMessage(new TextMessage(json));
    }

    /**
     * 广播消息给房间所有玩家
     */
    private void broadcastToRoom(long roomId, Map<String, Object> data) {
        String json;
        try {
            json = objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            logger.error("[ArchB-Gossip] Failed to serialize broadcast json", e);
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
                    logger.error("[ArchB-Gossip] Failed to send msg to session {}", sid, e);
                }
            }
        }
    }

    /**
     * 广播消息给房间所有玩家（排除指定session）
     */
    private void broadcastToRoomExcept(long roomId, Map<String, Object> data, String exceptSessionId) {
        String json;
        try {
            json = objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            logger.error("[ArchB-Gossip] Failed to serialize broadcast json", e);
            return;
        }

        Set<String> set = roomSessions.get(roomId);
        if (set == null) return;

        for (String sid : set) {
            if (sid.equals(exceptSessionId)) continue; // 跳过发送者

            WebSocketSession session = sessions.get(sid);
            if (session != null && session.isOpen()) {
                try {
                    session.sendMessage(new TextMessage(json));
                } catch (IOException e) {
                    logger.error("[ArchB-Gossip] Failed to send msg to session {}", sid, e);
                }
            }
        }
    }

    /**
     * 获取房间所有玩家用户名列表
     */
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

    /** 房间内 WebSocket 连接信息 */
    private static class PlayerConnection {
        final long roomId;
        final String username;
        PlayerConnection(long roomId, String username) {
            this.roomId = roomId;
            this.username = username;
        }
    }

    /** 游戏结束投票信息（包含玩家最终数据） */
    private static class GameEndVote {
        final String username;
        final String reason;
        final long timestamp;
        final int score;
        final int hp;
        final boolean alive;
        
        GameEndVote(String username, String reason, long timestamp, int score, int hp, boolean alive) {
            this.username = username;
            this.reason = reason;
            this.timestamp = timestamp;
            this.score = score;
            this.hp = hp;
            this.alive = alive;
        }

        public String getUsername() { return username; }
        public String getReason() { return reason; }
        public long getTimestamp() { return timestamp; }
        public int getScore() { return score; }
        public int getHp() { return hp; }
        public boolean isAlive() { return alive; }
    }
}
