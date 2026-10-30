package com.gambitrate.ingest;

import com.github.bhlangonijr.chesslib.game.Game;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.pgn.PgnHolder;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class PgnParser {

    public ParsedPgn parse(String pgn) {
        if (pgn == null || pgn.isBlank()) {
            throw new PgnParseException("PGN is empty.");
        }
        try {
            PgnHolder holder = new PgnHolder();
            holder.loadPgn(pgn);
            if (holder.getGames().isEmpty()) {
                throw new PgnParseException("PGN contains no games.");
            }

            Game game = holder.getGames().get(0);
            Map<String, String> properties = game.getProperty() == null ? Map.of() : game.getProperty();
            Map<Integer, String> comments = game.getComments() == null ? Map.of() : game.getComments();

            List<ParsedMove> parsedMoves = new ArrayList<>();
            int ply = 1;
            for (Move move : game.getHalfMoves()) {
                String san = move.getSan();
                if (san == null || san.isBlank()) {
                    throw new PgnParseException("PGN move at ply " + ply + " has no SAN.");
                }
                parsedMoves.add(new ParsedMove(ply, san, ClockParser.parseMillis(comments.get(ply))));
                ply++;
            }

            String movesSan = parsedMoves.stream()
                    .map(ParsedMove::san)
                    .collect(Collectors.joining(" "));

            return new ParsedPgn(
                    trimToNull(game.getEco()),
                    openingName(game.getOpening(), properties),
                    movesSan,
                    List.copyOf(parsedMoves)
            );
        } catch (PgnParseException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new PgnParseException("Could not parse PGN.", exception);
        }
    }

    private static String openingName(String openingHeader, Map<String, String> properties) {
        String opening = trimToNull(openingHeader);
        if (opening != null) {
            return opening;
        }
        String ecoUrl = trimToNull(properties.get("ECOUrl"));
        if (ecoUrl == null) {
            return null;
        }
        try {
            String path = URI.create(ecoUrl).getPath();
            if (path == null || path.isBlank()) {
                return null;
            }
            String slug = path.substring(path.lastIndexOf('/') + 1);
            return slug.isBlank() ? null : slug.replace('-', ' ').replace('_', ' ').trim();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
