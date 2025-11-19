package com.projectgroup5.gamedemo.dto;

public class GameScoreEntry {

    private String username;
    private int hp;
    private int score;
    private long elapsedMillis;
    private boolean finished;
    private long lastUpdateMillis;

    public GameScoreEntry(String username, int hp, int score, long elapsedMillis, boolean finished) {
        this.username = username;
        this.hp = hp;
        this.score = score;
        this.elapsedMillis = elapsedMillis;
        this.finished = finished;
    }
    
    public GameScoreEntry() {
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public int getHp() {
        return hp;
    }

    public void setHp(int hp) {
        this.hp = hp;
    }

    public int getScore() {
        return score;
    }

    public void setScore(int score) {
        this.score = score;
    }

    public long getElapsedMillis() {
        return elapsedMillis;
    }

    public void setElapsedMillis(long elapsedMillis) {
        this.elapsedMillis = elapsedMillis;
    }

    public boolean isFinished() {
        return finished;
    }

    public void setFinished(boolean finished) {
        this.finished = finished;
    }

    public long getLastUpdateMillis() {
        return lastUpdateMillis;
    }

    public void setLastUpdateMillis(long lastUpdateMillis) {
        this.lastUpdateMillis = lastUpdateMillis;
    }
}
