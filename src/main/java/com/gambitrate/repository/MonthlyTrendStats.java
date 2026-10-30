package com.gambitrate.repository;

public record MonthlyTrendStats(
        String month,
        long games,
        long wins,
        long draws,
        long losses,
        double winRate,
        Double averageRating,
        long ratingGames,
        Double averageAccuracy,
        long accuracyGames
) {
}
