package com.projectgroup5.gamedemo.service;

import com.projectgroup5.gamedemo.dao.UserRepository;
import com.projectgroup5.gamedemo.dto.LoginResponse;
import com.projectgroup5.gamedemo.entity.User;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {

    private final UserRepository userRepository;

    // key: username + "|" + passwordHash  -> token
    private final Map<String, String> userKeyToToken = new ConcurrentHashMap<>();

    // key: token -> session (包含用户 + 过期时间)
    private final Map<String, SessionInfo> tokenToSession = new ConcurrentHashMap<>();

    // 默认过期时间：1 天
    private static final long SESSION_TTL_HOURS = 24L;

    public AuthService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    private Instant newExpiryTime() {
        return Instant.now().plus(SESSION_TTL_HOURS, ChronoUnit.HOURS);
    }

    public Optional<LoginResponse> login(String username, String password) {
        Optional<User> userOpt = userRepository.findByUsernameAndPassword(username, password);
        if (userOpt.isEmpty()) {
            return Optional.empty();
        }

        User user = userOpt.get();
        long now = Instant.now().getEpochSecond();
        userRepository.updateLastLogin(user.getId(), now);
        user.setLastLoginAt(now);

        String userKey = user.getUsername() + "|" + user.getPasswordHash();
        String token = userKeyToToken.get(userKey);

        // 如果已有 token 且没过期，就复用并顺便刷新过期时间
        if (token != null) {
            SessionInfo existing = tokenToSession.get(token);
            if (existing != null && existing.getExpiresAt().isAfter(Instant.now())) {
                existing.setExpiresAt(newExpiryTime());
            } else {
                // 旧 token 已经失效，重新生成
                token = null;
            }
        }

        if (token == null) {
            token = UUID.randomUUID().toString();
            SessionInfo session = new SessionInfo(user, newExpiryTime());
            userKeyToToken.put(userKey, token);
            tokenToSession.put(token, session);
        }

        LoginResponse resp = new LoginResponse();
        resp.setToken(token);

        LoginResponse.UserDto dto = new LoginResponse.UserDto();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setEmail(user.getEmail());
        resp.setUser(dto);

        return Optional.of(resp);
    }

    public Optional<LoginResponse.UserDto> validateToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        SessionInfo session = tokenToSession.get(token);
        if (session == null) {
            return Optional.empty();
        }
        if (session.getExpiresAt().isBefore(Instant.now())) {
            // 过期：清除缓存
            tokenToSession.remove(token);
            return Optional.empty();
        }

        User user = session.getUser();
        LoginResponse.UserDto dto = new LoginResponse.UserDto();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setEmail(user.getEmail());
        return Optional.of(dto);
    }

    // 给 Lobby 用：拿到完整 User（如果需要）
    public Optional<User> getUserByToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        SessionInfo session = tokenToSession.get(token);
        if (session == null || session.getExpiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(session.getUser());
    }

    // 简单的 session 信息
    private static class SessionInfo {
        private final User user;
        private Instant expiresAt;

        SessionInfo(User user, Instant expiresAt) {
            this.user = user;
            this.expiresAt = expiresAt;
        }

        public User getUser() {
            return user;
        }

        public Instant getExpiresAt() {
            return expiresAt;
        }

        public void setExpiresAt(Instant expiresAt) {
            this.expiresAt = expiresAt;
        }
    }
}
