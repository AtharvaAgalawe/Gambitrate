package com.gambitrate.repository;

public record LossClockStats(
        String timeClass,
        long totalLosses,
        long lossesWithClock,
        long over30s,
        long between10And30s,
        long under10s
) {
}
