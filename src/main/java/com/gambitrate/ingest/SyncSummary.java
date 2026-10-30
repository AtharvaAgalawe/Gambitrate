package com.gambitrate.ingest;

public record SyncSummary(
        String username,
        int monthsProcessed,
        int gamesSeen,
        int variantsSkipped,
        int gamesImported,
        int gamesSkippedExisting,
        long movesImported
) {
}
