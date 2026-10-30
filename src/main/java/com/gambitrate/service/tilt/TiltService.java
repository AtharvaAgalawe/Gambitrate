package com.gambitrate.service.tilt;

import com.gambitrate.config.AnalyticsProperties;
import com.gambitrate.domain.Player;
import com.gambitrate.repository.DayStats;
import com.gambitrate.repository.HourStats;
import com.gambitrate.repository.PlayerRepository;
import com.gambitrate.repository.ResultComparisonStats;
import com.gambitrate.repository.SessionIndexStats;
import com.gambitrate.repository.TiltAnalyticsRepository;
import com.gambitrate.repository.TiltOverallStats;
import com.gambitrate.service.InvalidFilterException;
import com.gambitrate.service.PlayerNotSyncedException;
import com.gambitrate.service.analytics.AnalyticsFilter;
import com.gambitrate.service.analytics.AnalyticsMath;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TiltService {

    private final PlayerRepository playerRepository;
    private final TiltAnalyticsRepository analyticsRepository;
    private final AnalyticsProperties properties;

    public TiltService(
            PlayerRepository playerRepository,
            TiltAnalyticsRepository analyticsRepository,
            AnalyticsProperties properties
    ) {
        this.playerRepository = playerRepository;
        this.analyticsRepository = analyticsRepository;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public TiltAnalysis analyze(String rawUsername, AnalyticsFilter filter, String rawTimezone) {
        String username = normalizeUsername(rawUsername);
        Player player = playerRepository.findByUsername(username)
                .orElseThrow(() -> new PlayerNotSyncedException(username));
        return analyze(player, filter, rawTimezone);
    }

    @Transactional(readOnly = true)
    public TiltAnalysis analyze(Player player, AnalyticsFilter filter, String rawTimezone) {
        filter.validate();
        String timezone = validateTimezone(rawTimezone);
        long sessionGapSeconds = properties.tiltSessionGap().toSeconds();
        long sessionGapMinutes = properties.tiltSessionGap().toMinutes();

        TiltOverallStats overallStats = analyticsRepository.findOverall(
                player.getId(),
                filter.fromInclusiveUtc(),
                filter.toExclusiveUtc(),
                filter.timeClass()
        );
        Map<String, ResultComparisonStats> comparisons = analyticsRepository.findResultComparisons(
                        player.getId(),
                        filter.fromInclusiveUtc(),
                        filter.toExclusiveUtc(),
                        filter.timeClass(),
                        sessionGapSeconds
                ).stream()
                .collect(Collectors.toMap(ResultComparisonStats::previousResult, Function.identity()));
        List<SessionIndexStats> sessionIndexStats = analyticsRepository.findSessionIndexes(
                player.getId(),
                filter.fromInclusiveUtc(),
                filter.toExclusiveUtc(),
                filter.timeClass(),
                sessionGapSeconds,
                filter.minGames()
        );
        List<HourStats> hourStats = analyticsRepository.findHourly(
                player.getId(),
                filter.fromInclusiveUtc(),
                filter.toExclusiveUtc(),
                filter.timeClass(),
                timezone,
                filter.minGames()
        );
        List<DayStats> dayStats = analyticsRepository.findDaily(
                player.getId(),
                filter.fromInclusiveUtc(),
                filter.toExclusiveUtc(),
                filter.timeClass(),
                timezone,
                filter.minGames()
        );

        TiltAnalysis.Overall overall = new TiltAnalysis.Overall(
                overallStats.games(),
                overallStats.wins(),
                overallStats.draws(),
                overallStats.losses(),
                round(AnalyticsMath.winRate(overallStats.wins(), overallStats.games()), 4)
        );
        TiltAnalysis.TiltComparison postLoss = comparison(comparisons.get("loss"));
        TiltAnalysis.TiltComparison postWin = comparison(comparisons.get("win"));
        TiltSignal signal = TiltScoreCalculator.calculate(postLoss, postWin, filter.minGames());

        return new TiltAnalysis(
                player.getUsername(),
                filter,
                timezone,
                sessionGapMinutes,
                overall,
                postLoss,
                postWin,
                signal.score(),
                signal.sampleSufficient(),
                signal.significant(),
                signal.verdict(),
                sessionIndexStats.stream().map(this::toSessionIndex).toList(),
                hourStats.stream().map(this::toHour).toList(),
                dayStats.stream().map(this::toDay).toList(),
                notes(sessionGapMinutes),
                overall.games() == 0 ? "No games matched the requested filters." : null
        );
    }

    private static TiltAnalysis.TiltComparison comparison(ResultComparisonStats stats) {
        if (stats == null) {
            return null;
        }
        return new TiltAnalysis.TiltComparison(
                stats.previousResult(),
                stats.games(),
                stats.wins(),
                stats.draws(),
                stats.losses(),
                round(stats.winRate(), 4),
                stats.averageRatingChange() == null ? null : round(stats.averageRatingChange(), 2),
                stats.ratingChangeSamples()
        );
    }

    private TiltAnalysis.SessionIndexPerformance toSessionIndex(SessionIndexStats stats) {
        return new TiltAnalysis.SessionIndexPerformance(
                stats.gameIndex(),
                stats.games(),
                stats.wins(),
                stats.draws(),
                stats.losses(),
                round(stats.winRate(), 4)
        );
    }

    private TiltAnalysis.HourPerformance toHour(HourStats stats) {
        return new TiltAnalysis.HourPerformance(
                stats.hourOfDay(),
                stats.games(),
                stats.wins(),
                stats.draws(),
                stats.losses(),
                round(stats.winRate(), 4)
        );
    }

    private TiltAnalysis.DayPerformance toDay(DayStats stats) {
        String day = DayOfWeek.of(stats.isoDayOfWeek())
                .getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        return new TiltAnalysis.DayPerformance(
                stats.isoDayOfWeek(),
                day,
                stats.games(),
                stats.wins(),
                stats.draws(),
                stats.losses(),
                round(stats.winRate(), 4)
        );
    }

    private static List<String> notes(long sessionGapMinutes) {
        return List.of(
                "A session is consecutive games with a gap under %d minutes; the gap is configurable."
                        .formatted(sessionGapMinutes),
                "Tilt score = (post-win winrate - post-loss winrate) x 100; "
                        + "positive means the player performs worse immediately after a loss.",
                "A tilt verdict requires minGames on both sides and a two-proportion 95% z-test.",
                "Hour and day groups use PostgreSQL timezone conversion with tz; "
                        + "date filters remain UTC.",
                "Average rating change is the difference between consecutive pre-game ratings "
                        + "in the same time class; it reflects the previous result's rating effect, "
                        + "and ratingChangeSamples shows coverage."
        );
    }

    private static String validateTimezone(String rawTimezone) {
        String timezone = rawTimezone == null || rawTimezone.isBlank() ? "UTC" : rawTimezone.trim();
        try {
            ZoneId zone = ZoneId.of(timezone);
            if (!"UTC".equals(zone.getId()) && zone.getRules().isFixedOffset()) {
                throw new InvalidFilterException(
                        "'tz' must be an IANA region ID such as America/New_York, or UTC."
                );
            }
            return zone.getId();
        } catch (DateTimeException exception) {
            throw new InvalidFilterException(
                    "'tz' must be a valid IANA timezone such as America/New_York, or UTC."
            );
        }
    }

    private static String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private static double round(double value, int places) {
        return AnalyticsMath.round(value, places);
    }
}
