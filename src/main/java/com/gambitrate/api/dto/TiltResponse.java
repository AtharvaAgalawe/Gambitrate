package com.gambitrate.api.dto;

import com.gambitrate.service.tilt.TiltAnalysis;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "TiltResponse",
        description = "Post-loss vs post-win performance, session index, and timezone-aware hour/day win rates.")
public record TiltResponse(
        @Schema(example = "hikaru") String username,
        AnalyticsFiltersResponse filters,
        @Schema(example = "30") long sessionGapMinutes,
        Overall overall,
        TiltComparison postLoss,
        TiltComparison postWin,
        @Schema(example = "12.5", nullable = true,
                description = "Percentage-point drop from post-win to post-loss winrate; positive means worse after a loss.")
        Double tiltScore,
        @Schema(example = "true") boolean sampleSufficient,
        @Schema(example = "true") boolean significant,
        @Schema(example = "After a loss, winrate drops to 38.0% from 52.0% after a win, "
                + "a 14.0-point tilt signal over 140 post-loss and 138 post-win games.")
        String verdict,
        List<SessionIndexPerformance> bySessionIndex,
        List<HourPerformance> byHour,
        List<DayPerformance> byDay,
        List<String> notes,
        @Schema(example = "No games matched the requested filters.", nullable = true)
        String message
) {

    public static TiltResponse from(TiltAnalysis analysis) {
        return new TiltResponse(
                analysis.username(),
                AnalyticsFiltersResponse.from(analysis.filter(), analysis.timezone()),
                analysis.sessionGapMinutes(),
                new Overall(
                        analysis.overall().games(),
                        analysis.overall().wins(),
                        analysis.overall().draws(),
                        analysis.overall().losses(),
                        analysis.overall().winRate()
                ),
                comparison(analysis.postLoss()),
                comparison(analysis.postWin()),
                analysis.tiltScore(),
                analysis.sampleSufficient(),
                analysis.significant(),
                analysis.verdict(),
                analysis.bySessionIndex().stream()
                        .map(stat -> new SessionIndexPerformance(
                                stat.gameIndex(),
                                stat.games(),
                                stat.wins(),
                                stat.draws(),
                                stat.losses(),
                                stat.winRate()
                        ))
                        .toList(),
                analysis.byHour().stream()
                        .map(stat -> new HourPerformance(
                                stat.hourOfDay(),
                                stat.games(),
                                stat.wins(),
                                stat.draws(),
                                stat.losses(),
                                stat.winRate()
                        ))
                        .toList(),
                analysis.byDay().stream()
                        .map(stat -> new DayPerformance(
                                stat.isoDayOfWeek(),
                                stat.day(),
                                stat.games(),
                                stat.wins(),
                                stat.draws(),
                                stat.losses(),
                                stat.winRate()
                        ))
                        .toList(),
                analysis.notes(),
                analysis.message()
        );
    }

    private static TiltComparison comparison(TiltAnalysis.TiltComparison comparison) {
        if (comparison == null) {
            return null;
        }
        return new TiltComparison(
                comparison.previousResult(),
                comparison.games(),
                comparison.wins(),
                comparison.draws(),
                comparison.losses(),
                comparison.winRate(),
                comparison.averageRatingChange(),
                comparison.ratingChangeSamples()
        );
    }

    @Schema(name = "TiltOverall")
    public record Overall(
            @Schema(example = "300") long games,
            @Schema(example = "150") long wins,
            @Schema(example = "30") long draws,
            @Schema(example = "120") long losses,
            @Schema(example = "0.5") double winRate
    ) {
    }

    @Schema(name = "TiltComparison")
    public record TiltComparison(
            @Schema(example = "loss") String previousResult,
            @Schema(example = "140") long games,
            @Schema(example = "53") long wins,
            @Schema(example = "5") long draws,
            @Schema(example = "82") long losses,
            @Schema(example = "0.3786") double winRate,
            @Schema(example = "-3.2", nullable = true) Double averageRatingChange,
            @Schema(example = "138") long ratingChangeSamples
    ) {
    }

    @Schema(name = "SessionIndexPerformance")
    public record SessionIndexPerformance(
            @Schema(example = "1") int gameIndex,
            @Schema(example = "150") long games,
            @Schema(example = "80") long wins,
            @Schema(example = "10") long draws,
            @Schema(example = "60") long losses,
            @Schema(example = "0.5333") double winRate
    ) {
    }

    @Schema(name = "HourPerformance")
    public record HourPerformance(
            @Schema(example = "21") int hourOfDay,
            @Schema(example = "40") long games,
            @Schema(example = "16") long wins,
            @Schema(example = "4") long draws,
            @Schema(example = "20") long losses,
            @Schema(example = "0.4") double winRate
    ) {
    }

    @Schema(name = "DayPerformance")
    public record DayPerformance(
            @Schema(example = "1") int isoDayOfWeek,
            @Schema(example = "Monday") String day,
            @Schema(example = "50") long games,
            @Schema(example = "20") long wins,
            @Schema(example = "5") long draws,
            @Schema(example = "25") long losses,
            @Schema(example = "0.4") double winRate
    ) {
    }
}
