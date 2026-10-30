package com.gambitrate.ingest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeControlParserTest {

    @Test
    void parsesBaseAndIncrement() {
        TimeControlParser.TimeControl control = TimeControlParser.parse("600+5");

        assertThat(control.baseSeconds()).isEqualTo(600);
        assertThat(control.incrementSeconds()).isEqualTo(5);
    }

    @Test
    void parsesBaseWithoutIncrement() {
        TimeControlParser.TimeControl control = TimeControlParser.parse("180");

        assertThat(control.baseSeconds()).isEqualTo(180);
        assertThat(control.incrementSeconds()).isZero();
    }

    @Test
    void parsesDailyTimeControl() {
        TimeControlParser.TimeControl control = TimeControlParser.parse("1/86400");

        assertThat(control.baseSeconds()).isEqualTo(1);
        assertThat(control.incrementSeconds()).isZero();
    }

    @Test
    void rejectsUnsupportedTimeControl() {
        assertThatThrownBy(() -> TimeControlParser.parse("not-a-control"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported time_control");
    }
}
