package com.gambitrate.service.timecontrols;

import com.gambitrate.domain.Player;
import com.gambitrate.repository.ClockAtMoveStats;
import com.gambitrate.repository.GameLengthStats;
import com.gambitrate.repository.LossClockStats;
import com.gambitrate.repository.LossTerminationStats;
import com.gambitrate.repository.PlayerRepository;
import com.gambitrate.repository.TimeClassStats;
import com.gambitrate.repository.TimeControlAnalyticsRepository;
import com.gambitrate.repository.TimeControlOverallStats;
import com.gambitrate.service.PlayerNotSyncedException;
import com.gambitrate.service.analytics.AnalyticsFilter;
import com.gambitrate.service.analytics.AnalyticsMath;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
public class TimeControlsService {

    private static final List<String> NOTES = List.of(
            "Game-length buckets use full moves: 1-19, 20-39, 40-59, 60-79, 80-99, and 100+.",
            "Clock at move N is the player's remaining clock after their Nth move; "
                    + "coverage shows how many games in that time class reached it.",
            "Loss-clock shares use losses with a recorded final player clock; "
                    + "coverage shows how many losses had that data.",
            "Clock data is a time measurement, not evidence that a move was a blunder."
    );

    private final PlayerRepository playerRepository;
    private final TimeControlAnalyticsRepository analyticsRepository;

    public TimeControlsService(
            PlayerRepository playerRepository,
            TimeControlAnalyticsRepository analyticsRepository
    ) {
        this.playerRepository = playerRepository;
        this.analyticsRepository = analyticsRepository;
    }

    @Transactional(readOnly = true)
    public TimeControlsAnalysis analyze(String rawUsername, AnalyticsFilter filter) {
        String username = normalizeUsername(rawUsername);
        Player player = playerRepository.findByUsername(username)
                .orElseThrow(() -> new PlayerNotSyncedException(username));
        return analyze(player, filter);
    }

    @Transactional(readOnly = true)
    public TimeControlsAnalysis analyze(Player player, AnalyticsFilter filter) {
        filter.validate();

        TimeControlOverallStats overallStats = analyticsRepository.findOverall(
                player.getId(),
                filter.fromInclusiveUtc(),
                filter.toExclusiveUtc(),
                filter.timeClass()
        );
        List<TimeClassStats> timeClassStats = analyticsRepository.findByTimeClass(
                player.getId(),
                filter.fromInclusiveUtc(),
                filter.toExclusiveUtc(),
                filter.timeClass(),
                filter.minGames()
        );
        List<GameLengthStats> gameLengthStats = analyticsRepository.findByGameLength(
                player.getId(),
                filter.fromInclusiveUtc(),
                filter.toExclusiveUtc(),
                filter.timeClass(),
                filter.minGames()
        );
        List<LossTerminationStats> terminationStats = analyticsRepository.findLossTerminations(
                player.getId(),
                filter.fromInclusiveUtc(),
                filter.toExclusiveUtc(),
                filter.timeClass(),
                filter.minGames()
        );
        List<ClockAtMoveStats> clockStats = analyticsRepository.findClockAtMoves(
                player.getId(),
                filter.fromInclusiveUtc(),
                filter.toExclusiveUtc(),
                filter.timeClass(),
                filter.minGames()
        );
        List<LossClockStats> lossClockStats = analyticsRepository.findLossClocks(
                player.getId(),
                filter.fromInclusiveUtc(),
                filter.toExclusiveUtc(),
                filter.timeClass(),
                filter.minGames()
        );

        TimeControlsAnalysis.Overall overall = new TimeControlsAnalysis.Overall(
                overallStats.games(),
                overallStats.wins(),
                overallStats.draws(),
                overallStats.losses(),
                round(AnalyticsMath.winRate(overallStats.wins(), overallStats.games()), 4)
        );
        List<TimeControlsAnalysis.TimeClassPerformance> byTimeClass = timeClassStats.stream()
                .map(this::toTimeClassPerformance)
                .toList();
        List<TimeControlsAnalysis.GameLengthPerformance> byGameLength = gameLengthStats.stream()
                .map(this::toGameLengthPerformance)
                .toList();
        List<TimeControlsAnalysis.LossTermination> lossTerminations = terminationStats.stream()
                .map(this::toLossTermination)
                .toList();
        List<TimeControlsAnalysis.ClockAtMove> clockAtMoves = clockStats.stream()
                .map(this::toClockAtMove)
                .toList();
        List<TimeControlsAnalysis.LossClock> lossClocks = lossClockStats.stream()
                .map(this::toLossClock)
                .toList();

        return new TimeControlsAnalysis(
                player.getUsername(),
                filter,
                overall,
                byTimeClass,
                byGameLength,
                lossTerminations,
                clockAtMoves,
                lossClocks,
                NOTES,
                message(overall, byTimeClass)
        );
    }

