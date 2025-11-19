package com.projectgroup5.gamedemo.game;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projectgroup5.gamedemo.dao.GameLogRepository;
import com.projectgroup5.gamedemo.entity.GameLog;
import com.projectgroup5.gamedemo.event.EventBus;
import com.projectgroup5.gamedemo.event.GameEndedEvent;
import com.projectgroup5.gamedemo.service.LobbyService;
import com.projectgroup5.gamedemo.websocket.GameWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 游戏主循环调度器 - Architecture A 核心
 * 25Hz (每40ms一帧) 的固定时间步长
 *
 * 数据流:
 * Client Input → WebSocket → Event:InputReceived → Physics Tick →
 * Collision Detection → Event:Collision/Score → State Snapshot →
 * WebSocket → Clients (Render)
 */
/**
 * Architecture A 的主游戏循环：
 * - 固定 25 FPS（40ms 一帧）
 * - 从 GameRoomManager 拿到所有活跃的 GameWorld
 * - 更新物理、碰撞
 * - 通过 GameWebSocketHandler 广播 GAME_STATE
 */
@Component
public class GameTickScheduler {

    private static final Logger logger = LoggerFactory.getLogger(GameTickScheduler.class);

    private static final double TICK_RATE = 25.0;        // 25 FPS
    private static final double DELTA_TIME = 1.0 / TICK_RATE; // 0.04s

    private final GameRoomManager roomManager;
    private final PhysicsEngine physicsEngine;
    private final GameWebSocketHandler webSocketHandler;
    private final EventBus eventBus;
    private final ObjectMapper objectMapper;
    private final GameLogRepository gameLogRepository;
    private final LobbyService lobbyService;

    public GameTickScheduler(GameRoomManager roomManager,
                             PhysicsEngine physicsEngine,
                             GameWebSocketHandler webSocketHandler,
                             EventBus eventBus,
                             ObjectMapper objectMapper,
                             GameLogRepository gameLogRepository,
                             LobbyService lobbyService) {
        this.roomManager = roomManager;
        this.physicsEngine = physicsEngine;
        this.webSocketHandler = webSocketHandler;
        this.eventBus = eventBus;
        this.objectMapper = objectMapper;
        this.gameLogRepository = gameLogRepository;
        this.lobbyService = lobbyService;
    }

    /** 唯一的游戏主循环 */
    @Scheduled(fixedRate = 40) // 约等于 25 FPS
    public void tick() {
        roomManager.getAllActiveGames().forEach((roomId, world) -> {
            try {
                processGameWorld(world);
            } catch (Exception e) {
                logger.error("Error processing game world {}", roomId, e);
            }
        });
    }

    private void processGameWorld(GameWorld world) {
        long now = System.currentTimeMillis();

        switch (world.getPhase()) {
            case WAITING:
                // 等待玩家，不动；纯靠 WebSocket JOIN 把玩家加进来
                broadcastGameState(world);
                break;

            case COUNTDOWN:
                if (now >= world.getGameStartTime()) {
                    world.setPhase(GameWorld.GamePhase.IN_PROGRESS);
                    logger.info("Game {} started", world.getRoomId());
                }
                broadcastGameState(world);
                break;

            case IN_PROGRESS:
                // 1) 物理更新：玩家位置（根据 velocity）、子弹、石头
                physicsEngine.updatePositions(world, DELTA_TIME);

                // 2) 碰撞检测：子弹 vs 石头、石头 vs 玩家、子弹 vs 玩家
                physicsEngine.detectCollisions(world);

                // 3) 检查是否满足胜利条件
                if (checkWinCondition(world)) {
                    finishGame(world);
                }

                // 4) 广播状态
                broadcastGameState(world);

                // 5) 帧号 +1
                world.incrementFrame();

                // 打一点简单日志看玩家是否存在
                if (!world.getPlayers().isEmpty()) {
                    world.getPlayers().values().forEach(p ->
                            logger.debug("Room {} Player {} at ({},{}), hp={}, score={}",
                                    world.getRoomId(), p.username, p.x, p.y, p.hp, p.score));
                }
                break;

            case FINISHED:
                // 已结束：广播一次最终状态即可，后面会被清理
                broadcastGameState(world);
                break;
        }
    }

    /** 胜利条件：你原来 winMode 的那一套逻辑 */
    private boolean checkWinCondition(GameWorld world) {
        String winMode = world.getWinMode();

        if (winMode.startsWith("SCORE_")) {
            int targetScore = Integer.parseInt(winMode.substring(6));
            return world.getPlayers().values().stream()
                    .anyMatch(p -> p.score >= targetScore);
        }

        if (winMode.startsWith("TIME_")) {
            String timeStr = winMode.substring(5);
            int minutes = Integer.parseInt(timeStr.substring(0, timeStr.length() - 1));
            long elapsed = System.currentTimeMillis() - world.getGameStartTime();
            return elapsed >= minutes * 60 * 1000L;
        }

        long alive = world.getPlayers().values().stream()
                .filter(p -> p.alive).count();
        return alive <= 1;
    }

