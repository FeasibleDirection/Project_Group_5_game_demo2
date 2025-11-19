package com.projectgroup5.gamedemo.controller;

import com.projectgroup5.gamedemo.dto.GameHeartbeatRequest;
import com.projectgroup5.gamedemo.dto.GameScoreEntry;
import com.projectgroup5.gamedemo.entity.User;
import com.projectgroup5.gamedemo.service.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/game")
@CrossOrigin(origins = "*")
public class GameController {

    private final AuthService authService;
    private final LobbyService lobbyService;
    private final GameServiceArchA gameServiceArchA;
    private final GameServiceArchB gameServiceArchB;

    public GameController(AuthService authService,
                          LobbyService lobbyService,
                          GameServiceArchA gameServiceArchA,
                          GameServiceArchB gameServiceArchB) {
        this.authService = authService;
        this.lobbyService = lobbyService;
        this.gameServiceArchA = gameServiceArchA;
        this.gameServiceArchB = gameServiceArchB;
    }

    private String getUsernameFromAuth(String authHeader) {
        String token = authHeader.replaceFirst("Bearer ", "").trim();
        User user = authService.getUserByToken(token)
                .orElseThrow(() -> new RuntimeException("Invalid token"));
        return user.getUsername();
    }

    private GameMode resolveMode(long roomId) {
        return lobbyService.getModeForRoom(roomId);
    }

    /**
     * 客户端心跳：上报 hp/score/time，服务端根据房间的架构模式转发到不同 GameService。
     */
    @PostMapping("/heartbeat")
    public ResponseEntity<Void> heartbeat(@RequestBody GameHeartbeatRequest req,
                                          @RequestHeader("Authorization") String authHeader) {
        String username = getUsernameFromAuth(authHeader);
        GameMode mode = resolveMode(req.getRoomId());

        switch (mode) {
            case ARCH_A -> gameServiceArchA.handleHeartbeat(username, req);
            case ARCH_B -> gameServiceArchB.handleHeartbeat(username, req);
        }
        return ResponseEntity.ok().build();
    }

    /**
     * 右侧 Scoreboard 获取当前房间的所有玩家分数。
     */
    @GetMapping("/scoreboard")
    public ResponseEntity<List<GameScoreEntry>> getScoreboard(@RequestParam("roomId") long roomId) {
        GameMode mode = resolveMode(roomId);
        List<GameScoreEntry> list = switch (mode) {
            case ARCH_A -> gameServiceArchA.getScoreboard(roomId);
            case ARCH_B -> gameServiceArchB.getScoreboard(roomId);
        };
        return ResponseEntity.ok(list);
    }

    /**
     * 玩家主动离开游戏（点“Return to Lobby”）
     */
    @PostMapping("/room/{roomId}/leave")
    public ResponseEntity<Void> leaveRoom(@PathVariable("roomId") long roomId,
                                          @RequestHeader("Authorization") String authHeader) {
        String username = getUsernameFromAuth(authHeader);
        GameMode mode = resolveMode(roomId);

        switch (mode) {
            case ARCH_A -> gameServiceArchA.playerLeave(roomId, username);
            case ARCH_B -> gameServiceArchB.playerLeave(roomId, username);
        }
        return ResponseEntity.ok().build();
    }
}
