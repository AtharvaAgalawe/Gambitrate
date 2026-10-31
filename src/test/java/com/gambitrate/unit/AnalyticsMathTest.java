package com.gambitrate.unit;

import com.gambitrate.service.analytics.AnalyticsMath;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class AnalyticsMathTest {

    @Test
    void estimatesImpactFromWinRateDeficitTimesGames() {
        assertThat(AnalyticsMath.estimatedImpact(0.60, 0.20, 10))
                .isCloseTo(40.0, within(0.0001));
        assertThat(AnalyticsMath.estimatedImpact(0.40, 0.50, 20))
                .isCloseTo(-20.0, within(0.0001));
    }

    @Test
    void smallSampleDoesNotPassTheGuard() {
        assertThat(AnalyticsMath.significantlyBelow(0.20, 0.60, 5)).isFalse();
        assertThat(AnalyticsMath.significantlyBelow(0.20, 0.60, 10)).isTrue();
    }

    @Test
    void twoProportionGuardDetectsALargePostLossDrop() {
        assertThat(AnalyticsMath.twoProportionSignificantlyBelow(1, 18, 7, 9)).isTrue();
        assertThat(AnalyticsMath.twoProportionSignificantlyBelow(1, 5, 4, 5)).isFalse();
    }
}
