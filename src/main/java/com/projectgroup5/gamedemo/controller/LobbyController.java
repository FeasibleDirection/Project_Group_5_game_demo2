package com.projectgroup5.gamedemo.controller;


import com.projectgroup5.gamedemo.dto.CreateRoomRequest;
import com.projectgroup5.gamedemo.dto.LobbySlotDto;
import com.projectgroup5.gamedemo.dto.GameRoomConfigDto;
import com.projectgroup5.gamedemo.dto.RoomDto;
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

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/lobby")
@CrossOrigin(origins = "*")
public class LobbyController {

    private final LobbyService lobbyService;
    private final AuthService authService;
    private final GameRoomManager gameRoomManager;

    private static final Logger logger = LoggerFactory.getLogger(LobbyController.class);

    public LobbyController(LobbyService lobbyService, 
                          AuthService authService,
                          GameRoomManager gameRoomManager) {
        this.lobbyService = lobbyService;
        this.authService = authService;
        this.gameRoomManager = gameRoomManager;
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
        RoomDto room = lobbyService.startGame(roomId, username);
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
        RoomDto room = lobbyService.startGame(roomId, username);
        if (room == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Cannot start game (not owner or someone not ready).");
        }
        
        // ★ Architecture B (P2P Lockstep)
        // 游戏逻辑在Host客户端运行，服务器只负责中转消息
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
}
