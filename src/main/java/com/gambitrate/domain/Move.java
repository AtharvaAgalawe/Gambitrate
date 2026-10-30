package com.gambitrate.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "move")
public class Move {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "game_id", nullable = false)
    private Game game;

    @Column(nullable = false)
    private int ply;

    @Column(nullable = false, length = 16)
    private String san;

    @Column(name = "clock_remaining_ms")
    private Long clockRemainingMs;

    @Column(name = "is_player_move", nullable = false)
    private boolean playerMove;

    protected Move() {
    }

    public Move(int ply, String san, Long clockRemainingMs, boolean playerMove) {
        this.ply = ply;
        this.san = san;
        this.clockRemainingMs = clockRemainingMs;
        this.playerMove = playerMove;
    }

    void attachTo(Game game) {
        this.game = game;
    }

    public Long getId() {
        return id;
    }

    public Game getGame() {
        return game;
    }

    public int getPly() {
        return ply;
    }

    public String getSan() {
        return san;
    }

    public Long getClockRemainingMs() {
        return clockRemainingMs;
    }

    public boolean isPlayerMove() {
        return playerMove;
    }
}
