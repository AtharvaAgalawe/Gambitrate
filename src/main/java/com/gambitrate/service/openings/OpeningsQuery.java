package com.gambitrate.service.openings;

import java.time.LocalDate;

public record OpeningsQuery(
        LocalDate from,
        LocalDate to,
        String timeClass,
        int minGames,
        boolean groupByLine,
        int firstMoves
) {
}
