package com.gambitrate.service.trends;

import com.gambitrate.service.analytics.AnalyticsFilter;

import java.util.List;

public record TrendsAnalysis(
        String username,
        AnalyticsFilter filter,
        AccuracyCoverage accuracyCoverage,
        List<MonthlyTrend> months,
        List<String> notes,
        String message
) {

    public record AccuracyCoverage(
            long gamesWithAccuracy,
            long totalGames,
            double coverage
    ) {
    }

    public record MonthlyTrend(
            String month,
            long games,
            long wins,
            long draws,
            long losses,
            double winRate,
            Double averageRating,
            long ratingGames,
            Double averageAccuracy,
            long accuracyGames,
            double accuracyCoverage
    ) {
    }
}
