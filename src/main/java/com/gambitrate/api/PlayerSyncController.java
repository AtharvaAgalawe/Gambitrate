package com.gambitrate.api;

import com.gambitrate.api.dto.ApiError;
import com.gambitrate.api.dto.SyncJobResponse;
import com.gambitrate.service.SyncJobService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

@RestController
@RequestMapping("/api/players")
@Validated
@Tag(name = "Ingestion")
public class PlayerSyncController {

    private static final String USERNAME_PATTERN = "^[A-Za-z0-9_-]{1,50}$";
    private static final String USERNAME_MESSAGE =
            "username must be 1-50 letters, digits, underscores, or hyphens";

    private final SyncJobService syncJobService;

    public PlayerSyncController(SyncJobService syncJobService) {
        this.syncJobService = syncJobService;
    }

    @PostMapping("/{username}/sync")
    @Operation(
            summary = "Start an asynchronous chess.com sync",
            description = "Validates a first-time username against chess.com, queues a persisted sync job, "
                    + "and returns 202. Poll GET .../sync/status for progress."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Sync job queued or already running."),
            @ApiResponse(responseCode = "400", description = "Invalid username.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Username does not exist on chess.com.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "502", description = "Chess.com failed or returned unusable data.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "503", description = "Chess.com rate limit exhausted; Retry-After is set.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<SyncJobResponse> sync(
            @PathVariable
            @Pattern(regexp = USERNAME_PATTERN, message = USERNAME_MESSAGE)
            String username
    ) {
        SyncJobResponse job = SyncJobResponse.from(syncJobService.start(username));
        URI statusUri = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/players/{username}/sync/status")
                .buildAndExpand(job.username())
                .toUri();
        return ResponseEntity.accepted().location(statusUri).body(job);
    }

    @GetMapping("/{username}/sync/status")
    @Operation(
            summary = "Get the latest sync job status",
            description = "Returns QUEUED, RUNNING, DONE, or FAILED with month progress and any error."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Latest sync job."),
            @ApiResponse(responseCode = "400", description = "Invalid username.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Player or sync job has not been created.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public SyncJobResponse status(
            @PathVariable
            @Pattern(regexp = USERNAME_PATTERN, message = USERNAME_MESSAGE)
            String username
    ) {
        return SyncJobResponse.from(syncJobService.status(username));
    }
}
