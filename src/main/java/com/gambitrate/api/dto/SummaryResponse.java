package com.gambitrate.api.dto;

import com.gambitrate.service.summary.SummaryAnalysis;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "SummaryResponse",
        description = "Top three statistically guarded leaks ranked by estimated rating impact.")
public record SummaryResponse(
        @Schema(example = "hikaru") String username,
        AnalyticsFiltersResponse filters,
        List<Leak> leaks,
        List<String> notes,
        @Schema(example = "No statistically guarded leaks were found for the requested filters.",
                nullable = true)
        String message
) {

    public static SummaryResponse from(SummaryAnalysis analysis) {
        AnalyticsFiltersResponse filters = AnalyticsFiltersResponse.from(
                analysis.filter(),
                analysis.timezone()
        );
        return new SummaryResponse(
                analysis.username(),
                filters,
                analysis.leaks().stream()
                        .map(leak -> new Leak(
                                leak.rank(),
                                leak.area(),
                                leak.label(),
                                filters,
                                leak.evidence().sampleSize(),
                                leak.estimatedRatingImpact(),
                                new Evidence(
                                        leak.evidence().color(),
                                        leak.evidence().ecoCode(),
                                        leak.evidence().openingName(),
                                        leak.evidence().timeClass(),
                                        leak.evidence().bucket(),
                                        leak.evidence().hourOfDay(),
                                        leak.evidence().day(),
                                        leak.evidence().sampleSize(),
                                        leak.evidence().wins(),
                                        leak.evidence().draws(),
                                        leak.evidence().losses(),
                                        leak.evidence().winRate(),
                                        leak.evidence().baselineWinRate(),
                                        leak.evidence().baselineSampleSize(),
                                        leak.evidence().averageRatingChange(),
                                        leak.evidence().ratingChangeSamples()
                                ),
                                leak.explanation()
                        ))
                        .toList(),
                analysis.notes(),
                analysis.message()
        );
    }

    @Schema(name = "SummaryLeak")
    public record Leak(
            @Schema(example = "1") int rank,
            @Schema(example = "postLoss",
                    allowableValues = {"openings", "timeClass", "gameLength", "postLoss",
                            "hourOfDay", "dayOfWeek"})
            String area,
            @Schema(example = "After a loss") String label,
            AnalyticsFiltersResponse filters,
            @Schema(example = "14") long sampleSize,
            @Schema(example = "98.89",
                    description = "(baselineWinRate - groupWinRate) x games x 10; heuristic estimate.")
            double estimatedRatingImpact,
            Evidence evidence,
            @Schema(example = "After a loss, winrate is 7.1% vs 77.8% after a win over "
                    + "14 post-loss and 9 post-win games; the two-proportion guard is met.")
            String explanation
    ) {
    }

    @Schema(name = "SummaryEvidence",
            description = "Supporting numbers for a ranked leak. Null fields do not apply to that area.")
    public record Evidence(
            @Schema(example = "white", nullable = true) String color,
            @Schema(example = "B01", nullable = true) String ecoCode,
            @Schema(example = "Scandinavian Defense", nullable = true) String openingName,
            @Schema(example = "rapid", nullable = true) String timeClass,
            @Schema(example = "20-39", nullable = true) String bucket,
            @Schema(example = "21", nullable = true) Integer hourOfDay,
            @Schema(example = "Monday", nullable = true) String day,
            @Schema(example = "10") long sampleSize,
            @Schema(example = "1") long wins,
            @Schema(example = "0") long draws,
            @Schema(example = "9") long losses,
            @Schema(example = "0.1") double winRate,
            @Schema(example = "0.5") double baselineWinRate,
            @Schema(example = "20") long baselineSampleSize,
            @Schema(example = "-1.2", nullable = true) Double averageRatingChange,
            @Schema(example = "9", nullable = true) Long ratingChangeSamples
    ) {
    }
}
