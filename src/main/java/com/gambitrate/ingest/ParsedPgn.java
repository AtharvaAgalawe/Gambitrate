package com.gambitrate.ingest;

import java.util.List;

public record ParsedPgn(
        String ecoCode,
        String openingName,
        String movesSan,
        List<ParsedMove> moves
) {
}
