package com.gambitrate.service.analytics;

import com.gambitrate.service.InvalidFilterException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Set;

public record AnalyticsFilter(
        LocalDate from,
        LocalDate to,
        String timeClass,
        int minGames
) {

    public static final int DEFAULT_MIN_GAMES = 5;
    public static final int MAX_MIN_GAMES = 10_000;
    private static final Set<String> TIME_CLASSES = Set.of("bullet", "blitz", "rapid", "daily");

    public AnalyticsFilter {
        timeClass = timeClass == null ? null : timeClass.trim().toLowerCase(Locale.ROOT);
    }

    public void validate() {
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidFilterException("'from' must be on or before 'to'.");
        }
        if (minGames < 1 || minGames > MAX_MIN_GAMES) {
            throw new InvalidFilterException("'minGames' must be between 1 and 10000.");
        }
        if (timeClass != null && !TIME_CLASSES.contains(timeClass)) {
            throw new InvalidFilterException(
                    "'timeClass' must be one of bullet, blitz, rapid, or daily."
            );
        }
    }

    public OffsetDateTime fromInclusiveUtc() {
        return from == null ? null : from.atStartOfDay().atOffset(ZoneOffset.UTC);
    }

    public OffsetDateTime toExclusiveUtc() {
        return to == null ? null : to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
    }
}
