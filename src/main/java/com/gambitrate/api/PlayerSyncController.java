package com.gambitrate.api;

import com.gambitrate.api.dto.ApiError;
import com.gambitrate.api.dto.SyncResponse;
import com.gambitrate.ingest.PlayerSyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

@RestController
@RequestMapping("/api/players")
@Validated
@Tag(name = "Ingestion")
public class PlayerSyncController {

    private final PlayerSyncService playerSyncService;

    public PlayerSyncController(PlayerSyncService playerSyncService) {
        this.playerSyncService = playerSyncService;
    }

    @PostMapping("/{username}/sync")
    @Operation(
            summary = "Ingest a chess.com player's games",
            description = "Fetches every monthly archive, keeps only standard chess, parses PGN moves and clocks, "
                    + "and persists games idempotently. Phase 1 runs synchronously; Phase 3 changes this to 202 + status polling."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Sync completed; counts show what was persisted."),
            @ApiResponse(responseCode = "400", description = "Invalid username.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Username does not exist on chess.com.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "502", description = "Chess.com failed or returned unusable data.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "503", description = "Chess.com rate limit exhausted; Retry-After is set.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public SyncResponse sync(
            @PathVariable
            @Pattern(
                    regexp = "^[A-Za-z0-9_-]{1,50}$",
                    message = "username must be 1-50 letters, digits, underscores, or hyphens"
            )
            String username
    ) {
        return SyncResponse.from(playerSyncService.sync(username));
    }
}
