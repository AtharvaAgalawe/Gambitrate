package com.gambitrate.api;

import com.gambitrate.api.dto.ApiError;
import com.gambitrate.api.dto.TimeControlsResponse;
import com.gambitrate.service.analytics.AnalyticsFilter;
import com.gambitrate.service.timecontrols.TimeControlsService;
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
public class TimeControlsController {

    private final TimeControlsService timeControlsService;

    public TimeControlsController(TimeControlsService timeControlsService) {
        this.timeControlsService = timeControlsService;
    }

    @GetMapping("/{username}/time-controls")
    @Operation(
            summary = "Find time-control and clock patterns costing rating",
            description = """
                    Returns win rates by time class and full-move game-length bucket, loss shares by
                    termination, average clock remaining after the player's 10th, 20th, and 30th move,
                    and the share of losses with more than 30 seconds or less than 10 seconds left.

                    Game-length buckets are 1-19, 20-39, 40-59, 60-79, 80-99, and 100+ full moves.
                    Clock-at-move-N is the player's clock after their Nth move, never a claim about
                    move quality. Clock and termination metrics always include sample counts and
                    coverage. Date filters are inclusive and interpreted in UTC.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Analysis completed; groups may be empty."),
            @ApiResponse(responseCode = "400", description = "Invalid username or filter.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Player has not been synced yet.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public TimeControlsResponse timeControls(
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

            @Parameter(description = "Hide groups with fewer games. Default 5.")
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
        return TimeControlsResponse.from(timeControlsService.analyze(username, filter));
    }
}
