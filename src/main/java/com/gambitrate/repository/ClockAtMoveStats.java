package com.gambitrate.repository;

public record ClockAtMoveStats(
        String timeClass,
        int moveNumber,
        long gamesInTimeClass,
        long gamesWithClock,
        Double averageClockRemainingMs
) {
}
