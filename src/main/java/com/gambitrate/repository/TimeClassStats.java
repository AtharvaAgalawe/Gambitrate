package com.gambitrate.repository;

public record TimeClassStats(
        String timeClass,
        long games,
        long wins,
        long draws,
        long losses,
        double winRate
) {
}