    /** 结束游戏 + 写 GameLog + 通知 Lobby + 延迟清理 GameWorld */
    private void finishGame(GameWorld world) {
        if (world.getPhase() == GameWorld.GamePhase.FINISHED) return;

        world.setPhase(GameWorld.GamePhase.FINISHED);
        long now = System.currentTimeMillis();
        long elapsedMs = now - world.getGameStartTime();

        Map<String, Integer> finalScores = world.getPlayers().values().stream()
                .collect(Collectors.toMap(p -> p.username, p -> p.score));

        String winner = world.getPlayers().values().stream()
                .max(Comparator.comparingInt(p -> p.score))
                .map(p -> p.username)
                .orElse("N/A");

        eventBus.publish(new GameEndedEvent(world.getRoomId(), finalScores, winner));
        logger.info("Game {} finished, winner={}, scores={}",
                world.getRoomId(), winner, finalScores);

        // 保存到 GameLog
        try {
            List<Map<String, Object>> players = new ArrayList<>();
            for (PlayerEntity p : world.getPlayers().values()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("username", p.username);
                m.put("score", p.score);
                m.put("hp", p.hp);
                m.put("alive", p.alive);
                m.put("elapsedMillis", elapsedMs);
                players.add(m);
            }

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("winner", winner);
            meta.put("mapName", world.getMapName());
            meta.put("winMode", world.getWinMode());
            meta.put("maxPlayers", world.getMaxPlayers());
            meta.put("architecture", "A");
            meta.put("totalFrames", world.getCurrentFrameNumber());

            Map<String, Object> root = new LinkedHashMap<>();
            root.put("players", players);
            root.put("metadata", meta);

            String json = objectMapper.writeValueAsString(root);

            GameLog log = new GameLog();
            log.setRoomId(world.getRoomId());
            log.setStartedAt(world.getGameStartTime());
            log.setEndedAt(now);
            log.setResultJson(json);
            gameLogRepository.insert(log);

            logger.info("Game log saved for room {}", world.getRoomId());
        } catch (Exception e) {
            logger.error("Failed to save game log for room {}", world.getRoomId(), e);
        }

        // 通知 Lobby 把房间状态重置
        lobbyService.resetRoomAfterGame(world.getRoomId());

        // 5 秒后清理 GameWorld
        new Thread(() -> {
            try {
                Thread.sleep(5000);
                roomManager.removeGameRoom(world.getRoomId());
                logger.info("GameWorld removed for room {}", world.getRoomId());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    /** 构造 GAME_STATE JSON，并通过 GameWebSocketHandler 广播 */
    private void broadcastGameState(GameWorld world) {
        try {
            Map<String, Object> state = new HashMap<>();
            state.put("type", "GAME_STATE");
            state.put("roomId", world.getRoomId());
            state.put("frame", world.getCurrentFrameNumber());
            state.put("phase", world.getPhase().name());

            if (world.getPhase() == GameWorld.GamePhase.COUNTDOWN) {
                long remain = world.getGameStartTime() - System.currentTimeMillis();
                state.put("countdownMs", Math.max(0, remain));
            }

            if (world.getPhase() == GameWorld.GamePhase.IN_PROGRESS) {
                long elapsed = System.currentTimeMillis() - world.getGameStartTime();
                state.put("elapsedMs", elapsed);
            }

            List<Map<String, Object>> players = new ArrayList<>();
            world.getPlayers().forEach((u, p) -> {
                Map<String, Object> m = new HashMap<>();
                m.put("username", u);
                m.put("x", p.x);
                m.put("y", p.y);
                m.put("hp", p.hp);
                m.put("score", p.score);
                m.put("alive", p.alive);
                players.add(m);
            });
            state.put("players", players);

            List<Map<String, Object>> bullets = new ArrayList<>();
            world.getBullets().forEach((id, b) -> {
                Map<String, Object> m = new HashMap<>();
                m.put("id", id);
                m.put("owner", b.owner);
                m.put("x", b.x);
                m.put("y", b.y);
                bullets.add(m);
            });
            state.put("bullets", bullets);

            List<Map<String, Object>> asteroids = new ArrayList<>();
            world.getAsteroids().forEach((id, a) -> {
                Map<String, Object> m = new HashMap<>();
                m.put("id", id);
                m.put("x", a.x);
                m.put("y", a.y);
                m.put("radius", a.radius);
                m.put("hp", a.hp);
                m.put("isBig", a.isBig);
                asteroids.add(m);
            });
            state.put("asteroids", asteroids);

            String json = objectMapper.writeValueAsString(state);
            webSocketHandler.broadcastToRoom(world.getRoomId(), json);

        } catch (Exception e) {
            logger.error("Failed to broadcast game state", e);
        }
    }
}

