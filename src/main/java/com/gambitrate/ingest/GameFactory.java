package com.gambitrate.ingest;

import com.gambitrate.domain.Game;
import com.gambitrate.domain.GameResult;
import com.gambitrate.domain.Move;
import com.gambitrate.domain.Player;
import com.gambitrate.domain.PlayerColor;
import com.gambitrate.domain.TerminationType;
import com.gambitrate.ingest.dto.MonthlyGame;
import com.gambitrate.ingest.dto.Side;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;

@Component
public class GameFactory {

    private static final Set<String> DRAW_REASONS = Set.of(
            "agreed", "agreement", "draw", "repetition", "threefoldrepetition",
            "stalemate", "insufficient", "insufficientmaterial", "50move", "timevsinsufficient"
    );

    public Game create(Player player, MonthlyGame source, ParsedPgn parsedPgn) {
        if (source.white() == null || source.black() == null) {
            throw new ChessComUpstreamException("Chess.com game " + gameId(source) + " is missing a side.");
        }

        boolean playerIsWhite = source.white().username() != null
                && source.white().username().equalsIgnoreCase(player.getUsername());
        boolean playerIsBlack = source.black().username() != null
                && source.black().username().equalsIgnoreCase(player.getUsername());
        if (playerIsWhite == playerIsBlack) {
            throw new ChessComUpstreamException(
                    "Could not identify player '" + player.getUsername() + "' in game " + gameId(source) + ".");
        }

        Side playerSide = playerIsWhite ? source.white() : source.black();
        Side opponentSide = playerIsWhite ? source.black() : source.white();
        PlayerColor color = playerIsWhite ? PlayerColor.WHITE : PlayerColor.BLACK;
        GameResult result = result(playerSide.result(), opponentSide.result(), gameId(source));
        TerminationType termination = termination(playerSide.result(), opponentSide.result(), result);

        TimeControlParser.TimeControl timeControl;
        try {
            timeControl = TimeControlParser.parse(source.timeControl());
        } catch (IllegalArgumentException exception) {
            throw new ChessComUpstreamException(
                    "Invalid time_control for game " + gameId(source) + ": " + source.timeControl(), exception);
        }

        if (source.timeClass() == null || source.timeClass().isBlank()) {
            throw new ChessComUpstreamException("Missing time_class for game " + gameId(source) + ".");
        }
        if (source.endTime() == null) {
            throw new ChessComUpstreamException("Missing end_time for game " + gameId(source) + ".");
        }

        Game game = new Game(
                gameId(source),
                player,
                Instant.ofEpochSecond(source.endTime()),
                source.timeClass().trim().toLowerCase(Locale.ROOT),
                source.timeControl().trim(),
                timeControl.baseSeconds(),
                timeControl.incrementSeconds(),
                color,
                result,
                termination,
                playerSide.rating(),
                opponentSide.rating(),
                parsedPgn.ecoCode(),
                parsedPgn.openingName(),
                parsedPgn.movesSan(),
                accuracy(source, playerIsWhite),
                source.pgn()
        );

        for (ParsedMove move : parsedPgn.moves()) {
            boolean playerMove = (move.ply() % 2 == 1) == playerIsWhite;
            game.addMove(new Move(move.ply(), move.san(), move.clockRemainingMs(), playerMove));
        }
        return game;
    }

    private static String gameId(MonthlyGame source) {
        if (source.uuid() != null && !source.uuid().isBlank()) {
            return source.uuid().trim();
        }
        if (source.url() != null && !source.url().isBlank()) {
            return source.url().trim();
        }
        throw new ChessComUpstreamException("Chess.com returned a game without a UUID or URL.");
    }

    private static GameResult result(String playerResult, String opponentResult, String gameId) {
        if (playerResult == null || opponentResult == null) {
            throw new ChessComUpstreamException("Game " + gameId + " is missing a result.");
        }
        String normalized = playerResult.trim().toLowerCase(Locale.ROOT);
        if ("win".equals(normalized)) {
            return GameResult.WIN;
        }
        if (DRAW_REASONS.contains(normalized) && DRAW_REASONS.contains(opponentResult.trim().toLowerCase(Locale.ROOT))) {
            return GameResult.DRAW;
        }
        return GameResult.LOSS;
    }

    private static TerminationType termination(String playerResult, String opponentResult, GameResult result) {
        String reason = result == GameResult.WIN ? opponentResult : playerResult;
        if (reason == null) {
            return TerminationType.OTHER;
        }
        return switch (reason.trim().toLowerCase(Locale.ROOT)) {
            case "checkmated", "checkmate" -> TerminationType.CHECKMATE;
            case "resigned", "resign" -> TerminationType.RESIGN;
            case "timeout" -> TerminationType.TIMEOUT;
            case "abandoned" -> TerminationType.ABANDONED;
            case "stalemate" -> TerminationType.STALEMATE;
            case "agreed", "agreement", "draw" -> TerminationType.AGREEMENT;
            case "repetition", "threefoldrepetition" -> TerminationType.REPETITION;
            case "insufficient", "insufficientmaterial" -> TerminationType.INSUFFICIENT;
            default -> TerminationType.OTHER;
        };
    }

    private static BigDecimal accuracy(MonthlyGame source, boolean playerIsWhite) {
        if (source.accuracies() == null) {
            return null;
        }
        Double accuracy = playerIsWhite ? source.accuracies().white() : source.accuracies().black();
        return accuracy == null ? null : BigDecimal.valueOf(accuracy).setScale(2, RoundingMode.HALF_UP);
    }
}
