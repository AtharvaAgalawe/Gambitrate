package com.gambitrate.service.trends;

import com.gambitrate.domain.Player;
import com.gambitrate.repository.MonthlyTrendStats;
import com.gambitrate.repository.PlayerRepository;
import com.gambitrate.repository.TrendAnalyticsRepository;
import com.gambitrate.service.PlayerNotSyncedException;
import com.gambitrate.service.analytics.AnalyticsFilter;
import com.gambitrate.service.analytics.AnalyticsMath;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
public class TrendsService {

    private static final List<String> NOTES = List.of(
            "Months are calendar months in UTC.",
            "averageRating is the mean pre-game player rating for games with a rating; "
                    + "ratingGames shows how many games contributed.",
            "averageAccuracy is computed only from games where chess.com supplied accuracy; "
                    + "coverage = contributing games / all games in the month.",
            "Months below minGames are hidden."
    );

    private final PlayerRepository playerRepository;
    private final TrendAnalyticsRepository analyticsRepository;

    public TrendsService(
            PlayerRepository playerRepository,
            TrendAnalyticsRepository analyticsRepository
    ) {
        this.playerRepository = playerRepository;
        this.analyticsRepository = analyticsRepository;
    }

    @Transactional(readOnly = true)
    public TrendsAnalysis analyze(String rawUsername, AnalyticsFilter filter) {
        String username = normalizeUsername(rawUsername);
        Player player = playerRepository.findByUsername(username)
                .orElseThrow(() -> new PlayerNotSyncedException(username));

        filter.validate();
        List<MonthlyTrendStats> stats = analyticsRepository.findMonthly(
                player.getId(),
                filter.fromInclusiveUtc(),
                filter.toExclusiveUtc(),
                filter.timeClass(),
                filter.minGames()
        );

        List<TrendsAnalysis.MonthlyTrend> months = stats.stream()
                .map(this::toMonthlyTrend)
                .toList();
        long totalGames = months.stream().mapToLong(TrendsAnalysis.MonthlyTrend::games).sum();
        long gamesWithAccuracy = months.stream()
                .mapToLong(TrendsAnalysis.MonthlyTrend::accuracyGames)
                .sum();
        TrendsAnalysis.AccuracyCoverage coverage = new TrendsAnalysis.AccuracyCoverage(
                gamesWithAccuracy,
                totalGames,
                totalGames == 0 ? 0.0 : round((double) gamesWithAccuracy / totalGames, 4)
        );

        return new TrendsAnalysis(
                player.getUsername(),
                filter,
                coverage,
                months,
                NOTES,
                months.isEmpty() ? "No months matched the requested filters and minGames threshold." : null
        );
    }

    private TrendsAnalysis.MonthlyTrend toMonthlyTrend(MonthlyTrendStats stats) {
        double accuracyCoverage = stats.games() == 0
                ? 0.0
                : (double) stats.accuracyGames() / stats.games();
        return new TrendsAnalysis.MonthlyTrend(
                stats.month(),
                stats.games(),
                stats.wins(),
                stats.draws(),
                stats.losses(),
                round(stats.winRate(), 4),
                stats.averageRating() == null ? null : round(stats.averageRating(), 2),
                stats.ratingGames(),
                stats.averageAccuracy() == null ? null : round(stats.averageAccuracy(), 2),
                stats.accuracyGames(),
                round(accuracyCoverage, 4)
        );
    }

    private static String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private static double round(double value, int places) {
        return AnalyticsMath.round(value, places);
    }
}
