package com.gambitrate.service.summary;

import com.gambitrate.service.analytics.AnalyticsFilter;

import java.util.List;

public record SummaryAnalysis(
        String username,
        AnalyticsFilter filter,
        String timezone,
        List<SummaryLeak> leaks,
        List<String> notes,
        String message
) {

    public record SummaryLeak(
            int rank,
            String area,
            String label,
            Evidence evidence,
            double estimatedRatingImpact,
            String explanation
    ) {
    }

    public record Evidence(
            String color,
            String ecoCode,
            String openingName,
            String timeClass,
            String bucket,
            Integer hourOfDay,
            String day,
            long sampleSize,
            long wins,
            long draws,
            long losses,
            double winRate,
            double baselineWinRate,
            long baselineSampleSize,
            Double averageRatingChange,
            Long ratingChangeSamples
    ) {
    }
}
