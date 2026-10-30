package com.gambitrate.repository;

public record DayStats(
        int isoDayOfWeek,
        long games,
        long wins,
        long draws,
        long losses,
        double winRate
) {
}
