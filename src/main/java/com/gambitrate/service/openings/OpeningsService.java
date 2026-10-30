package com.gambitrate.service.openings;

import com.gambitrate.domain.Player;
import com.gambitrate.repository.OpeningAnalyticsRepository;
import com.gambitrate.repository.OpeningGroupStats;
import com.gambitrate.repository.PlayerRepository;
import com.gambitrate.service.InvalidFilterException;
import com.gambitrate.service.PlayerNotSyncedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class OpeningsService {

    private static final double Z_THRESHOLD = 1.96;
    private static final long MIN_VERDICT_GAMES = 5;
    private static final double ELO_POINTS_PER_SCORE_UNIT = 10.0;
    private static final Set<String> TIME_CLASSES = Set.of("bullet", "blitz", "rapid", "daily");

    private final PlayerRepository playerRepository;
    private final OpeningAnalyticsRepository openingAnalyticsRepository;

    public OpeningsService(
            PlayerRepository playerRepository,
            OpeningAnalyticsRepository openingAnalyticsRepository
    ) {
        this.playerRepository = playerRepository;
        this.openingAnalyticsRepository = openingAnalyticsRepository;
    }

    @Transactional(readOnly = true)
    public OpeningsAnalysis analyze(String rawUsername, OpeningsQuery query) {
        String username = normalizeUsername(rawUsername);
        validate(query);
        Player player = playerRepository.findByUsername(username)
                .orElseThrow(() -> new PlayerNotSyncedException(username));

        OffsetDateTime from = query.from() == null
                ? null
                : query.from().atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime toExclusive = query.to() == null
                ? null
                : query.to().plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        int linePlies = query.groupByLine() ? query.firstMoves() * 2 : 0;

        List<OpeningGroup> groups = openingAnalyticsRepository.findOpeningGroups(
                        player.getId(),
                        from,
                        toExclusive,
                        query.timeClass(),
                        query.minGames(),
                        linePlies
                ).stream()
                .map(this::toOpeningGroup)
                .sorted(Comparator.comparingDouble(OpeningGroup::estimatedRatingCost).reversed()
                        .thenComparing(OpeningGroup::color)
                        .thenComparing(OpeningGroup::ecoCode))
                .toList();

        String message = groups.isEmpty()
                ? "No openings matched the requested filters and minimum game count."
                : null;

        return new OpeningsAnalysis(username, query, groups, message);
    }

    private OpeningGroup toOpeningGroup(OpeningGroupStats stats) {
        double winRate = round(stats.winRate(), 4);
        double drawRate = round(stats.drawRate(), 4);
        double baselineWinRate = round(stats.baselineWinRate(), 4);
        Double avgRatingDelta = stats.avgRatingDelta() == null
                ? null
                : round(stats.avgRatingDelta(), 4);
        double estimatedRatingCost = round(
                (stats.baselineWinRate() - stats.winRate()) * stats.games() * ELO_POINTS_PER_SCORE_UNIT,
                2
        );

        OpeningVerdict verdict = verdict(stats.games(), stats.winRate(), stats.baselineWinRate());
        String reason = verdictReason(
                verdict,
                stats.color(),
                stats.games(),
                stats.winRate(),
                stats.baselineWinRate()
        );

        return new OpeningGroup(
                stats.color(),
                stats.ecoCode(),
                stats.openingName(),
                stats.line(),
                stats.games(),
                stats.wins(),
                stats.draws(),
                stats.losses(),
                winRate,
                drawRate,
                avgRatingDelta,
                stats.ratingDeltaGames(),
                baselineWinRate,
                stats.baselineGames(),
                estimatedRatingCost,
                verdict,
                reason
        );
    }

    private static OpeningVerdict verdict(long games, double winRate, double baselineWinRate) {
        if (games < MIN_VERDICT_GAMES) {
            return OpeningVerdict.FINE;
        }
        double standardError = Math.sqrt(baselineWinRate * (1.0 - baselineWinRate) / games);
        if (standardError == 0.0) {
            return OpeningVerdict.FINE;
        }
        double zScore = (winRate - baselineWinRate) / standardError;
        if (zScore >= Z_THRESHOLD) {
            return OpeningVerdict.STRENGTH;
        }
        if (zScore <= -Z_THRESHOLD) {
            return OpeningVerdict.LEAK;
        }
        return OpeningVerdict.FINE;
    }

    private static String verdictReason(
            OpeningVerdict verdict,
            String color,
            long games,
            double winRate,
            double baselineWinRate
    ) {
        String actual = percent(winRate);
        String baseline = percent(baselineWinRate);
        if (games < MIN_VERDICT_GAMES) {
            return "%d games is too small to flag; %s wins vs a %s %s baseline."
                    .formatted(games, actual, baseline, color);
        }
        return switch (verdict) {
            case LEAK -> "%s wins vs a %s %s baseline over %d games; the gap passes the 95%% guard."
                    .formatted(actual, baseline, color, games);
            case STRENGTH -> "%s wins vs a %s %s baseline over %d games; the gap passes the 95%% guard."
                    .formatted(actual, baseline, color, games);
            case FINE -> "Within normal game-to-game variation: %s wins vs a %s %s baseline over %d games."
                    .formatted(actual, baseline, color, games);
        };
    }

    private static String percent(double rate) {
        return String.format(Locale.ROOT, "%.1f%%", rate * 100.0);
    }

    private static void validate(OpeningsQuery query) {
        if (query.from() != null && query.to() != null && query.from().isAfter(query.to())) {
            throw new InvalidFilterException("'from' must be on or before 'to'.");
        }
        if (query.minGames() < 1) {
            throw new InvalidFilterException("'minGames' must be at least 1.");
        }
        if (query.firstMoves() < 1 || query.firstMoves() > 20) {
            throw new InvalidFilterException("'firstMoves' must be between 1 and 20.");
        }
        if (query.timeClass() != null && !TIME_CLASSES.contains(query.timeClass())) {
            throw new InvalidFilterException(
                    "'timeClass' must be one of bullet, blitz, rapid, or daily."
            );
        }
    }

    private static String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private static double round(double value, int places) {
        double factor = Math.pow(10, places);
        return Math.round(value * factor) / factor;
    }
}
