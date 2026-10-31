package com.gambitrate.repository;

public record SessionIndexStats(
        int gameIndex,
        long games,
        long wins,
        long draws,
        long losses,
        double winRate
) {
}
