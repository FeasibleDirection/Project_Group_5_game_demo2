package com.projectgroup5.gamedemo.dto;

import com.projectgroup5.gamedemo.service.GameMode;

public class GameRoomConfigDto {
    private long roomId;
    private GameMode mode;
    private String mapName;
    private String winMode;
    private int maxPlayers;

    // getters & setters
    public long getRoomId() { return roomId; }
    public void setRoomId(long roomId) { this.roomId = roomId; }

    public GameMode getMode() { return mode; }
    public void setMode(GameMode mode) { this.mode = mode; }

    public String getMapName() { return mapName; }
    public void setMapName(String mapName) { this.mapName = mapName; }

    public String getWinMode() { return winMode; }
    public void setWinMode(String winMode) { this.winMode = winMode; }

    public int getMaxPlayers() { return maxPlayers; }
    public void setMaxPlayers(int maxPlayers) { this.maxPlayers = maxPlayers; }
}
