package com.cryptosignals.notifications;

import com.cryptosignals.analysis.AnalysisConfig;
import com.cryptosignals.analysis.LevelAnalysisEngineFactory;
import com.cryptosignals.analysis.model.AnalysisResult;
import com.cryptosignals.analysis.model.TradingSignal;
import com.cryptosignals.domain.MarketKey;
import com.cryptosignals.domain.PriceFormatter;
import com.cryptosignals.domain.Timeframe;
import com.cryptosignals.fixtures.Candles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FormattingTest {

    @Nested
    @DisplayName("price and time rendering")
    class Prices {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "100000,      '100,000'",
                "100000.5,    '100,000.5'",
                "1.5234,      1.5234",
                "10,          10",
                "0.000123456, 0.000123456",
                "0.00000001,  0.00000001",
                "1234567.89,  '1,234,567.89'"
        })
        @DisplayName("keeps significant digits at every scale instead of a fixed decimal count")
        void formatsAcrossScales(double value, String expected) {
            assertThat(PriceFormatter.price(value)).isEqualTo(expected);
        }

        @Test
        @DisplayName("a tiny price is never rounded away to zero")
        void neverCollapsesSmallPricesToZero() {
            assertThat(PriceFormatter.price(0.0000000123)).isNotEqualTo("0").contains("123");
        }

        @Test
        @DisplayName("timestamps render as UTC to the minute")
        void formatsTimestamps() {
            assertThat(PriceFormatter.timestamp(Instant.parse("2026-09-20T11:45:30Z")))
                    .isEqualTo("2026-09-20 11:45");
        }
    }

    @Nested
    @DisplayName("Telegram alert body")
    class AlertBody {

        private TradingSignal sampleSignal() {
            AnalysisResult result = new LevelAnalysisEngineFactory().analyze(
                    new MarketKey("BTCUSDT", Timeframe.M5), AnalysisConfig.defaults(),
                    Candles.fromPath(Timeframe.M5, Candles.zigzag(99_000, 15,
                            100_000, 99_000, 100_000, 99_000, 100_000, 99_500)));
            return result.signals().get(0);
        }

        @Test
        @DisplayName("matches the layout from the specification")
        void producesTheSpecifiedLayout() {
            String message = new SignalFormatter().format(sampleSignal());

            assertThat(message)
                    .contains("NEW 3-TOUCH SIGNAL")
                    .contains("Symbol: BTCUSDT")
                    .contains("Timeframe: 5m")
                    .contains("Type: RESISTANCE")
                    .contains("Level: 100,000")
                    .contains("Zone: 99,900 - 100,100")
                    .contains("Touches:")
                    .contains("1. ")
                    .contains("2. ")
                    .contains("3. ")
                    .contains("Breakout: NO")
                    .contains("Signal generated:");

            // Ordinals appear once each, in order.
            assertThat(message.indexOf("1. ")).isLessThan(message.indexOf("2. "));
            assertThat(message.indexOf("2. ")).isLessThan(message.indexOf("3. "));
        }

        @Test
        @DisplayName("times can be shown in a configured time zone, labelled with it")
        void usesConfiguredTimeZone() {
            TradingSignal signal = sampleSignal();
            String utc = new SignalFormatter().format(signal);
            String yerevan = new SignalFormatter(java.time.ZoneId.of("Asia/Yerevan")).format(signal);

            assertThat(utc).contains(" UTC");
            assertThat(yerevan).contains("Asia/Yerevan").doesNotContain(" UTC");
            // Yerevan is UTC+4: the first touch is printed four hours later on the clock.
            String utcFirst = PriceFormatter.timestamp(signal.touches().get(0).extremeTime());
            String localFirst = PriceFormatter.timestamp(signal.touches().get(0).extremeTime(),
                    java.time.ZoneId.of("Asia/Yerevan"));
            assertThat(utc).contains(utcFirst);
            assertThat(yerevan).contains(localFirst);
            assertThat(localFirst).isNotEqualTo(utcFirst);
        }

        @Test
        @DisplayName("a historical signal says so, so nobody mistakes it for a live alert")
        void labelsHistoricalSignals() {
            assertThat(new SignalFormatter().format(sampleSignal().asHistorical(true)))
                    .contains("historical");
            assertThat(new SignalFormatter().format(sampleSignal().asHistorical(false)))
                    .doesNotContain("historical");
        }

        @Test
        @DisplayName("stays plain text, so no channel mangles a symbol containing punctuation")
        void staysPlainText() {
            String message = new SignalFormatter().format(sampleSignal());

            assertThat(message).doesNotContain("<b>").doesNotContain("**").doesNotContain("\\_");
        }
    }
}
