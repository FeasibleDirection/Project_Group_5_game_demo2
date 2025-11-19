package com.projectgroup5.gamedemo.dto;

public class LobbySlotDto {
    private int index;      // 0-19
    private boolean occupied;
    private RoomDto room;   // occupied == true 时非空

    public int getIndex() { return index; }
    public void setIndex(int index) { this.index = index; }

    public boolean isOccupied() { return occupied; }
    public void setOccupied(boolean occupied) { this.occupied = occupied; }

    public RoomDto getRoom() { return room; }
    public void setRoom(RoomDto room) { this.room = room; }
}
