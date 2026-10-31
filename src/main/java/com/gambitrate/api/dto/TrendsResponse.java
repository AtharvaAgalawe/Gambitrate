package com.gambitrate.api.dto;

import com.gambitrate.service.trends.TrendsAnalysis;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "TrendsResponse",
        description = "Monthly rating, winrate, game count, and accuracy coverage.")
public record TrendsResponse(
        @Schema(example = "hikaru") String username,
        AnalyticsFiltersResponse filters,
        AccuracyCoverage accuracyCoverage,
        @Schema(description = "Chronologically sorted by month.")
        List<MonthlyTrend> months,
        List<String> notes,
        @Schema(example = "No months matched the requested filters and minGames threshold.",
                nullable = true)
        String message
) {

    public static TrendsResponse from(TrendsAnalysis analysis) {
        return new TrendsResponse(
                analysis.username(),
                AnalyticsFiltersResponse.from(analysis.filter()),
                new AccuracyCoverage(
                        analysis.accuracyCoverage().gamesWithAccuracy(),
                        analysis.accuracyCoverage().totalGames(),
                        analysis.accuracyCoverage().coverage()
                ),
                analysis.months().stream()
                        .map(month -> new MonthlyTrend(
                                month.month(),
                                month.games(),
                                month.wins(),
                                month.draws(),
                                month.losses(),
                                month.winRate(),
                                month.averageRating(),
                                month.ratingGames(),
                                month.averageAccuracy(),
                                month.accuracyGames(),
                                month.accuracyCoverage()
                        ))
                        .toList(),
                analysis.notes(),
                analysis.message()
        );
    }

    @Schema(name = "AccuracyCoverage",
            description = "Accuracy games divided by total games. Always shown with accuracy.")
    public record AccuracyCoverage(
            @Schema(example = "7") long gamesWithAccuracy,
            @Schema(example = "11") long totalGames,
            @Schema(example = "0.6364") double coverage
    ) {
    }

    @Schema(name = "MonthlyTrend")
    public record MonthlyTrend(
            @Schema(example = "2024-01") String month,
            @Schema(example = "30") long games,
            @Schema(example = "15") long wins,
            @Schema(example = "5") long draws,
            @Schema(example = "10") long losses,
            @Schema(example = "0.5") double winRate,
            @Schema(example = "1512.5", nullable = true) Double averageRating,
            @Schema(example = "30") long ratingGames,
            @Schema(example = "85.0", nullable = true) Double averageAccuracy,
            @Schema(example = "4") long accuracyGames,
            @Schema(example = "0.6667") double accuracyCoverage
    ) {
    }
}
