package com.gambitrate.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "gambitrate.chesscom")
public record ChessComProperties(
        @NotBlank String baseUrl,
        @NotBlank String userAgent,
        @NotNull Duration requestDelay,
        @Min(1) int maxAttempts,
        @NotNull Duration pastMonthTtl,
        @NotNull Duration currentMonthTtl,
        @NotNull Duration archivesTtl,
        @NotNull Duration etagTtl,
        @NotNull Duration hostLockLease,
        @NotNull Duration hostLockWait
) {
}
