package com.gambitrate.repository;

public record HourStats(
        int hourOfDay,
        long games,
        long wins,
        long draws,
        long losses,
        double winRate
) {
}
