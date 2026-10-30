package com.gambitrate.api.dto;

import com.gambitrate.service.timecontrols.TimeControlsAnalysis;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "TimeControlsResponse",
        description = "Win rates, loss terminations, and clock coverage by time class and game length.")
public record TimeControlsResponse(
        @Schema(example = "hikaru") String username,
        AnalyticsFiltersResponse filters,
        Overall overall,
        List<TimeClassPerformance> byTimeClass,
        List<GameLengthPerformance> byGameLength,
        List<LossTermination> lossTerminations,
        List<ClockAtMove> clockAtMoves,
        List<LossClock> lossClocks,
        @Schema(description = "Definitions and honesty notes for the returned clock metrics.")
        List<String> notes,
        @Schema(example = "No games matched the requested filters.", nullable = true)
        String message
) {

    public static TimeControlsResponse from(TimeControlsAnalysis analysis) {
        return new TimeControlsResponse(
                analysis.username(),
                AnalyticsFiltersResponse.from(analysis.filter()),
                new Overall(
                        analysis.overall().games(),
                        analysis.overall().wins(),
                        analysis.overall().draws(),
                        analysis.overall().losses(),
                        analysis.overall().winRate()
                ),
                analysis.byTimeClass().stream()
                        .map(stat -> new TimeClassPerformance(
                                stat.timeClass(),
                                stat.games(),
                                stat.wins(),
                                stat.draws(),
                                stat.losses(),
                                stat.winRate()
                        ))
                        .toList(),
                analysis.byGameLength().stream()
                        .map(stat -> new GameLengthPerformance(
                                stat.bucket(),
                                stat.label(),
                                stat.games(),
                                stat.wins(),
                                stat.draws(),
                                stat.losses(),
                                stat.winRate()
                        ))
                        .toList(),
                analysis.lossTerminations().stream()
                        .map(stat -> new LossTermination(
                                stat.timeClass(),
                                stat.totalGames(),
                                stat.totalLosses(),
                                new LossReason(stat.timeout().losses(), stat.timeout().share()),
                                new LossReason(stat.checkmate().losses(), stat.checkmate().share()),
                                new LossReason(stat.resign().losses(), stat.resign().share()),
                                new LossReason(stat.other().losses(), stat.other().share())
                        ))
                        .toList(),
                analysis.clockAtMoves().stream()
                        .map(stat -> new ClockAtMove(
                                stat.timeClass(),
                                stat.moveNumber(),
                                stat.gamesInTimeClass(),
                                stat.gamesWithClock(),
                                stat.coverage(),
                                stat.averageClockRemainingMs(),
                                stat.averageClockRemainingSeconds()
                        ))
                        .toList(),
                analysis.lossClocks().stream()
                        .map(stat -> new LossClock(
                                stat.timeClass(),
                                stat.totalLosses(),
                                stat.lossesWithClock(),
                                stat.coverage(),
                                stat.over30s(),
                                stat.between10And30s(),
                                stat.under10s(),
                                stat.shareOver30s(),
                                stat.shareBetween10And30s(),
                                stat.shareUnder10s()
                        ))
                        .toList(),
                analysis.notes(),
                analysis.message()
        );
    }

    @Schema(name = "TimeControlsOverall")
    public record Overall(
            @Schema(example = "120") long games,
            @Schema(example = "55") long wins,
            @Schema(example = "20") long draws,
            @Schema(example = "45") long losses,
            @Schema(example = "0.4583") double winRate
    ) {
    }

    @Schema(name = "TimeClassPerformance")
    public record TimeClassPerformance(
            @Schema(example = "rapid") String timeClass,
            @Schema(example = "120") long games,
            @Schema(example = "55") long wins,
            @Schema(example = "20") long draws,
            @Schema(example = "45") long losses,
            @Schema(example = "0.4583") double winRate
    ) {
    }

    @Schema(name = "GameLengthPerformance")
    public record GameLengthPerformance(
            @Schema(example = "20-39") String bucket,
            @Schema(example = "20-39 moves") String label,
            @Schema(example = "35") long games,
            @Schema(example = "14") long wins,
            @Schema(example = "6") long draws,
            @Schema(example = "15") long losses,
            @Schema(example = "0.4") double winRate
    ) {
    }

    @Schema(name = "LossTermination")
    public record LossTermination(
            @Schema(example = "rapid") String timeClass,
            @Schema(example = "120") long totalGames,
            @Schema(example = "45") long totalLosses,
            LossReason timeout,
            LossReason checkmate,
            LossReason resign,
            LossReason other
    ) {
    }

    @Schema(name = "LossReason")
    public record LossReason(
            @Schema(example = "10") long losses,
            @Schema(example = "0.2222") double share
    ) {
    }

    @Schema(name = "ClockAtMove")
    public record ClockAtMove(
            @Schema(example = "rapid") String timeClass,
            @Schema(example = "10") int moveNumber,
            @Schema(example = "120") long gamesInTimeClass,
            @Schema(example = "100") long gamesWithClock,
            @Schema(example = "0.8333") double coverage,
            @Schema(example = "345000", nullable = true) Long averageClockRemainingMs,
            @Schema(example = "345.0", nullable = true) Double averageClockRemainingSeconds
    ) {
    }

    @Schema(name = "LossClock")
    public record LossClock(
            @Schema(example = "rapid") String timeClass,
            @Schema(example = "45") long totalLosses,
            @Schema(example = "40") long lossesWithClock,
            @Schema(example = "0.8889") double coverage,
            @Schema(example = "12") long over30s,
            @Schema(example = "8") long between10And30s,
            @Schema(example = "20") long under10s,
            @Schema(example = "0.3", nullable = true) Double shareOver30s,
            @Schema(example = "0.2", nullable = true) Double shareBetween10And30s,
            @Schema(example = "0.5", nullable = true) Double shareUnder10s
    ) {
    }
}
