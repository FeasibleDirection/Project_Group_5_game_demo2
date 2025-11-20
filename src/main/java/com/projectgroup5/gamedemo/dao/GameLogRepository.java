package com.projectgroup5.gamedemo.dao;

import com.projectgroup5.gamedemo.entity.GameLog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Repository
public class GameLogRepository {

    private final JdbcTemplate jdbcTemplate;

    public GameLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(GameLog log) {
        String sql = "INSERT INTO game_logs (room_id, started_at, ended_at, result_json) " +
                     "VALUES (?, ?, ?, ?)";
        jdbcTemplate.update(sql,
                log.getRoomId(),
                log.getStartedAt(),
                log.getEndedAt(),
                log.getResultJson()
        );
    }

    // 🔥 新增：查询所有游戏日志（用于统计排行榜）
    public List<GameLog> findAll() {
        String sql = "SELECT id, room_id, started_at, ended_at, result_json FROM game_logs";
        return jdbcTemplate.query(sql, new GameLogRowMapper());
    }

    private static class GameLogRowMapper implements RowMapper<GameLog> {
        @Override
        public GameLog mapRow(ResultSet rs, int rowNum) throws SQLException {
            GameLog log = new GameLog();
            log.setId(rs.getLong("id"));
            log.setRoomId(rs.getLong("room_id"));
            log.setStartedAt(rs.getLong("started_at"));
            log.setEndedAt(rs.getLong("ended_at"));
            log.setResultJson(rs.getString("result_json"));
            return log;
        }
    }
}
