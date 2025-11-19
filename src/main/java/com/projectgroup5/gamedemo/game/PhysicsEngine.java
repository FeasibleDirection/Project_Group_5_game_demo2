package com.projectgroup5.gamedemo.game;

import com.projectgroup5.gamedemo.event.CollisionDetectedEvent;
import com.projectgroup5.gamedemo.event.EventBus;
import com.projectgroup5.gamedemo.event.ScoreUpdatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 物理引擎 - 处理移动和碰撞（服务器权威）
 * Architecture A: 所有物理计算在服务器端
 */
@Component
public class PhysicsEngine {
    private static final Logger logger = LoggerFactory.getLogger(PhysicsEngine.class);
    
    private static final double PLAYER_SPEED = 200.0; // pixels/second
    private static final int WORLD_WIDTH = 480;
    private static final int WORLD_HEIGHT = 640;
    private static final double ASTEROID_SPAWN_INTERVAL_MS = 800.0; // 每800ms生成一个石头
    private static final double ASTEROID_SPAWN_X_MARGIN = 30.0; // 离边界30px
    
    private final EventBus eventBus;
    
    public PhysicsEngine(EventBus eventBus) {
        this.eventBus = eventBus;
    }
    
    /**
     * 应用玩家输入到速度
     */
    public void applyPlayerInput(PlayerEntity player, PlayerInput input) {
        double vx = 0, vy = 0;
        
        if (input.isMoveUp()) vy -= 1;
        if (input.isMoveDown()) vy += 1;
        if (input.isMoveLeft()) vx -= 1;
        if (input.isMoveRight()) vx += 1;
        
        // 归一化对角线移动（避免斜向移动更快）
        double magnitude = Math.sqrt(vx * vx + vy * vy);
        if (magnitude > 0) {
            vx = (vx / magnitude) * PLAYER_SPEED;
            vy = (vy / magnitude) * PLAYER_SPEED;
        }
        
        player.velocityX = vx;
        player.velocityY = vy;
    }
    
    /**
     * 更新所有实体位置（固定时间步长）
     */
    public void updatePositions(GameWorld world, double deltaSeconds) {
        // 更新玩家位置
        world.getPlayers().values().forEach(player -> {
            if (player.alive) {
                player.x += player.velocityX * deltaSeconds;
                player.y += player.velocityY * deltaSeconds;
                
                // 边界限制
                player.x = Math.max(PlayerEntity.WIDTH / 2, 
                    Math.min(WORLD_WIDTH - PlayerEntity.WIDTH / 2, player.x));
                player.y = Math.max(PlayerEntity.HEIGHT / 2, 
                    Math.min(WORLD_HEIGHT - PlayerEntity.HEIGHT / 2, player.y));
            }
        });
        
        // 更新子弹位置
        world.getBullets().values().removeIf(bullet -> {
            bullet.x += bullet.velocityX * deltaSeconds;
            bullet.y += bullet.velocityY * deltaSeconds;
            
            // 移除超出边界的子弹
            return bullet.x < -10 || bullet.x > WORLD_WIDTH + 10 ||
                   bullet.y < -10 || bullet.y > WORLD_HEIGHT + 10;
        });
        
        // 更新石头位置（向下掉落）
        world.getAsteroids().values().removeIf(asteroid -> {
            asteroid.y += asteroid.velocityY * deltaSeconds;
            
            // 移除飞出屏幕底部的石头
            return asteroid.isOffScreen(WORLD_HEIGHT);
        });
        
        // 生成新石头
        spawnAsteroids(world, deltaSeconds);
    }
    
    /**
     * 生成石头（按固定间隔）
     */
    private void spawnAsteroids(GameWorld world, double deltaSeconds) {
        double timer = world.getAsteroidSpawnTimer() + deltaSeconds * 1000;
        
        if (timer >= ASTEROID_SPAWN_INTERVAL_MS) {
            timer = 0;
            
            // 随机位置（X轴）
            double x = ASTEROID_SPAWN_X_MARGIN + 
                Math.random() * (WORLD_WIDTH - 2 * ASTEROID_SPAWN_X_MARGIN);
            
            // 40%概率生成大石头
            boolean isBig = Math.random() < 0.4;
            
            AsteroidEntity asteroid = new AsteroidEntity(x, -30, isBig);
            world.getAsteroids().put(asteroid.id, asteroid);
            
            logger.debug("Spawned asteroid at x={}, isBig={}", x, isBig);
        }
        
        world.setAsteroidSpawnTimer(timer);
    }
    
