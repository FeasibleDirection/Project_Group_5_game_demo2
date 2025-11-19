package com.projectgroup5.gamedemo.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projectgroup5.gamedemo.dto.GameHeartbeatRequest;
import com.projectgroup5.gamedemo.dao.GameLogRepository;
import com.projectgroup5.gamedemo.dto.GameScoreEntry;
import com.projectgroup5.gamedemo.entity.GameLog;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class GameServiceArchA {

    private static class PlayerState {
        String username;
        int hp = 3;
        int score = 0;
        long elapsedMillis;
        boolean finished;
        long lastUpdateMillis;
    }

    private static class Session {
        long roomId;
        long startedAt = System.currentTimeMillis();
        Long endedAt;
        Map<String, PlayerState> players = new ConcurrentHashMap<>();
    }

    // roomId -> Session
    private final Map<Long, Session> sessions = new ConcurrentHashMap<>();

    private final GameLogRepository logRepository;
    private final LobbyService lobbyService;
    private final ObjectMapper objectMapper;

    // 一些简单限制，防止作弊
    private static final int MAX_HP = 3;
    private static final int MAX_SCORE_PER_SEC = 100;
    private static final long PLAYER_TIMEOUT_MS = 30_000;

    public GameServiceArchA(GameLogRepository logRepository,
                            @Lazy LobbyService lobbyService,
                            ObjectMapper objectMapper) {
        this.logRepository = logRepository;
        this.lobbyService = lobbyService;
        this.objectMapper = objectMapper;
    }

    /** 房间开始时由 LobbyService 调用，初始化 Session */
    public void startSession(long roomId, Collection<String> players) {
        Session session = new Session();
        session.roomId = roomId;
        for (String u : players) {
            PlayerState st = new PlayerState();
            st.username = u;
            session.players.put(u, st);
        }
        sessions.put(roomId, session);
    }

    public void handleHeartbeat(String username, GameHeartbeatRequest req) {
        Session session = sessions.get(req.getRoomId());
        if (session == null) {
            // 没有 session 说明这局已经结束或不存在
            return;
        }
        PlayerState st = session.players.get(username);
        if (st == null) {
            // 非房间内玩家，丢弃
            return;
        }

        long now = System.currentTimeMillis();
        long deltaMs = now - st.lastUpdateMillis;
        if (deltaMs < 0) deltaMs = 0;
        st.lastUpdateMillis = now;

        // 服务端限制：时间递增不可回退
        if (req.getElapsedMillis() > st.elapsedMillis) {
            st.elapsedMillis = req.getElapsedMillis();
        }

        // 服务端限制 hp：不能超过 MAX_HP，也不能小于 0
        int hp = Math.max(0, Math.min(MAX_HP, req.getHp()));
        st.hp = hp;

        // 简单的“每秒最大得分”限制，用于演示服务器验证
        int clientScore = Math.max(0, req.getScore());
        int maxAllowed = st.score + (int) (deltaMs / 1000.0 * MAX_SCORE_PER_SEC + 1);
        st.score = Math.min(clientScore, maxAllowed);

        if (req.isFinished() || st.hp <= 0) {
            st.finished = true;
        }

        cleanupTimedOutPlayers(session);
        tryFinalize(session);
    }

    private void cleanupTimedOutPlayers(Session session) {
        long now = System.currentTimeMillis();
        for (PlayerState st : session.players.values()) {
            if (!st.finished && now - st.lastUpdateMillis > PLAYER_TIMEOUT_MS) {
                st.finished = true;
            }
        }
    }

    public List<GameScoreEntry> getScoreboard(long roomId) {
        Session session = sessions.get(roomId);
        if (session == null) return Collections.emptyList();

        List<GameScoreEntry> list = new ArrayList<>();
        for (PlayerState st : session.players.values()) {
            list.add(new GameScoreEntry(
                    st.username, st.hp, st.score, st.elapsedMillis, st.finished
            ));
        }
        // 按分数排序
        list.sort(Comparator.comparingInt(GameScoreEntry::getScore).reversed());
        return list;
    }

    public void markPlayerFinished(long roomId, String username) {
        Session session = sessions.get(roomId);
        if (session == null) return;
        PlayerState st = session.players.get(username);
        if (st != null) {
            st.finished = true;
        }
        tryFinalize(session);
    }

    private void tryFinalize(Session session) {
        if (session.endedAt != null) return;

        boolean allFinished = session.players.values().stream()
                .allMatch(st -> st.finished || st.hp <= 0);
        if (!allFinished) return;

        session.endedAt = System.currentTimeMillis();

        // 写日志
        List<Map<String, Object>> result = new ArrayList<>();
        for (PlayerState st : session.players.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("gamemodel", "ARCH_A");
            m.put("username", st.username);
            m.put("score", st.score);
            m.put("hp", st.hp);
            m.put("elapsedMillis", st.elapsedMillis);
            result.add(m);
        }

        String json;
        try {
            json = objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            json = "[]";
        }

        GameLog log = new GameLog();
        log.setRoomId(session.roomId);
        log.setStartedAt(session.startedAt);
        log.setEndedAt(session.endedAt);
        log.setResultJson(json);
        logRepository.insert(log);

        // 通知 Lobby：本局结束，重置房间状态
        lobbyService.resetRoomAfterGame(session.roomId);

        sessions.remove(session.roomId);
    }

    public void playerLeave(long roomId, String username) {
        markPlayerFinished(roomId, username);
    }
}
