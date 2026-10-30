package com.gambitrate.api.dto;

import com.gambitrate.ingest.SyncSummary;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "SyncResponse", description = "Result of a Phase 1 synchronous chess.com ingestion.")
public record SyncResponse(
        @Schema(example = "magnuscarlsen") String username,
        @Schema(example = "18") int monthsProcessed,
        @Schema(example = "412") int gamesSeen,
        @Schema(example = "3") int variantsSkipped,
        @Schema(example = "409") int gamesImported,
        @Schema(example = "0") int gamesSkippedExisting,
        @Schema(example = "24481") long movesImported
) {

    public static SyncResponse from(SyncSummary summary) {
        return new SyncResponse(
                summary.username(),
                summary.monthsProcessed(),
                summary.gamesSeen(),
                summary.variantsSkipped(),
                summary.gamesImported(),
                summary.gamesSkippedExisting(),
                summary.movesImported()
        );
    }
}