    /**
     * 检测碰撞并发布事件
     */
    public void detectCollisions(GameWorld world) {
        long roomId = world.getRoomId();
        
        // 子弹 vs 石头
        world.getBullets().values().removeIf(bullet -> {
            for (AsteroidEntity asteroid : world.getAsteroids().values()) {
                if (checkCircleCollision(
                    bullet.x, bullet.y, BulletEntity.RADIUS,
                    asteroid.x, asteroid.y, asteroid.radius)) {
                    
                    // 石头受伤
                    asteroid.hp -= bullet.damage;
                    
                    if (asteroid.hp <= 0) {
                        // 石头被摧毁，击毁者加分
                        PlayerEntity shooter = world.getPlayers().get(bullet.owner);
                        if (shooter != null) {
                            int points = asteroid.isBig ? 10 : 5;
                            shooter.score += points;
                            eventBus.publish(new ScoreUpdatedEvent(
                                roomId, shooter.username, points, shooter.score));
                            logger.debug("Player {} destroyed asteroid, +{} points", 
                                shooter.username, points);
                        }
                        
                        // 标记石头待删除
                        world.getAsteroids().remove(asteroid.id);
                    }
                    
                    return true; // 移除子弹
                }
            }
            return false;
        });
        
        // 石头 vs 玩家
        world.getAsteroids().values().removeIf(asteroid -> {
            for (PlayerEntity player : world.getPlayers().values()) {
                if (!player.alive) continue;
                
                // 矩形与圆形碰撞检测（简化为圆形）
                if (checkCircleCollision(
                    asteroid.x, asteroid.y, asteroid.radius,
                    player.x, player.y, PlayerEntity.COLLISION_RADIUS)) {
                    
                    // 玩家受伤
                    player.hp -= 1;
                    if (player.hp <= 0) {
                        player.hp = 0;
                        player.alive = false;
                        logger.info("Player {} destroyed by asteroid", player.username);
                    }
                    
                    eventBus.publish(new CollisionDetectedEvent(
                        roomId, 
                        "asteroid", 
                        player.username,
                        CollisionDetectedEvent.CollisionType.BULLET_HIT_PLAYER
                    ));
                    
                    return true; // 移除石头
                }
            }
            return false;
        });
        
        // 子弹 vs 玩家（PvP碰撞，如果需要）
        world.getBullets().values().removeIf(bullet -> {
            for (PlayerEntity player : world.getPlayers().values()) {
                if (!player.alive) continue;
                if (player.username.equals(bullet.owner)) continue; // 不能打到自己
                
                if (checkCircleCollision(
                    bullet.x, bullet.y, BulletEntity.RADIUS,
                    player.x, player.y, PlayerEntity.COLLISION_RADIUS)) {
                    
                    // 发布碰撞事件
                    eventBus.publish(new CollisionDetectedEvent(
                        roomId, 
                        bullet.owner, 
                        player.username,
                        CollisionDetectedEvent.CollisionType.BULLET_HIT_PLAYER
                    ));
                    
                    // 应用伤害
                    player.hp -= bullet.damage;
                    if (player.hp <= 0) {
                        player.hp = 0;
                        player.alive = false;
                        logger.info("Player {} eliminated by {}", player.username, bullet.owner);
                        
                        // 击杀者加分
                        PlayerEntity killer = world.getPlayers().get(bullet.owner);
                        if (killer != null) {
                            int oldScore = killer.score;
                            killer.score += 50;
                            eventBus.publish(new ScoreUpdatedEvent(
                                roomId, killer.username, 50, killer.score));
                            logger.info("Player {} scored kill: {} -> {}", 
                                killer.username, oldScore, killer.score);
                        }
                    }
                    
                    return true; // 移除子弹
                }
            }
            return false;
        });
    }
    
    /**
     * 创建子弹
     */
    public BulletEntity createBullet(String owner, double x, double y) {
        return new BulletEntity(owner, x, y);
    }
    
    /**
     * 验证射击是否合法（反作弊）
     */
    public boolean canFire(PlayerEntity player, long currentTime) {
        final long MIN_FIRE_INTERVAL_MS = 200; // 最快5发/秒
        return currentTime - player.lastFireTime >= MIN_FIRE_INTERVAL_MS;
    }
    
    /**
     * 圆形碰撞检测
     */
    private boolean checkCircleCollision(double x1, double y1, double r1,
                                         double x2, double y2, double r2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        double distance = Math.sqrt(dx * dx + dy * dy);
        return distance < (r1 + r2);
    }
}

