package com.gambitrate.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "player")
public class Player {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "last_synced_month", length = 7)
    private String lastSyncedMonth;

    @Column(name = "last_game_end_time")
    private Instant lastGameEndTime;

    protected Player() {
    }

    public Player(String username) {
        this.username = username;
        this.createdAt = Instant.now();
    }

    public void markSynced(Instant syncedAt, String month, Instant lastGameEndTime) {
        this.lastSyncedAt = syncedAt;
        this.lastSyncedMonth = month;
        if (lastGameEndTime != null
                && (this.lastGameEndTime == null || lastGameEndTime.isAfter(this.lastGameEndTime))) {
            this.lastGameEndTime = lastGameEndTime;
        }
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastSyncedAt() {
        return lastSyncedAt;
    }

    public String getLastSyncedMonth() {
        return lastSyncedMonth;
    }

    public Instant getLastGameEndTime() {
        return lastGameEndTime;
    }
}
