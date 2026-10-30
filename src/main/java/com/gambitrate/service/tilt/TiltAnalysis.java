package com.gambitrate.service.tilt;

import com.gambitrate.service.analytics.AnalyticsFilter;

import java.util.List;

public record TiltAnalysis(
        String username,
        AnalyticsFilter filter,
        String timezone,
        long sessionGapMinutes,
        Overall overall,
        TiltComparison postLoss,
        TiltComparison postWin,
        Double tiltScore,
        boolean sampleSufficient,
        boolean significant,
        String verdict,
        List<SessionIndexPerformance> bySessionIndex,
        List<HourPerformance> byHour,
        List<DayPerformance> byDay,
        List<String> notes,
        String message
) {

    public record Overall(
            long games,
            long wins,
            long draws,
            long losses,
            double winRate
    ) {
    }

    public record TiltComparison(
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

    public record SessionIndexPerformance(
            int gameIndex,
            long games,
            long wins,
            long draws,
            long losses,
            double winRate
    ) {
    }

    public record HourPerformance(
            int hourOfDay,
            long games,
            long wins,
            long draws,
            long losses,
            double winRate
    ) {
    }

    public record DayPerformance(
            int isoDayOfWeek,
            String day,
            long games,
            long wins,
            long draws,
            long losses,
            double winRate
    ) {
    }
}
