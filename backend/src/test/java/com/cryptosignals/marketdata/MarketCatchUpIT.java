package com.cryptosignals.marketdata;

import com.cryptosignals.domain.Candle;
import com.cryptosignals.domain.LevelType;
import com.cryptosignals.domain.MarketKey;
import com.cryptosignals.domain.Timeframe;
import com.cryptosignals.fixtures.Candles;
import com.cryptosignals.persistence.entity.SignalEntity;
import com.cryptosignals.persistence.jdbc.CandleJdbcRepository;
import com.cryptosignals.persistence.repository.SignalRepository;
import com.cryptosignals.support.PostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduces the production incident: candles reach the database (reconciliation downloaded them)
 * but never reach the engine (the live stream was dead). The analysis must catch up from storage.
 */
class MarketCatchUpIT extends PostgresIntegrationTest {

    private static final Timeframe TF = Timeframe.M5;

    @Autowired MarketAnalysisService analysisService;
    @Autowired CandleJdbcRepository candles;
    @Autowired SignalRepository signals;

    @Test
    @DisplayName("candles stored while the stream was down are analysed on the next catch-up")
    void catchesUpFromStoredCandles() {
        MarketKey key = new MarketKey("CATCHUPUSDT", TF);
        // A textbook three-touch resistance, ending just before now so it lies inside the lookback.
        double[] path = Candles.zigzag(99_000, 15,
                100_000, 99_000, 100_000, 99_000, 100_000, 99_500);
        long start = TF.alignDown(System.currentTimeMillis()) - (long) (path.length + 2) * TF.durationMillis();
        List<Candle> series = Candles.fromPath(TF, start, path);

        MarketEngine engine = analysisService.initialise(key);
        long marketId = engine.market().getId();

        // The stream delivers the first 40 candles, then dies silently.
        candles.upsert(marketId, series.subList(0, 40));
        analysisService.replayHistory(engine);
        assertThat(engine.lastCandleOpenTime()).isEqualTo(series.get(39).openTime());

        // Reconciliation downloads the rest into the database, but the engine has not seen them.
        candles.upsert(marketId, series.subList(40, series.size()));
        assertThat(signalsFor(key)).isEmpty();

        int analysed = analysisService.catchUp(engine);

        assertThat(analysed).isEqualTo(series.size() - 40);
        assertThat(engine.lastCandleOpenTime()).isEqualTo(series.get(series.size() - 1).openTime());
        assertThat(signalsFor(key)).singleElement().satisfies(signal -> {
            assertThat(signal.getType()).isEqualTo(LevelType.RESISTANCE);
            assertThat(signal.getTouchCount()).isEqualTo(3);
        });

        // Running it again finds nothing new and changes nothing.
        assertThat(analysisService.catchUp(engine)).isZero();
        assertThat(signalsFor(key)).hasSize(1);
    }

    private List<SignalEntity> signalsFor(MarketKey key) {
        return signals.findAll().stream()
                .filter(s -> s.getSymbol().equals(key.symbol()) && s.getTimeframe().equals(key.timeframe().code()))
                .toList();
    }
}
