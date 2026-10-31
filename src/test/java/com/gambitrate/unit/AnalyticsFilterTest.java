package com.gambitrate.unit;

import com.gambitrate.service.InvalidFilterException;
import com.gambitrate.service.analytics.AnalyticsFilter;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalyticsFilterTest {

    @Test
    void convertsInclusiveToDateToHalfOpenUtcRange() {
        AnalyticsFilter filter = new AnalyticsFilter(
                LocalDate.of(2024, 1, 1),
                LocalDate.of(2024, 1, 31),
                "RAPID",
                5
        );

        filter.validate();

        assertThat(filter.timeClass()).isEqualTo("rapid");
        assertThat(filter.fromInclusiveUtc()).hasToString("2024-01-01T00:00Z");
        assertThat(filter.toExclusiveUtc()).hasToString("2024-02-01T00:00Z");
    }

    @Test
    void rejectsReversedDateRange() {
        AnalyticsFilter filter = new AnalyticsFilter(
                LocalDate.of(2024, 2, 1),
                LocalDate.of(2024, 1, 31),
                null,
                5
        );

        assertThatThrownBy(filter::validate)
                .isInstanceOf(InvalidFilterException.class)
                .hasMessageContaining("from")
                .hasMessageContaining("to");
    }

    @Test
    void rejectsUnknownTimeClassAndTooSmallMinimum() {
        AnalyticsFilter badTimeClass = new AnalyticsFilter(null, null, "classical", 5);
        AnalyticsFilter badMinimum = new AnalyticsFilter(null, null, null, 0);

        assertThatThrownBy(badTimeClass::validate)
                .isInstanceOf(InvalidFilterException.class)
                .hasMessageContaining("timeClass");
        assertThatThrownBy(badMinimum::validate)
                .isInstanceOf(InvalidFilterException.class)
                .hasMessageContaining("minGames");
    }
}
