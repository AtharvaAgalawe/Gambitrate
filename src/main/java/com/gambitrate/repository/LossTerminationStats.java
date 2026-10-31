package com.gambitrate.repository;

public record LossTerminationStats(
        String timeClass,
        long totalGames,
        long totalLosses,
        long timeoutLosses,
        long checkmateLosses,
        long resignLosses,
        long otherLosses
) {
}
