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
public class GameServiceArchB {

    private static class PlayerState {
        String username;
        int hp;
        int score;
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

    private final Map<Long, Session> sessions = new ConcurrentHashMap<>();

    private final GameLogRepository logRepository;
    private final LobbyService lobbyService;
    private final ObjectMapper objectMapper;

    private static final long PLAYER_TIMEOUT_MS = 30_000;

    public GameServiceArchB(GameLogRepository logRepository,
                            @Lazy LobbyService lobbyService,
                            ObjectMapper objectMapper) {
        this.logRepository = logRepository;
        this.lobbyService = lobbyService;
        this.objectMapper = objectMapper;
    }

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
        if (session == null) return;

        PlayerState st = session.players.get(username);
        if (st == null) return;

        st.hp = req.getHp();
        st.score = req.getScore();
        st.elapsedMillis = req.getElapsedMillis();
        st.finished = req.isFinished() || st.hp <= 0;
        st.lastUpdateMillis = System.currentTimeMillis();

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
                .allMatch(st -> st.finished);
        if (!allFinished) return;

        session.endedAt = System.currentTimeMillis();

        List<Map<String, Object>> result = new ArrayList<>();
        for (PlayerState st : session.players.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("gamemodel", "ARCH_B");
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

        lobbyService.resetRoomAfterGame(session.roomId);
        sessions.remove(session.roomId);
    }

    public void playerLeave(long roomId, String username) {
        markPlayerFinished(roomId, username);
    }
}
