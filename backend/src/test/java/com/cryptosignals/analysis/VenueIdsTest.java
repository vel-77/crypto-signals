package com.cryptosignals.analysis;

import com.cryptosignals.analysis.model.AnalysisResult;
import com.cryptosignals.analysis.model.DetectedLevel;
import com.cryptosignals.analysis.model.TradingSignal;
import com.cryptosignals.domain.Candle;
import com.cryptosignals.domain.LevelType;
import com.cryptosignals.domain.MarketKey;
import com.cryptosignals.domain.Timeframe;
import com.cryptosignals.fixtures.Candles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Spot and futures levels on the same coin never share an id, and spot ids stay as they were. */
class VenueIdsTest {

    private static final MarketKey MARKET = new MarketKey("BTCUSDT", Timeframe.M15);

    private static List<Candle> threeTouchResistance() {
        return Candles.fromPath(Timeframe.M15, Candles.zigzag(99_000, 15,
                100_000, 99_000, 100_000, 99_000, 100_000, 99_500));
    }

    @Test
    @DisplayName("an empty namespace gives the ids levels always had")
    void emptyNamespaceKeepsIds() {
        assertThat(Ids.levelId("", MARKET, LevelType.SUPPORT, 1_000L, 123.45))
                .isEqualTo(Ids.levelId(MARKET, LevelType.SUPPORT, 1_000L, 123.45))
                .isEqualTo(Ids.levelId(null, MARKET, LevelType.SUPPORT, 1_000L, 123.45));
        assertThat(Ids.levelId("BINANCE_FUTURES", MARKET, LevelType.SUPPORT, 1_000L, 123.45))
                .isNotEqualTo(Ids.levelId(MARKET, LevelType.SUPPORT, 1_000L, 123.45));
    }

    @Test
    @DisplayName("the same candles on two venues give two sets of levels and signals")
    void venuesAreKeptApart() {
        AnalysisConfig config = AnalysisConfig.defaults();
        List<Candle> candles = threeTouchResistance();

        AnalysisResult spot = new LevelAnalysisEngineFactory().analyze(MARKET, config, candles);
        AnalysisResult spotAgain = new LevelAnalysisEngineFactory().withIdNamespace("")
                .analyze(MARKET, config, candles);
        AnalysisResult futures = new LevelAnalysisEngineFactory().withIdNamespace("BINANCE_FUTURES")
                .analyze(MARKET, config, candles);

        assertThat(spot.signals()).isNotEmpty();
        assertThat(spotAgain.levels()).extracting(DetectedLevel::id)
                .containsExactlyElementsOf(spot.levels().stream().map(DetectedLevel::id).toList());
        assertThat(futures.levels()).hasSameSizeAs(spot.levels());
        assertThat(futures.levels()).extracting(DetectedLevel::id)
                .doesNotContainAnyElementsOf(spot.levels().stream().map(DetectedLevel::id).toList());
        assertThat(futures.signals()).extracting(TradingSignal::id)
                .doesNotContainAnyElementsOf(spot.signals().stream().map(TradingSignal::id).toList());
    }
}
