package com.gambitrate.repository;

public record TimeControlOverallStats(
        long games,
        long wins,
        long draws,
        long losses
) {
}
