package com.gambitrate.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "gambitrate.analytics")
public record AnalyticsProperties(
        @NotNull Duration tiltSessionGap
) {

    public AnalyticsProperties {
        if (tiltSessionGap != null && (tiltSessionGap.isZero() || tiltSessionGap.isNegative())) {
            throw new IllegalArgumentException("gambitrate.analytics.tilt-session-gap must be positive");
        }
    }
}
