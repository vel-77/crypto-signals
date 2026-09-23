package com.cryptosignals.analysis;

import com.cryptosignals.analysis.strategy.ConfigurableToleranceCalculator;
import com.cryptosignals.domain.ToleranceMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class ToleranceAndConfigTest {

    @Nested
    @DisplayName("zone tolerance")
    class Tolerance {

        private final ConfigurableToleranceCalculator calculator = new ConfigurableToleranceCalculator();

        @Test
        @DisplayName("the worked example from the specification: 100 000 at 0.1% is 99 900 .. 100 100")
        void matchesTheSpecificationExample() {
            AnalysisConfig config = AnalysisConfig.builder().touchTolerancePercent(0.1).build();
            double half = calculator.halfWidth(100_000, Double.NaN, config);

            assertThat(100_000 - half).isEqualTo(99_900.0);
            assertThat(100_000 + half).isEqualTo(100_100.0);
        }

        @ParameterizedTest(name = "price {0}")
        @ValueSource(doubles = {0.0001, 1.5234, 10, 100, 100_000, 1_000_000})
        @DisplayName("the zone is the same relative width at every price scale")
        void isScaleFree(double price) {
            AnalysisConfig config = AnalysisConfig.builder().touchTolerancePercent(0.1).build();
            double half = calculator.halfWidth(price, Double.NaN, config);

            assertThat(half / price * 100.0).isCloseTo(0.1, within(1e-12));
        }

        @Test
        @DisplayName("ATR mode scales with volatility")
        void atrMode() {
            AnalysisConfig config = AnalysisConfig.builder()
                    .toleranceMode(ToleranceMode.ATR).atrToleranceMultiplier(0.5).build();

            assertThat(calculator.halfWidth(100, 4.0, config)).isEqualTo(2.0);
        }

        @Test
        @DisplayName("ATR mode falls back to the percentage before warm-up")
        void atrFallsBackWhenCold() {
            AnalysisConfig config = AnalysisConfig.builder()
                    .toleranceMode(ToleranceMode.ATR).touchTolerancePercent(0.1).build();

            assertThat(calculator.halfWidth(100, Double.NaN, config)).isEqualTo(0.1);
        }

        @Test
        @DisplayName("MAX_OF_BOTH uses the percentage as a floor when volatility collapses")
        void maxOfBoth() {
            AnalysisConfig config = AnalysisConfig.builder()
                    .toleranceMode(ToleranceMode.MAX_OF_BOTH)
                    .touchTolerancePercent(0.1)
                    .atrToleranceMultiplier(0.5)
                    .build();

            assertThat(calculator.halfWidth(100, 0.01, config)).isEqualTo(0.1);   // percent wins
            assertThat(calculator.halfWidth(100, 4.0, config)).isEqualTo(2.0);    // ATR wins
        }
    }

    @Nested
    @DisplayName("configuration validation")
    class Validation {

        @Test
        @DisplayName("a breakout tolerance inside the touch zone is rejected")
        void rejectsContradictoryTolerances() {
            assertThatThrownBy(() -> AnalysisConfig.builder()
                    .touchTolerancePercent(0.5).breakoutTolerancePercent(0.2).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("every touch would instantly count as a breakout");
        }

        @Test
        @DisplayName("disabling both breakout conditions is rejected")
        void rejectsUnbreakableLevels() {
            assertThatThrownBy(() -> AnalysisConfig.builder()
                    .closeBeyondLevel(false).wickBreakAllowed(false).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("can never break");
        }

        @Test
        @DisplayName("a single touch cannot define a level")
        void rejectsMinTouchesBelowTwo() {
            assertThatThrownBy(() -> AnalysisConfig.builder().minTouches(1).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("nonsensical numbers are rejected")
        void rejectsInvalidNumbers() {
            assertThatThrownBy(() -> AnalysisConfig.builder().swingLeftBars(0).build())
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> AnalysisConfig.builder().touchTolerancePercent(0).build())
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> AnalysisConfig.builder().minDeparturePercent(-1).build())
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> AnalysisConfig.builder()
                    .minTimeBetweenTouches(Duration.ofMinutes(-5)).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("the documented defaults are valid and match the specification")
        void defaultsMatchTheSpecification() {
            AnalysisConfig defaults = AnalysisConfig.defaults();

            assertThat(defaults.minTouches()).isEqualTo(3);
            assertThat(defaults.touchTolerancePercent()).isEqualTo(0.1);
            assertThat(defaults.breakoutTolerancePercent()).isEqualTo(0.2);
            assertThat(defaults.minCandlesBetweenTouches()).isEqualTo(10);
            assertThat(defaults.confirmationCandles()).isEqualTo(1);
        }

        @Test
        @DisplayName("toBuilder round-trips every field")
        void toBuilderRoundTrips() {
            AnalysisConfig original = AnalysisConfig.builder()
                    .minTouches(4).swingBars(5).touchTolerancePercent(0.25)
                    .breakoutTolerancePercent(0.7).minDeparturePercent(1.1)
                    .minTimeBetweenTouches(Duration.ofHours(2)).enableRoleFlip(true).build();

            assertThat(original.toBuilder().build()).isEqualTo(original);
        }

        @Test
        @DisplayName("the fingerprint changes when a rule changes")
        void fingerprintTracksConfiguration() {
            AnalysisConfig a = AnalysisConfig.defaults();
            AnalysisConfig b = a.toBuilder().minTouches(4).build();

            assertThat(a.fingerprint()).isEqualTo(AnalysisConfig.defaults().fingerprint());
            assertThat(a.fingerprint()).isNotEqualTo(b.fingerprint());
        }
    }
}
