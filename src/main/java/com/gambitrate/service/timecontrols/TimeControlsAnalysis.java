package com.gambitrate.service.timecontrols;

import com.gambitrate.service.analytics.AnalyticsFilter;

import java.util.List;

public record TimeControlsAnalysis(
        String username,
        AnalyticsFilter filter,
        Overall overall,
        List<TimeClassPerformance> byTimeClass,
        List<GameLengthPerformance> byGameLength,
        List<LossTermination> lossTerminations,
        List<ClockAtMove> clockAtMoves,
        List<LossClock> lossClocks,
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

    public record TimeClassPerformance(
            String timeClass,
            long games,
            long wins,
            long draws,
            long losses,
            double winRate
    ) {
    }

    public record GameLengthPerformance(
            String bucket,
            String label,
            long games,
            long wins,
            long draws,
            long losses,
            double winRate
    ) {
    }

    public record LossTermination(
            String timeClass,
            long totalGames,
            long totalLosses,
            LossReason timeout,
            LossReason checkmate,
            LossReason resign,
            LossReason other
    ) {
    }

    public record LossReason(
            long losses,
            double share
    ) {
    }

    public record ClockAtMove(
            String timeClass,
            int moveNumber,
            long gamesInTimeClass,
            long gamesWithClock,
            double coverage,
            Long averageClockRemainingMs,
            Double averageClockRemainingSeconds
    ) {
    }

    public record LossClock(
            String timeClass,
            long totalLosses,
            long lossesWithClock,
            double coverage,
            long over30s,
            long between10And30s,
            long under10s,
            Double shareOver30s,
            Double shareBetween10And30s,
            Double shareUnder10s
    ) {
    }
}
