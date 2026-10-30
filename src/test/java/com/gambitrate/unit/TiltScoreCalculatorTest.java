package com.gambitrate.unit;

import com.gambitrate.service.tilt.TiltAnalysis;
import com.gambitrate.service.tilt.TiltScoreCalculator;
import com.gambitrate.service.tilt.TiltSignal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TiltScoreCalculatorTest {

    @Test
    void calculatesPositiveScoreWhenPostLossWinRateDrops() {
        TiltSignal signal = TiltScoreCalculator.calculate(
                comparison("loss", 1, 18),
                comparison("win", 7, 9),
                5
        );

        assertThat(signal.score()).isCloseTo(72.22, within(0.0001));
        assertThat(signal.sampleSufficient()).isTrue();
        assertThat(signal.significant()).isTrue();
        assertThat(signal.verdict()).contains("After a loss").contains("72.2-point");
    }

    @Test
    void withholdsScoreWhenEitherSideHasTooFewGames() {
        TiltSignal signal = TiltScoreCalculator.calculate(
                comparison("loss", 1, 3),
                comparison("win", 2, 2),
                5
        );

        assertThat(signal.score()).isNull();
        assertThat(signal.sampleSufficient()).isFalse();
        assertThat(signal.verdict()).contains("Not enough games");
    }

    @Test
    void reportsPositiveButUnprovenGapAsNotSignificant() {
        TiltSignal signal = TiltScoreCalculator.calculate(
                comparison("loss", 1, 5),
                comparison("win", 4, 5),
                5
        );

        assertThat(signal.score()).isCloseTo(60.0, within(0.0001));
        assertThat(signal.significant()).isFalse();
        assertThat(signal.verdict()).contains("95% guard is not met");
    }

    @Test
    void reportsNoTiltWhenPerformanceAfterLossIsBetter() {
        TiltSignal signal = TiltScoreCalculator.calculate(
                comparison("loss", 4, 5),
                comparison("win", 1, 5),
                5
        );

        assertThat(signal.score()).isCloseTo(-60.0, within(0.0001));
        assertThat(signal.verdict()).contains("No tilt signal");
    }

    private static TiltAnalysis.TiltComparison comparison(
            String previousResult,
            long wins,
            long games
    ) {
        return new TiltAnalysis.TiltComparison(
                previousResult,
                games,
                wins,
                0,
                games - wins,
                (double) wins / games,
                null,
                0
        );
    }
}
