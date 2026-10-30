package com.gambitrate.api.dto;

import com.gambitrate.service.SyncJobSnapshot;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(name = "SyncJob", description = "Persisted asynchronous sync state.")
public record SyncJobResponse(
        @Schema(example = "42") Long id,
        @Schema(example = "hikaru") String username,
        @Schema(allowableValues = {"QUEUED", "RUNNING", "DONE", "FAILED"}, example = "RUNNING")
        String status,
        @Schema(example = "18") int monthsTotal,
        @Schema(example = "7") int monthsDone,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        @Schema(nullable = true) String error
) {

    public static SyncJobResponse from(SyncJobSnapshot snapshot) {
        return new SyncJobResponse(
                snapshot.id(),
                snapshot.username(),
                snapshot.status(),
                snapshot.monthsTotal(),
                snapshot.monthsDone(),
                snapshot.createdAt(),
                snapshot.startedAt(),
                snapshot.finishedAt(),
                snapshot.error()
        );
    }
}
