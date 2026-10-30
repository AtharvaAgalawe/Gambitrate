package com.gambitrate.api.dto;

import com.gambitrate.service.analytics.AnalyticsFilter;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

@Schema(name = "AnalyticsFilters", description = "Filters echoed from an analytics request.")
public record AnalyticsFiltersResponse(
        @Schema(example = "2024-01-01") LocalDate from,
        @Schema(example = "2024-12-31") LocalDate to,
        @Schema(example = "rapid") String timeClass,
        @Schema(example = "5") int minGames,
        @Schema(example = "UTC", nullable = true)
        String timezone
) {

    public static AnalyticsFiltersResponse from(AnalyticsFilter filter) {
        return from(filter, null);
    }

    public static AnalyticsFiltersResponse from(AnalyticsFilter filter, String timezone) {
        return new AnalyticsFiltersResponse(
                filter.from(),
                filter.to(),
                filter.timeClass(),
                filter.minGames(),
                timezone
        );
    }
}
