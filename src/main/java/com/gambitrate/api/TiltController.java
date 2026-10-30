package com.gambitrate.api;

import com.gambitrate.api.dto.ApiError;
import com.gambitrate.api.dto.TiltResponse;
import com.gambitrate.service.analytics.AnalyticsFilter;
import com.gambitrate.service.tilt.TiltService;
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
public class TiltController {

    private final TiltService tiltService;

    public TiltController(TiltService tiltService) {
        this.tiltService = tiltService;
    }

    @GetMapping("/{username}/tilt")
    @Operation(
            summary = "Measure post-loss tilt, session position, and time-of-day performance",
            description = """
                    Compares the game immediately after a loss with the game immediately after a win
                    using LAG inside sessions. A session is a run of consecutive games with a gap
                    under the configured threshold (30 minutes by default).

                    Returns a tilt score and a sample-guarded plain-English verdict, winrate by
                    session game index, and winrates by local hour and weekday. Hour/day grouping
                    uses PostgreSQL timezone conversion. A tilt verdict requires minGames on both
                    sides and a two-proportion 95% z-test. Date filters are inclusive and
                    interpreted in UTC.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Analysis completed; groups may be empty."),
            @ApiResponse(responseCode = "400", description = "Invalid username, filter, or timezone.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Player has not been synced yet.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public TiltResponse tilt(
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

            @Parameter(description = "Hide grouped results with fewer games. Default 5.")
            @RequestParam(defaultValue = "5")
            @Min(value = 1, message = "minGames must be at least 1")
            @Max(value = 10_000, message = "minGames must be at most 10000")
            int minGames,

            @Parameter(description = "IANA timezone for hour/day grouping, e.g. America/New_York. Default UTC.")
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
        return TiltResponse.from(tiltService.analyze(username, filter, tz));
    }
}
