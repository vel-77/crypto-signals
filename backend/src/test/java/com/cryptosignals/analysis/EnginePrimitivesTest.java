package com.cryptosignals.analysis;

import com.cryptosignals.analysis.engine.AtrCalculator;
import com.cryptosignals.analysis.engine.CandleWindow;
import com.cryptosignals.domain.Candle;
import com.cryptosignals.domain.LevelType;
import com.cryptosignals.domain.MarketKey;
import com.cryptosignals.domain.Timeframe;
import com.cryptosignals.fixtures.Candles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class EnginePrimitivesTest {

    @Nested
    @DisplayName("bounded candle window")
    class Window {

        @Test
        @DisplayName("addresses candles by absolute index and evicts the oldest")
        void evictsOldestBeyondCapacity() {
            CandleWindow window = new CandleWindow(3);
            List<Candle> series = Candles.fromPath(Timeframe.M5, new double[]{10, 11, 12, 13, 14});

            for (Candle candle : series) {
                window.append(candle);
            }

            assertThat(window.lastIndex()).isEqualTo(4);
            assertThat(window.firstRetainedIndex()).isEqualTo(2);
            assertThat(window.size()).isEqualTo(3);
            assertThat(window.at(4).close()).isEqualTo(14.0);
            assertThat(window.has(1)).isFalse();
            assertThatThrownBy(() -> window.at(1)).isInstanceOf(IndexOutOfBoundsException.class);
        }

        @Test
        @DisplayName("memory stays flat no matter how long the series is")
        void capacityIsFixed() {
            CandleWindow window = new CandleWindow(8);
            for (int i = 0; i < 10_000; i++) {
                window.append(Candles.candle(Timeframe.M5,
                        Candles.T0 + (long) i * Timeframe.M5.durationMillis(), 10, 10, 10, 10));
            }

            assertThat(window.size()).isEqualTo(8);
            assertThat(window.capacity()).isEqualTo(8);
            assertThat(window.lastIndex()).isEqualTo(9_999);
        }
    }

    @Nested
    @DisplayName("Wilder ATR")
    class Atr {

        @Test
        @DisplayName("is NaN until the period is filled, then seeds with the simple average")
        void seedsThenSmooths() {
            AtrCalculator atr = new AtrCalculator(2);
            assertThat(atr.isWarm()).isFalse();

            // TR = 2 (no previous close)
            atr.update(Candles.candle(Timeframe.M5, Candles.T0, 11, 12, 10, 11));
            assertThat(atr.isWarm()).isFalse();

            // TR = max(14-10, |14-11|, |10-11|) = 4  ->  seed = (2+4)/2 = 3
            atr.update(Candles.candle(Timeframe.M5, Candles.T0 + 1000, 11, 14, 10, 13));
            assertThat(atr.value()).isCloseTo(3.0, within(1e-12));

            // TR = 6  ->  (3*(2-1) + 6) / 2 = 4.5
            atr.update(Candles.candle(Timeframe.M5, Candles.T0 + 2000, 13, 19, 13, 18));
            assertThat(atr.value()).isCloseTo(4.5, within(1e-12));
        }

        @Test
        @DisplayName("rejects a nonsensical period")
        void rejectsBadPeriod() {
            assertThatThrownBy(() -> new AtrCalculator(0)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("deterministic identifiers")
    class Identifiers {

        @Test
        @DisplayName("the same level content always yields the same id")
        void levelIdsAreContentAddressed() {
            MarketKey market = new MarketKey("BTCUSDT", Timeframe.M5);

            String first = Ids.levelId(market, LevelType.RESISTANCE, Candles.T0, 100_000);
            String second = Ids.levelId(market, LevelType.RESISTANCE, Candles.T0, 100_000);

            assertThat(first).isEqualTo(second);
        }

        @Test
        @DisplayName("different markets, roles, times or prices yield different ids")
        void levelIdsDiscriminate() {
            MarketKey m5 = new MarketKey("BTCUSDT", Timeframe.M5);
            MarketKey m1 = new MarketKey("BTCUSDT", Timeframe.M1);
            String base = Ids.levelId(m5, LevelType.RESISTANCE, Candles.T0, 100_000);

            assertThat(base)
                    .isNotEqualTo(Ids.levelId(m1, LevelType.RESISTANCE, Candles.T0, 100_000))
                    .isNotEqualTo(Ids.levelId(m5, LevelType.SUPPORT, Candles.T0, 100_000))
                    .isNotEqualTo(Ids.levelId(m5, LevelType.RESISTANCE, Candles.T0 + 1, 100_000))
                    .isNotEqualTo(Ids.levelId(m5, LevelType.RESISTANCE, Candles.T0, 100_001));
        }

        @Test
        @DisplayName("signal ids distinguish touch ordinals on the same level")
        void signalIdsIncludeTouchCount() {
            assertThat(Ids.signalId("level-1", 3)).isNotEqualTo(Ids.signalId("level-1", 4));
        }

        @Test
        @DisplayName("prices at any scale render canonically")
        void canonicalPriceHandlesExtremes() {
            assertThat(Ids.canonicalPrice(0.000000123)).isNotEqualTo(Ids.canonicalPrice(0.000000124));
            assertThat(Ids.canonicalPrice(1_234_567.89)).isNotEqualTo(Ids.canonicalPrice(1_234_567.88));
        }
    }
}
