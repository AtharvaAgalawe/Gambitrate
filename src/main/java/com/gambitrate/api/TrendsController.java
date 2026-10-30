package com.gambitrate.api;

import com.gambitrate.api.dto.ApiError;
import com.gambitrate.api.dto.TrendsResponse;
import com.gambitrate.service.analytics.AnalyticsFilter;
import com.gambitrate.service.trends.TrendsService;
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
public class TrendsController {

    private final TrendsService trendsService;

    public TrendsController(TrendsService trendsService) {
        this.trendsService = trendsService;
    }

    @GetMapping("/{username}/trends")
    @Operation(
            summary = "Show monthly rating, winrate, and accuracy trends",
            description = """
                    Groups games into UTC calendar months and returns winrate, game count, average
                    pre-game rating, and average accuracy.

                    Accuracy is nullable on chess.com. The monthly average is computed only from
                    games that have an accuracy value, and every accuracy number is returned with
                    the contributing game count and coverage. Months below minGames are hidden.
                    Date filters are inclusive and interpreted in UTC.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Trend completed; months may be empty."),
            @ApiResponse(responseCode = "400", description = "Invalid username or filter.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Player has not been synced yet.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public TrendsResponse trends(
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

            @Parameter(description = "Hide months with fewer games. Default 5.")
            @RequestParam(defaultValue = "5")
            @Min(value = 1, message = "minGames must be at least 1")
            @Max(value = 10_000, message = "minGames must be at most 10000")
            int minGames
    ) {
        AnalyticsFilter filter = new AnalyticsFilter(
                from,
                to,
                timeClass == null ? null : timeClass.toLowerCase(Locale.ROOT),
                minGames
        );
        return TrendsResponse.from(trendsService.analyze(username, filter));
    }
}
