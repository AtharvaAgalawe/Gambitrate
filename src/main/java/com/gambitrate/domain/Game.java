package com.gambitrate.domain;

import com.gambitrate.domain.converter.GameResultConverter;
import com.gambitrate.domain.converter.PlayerColorConverter;
import com.gambitrate.domain.converter.TerminationTypeConverter;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "game")
public class Game {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chesscom_id", nullable = false, unique = true, length = 255)
    private String chesscomId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    @Column(name = "played_at", nullable = false)
    private Instant playedAt;

    @Column(name = "time_class", nullable = false, length = 20)
    private String timeClass;

    @Column(name = "time_control", nullable = false, length = 32)
    private String timeControl;

    @Column(name = "base_seconds", nullable = false)
    private int baseSeconds;

    @Column(name = "increment_seconds", nullable = false)
    private int incrementSeconds;

    @Convert(converter = PlayerColorConverter.class)
    @Column(nullable = false, length = 5)
    private PlayerColor color;

    @Convert(converter = GameResultConverter.class)
    @Column(nullable = false, length = 8)
    private GameResult result;

    @Convert(converter = TerminationTypeConverter.class)
    @Column(nullable = false, length = 16)
    private TerminationType termination;

    @Column(name = "player_rating")
    private Integer playerRating;

    @Column(name = "opponent_rating")
    private Integer opponentRating;

    @Column(name = "eco_code", length = 8)
    private String ecoCode;

    @Column(name = "opening_name", length = 255)
    private String openingName;

    @Column(name = "moves_san", nullable = false, columnDefinition = "text")
    private String movesSan;

    @Column(name = "player_accuracy", precision = 5, scale = 2)
    private BigDecimal playerAccuracy;

    @Column(nullable = false, columnDefinition = "text")
    private String pgn;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "game", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("ply ASC")
    private List<Move> moves = new ArrayList<>();

    protected Game() {
    }

    public Game(
            String chesscomId,
            Player player,
            Instant playedAt,
            String timeClass,
            String timeControl,
            int baseSeconds,
            int incrementSeconds,
            PlayerColor color,
            GameResult result,
            TerminationType termination,
            Integer playerRating,
            Integer opponentRating,
            String ecoCode,
            String openingName,
            String movesSan,
            BigDecimal playerAccuracy,
            String pgn
    ) {
        this.chesscomId = chesscomId;
        this.player = player;
        this.playedAt = playedAt;
        this.timeClass = timeClass;
        this.timeControl = timeControl;
        this.baseSeconds = baseSeconds;
        this.incrementSeconds = incrementSeconds;
        this.color = color;
        this.result = result;
        this.termination = termination;
        this.playerRating = playerRating;
        this.opponentRating = opponentRating;
        this.ecoCode = ecoCode;
        this.openingName = openingName;
        this.movesSan = movesSan;
        this.playerAccuracy = playerAccuracy;
        this.pgn = pgn;
        this.createdAt = Instant.now();
    }

    public void addMove(Move move) {
        moves.add(move);
        move.attachTo(this);
    }

    public Long getId() {
        return id;
    }

    public String getChesscomId() {
        return chesscomId;
    }

    public Player getPlayer() {
        return player;
    }

    public Instant getPlayedAt() {
        return playedAt;
    }

    public String getTimeClass() {
        return timeClass;
    }

    public String getTimeControl() {
        return timeControl;
    }

    public int getBaseSeconds() {
        return baseSeconds;
    }

    public int getIncrementSeconds() {
        return incrementSeconds;
    }

    public PlayerColor getColor() {
        return color;
    }

    public GameResult getResult() {
        return result;
    }

    public TerminationType getTermination() {
        return termination;
    }

    public Integer getPlayerRating() {
        return playerRating;
    }

    public Integer getOpponentRating() {
        return opponentRating;
    }

    public String getEcoCode() {
        return ecoCode;
    }

    public String getOpeningName() {
        return openingName;
    }

    public String getMovesSan() {
        return movesSan;
    }

    public BigDecimal getPlayerAccuracy() {
        return playerAccuracy;
    }

    public String getPgn() {
        return pgn;
    }

    public List<Move> getMoves() {
        return moves;
    }
}
