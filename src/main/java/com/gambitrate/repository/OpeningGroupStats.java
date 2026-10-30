package com.gambitrate.repository;

public record OpeningGroupStats(
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
        long baselineGames
) {
}
