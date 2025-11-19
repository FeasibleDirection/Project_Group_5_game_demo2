package com.projectgroup5.gamedemo.game;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 游戏世界状态（服务器权威）
 * 单个房间的完整游戏状态
 */
public class GameWorld {
    private final long roomId;
    private final String mapName;
    private final String winMode;
    private final int maxPlayers;
    
    // 游戏时间
    private long gameStartTime;
    private long currentFrameNumber = 0;
    
    // 玩家状态
    private final Map<String, PlayerEntity> players = new ConcurrentHashMap<>();
    
    // 游戏实体
    private final Map<String, BulletEntity> bullets = new ConcurrentHashMap<>();
    private final Map<String, AsteroidEntity> asteroids = new ConcurrentHashMap<>();
    
    // 石头生成计时器（毫秒）
    private double asteroidSpawnTimer = 0;
    
    // 游戏阶段
    private volatile GamePhase phase = GamePhase.WAITING;
    
    public enum GamePhase {
        WAITING,      // 等待玩家进入
        COUNTDOWN,    // 倒计时3秒
        IN_PROGRESS,  // 游戏中
        FINISHED      // 已结束
    }

    public GameWorld(long roomId, String mapName, String winMode, int maxPlayers) {
        this.roomId = roomId;
        this.mapName = mapName;
        this.winMode = winMode;
        this.maxPlayers = Math.min(maxPlayers, 4);
    }

    /**
     * 初始化玩家（分散初始位置）
     */
    public void addPlayer(String username) {
        if (players.size() >= maxPlayers) {
            throw new IllegalStateException("房间已满");
        }
        
        // 根据玩家数量分散位置
        double baseX = 240;
        double spacing = 60;
        double offsetX = (players.size() - (maxPlayers - 1) / 2.0) * spacing;
        
        PlayerEntity player = new PlayerEntity(
            username,
            baseX + offsetX,
            500 // Y位置固定在底部
        );
        
        players.put(username, player);
    }
    
    public void removePlayer(String username) {
        players.remove(username);
        // 移除该玩家的所有子弹
        bullets.entrySet().removeIf(e -> e.getValue().owner.equals(username));
    }
    
    // Getters
    public long getRoomId() { 
        return roomId; 
    }
    
    public String getMapName() { 
        return mapName; 
    }
    
    public String getWinMode() { 
        return winMode; 
    }
    
    public GamePhase getPhase() { 
        return phase; 
    }
    
    public void setPhase(GamePhase phase) { 
        this.phase = phase; 
    }
    
    public Map<String, PlayerEntity> getPlayers() { 
        return players; 
    }
    
    public Map<String, BulletEntity> getBullets() { 
        return bullets; 
    }
    
    public Map<String, AsteroidEntity> getAsteroids() {
        return asteroids;
    }
    
    public double getAsteroidSpawnTimer() {
        return asteroidSpawnTimer;
    }
    
    public void setAsteroidSpawnTimer(double timer) {
        this.asteroidSpawnTimer = timer;
    }
    
    public long getCurrentFrameNumber() { 
        return currentFrameNumber; 
    }
    
    public void incrementFrame() { 
        this.currentFrameNumber++; 
    }
    
    public long getGameStartTime() { 
        return gameStartTime; 
    }
    
    public void setGameStartTime(long time) { 
        this.gameStartTime = time; 
    }
    
    public int getMaxPlayers() {
        return maxPlayers;
    }
}

