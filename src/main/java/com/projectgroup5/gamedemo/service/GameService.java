package com.projectgroup5.gamedemo.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projectgroup5.gamedemo.dto.GameHeartbeatRequest;
import com.projectgroup5.gamedemo.dao.GameLogRepository;
import com.projectgroup5.gamedemo.dto.GameScoreEntry;
import com.projectgroup5.gamedemo.entity.GameLog;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class GameService {

    private static class PlayerState {
        String username;
        long roomId;
        int hp;
        int score;
        long elapsedMillis;
        boolean finished;
        long lastUpdateMillis;
    }

    private static class GameSession {
        long roomId;
        long startedAt;
        Long endedAt; // null 表示还没真正结束
        Map<String, PlayerState> players = new ConcurrentHashMap<>();
    }

    // roomId -> GameSession
    private final Map<Long, GameSession> sessions = new ConcurrentHashMap<>();

    private final GameLogRepository gameLogRepository;
    private final ObjectMapper objectMapper;
    private final LobbyService lobbyService;

    // 心跳超时时间（一个玩家超过这个时间不发心跳，就当离线并结束）
    private static final long PLAYER_TIMEOUT_MILLIS = 30_000L;

    public GameService(GameLogRepository gameLogRepository,
                       ObjectMapper objectMapper,
                       LobbyService lobbyService) {
        this.gameLogRepository = gameLogRepository;
        this.objectMapper = objectMapper;
        this.lobbyService = lobbyService;
    }

    // 客户端每 2s 调一次
    public void heartbeat(String username, GameHeartbeatRequest req) {
        long now = System.currentTimeMillis();
        long roomId = req.getRoomId();
        if (roomId <= 0) {
            return;
        }

        GameSession session = sessions.computeIfAbsent(roomId, id -> {
            GameSession s = new GameSession();
            s.roomId = id;
            s.startedAt = now;
            return s;
        });

        PlayerState st = session.players.computeIfAbsent(username, u -> {
            PlayerState ps = new PlayerState();
            ps.username = u;
            ps.roomId = roomId;
            return ps;
        });

        st.hp = req.getHp();
        st.score = req.getScore();
        st.elapsedMillis = req.getElapsedMillis();
        st.finished = req.isFinished();
        st.lastUpdateMillis = now;

        checkIfSessionFinished(session);
    }

    // 大厅/游戏页面拉取记分板
    public List<GameScoreEntry> getScoreboard(long roomId) {
        GameSession session = sessions.get(roomId);
        if (session == null) {
            return Collections.emptyList();
        }
        cleanupTimeoutPlayers(session);

        List<GameScoreEntry> list = new ArrayList<>();
        for (PlayerState st : session.players.values()) {
            GameScoreEntry dto = new GameScoreEntry();
            dto.setUsername(st.username);
            dto.setHp(st.hp);
            dto.setScore(st.score);
            dto.setElapsedMillis(st.elapsedMillis);
            dto.setFinished(st.finished);
            list.add(dto);
        }
        return list;
    }

    // 一个玩家主动“提前结束”（如果你想单独提供 /leave API，可以用这个）
    public void markPlayerFinished(String username, long roomId) {
        GameSession session = sessions.get(roomId);
        if (session == null) return;
        PlayerState st = session.players.get(username);
        if (st == null) return;
        st.finished = true;
        st.lastUpdateMillis = System.currentTimeMillis();
        checkIfSessionFinished(session);
    }

    // --- 内部逻辑 ---

    private void cleanupTimeoutPlayers(GameSession session) {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, PlayerState>> it = session.players.entrySet().iterator();
        while (it.hasNext()) {
            PlayerState st = it.next().getValue();
            if (now - st.lastUpdateMillis > PLAYER_TIMEOUT_MILLIS) {
                // 这里的策略：当成 finished 并从会话中清除。
                it.remove();
            }
        }
    }

    private void checkIfSessionFinished(GameSession session) {
        cleanupTimeoutPlayers(session);
        if (session.players.isEmpty()) {
            // 没人了，直接结束会话
            finalizeAndPersistIfNeeded(session);
            return;
        }

        boolean allFinished = true;
        for (PlayerState st : session.players.values()) {
            if (!st.finished) {
                allFinished = false;
                break;
            }
        }
        if (allFinished) {
            finalizeAndPersistIfNeeded(session);
        }
    }

    private void finalizeAndPersistIfNeeded(GameSession session) {
        if (session.endedAt != null) {
            return; // 已经处理过了
        }
        session.endedAt = System.currentTimeMillis();

        // 构造 JSON 结果
        List<Map<String, Object>> resultList = new ArrayList<>();
        for (PlayerState st : session.players.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("username", st.username);
            m.put("score", st.score);
            m.put("hp", st.hp);
            m.put("elapsedMillis", st.elapsedMillis);
            m.put("finished", st.finished);
            resultList.add(m);
        }

        String json;
        try {
            json = objectMapper.writeValueAsString(resultList);
        } catch (JsonProcessingException e) {
            json = "[]";
        }

        GameLog log = new GameLog();
        log.setRoomId(session.roomId);
        log.setStartedAt(session.startedAt);
        log.setEndedAt(session.endedAt);
        log.setResultJson(json);

        gameLogRepository.insert(log);

        // ★ 通知 Lobby：这一局结束了，重置房间状态（started=false、全部未准备）
        lobbyService.resetRoomAfterGame(session.roomId);

        // 清理内存里的这个 session
        sessions.remove(session.roomId);
    }



}
