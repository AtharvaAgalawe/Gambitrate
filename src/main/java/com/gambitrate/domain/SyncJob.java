package com.gambitrate.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "sync_job")
public class SyncJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SyncStatus status;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "months_total", nullable = false)
    private int monthsTotal;

    @Column(name = "months_done", nullable = false)
    private int monthsDone;

    @Column(columnDefinition = "text")
    private String error;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SyncJob() {
    }

    public SyncJob(Player player) {
        this.player = player;
        this.status = SyncStatus.QUEUED;
        this.createdAt = Instant.now();
    }

    public void markRunning(Instant now) {
        this.status = SyncStatus.RUNNING;
        this.startedAt = now;
    }

    public void setMonthsTotal(int monthsTotal) {
        this.monthsTotal = monthsTotal;
    }

    public void recordMonthDone(int monthsDone) {
        this.monthsDone = Math.min(monthsDone, monthsTotal);
    }

    public void markDone(Instant now) {
        this.status = SyncStatus.DONE;
        this.finishedAt = now;
        this.monthsDone = this.monthsTotal;
        this.error = null;
    }

    public void markFailed(Instant now, String error) {
        this.status = SyncStatus.FAILED;
        this.finishedAt = now;
        this.error = error;
    }

    public Long getId() {
        return id;
    }

    public Player getPlayer() {
        return player;
    }

    public SyncStatus getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public int getMonthsTotal() {
        return monthsTotal;
    }

    public int getMonthsDone() {
        return monthsDone;
    }

    public String getError() {
        return error;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
