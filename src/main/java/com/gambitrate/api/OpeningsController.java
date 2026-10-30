package com.gambitrate.api;

import com.gambitrate.api.dto.ApiError;
import com.gambitrate.api.dto.OpeningsResponse;
import com.gambitrate.service.openings.OpeningsQuery;
import com.gambitrate.service.openings.OpeningsService;
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
public class OpeningsController {

    private final OpeningsService openingsService;

    public OpeningsController(OpeningsService openingsService) {
        this.openingsService = openingsService;
    }

    @GetMapping("/{username}/openings")
    @Operation(
            summary = "Find the openings costing the player rating",
            description = """
                    Groups games by color + ECO (or by opening line), calculates winrate, draw rate,
                    average rating delta, and a heuristic rating cost, then assigns a sample-size-guarded
                    verdict: strength, fine, or leak.

                    Date filters are inclusive and interpreted in UTC. The default minimum is 5 games.
                    A verdict also requires a 95% z-test against the same color's filtered baseline.
                    Rating cost is (baselineWinRate - groupWinRate) x games x 10 and is used for ordering.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Analysis completed; groups may be empty."),
            @ApiResponse(responseCode = "400", description = "Invalid username, filter, or date range.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Player has not been synced yet.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public OpeningsResponse openings(
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
            int minGames,

            @Parameter(description = "eco (default) or line.")
            @RequestParam(defaultValue = "eco")
            @Pattern(regexp = "(?i)eco|line", message = "groupBy must be eco or line")
            String groupBy,

            @Parameter(description = "Full moves to include when groupBy=line. Default 6.")
            @RequestParam(defaultValue = "6")
            @Min(value = 1, message = "firstMoves must be at least 1")
            @Max(value = 20, message = "firstMoves must be at most 20")
            int firstMoves
    ) {
        OpeningsQuery query = new OpeningsQuery(
                from,
                to,
                timeClass == null ? null : timeClass.toLowerCase(Locale.ROOT),
                minGames,
                "line".equalsIgnoreCase(groupBy),
                firstMoves
        );
        return OpeningsResponse.from(openingsService.analyze(username, query));
    }
}
