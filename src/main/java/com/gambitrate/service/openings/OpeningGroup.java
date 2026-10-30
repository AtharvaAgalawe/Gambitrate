package com.gambitrate.service.openings;

public record OpeningGroup(
        String color,
        String ecoCode,
        String openingName,
        String line,
        long games,
        long wins,
        long draws,
        long losses,
        double winRate,
        double drawRate,
        Double avgRatingDelta,
        long ratingDeltaGames,
        double baselineWinRate,
        long baselineGames,
        double estimatedRatingCost,
        OpeningVerdict verdict,
        String verdictReason
) {
}
