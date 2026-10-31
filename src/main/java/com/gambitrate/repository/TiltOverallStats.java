package com.gambitrate.repository;

public record TiltOverallStats(
        long games,
        long wins,
        long draws,
        long losses
) {
}
