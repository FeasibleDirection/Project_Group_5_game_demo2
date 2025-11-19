package com.projectgroup5.gamedemo.game;

/**
 * 玩家实体（服务器权威）
 */
public class PlayerEntity {
    public String username;
    public double x, y;
    public double velocityX, velocityY;
    public int hp;
    public int score;
    public boolean alive;
    public long lastFireTime = 0;
    
    // 碰撞体积
    public static final double WIDTH = 32;
    public static final double HEIGHT = 32;
    public static final double COLLISION_RADIUS = 16;
    
    public PlayerEntity(String username, double x, double y) {
        this.username = username;
        this.x = x;
        this.y = y;
        this.hp = 3;
        this.score = 0;
        this.alive = true;
        this.velocityX = 0;
        this.velocityY = 0;
    }
}

