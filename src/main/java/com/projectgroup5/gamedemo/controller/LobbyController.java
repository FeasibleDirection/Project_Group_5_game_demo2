package com.projectgroup5.gamedemo.controller;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projectgroup5.gamedemo.dao.GameLogRepository;
import com.projectgroup5.gamedemo.dto.CreateRoomRequest;
import com.projectgroup5.gamedemo.dto.LeaderboardEntryDto;
import com.projectgroup5.gamedemo.dto.LobbySlotDto;
import com.projectgroup5.gamedemo.dto.GameRoomConfigDto;
import com.projectgroup5.gamedemo.dto.RoomDto;
import com.projectgroup5.gamedemo.entity.GameLog;
import com.projectgroup5.gamedemo.entity.User;
import com.projectgroup5.gamedemo.game.GameRoomManager;
import com.projectgroup5.gamedemo.service.AuthService;
import com.projectgroup5.gamedemo.service.GameMode;
import com.projectgroup5.gamedemo.service.LobbyService;
import com.projectgroup5.gamedemo.websocket.GameWebSocketHandlerB;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/lobby")
@CrossOrigin(origins = "*")
public class LobbyController {

    private final LobbyService lobbyService;
    private final AuthService authService;
    private final GameRoomManager gameRoomManager;
    private final GameLogRepository gameLogRepository;
    private final ObjectMapper objectMapper;

    private static final Logger logger = LoggerFactory.getLogger(LobbyController.class);

    public LobbyController(LobbyService lobbyService, 
                          AuthService authService,
                          GameRoomManager gameRoomManager,
                          GameLogRepository gameLogRepository,
                          ObjectMapper objectMapper) {
        this.lobbyService = lobbyService;
        this.authService = authService;
        this.gameRoomManager = gameRoomManager;
        this.gameLogRepository = gameLogRepository;
        this.objectMapper = objectMapper;
    }

    // 大厅 20 个桌子状态
    @GetMapping
    public ResponseEntity<List<LobbySlotDto>> getLobby() {
        return ResponseEntity.ok(lobbyService.getLobbySnapshot());
    }

    // 创建房间
    @PostMapping("/rooms")
    public ResponseEntity<?> createRoom(
            @RequestHeader(name = "Authorization", required = false) String authHeader,
            @RequestBody CreateRoomRequest request) {

        Optional<User> userOpt = getUserByAuth(authHeader);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid or missing token");
        }

