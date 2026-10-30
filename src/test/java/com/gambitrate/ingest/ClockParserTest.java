package com.gambitrate.ingest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClockParserTest {

    @Test
    void parsesHoursMinutesSecondsAndTenths() {
        assertThat(ClockParser.parseMillis("{[%clk 0:02:59.9]}")).isEqualTo(179_900L);
    }

    @Test
    void parsesMinutesAndSecondsWithoutHours() {
        assertThat(ClockParser.parseMillis("[%clk 2:59.9]")).isEqualTo(179_900L);
    }

    @Test
    void returnsNullForNonClockComments() {
        assertThat(ClockParser.parseMillis("{[%eval 0.25]}")).isNull();
        assertThat(ClockParser.parseMillis(null)).isNull();
    }
}
