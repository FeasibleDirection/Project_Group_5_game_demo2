package com.projectgroup5.gamedemo.game;

import com.projectgroup5.gamedemo.dto.RoomDto;
import com.projectgroup5.gamedemo.event.EventBus;
import com.projectgroup5.gamedemo.event.PlayerJoinedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 游戏房间管理器 - 管理所有活跃的游戏世界
 * Architecture A: 每个房间维护独立的游戏状态
 */
@Component
public class GameRoomManager {
    private static final Logger logger = LoggerFactory.getLogger(GameRoomManager.class);
    
    // roomId -> GameWorld
    private final Map<Long, GameWorld> activeGames = new ConcurrentHashMap<>();
    
    private final EventBus eventBus;
    
    public GameRoomManager(EventBus eventBus) {
        this.eventBus = eventBus;
    }
    
    /**
     * 创建游戏房间（Architecture A模式）
     */
    public GameWorld createGameRoom(RoomDto roomDto) {
        long roomId = roomDto.getRoomId();
        
        if (activeGames.containsKey(roomId)) {
            logger.warn("GameWorld already exists for roomId={}", roomId);
            return activeGames.get(roomId);
        }
        
        GameWorld world = new GameWorld(
            roomId,
            roomDto.getMapName(),
            roomDto.getWinMode(),
            roomDto.getMaxPlayers()
        );
        
        // 初始化所有玩家
        roomDto.getPlayers().forEach(player -> {
            world.addPlayer(player.getUsername());
            eventBus.publish(new PlayerJoinedEvent(roomId, player.getUsername()));
            logger.info("Added player {} to game room {}", player.getUsername(), roomId);
        });
        
        // 设置游戏阶段为倒计时
        world.setPhase(GameWorld.GamePhase.COUNTDOWN);
        world.setGameStartTime(System.currentTimeMillis() + 3000); // 3秒倒计时
        
        activeGames.put(roomId, world);
        logger.info("Created GameWorld (Architecture A) for roomId={}, players={}", 
            roomId, world.getPlayers().size());
        
        return world;
    }
    
    /**
     * 获取游戏房间
     */
    public Optional<GameWorld> getGameRoom(long roomId) {
        return Optional.ofNullable(activeGames.get(roomId));
    }
    
    /**
     * 玩家是否在该房间中
     */
    public boolean isPlayerInRoom(long roomId, String username) {
        return getGameRoom(roomId)
            .map(world -> world.getPlayers().containsKey(username))
            .orElse(false);
    }
    
    /**
     * 移除游戏房间
     */
    public void removeGameRoom(long roomId) {
        GameWorld world = activeGames.remove(roomId);
        if (world != null) {
            logger.info("Removed GameWorld for roomId={}", roomId);
        }
    }
    
    /**
     * 获取所有活跃游戏
     */
    public Map<Long, GameWorld> getAllActiveGames() {
        return activeGames;
    }

    public Collection<GameWorld> getActiveWorlds() {
        return activeGames.values();
    }

}

