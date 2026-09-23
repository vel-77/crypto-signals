package com.cryptosignals.domain;

import com.cryptosignals.fixtures.Candles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainTest {

    @Nested
    @DisplayName("candle invariants")
    class Candles_ {

        @Test
        @DisplayName("rejects impossible OHLC combinations")
        void rejectsInvalidOhlc() {
            long t = Candles.T0;
            assertThatThrownBy(() -> new Candle(t, t + 1000, 10, 9, 8, 9, 1))
                    .hasMessageContaining("high");
            assertThatThrownBy(() -> new Candle(t, t + 1000, 10, 12, 11, 11, 1))
                    .hasMessageContaining("low");
            assertThatThrownBy(() -> new Candle(t, t + 1000, 10, 8, 12, 9, 1))
                    .hasMessageContaining("high");
        }

        @Test
        @DisplayName("rejects non-finite, non-positive prices and bad timestamps")
        void rejectsGarbage() {
            long t = Candles.T0;
            assertThatThrownBy(() -> new Candle(t, t + 1000, Double.NaN, 12, 8, 9, 1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Candle(t, t + 1000, 0, 12, 8, 9, 1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Candle(t, t, 10, 12, 8, 9, 1))
                    .hasMessageContaining("closeTime");
            assertThatThrownBy(() -> new Candle(t, t + 1000, 10, 12, 8, 9, -1))
                    .hasMessageContaining("volume");
        }

        @Test
        @DisplayName("accepts a valid candle at an extremely small price")
        void acceptsMicroPrices() {
            Candle candle = new Candle(Candles.T0, Candles.T0 + 1000,
                    0.000000012, 0.000000015, 0.000000011, 0.000000014, 5);

            assertThat(candle.range()).isGreaterThan(0);
            assertThat(candle.bullish()).isTrue();
            assertThat(candle.bodyHigh()).isEqualTo(0.000000014);
        }
    }

    @Nested
    @DisplayName("timeframes")
    class Timeframes {

        @ParameterizedTest
        @CsvSource({"1m,60000", "5m,300000", "15m,900000", "1h,3600000", "4h,14400000", "1d,86400000"})
        @DisplayName("the required timeframes exist with the right durations")
        void durationsAreCorrect(String code, long millis) {
            assertThat(Timeframe.fromCode(code).durationMillis()).isEqualTo(millis);
        }

        @Test
        @DisplayName("parses exchange codes and enum names, case-insensitively")
        void parsingIsForgiving() {
            assertThat(Timeframe.fromCode("5m")).isEqualTo(Timeframe.M5);
            assertThat(Timeframe.fromCode("5M")).isEqualTo(Timeframe.M5);
            assertThat(Timeframe.fromCode(" M5 ")).isEqualTo(Timeframe.M5);
            assertThatThrownBy(() -> Timeframe.fromCode("7q"))
                    .hasMessageContaining("Unsupported timeframe");
        }

        @Test
        @DisplayName("aligns a timestamp down to its candle open")
        void alignsDown() {
            long t = Candles.T0 + Duration.ofMinutes(7).toMillis() + 1234;

            assertThat(Timeframe.M5.alignDown(t)).isEqualTo(Candles.T0 + Duration.ofMinutes(5).toMillis());
            assertThat(Timeframe.H1.alignDown(t)).isEqualTo(Candles.T0);
        }

        @Test
        @DisplayName("counts how many candles fit in a lookback period")
        void countsCandlesInPeriod() {
            assertThat(Timeframe.M5.candlesIn(Duration.ofDays(1))).isEqualTo(288);
            assertThat(Timeframe.H4.candlesIn(Duration.ofDays(365))).isEqualTo(2190);
        }
    }

    @Nested
    @DisplayName("market key")
    class MarketKeys {

        @Test
        @DisplayName("normalises the symbol and rejects blanks")
        void normalises() {
            assertThat(new MarketKey(" btcusdt ", Timeframe.M5).symbol()).isEqualTo("BTCUSDT");
            assertThat(MarketKey.of("ethusdt", "1h").toString()).isEqualTo("ETHUSDT:1h");
            assertThatThrownBy(() -> new MarketKey("  ", Timeframe.M5))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("the same symbol on two timeframes is two different markets")
        void timeframeIsPartOfIdentity() {
            assertThat(MarketKey.of("BTCUSDT", "1m")).isNotEqualTo(MarketKey.of("BTCUSDT", "5m"));
        }
    }
}