    private TimeControlsAnalysis.TimeClassPerformance toTimeClassPerformance(TimeClassStats stats) {
        return new TimeControlsAnalysis.TimeClassPerformance(
                stats.timeClass(),
                stats.games(),
                stats.wins(),
                stats.draws(),
                stats.losses(),
                round(stats.winRate(), 4)
        );
    }

    private TimeControlsAnalysis.GameLengthPerformance toGameLengthPerformance(GameLengthStats stats) {
        return new TimeControlsAnalysis.GameLengthPerformance(
                stats.bucket(),
                stats.bucket() + " moves",
                stats.games(),
                stats.wins(),
                stats.draws(),
                stats.losses(),
                round(stats.winRate(), 4)
        );
    }

    private TimeControlsAnalysis.LossTermination toLossTermination(LossTerminationStats stats) {
        return new TimeControlsAnalysis.LossTermination(
                stats.timeClass(),
                stats.totalGames(),
                stats.totalLosses(),
                reason(stats.timeoutLosses(), stats.totalLosses()),
                reason(stats.checkmateLosses(), stats.totalLosses()),
                reason(stats.resignLosses(), stats.totalLosses()),
                reason(stats.otherLosses(), stats.totalLosses())
        );
    }

    private static TimeControlsAnalysis.LossReason reason(long losses, long totalLosses) {
        double share = totalLosses == 0 ? 0.0 : (double) losses / totalLosses;
        return new TimeControlsAnalysis.LossReason(losses, round(share, 4));
    }

    private TimeControlsAnalysis.ClockAtMove toClockAtMove(ClockAtMoveStats stats) {
        double coverage = stats.gamesInTimeClass() == 0
                ? 0.0
                : (double) stats.gamesWithClock() / stats.gamesInTimeClass();
        Long averageMs = stats.averageClockRemainingMs() == null
                ? null
                : Math.round(stats.averageClockRemainingMs());
        Double averageSeconds = averageMs == null ? null : round(averageMs / 1000.0, 2);
        return new TimeControlsAnalysis.ClockAtMove(
                stats.timeClass(),
                stats.moveNumber(),
                stats.gamesInTimeClass(),
                stats.gamesWithClock(),
                round(coverage, 4),
                averageMs,
                averageSeconds
        );
    }

    private TimeControlsAnalysis.LossClock toLossClock(LossClockStats stats) {
        double coverage = stats.totalLosses() == 0
                ? 0.0
                : (double) stats.lossesWithClock() / stats.totalLosses();
        Double overShare = stats.lossesWithClock() == 0
                ? null
                : round((double) stats.over30s() / stats.lossesWithClock(), 4);
        Double betweenShare = stats.lossesWithClock() == 0
                ? null
                : round((double) stats.between10And30s() / stats.lossesWithClock(), 4);
        Double underShare = stats.lossesWithClock() == 0
                ? null
                : round((double) stats.under10s() / stats.lossesWithClock(), 4);
        return new TimeControlsAnalysis.LossClock(
                stats.timeClass(),
                stats.totalLosses(),
                stats.lossesWithClock(),
                round(coverage, 4),
                stats.over30s(),
                stats.between10And30s(),
                stats.under10s(),
                overShare,
                betweenShare,
                underShare
        );
    }

    private static String message(
            TimeControlsAnalysis.Overall overall,
            List<TimeControlsAnalysis.TimeClassPerformance> byTimeClass
    ) {
        if (overall.games() == 0) {
            return "No games matched the requested filters.";
        }
        if (byTimeClass.isEmpty()) {
            return "Games matched, but no time class met the minGames threshold.";
        }
        return null;
    }

    private static String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private static double round(double value, int places) {
        return AnalyticsMath.round(value, places);
    }
}
