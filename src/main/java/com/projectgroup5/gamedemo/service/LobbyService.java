package com.projectgroup5.gamedemo.service;

import com.projectgroup5.gamedemo.dto.CreateRoomRequest;
import com.projectgroup5.gamedemo.dto.LobbySlotDto;
import com.projectgroup5.gamedemo.dto.PlayerInfoDto;
import com.projectgroup5.gamedemo.dto.RoomDto;
import com.projectgroup5.gamedemo.game.GameRoomManager;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class LobbyService {
    private static final Logger logger = LoggerFactory.getLogger(LobbyService.class);

    private static final int TABLE_COUNT = 20;

    public LobbyService(GameServiceArchA gameServiceArchA, GameServiceArchB gameServiceArchB) {
        this.gameServiceArchA = gameServiceArchA;
        this.gameServiceArchB = gameServiceArchB;
    }

    // 房间内部模型
    public static class Room {
        public long roomId;
        public int tableIndex;
        public int maxPlayers;
        public String mapName;
        public String winMode;
        public String ownerName;
        public boolean started;                // 是否已经开始游戏
        public String architectureMode;

        // 玩家列表（第一个一定是房主）
        public LinkedHashSet<String> players = new LinkedHashSet<>();
        // 已经点了"准备"的玩家（房主不用准备）
        public Set<String> readyPlayers = new HashSet<>();

        // ★ 新增：当前房间使用的架构模式 (A / B)
        public GameMode mode = GameMode.ARCH_A;
        
        // 🔥 新增：游戏局数ID，每次开始新游戏时+1，用于前端检测"新游戏开始"
        public int gameSessionId = 0;
    }

    private final Room[] tables = new Room[TABLE_COUNT];
    private final AtomicLong roomIdGenerator = new AtomicLong(1);

    // 方便查找：roomId -> Room
    private final Map<Long, Room> roomsById = new HashMap<>();
    // 每个玩家最多在一个房间：username -> roomId
    private final Map<String, Long> userToRoom = new HashMap<>();
    private final AtomicLong roomIdSeq = new AtomicLong(1);
    private final GameServiceArchA gameServiceArchA;
    private final GameServiceArchB gameServiceArchB;


    // 获取大厅快照
    public synchronized List<LobbySlotDto> getLobbySnapshot() {
        List<LobbySlotDto> list = new ArrayList<>();
        for (int i = 0; i < TABLE_COUNT; i++) {
            LobbySlotDto slot = new LobbySlotDto();
            slot.setIndex(i);
            Room r = tables[i];
            if (r == null) {
                slot.setOccupied(false);
                slot.setRoom(null);
            } else {
                slot.setOccupied(true);
                slot.setRoom(toDto(r));
            }
            list.add(slot);
        }
        return list;
    }

    // 创建房间（房主自动加入）
    public synchronized RoomDto createRoom(CreateRoomRequest req, String ownerName) {
        // 已经在别的房间里了，拒绝
        if (userToRoom.containsKey(ownerName)) {
            return null;
        }

        // 找空桌子
        int freeIndex = -1;
        for (int i = 0; i < TABLE_COUNT; i++) {
            if (tables[i] == null) {
                freeIndex = i;
                break;
            }
        }
        if (freeIndex == -1) {
            return null;
        }

        Room r = new Room();
        r.roomId = roomIdGenerator.getAndIncrement();
        r.tableIndex = freeIndex;
        r.maxPlayers = Math.max(1, Math.min(4, req.getMaxPlayers()));
        r.mapName = req.getMapName();
        r.winMode = req.getWinMode();
        r.ownerName = ownerName;
        r.started = false;
        r.players.add(ownerName);          // 房主加入

        tables[freeIndex] = r;
        roomsById.put(r.roomId, r);
        userToRoom.put(ownerName, r.roomId);

        return toDto(r);
    }

    /**
     * 房主点击开始，选择架构模式 A/B。
     * - 检查房主身份
     * - 检查所有玩家已准备
     * - 设置 Room.started = true，Room.mode = 指定模式
     */
    public void startRoom(long roomId, String username, GameMode mode) {
        Room room = roomsById.get(roomId);
        if (room == null) {
            throw new IllegalArgumentException("Room not found: " + roomId);
        }

        if (!Objects.equals(room.ownerName, username)) {
            throw new IllegalStateException("Only room owner can start the game");
        }

        if (room.started) {
            // 已经开始就不重复设置
            return;
        }

        if (!room.readyPlayers.containsAll(room.players)) {
            throw new IllegalStateException("Not all players are ready");
        }

        room.mode = mode != null ? mode : GameMode.ARCH_A;
        room.started = true;
        // 🔥 每次开始新游戏时，gameSessionId +1
        room.gameSessionId++;
        logger.info("Room {} starting game session #{} with mode {}", 
                roomId, room.gameSessionId, room.mode);

        // ★ 根据 mode 初始化对应的 GameService
        if (room.mode == GameMode.ARCH_A) {
            gameServiceArchA.startSession(room.roomId, room.players);
        } else {
            gameServiceArchB.startSession(room.roomId, room.players);
        }
    }


    // 加入房间
    public synchronized RoomDto joinRoom(long roomId, String username) {
        // 已在一个房间里，且不是当前房间 => 拒绝
        Long current = userToRoom.get(username);
        if (current != null && current != roomId) {
            return null;
        }

        Room r = roomsById.get(roomId);
        if (r == null || r.started) return null;
        if (r.players.size() >= r.maxPlayers) return null;

        r.players.add(username);
        userToRoom.put(username, roomId);
        // 加入时默认未准备
        r.readyPlayers.remove(username);

        return toDto(r);
    }

    // 离开房间
    public synchronized void leaveRoom(long roomId, String username) {
        Room r = roomsById.get(roomId);
        if (r == null) return;
        if (!r.players.remove(username)) return;

        userToRoom.remove(username);
        r.readyPlayers.remove(username);

        if (r.players.isEmpty()) {
            // 房间没人了，清空桌子
            tables[r.tableIndex] = null;
            roomsById.remove(roomId);
            return;
        }

        // 房主离开 -> 把第一个玩家设成新房主
        if (username.equals(r.ownerName)) {
            r.ownerName = r.players.iterator().next();
        }
    }

    // 切换准备状态（房主不用准备）
    public synchronized RoomDto toggleReady(long roomId, String username) {
        Room r = roomsById.get(roomId);
        if (r == null || r.started) return null;
        if (!r.players.contains(username)) return null;
        if (username.equals(r.ownerName)) return toDto(r);

        if (r.readyPlayers.contains(username)) {
            r.readyPlayers.remove(username);
        } else {
            r.readyPlayers.add(username);
        }
        return toDto(r);
    }

    // 房主点击开始
    public synchronized RoomDto startGame(long roomId, String ownerName) {
        return startGame(roomId, ownerName, null);
    }

    // 🔥 新增：支持指定架构模式
    public synchronized RoomDto startGame(long roomId, String ownerName, GameMode mode) {
        Room r = roomsById.get(roomId);
        if (r == null) return null;
        if (!ownerName.equals(r.ownerName)) return null;
        if (r.started) return toDto(r);

        // （可选）要求所有非房主玩家都准备好再开始
        for (String p : r.players) {
            if (p.equals(r.ownerName)) continue;
            if (!r.readyPlayers.contains(p)) {
                return null; // 还有人没准备
            }
        }

        r.started = true;
        // 🔥 设置架构模式（如果提供）
        if (mode != null) {
            r.mode = mode;
        }
        return toDto(r);
    }

    // 查询某个玩家当前房间（用于前端判断是否在房间里）
    public synchronized Long getRoomIdByUser(String username) {
        return userToRoom.get(username);
    }

    /**
     * 一局游戏彻底结束后（GameService 通知），重置房间：
     * - 清空 ready 列表（所有人变回“未准备”）
     * - started = false
     * - 保留玩家列表和 mode，方便下一局继续在同一架构模式下玩
     */
    // 🔥 重置房间状态（游戏结束后）
    public synchronized void resetRoomAfterGame(long roomId) {
        Room r = roomsById.get(roomId);
        if (r == null) return;
        
        r.started = false;
        // 清空准备状态，让玩家重新准备
        r.readyPlayers.clear();
        
        logger.info("Room {} reset after game: started=false, ready cleared", roomId);
    }

    private RoomDto toDto(Room r) {
        RoomDto dto = new RoomDto();
        dto.setRoomId(r.roomId);
        dto.setTableIndex(r.tableIndex);
        dto.setMaxPlayers(r.maxPlayers);
        dto.setCurrentPlayers(r.players.size());
        dto.setMapName(r.mapName);
        dto.setWinMode(r.winMode);
        dto.setOwnerName(r.ownerName);
        dto.setStarted(r.started);
        // 🔥 设置架构模式
        dto.setArchitecture(r.mode == GameMode.ARCH_B ? "B" : "A");
        // 🔥 设置游戏局数ID
        dto.setGameSessionId(r.gameSessionId);
        List<PlayerInfoDto> playerDtos = new ArrayList<>();
        for (String username : r.players) {
            PlayerInfoDto p = new PlayerInfoDto();
            p.setUsername(username);
            p.setOwner(username.equals(r.ownerName));
            p.setReady(r.readyPlayers.contains(username));  // readyPlayers 里有就说明已准备
            playerDtos.add(p);
        }
        dto.setPlayers(playerDtos);
        dto.setReadyUsernames(new ArrayList<>(r.readyPlayers));

        return dto;
    }

    public GameMode getModeForRoom(long roomId) {
        Room room = roomsById.get(roomId);
        if (room == null) {
            // 默认按 A 处理，防止 null
            return GameMode.ARCH_A;
        }
        return room.mode;
    }

    /** 也顺便提供一个 RoomDto 用来给前端查询房间配置（mode/map/maxPlayers 等） */
    public Optional<Room> findRoom(long roomId) {
        return Optional.ofNullable(roomsById.get(roomId));
    }

    /**
     * 判断某个用户当前是否在指定 room 里
     */
    public synchronized boolean isPlayerInRoom(long roomId, String username) {
        Room room = roomsById.get(roomId);
        if (room == null) {
            return false;
        }
        return room.players.contains(username);
    }

}
