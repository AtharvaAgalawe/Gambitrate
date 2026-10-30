package com.gambitrate.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(name = "ApiError", description = "Consistent error body used by every endpoint.")
public record ApiError(
        @Schema(example = "404") int status,
        @Schema(example = "Not Found") String error,
        @Schema(example = "Chess.com player 'nobody' was not found.") String message,
        @Schema(example = "2026-10-30T12:00:00Z") Instant timestamp,
        @Schema(example = "/api/players/nobody/sync") String path
) {
}
