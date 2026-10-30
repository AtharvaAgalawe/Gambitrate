package com.gambitrate.service;

import java.time.Instant;

public record SyncJobSnapshot(
        Long id,
        String username,
        String status,
        int monthsTotal,
        int monthsDone,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        String error
) {
}
