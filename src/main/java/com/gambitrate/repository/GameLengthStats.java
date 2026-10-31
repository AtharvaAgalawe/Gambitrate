package com.gambitrate.repository;

public record GameLengthStats(
        String bucket,
        long games,
        long wins,
        long draws,
        long losses,
        double winRate
) {
}
