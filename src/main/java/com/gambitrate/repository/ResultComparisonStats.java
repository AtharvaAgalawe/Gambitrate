package com.gambitrate.repository;

public record ResultComparisonStats(
        String previousResult,
        long games,
        long wins,
        long draws,
        long losses,
        double winRate,
        Double averageRatingChange,
        long ratingChangeSamples
) {
}
