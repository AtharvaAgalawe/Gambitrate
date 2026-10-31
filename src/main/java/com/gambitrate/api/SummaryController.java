package com.gambitrate.api;

import com.gambitrate.api.dto.ApiError;
import com.gambitrate.api.dto.SummaryResponse;
import com.gambitrate.service.analytics.AnalyticsFilter;
import com.gambitrate.service.summary.SummaryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Locale;

@RestController
@RequestMapping("/api/players")
@Validated
@Tag(name = "Analytics")
public class SummaryController {

    private final SummaryService summaryService;

    public SummaryController(SummaryService summaryService) {
        this.summaryService = summaryService;
    }

    @GetMapping("/{username}/summary")
    @Operation(
            summary = "Get the top three leaks ranked by estimated rating impact",
            description = """
                    Combines the guarded results from openings, time controls, and tilt, then ranks
                    the top three leaks by an estimated rating impact:
                    (baselineWinRate - groupWinRate) x games x 10.

                    Opening candidates use the same-color baseline. Time-class, game-length, hour,
                    and day candidates use the overall filtered winrate. Post-loss candidates use
                    the post-win winrate. Every item exposes its filters, sample size, baseline,
                    supporting numbers, and the 95% guard that admitted it.

                    The impact is an explicit heuristic, not a chess.com rating calculation.
                    Trends is descriptive and does not create a leak candidate. Date filters are
                    inclusive and interpreted in UTC; hour/day candidates use tz.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Summary completed; leaks may be empty."),
            @ApiResponse(responseCode = "400", description = "Invalid username, filter, or timezone.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Player has not been synced yet.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public SummaryResponse summary(
            @PathVariable
            @Pattern(
                    regexp = "^[A-Za-z0-9_-]{1,50}$",
                    message = "username must be 1-50 letters, digits, underscores, or hyphens"
            )
            String username,

            @Parameter(description = "Inclusive UTC start date, ISO-8601 (YYYY-MM-DD).")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,

            @Parameter(description = "Inclusive UTC end date, ISO-8601 (YYYY-MM-DD).")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,

            @Parameter(description = "bullet, blitz, rapid, or daily.")
            @RequestParam(required = false)
            @Pattern(regexp = "(?i)bullet|blitz|rapid|daily",
                    message = "timeClass must be bullet, blitz, rapid, or daily")
            String timeClass,

            @Parameter(description = "Minimum sample for a candidate. Default 5.")
            @RequestParam(defaultValue = "5")
            @Min(value = 1, message = "minGames must be at least 1")
            @Max(value = 10_000, message = "minGames must be at most 10000")
            int minGames,

            @Parameter(description = "Timezone ID for hour/day candidates, e.g. America/New_York. Default UTC.")
            @RequestParam(defaultValue = "UTC")
            @Size(max = 64, message = "tz must be at most 64 characters")
            String tz
    ) {
        AnalyticsFilter filter = new AnalyticsFilter(
                from,
                to,
                timeClass == null ? null : timeClass.toLowerCase(Locale.ROOT),
                minGames
        );
        return SummaryResponse.from(summaryService.analyze(username, filter, tz));
    }
}
