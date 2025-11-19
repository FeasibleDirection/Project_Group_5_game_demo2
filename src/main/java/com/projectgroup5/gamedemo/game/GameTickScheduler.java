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
@Component
public class GameTickScheduler {
    private static final Logger logger = LoggerFactory.getLogger(GameTickScheduler.class);

    private static final double TICK_RATE = 25.0; // 25 FPS
    private static final double DELTA_TIME = 1.0 / TICK_RATE; // 0.04 seconds

    private final GameRoomManager roomManager;
    private final PhysicsEngine physicsEngine;
    private final GameWebSocketHandler webSocketHandler;
    private final EventBus eventBus;
    private final ObjectMapper objectMapper;
    private final GameLogRepository gameLogRepository;
    private final LobbyService lobbyService;

    public GameTickScheduler(
            GameRoomManager roomManager,
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

    /**
     * 主循环：每 40ms 执行一帧（25Hz / 25FPS）
     */
    @Scheduled(fixedRate = 40)
    public void tick() {
        roomManager.getAllActiveGames().forEach((roomId, world) -> {
            try {
                processGameWorld(world);
            } catch (Exception e) {
                logger.error("Error processing world {}", roomId, e);
            }
        });
    }

    /**
     * 单帧逻辑
     */
    private void processGameWorld(GameWorld world) {
        long now = System.currentTimeMillis();

        switch (world.getPhase()) {

            case COUNTDOWN:
                if (now >= world.getGameStartTime()) {
                    world.setPhase(GameWorld.GamePhase.IN_PROGRESS);
                    logger.info("Game {} started!", world.getRoomId());
                }
                broadcastGameState(world);
                break;

            case IN_PROGRESS:
                physicsEngine.updatePositions(world, DELTA_TIME);
                physicsEngine.detectCollisions(world);

                if (checkWinCondition(world)) {
                    finishGame(world);
                }

                broadcastGameState(world);
                world.incrementFrame();
                break;

            case FINISHED:
                // 游戏结束，不再更新
                break;
        }
    }

    /**
     * 胜利条件
     */
    private boolean checkWinCondition(GameWorld world) {
        String winMode = world.getWinMode();

        if (winMode.startsWith("SCORE_")) {
            int targetScore = Integer.parseInt(winMode.substring(6));
            return world.getPlayers().values().stream()
                    .anyMatch(p -> p.score >= targetScore);
        }

        if (winMode.startsWith("TIME_")) {
            int minutes = Integer.parseInt(winMode.substring(5, winMode.length() - 1));
            long elapsed = System.currentTimeMillis() - world.getGameStartTime();
            return elapsed >= minutes * 60 * 1000L;
        }

        long aliveCount = world.getPlayers().values().stream()
                .filter(p -> p.alive)
                .count();
        return aliveCount <= 1;
    }

    /**
     * 游戏结束
     */
    private void finishGame(GameWorld world) {
        world.setPhase(GameWorld.GamePhase.FINISHED);

        long now = System.currentTimeMillis();
        long elapsedMs = now - world.getGameStartTime();

        Map<String, Integer> finalScores = world.getPlayers().values()
                .stream().collect(Collectors.toMap(p -> p.username, p -> p.score));

        String winner = world.getPlayers().values().stream()
                .max(Comparator.comparingInt(p -> p.score))
                .map(p -> p.username)
                .orElse("Unknown");

        eventBus.publish(new GameEndedEvent(world.getRoomId(), finalScores, winner));
        broadcastGameState(world);

        saveGameLog(world, winner, elapsedMs);

        lobbyService.resetRoomAfterGame(world.getRoomId());

        new Thread(() -> {
            try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
            roomManager.removeGameRoom(world.getRoomId());
        }).start();
    }

    private void saveGameLog(GameWorld world, String winner, long elapsedMs) {
        try {
            List<Map<String, Object>> playerList = new ArrayList<>();
            for (PlayerEntity p : world.getPlayers().values()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("username", p.username);
                m.put("score", p.score);
                m.put("hp", p.hp);
                m.put("alive", p.alive);
                m.put("elapsedMillis", elapsedMs);
                playerList.add(m);
            }

            Map<String, Object> metadata = Map.of(
                    "winner", winner,
                    "mapName", world.getMapName(),
                    "winMode", world.getWinMode(),
                    "maxPlayers", world.getMaxPlayers(),
                    "architecture", "A",
                    "totalFrames", world.getCurrentFrameNumber()
            );

            Map<String, Object> result = Map.of(
                    "players", playerList,
                    "metadata", metadata
            );

            String json = objectMapper.writeValueAsString(result);

            GameLog log = new GameLog();
            log.setRoomId(world.getRoomId());
            log.setStartedAt(world.getGameStartTime());
            log.setEndedAt(System.currentTimeMillis());
            log.setResultJson(json);

            gameLogRepository.insert(log);

        } catch (Exception e) {
            logger.error("Failed to save game log", e);
        }
    }

    /**
     * 广播状态
     */
    private void broadcastGameState(GameWorld world) {
        try {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("type", "GAME_STATE");
            state.put("frame", world.getCurrentFrameNumber());
            state.put("phase", world.getPhase().name());
            state.put("roomId", world.getRoomId());

            List<Map<String, Object>> players = new ArrayList<>();
            world.getPlayers().forEach((u, p) -> {
                players.add(Map.of(
                        "username", p.username,
                        "x", p.x,
                        "y", p.y,
                        "hp", p.hp,
                        "score", p.score,
                        "alive", p.alive
                ));
            });
            state.put("players", players);

            List<Map<String, Object>> bullets = new ArrayList<>();
            world.getBullets().forEach((id, b) -> {
                bullets.add(Map.of("id", id, "owner", b.owner, "x", b.x, "y", b.y));
            });
            state.put("bullets", bullets);

            List<Map<String, Object>> asteroids = new ArrayList<>();
            world.getAsteroids().forEach((id, a) -> {
                asteroids.add(Map.of("id", id, "x", a.x, "y", a.y, "radius", a.radius, "hp", a.hp));
            });
            state.put("asteroids", asteroids);

            String json = objectMapper.writeValueAsString(state);
            webSocketHandler.broadcastToRoom(world.getRoomId(), json);

        } catch (Exception e) {
            logger.error("Failed to broadcast game state", e);
        }
    }
}


