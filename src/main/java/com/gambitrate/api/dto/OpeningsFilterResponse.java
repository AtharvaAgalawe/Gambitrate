package com.gambitrate.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

@Schema(name = "OpeningsFilters", description = "Filters echoed from the /openings request.")
public record OpeningsFilterResponse(
        @Schema(example = "2024-01-01") LocalDate from,
        @Schema(example = "2024-12-31") LocalDate to,
        @Schema(example = "rapid") String timeClass,
        @Schema(example = "5") int minGames,
        @Schema(example = "eco", allowableValues = {"eco", "line"}) String groupBy,
        @Schema(example = "6") int firstMoves
) {
}