        String ownerName = userOpt.get().getUsername();
        RoomDto room = lobbyService.createRoom(request, ownerName);
        if (room == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Cannot create room, maybe no free table or you are already in a room.");
        }
        return ResponseEntity.ok(room);
    }

    // 加入房间
    @PostMapping("/rooms/{roomId}/join")
    public ResponseEntity<?> joinRoom(
            @RequestHeader(name = "Authorization", required = false) String authHeader,
            @PathVariable long roomId) {

        Optional<User> userOpt = getUserByAuth(authHeader);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid or missing token");
        }

        String username = userOpt.get().getUsername();
        RoomDto room = lobbyService.joinRoom(roomId, username);
        if (room == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Cannot join room (maybe full, started or you are in another room).");
        }
        return ResponseEntity.ok(room);
    }

    // 离开房间
    @PostMapping("/rooms/{roomId}/leave")
    public ResponseEntity<?> leaveRoom(
            @RequestHeader(name = "Authorization", required = false) String authHeader,
            @PathVariable long roomId) {

        Optional<User> userOpt = getUserByAuth(authHeader);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid or missing token");
        }

        String username = userOpt.get().getUsername();
        lobbyService.leaveRoom(roomId, username);
        return ResponseEntity.ok().build();
    }

    // 切换准备状态
    @PostMapping("/rooms/{roomId}/toggle-ready")
    public ResponseEntity<?> toggleReady(
            @RequestHeader(name = "Authorization", required = false) String authHeader,
            @PathVariable long roomId) {

        Optional<User> userOpt = getUserByAuth(authHeader);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid or missing token");
        }

        String username = userOpt.get().getUsername();
        RoomDto room = lobbyService.toggleReady(roomId, username);
        if (room == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Cannot toggle ready.");
        }
        return ResponseEntity.ok(room);
    }

    // 房主点击开始 - Architecture A（服务器权威）
    @PostMapping("/rooms/{roomId}/start-architecture-a")
    public ResponseEntity<?> startGameArchitectureA(
            @RequestHeader(name = "Authorization", required = false) String authHeader,
            @PathVariable long roomId) {

        Optional<User> userOpt = getUserByAuth(authHeader);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid or missing token");
        }

        String username = userOpt.get().getUsername();
        // 🔥 指定Architecture A模式
        RoomDto room = lobbyService.startGame(roomId, username, GameMode.ARCH_A);
        if (room == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Cannot start game (not owner or someone not ready).");
        }
        
        // ★ 创建游戏世界（Architecture A）
        gameRoomManager.createGameRoom(room);
        
        return ResponseEntity.ok(room);
    }
    
    // 房主点击开始 - Architecture B（P2P Lockstep）
    @PostMapping("/rooms/{roomId}/start-architecture-b")
    public ResponseEntity<?> startGameArchitectureB(
            @RequestHeader(name = "Authorization", required = false) String authHeader,
            @PathVariable long roomId) {

        Optional<User> userOpt = getUserByAuth(authHeader);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid or missing token");
        }

        String username = userOpt.get().getUsername();
        // 🔥 指定Architecture B模式
        RoomDto room = lobbyService.startGame(roomId, username, GameMode.ARCH_B);
        if (room == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Cannot start game (not owner or someone not ready).");
        }
        
        // ★ Architecture B (P2P Gossip)
        // 游戏逻辑在每个客户端运行，服务器只负责中转消息
        // GameWebSocketHandlerB 会处理所有P2P通信
        logger.info("Starting game (Architecture B) for room {}, owner: {}", roomId, username);
        
        return ResponseEntity.ok(room);
    }

    // 工具函数
    private Optional<User> getUserByAuth(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return Optional.empty();
        }
        String token = authHeader.substring("Bearer ".length()).trim();
        return authService.getUserByToken(token);
    }


    @PostMapping("/rooms/{roomId}/start")
    public ResponseEntity<?> startRoom(
            @PathVariable("roomId") long roomId,
            @RequestParam(name = "mode", required = false, defaultValue = "ARCH_A") String modeStr,
            @RequestHeader("Authorization") String authHeader) {

        String token = authHeader.replaceFirst("Bearer ", "").trim();
        User user = authService.getUserByToken(token)
                .orElseThrow(() -> new RuntimeException("Invalid token"));

        GameMode mode = "ARCH_B".equalsIgnoreCase(modeStr)
                ? GameMode.ARCH_B
                : GameMode.ARCH_A;

        lobbyService.startRoom(roomId, user.getUsername(), mode);

        return ResponseEntity.ok().build();
    }

    /**
     * 给 game.html 用：进入游戏前先查当前房间配置（包括架构模式）
     */
    @GetMapping("/rooms/{roomId}/config")
    public ResponseEntity<GameRoomConfigDto> getRoomConfig(
            @PathVariable("roomId") long roomId) {

        return lobbyService.findRoom(roomId)
                .map(room -> {
                    GameRoomConfigDto dto = new GameRoomConfigDto();
                    dto.setRoomId(room.roomId);
                    dto.setMode(room.mode);
                    dto.setMapName(room.mapName);
                    dto.setWinMode(room.winMode);
                    dto.setMaxPlayers(room.maxPlayers);
                    return ResponseEntity.ok(dto);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 🔥 新增：排行榜 - 统计所有玩家的总得分
     */
    @GetMapping("/leaderboard")
    public ResponseEntity<List<LeaderboardEntryDto>> getLeaderboard() {
        logger.info("Fetching leaderboard...");
        
        try {
            // 1. 获取所有游戏日志
            List<GameLog> allLogs = gameLogRepository.findAll();
            logger.info("Found {} game logs for leaderboard", allLogs.size());

            // 2. 统计每个玩家的总分和游戏场次
            Map<String, Integer> playerTotalScore = new HashMap<>();
            Map<String, Integer> playerGamesPlayed = new HashMap<>();

            for (GameLog log : allLogs) {
                String resultJson = log.getResultJson();
                if (resultJson == null || resultJson.isEmpty()) {
                    logger.debug("Skipping log {} - empty result_json", log.getId());
                    continue;
                }

                try {
                    // 解析 JSON
                    JsonNode root = objectMapper.readTree(resultJson);
                    JsonNode playersNode = root.get("players");
                    
                    if (playersNode != null && playersNode.isArray()) {
                        logger.debug("Processing {} players from log {}", playersNode.size(), log.getId());
                        
                        for (JsonNode playerNode : playersNode) {
                            String username = playerNode.get("username").asText();
                            int score = playerNode.get("score").asInt();
                            
                            logger.debug("Player: {}, Score: {}", username, score);
                            
                            // 累加总分
                            playerTotalScore.merge(username, score, Integer::sum);
                            // 累加游戏场次
                            playerGamesPlayed.merge(username, 1, Integer::sum);
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Failed to parse result_json for log {}: {}", log.getId(), e.getMessage());
                }
            }

            // 3. 转换为 DTO 并按总分排序
            List<LeaderboardEntryDto> leaderboard = playerTotalScore.entrySet().stream()
                    .map(entry -> new LeaderboardEntryDto(
                            entry.getKey(),
                            entry.getValue(),
                            playerGamesPlayed.getOrDefault(entry.getKey(), 0)
                    ))
                    .sorted((a, b) -> Integer.compare(b.getTotalScore(), a.getTotalScore()))
                    .limit(10)  // 只返回前10名
                    .collect(Collectors.toList());

            logger.info("Leaderboard generated successfully with {} entries", leaderboard.size());
            logger.info("Returning leaderboard: {}", leaderboard);
            
            return ResponseEntity.ok(leaderboard);
        } catch (Exception e) {
            logger.error("Failed to generate leaderboard", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
