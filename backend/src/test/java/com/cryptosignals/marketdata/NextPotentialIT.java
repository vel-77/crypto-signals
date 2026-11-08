package com.cryptosignals.marketdata;

import com.cryptosignals.analysis.AnalysisConfig;
import com.cryptosignals.analysis.LevelAnalysisEngineFactory;
import com.cryptosignals.analysis.engine.LevelAnalysisEngine;
import com.cryptosignals.domain.Candle;
import com.cryptosignals.domain.LevelType;
import com.cryptosignals.domain.MarketKey;
import com.cryptosignals.domain.Timeframe;
import com.cryptosignals.fixtures.Candles;
import com.cryptosignals.persistence.AnalysisResultStore;
import com.cryptosignals.persistence.entity.MarketEntity;
import com.cryptosignals.persistence.entity.SignalEntity;
import com.cryptosignals.persistence.jdbc.CandleJdbcRepository;
import com.cryptosignals.persistence.repository.SignalRepository;
import com.cryptosignals.support.PostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The next potential low / high is stored with every signal, and filled in for old ones. */
class NextPotentialIT extends PostgresIntegrationTest {

    private static final Timeframe TF = Timeframe.M5;

    @Autowired MarketAnalysisService analysisService;
    @Autowired CandleJdbcRepository candles;
    @Autowired SignalRepository signals;
    @Autowired JdbcTemplate jdbc;
    @Autowired AnalysisResultStore store;

    /**
     * The resistance example: peaks at 105k, 107k, 102k, 104k and 101.5k, then three touches of a
     * 100k resistance. The troughs between the peaks build a 96k support, and the dips between the
     * 100k touches a 99k support.
     */
    private static List<Candle> example() {
        double[] path = Candles.zigzag(95_000, 15,
                105_000, 96_000, 107_000, 96_000, 102_000, 96_000, 104_000, 96_000, 101_500,
                99_000, 100_000, 99_000, 100_000, 99_000, 100_000, 99_500);
        long start = TF.alignDown(System.currentTimeMillis()) - (long) (path.length + 2) * TF.durationMillis();
        return Candles.fromPath(TF, start, path);
    }

    private MarketEngine replayExample(String symbol) {
        MarketKey key = new MarketKey(symbol, TF);
        List<Candle> series = example();

        MarketEngine engine = analysisService.initialise(key);
        candles.upsert(engine.market().getId(), series);
        analysisService.replayHistory(engine);
        return engine;
    }

    @Test
    @DisplayName("each signal is saved with the nearest qualifying swing beyond it, or N/A")
    void storedWithEverySignal() {
        MarketEngine engine = replayExample("NEXTPOTUSDT");
        List<SignalEntity> stored = signalsOf(engine);

        assertThat(stored).filteredOn(s -> s.getType() == LevelType.RESISTANCE)
                .singleElement().satisfies(s -> {
                    assertThat(s.getPrice()).isEqualByComparingTo("100000");
                    assertThat(s.getNextPotentialPrice()).isEqualByComparingTo("101500");
                    assertThat(s.getNextPotentialRule()).isNotNull();
                });
        // The 99k support: its next potential low is the 96k area, 3% lower.
        assertThat(stored).filteredOn(s -> s.getType() == LevelType.SUPPORT
                        && s.getPrice().compareTo(new BigDecimal("99000")) == 0)
                .isNotEmpty()
                .allSatisfy(s -> assertThat(s.getNextPotentialPrice()).isEqualByComparingTo("96000"));
        // The 96k support has nothing at least 1% below it: worked out, and N/A.
        assertThat(stored).filteredOn(s -> s.getType() == LevelType.SUPPORT
                        && s.getPrice().compareTo(new BigDecimal("96000")) == 0)
                .isNotEmpty()
                .allSatisfy(s -> {
                    assertThat(s.getNextPotentialRule()).isNotNull();
                    assertThat(s.getNextPotentialPrice()).isNull();
                });
    }

    @Test
    @DisplayName("signals stored before, or under other settings, are worked out again")
    void fillsInOldSignals() {
        MarketEngine engine = replayExample("NEXTPOTOLDUSDT");
        long marketId = engine.market().getId();
        List<SignalEntity> before = signalsOf(engine);

        // As if stored by the previous release, and under an older rule.
        jdbc.update("UPDATE signal SET next_potential_price = NULL, next_potential_time = NULL, "
                + "next_potential_rule = NULL WHERE market_id = ?", marketId);
        jdbc.update("UPDATE signal SET next_potential_price = 1, next_potential_rule = 'old' "
                + "WHERE market_id = ? AND type = 'RESISTANCE'", marketId);

        assertThat(analysisService.fillNextPotential(engine)).isEqualTo(before.size());
        assertThat(signalsOf(engine)).usingRecursiveFieldByFieldElementComparatorOnFields(
                        "id", "nextPotentialPrice", "nextPotentialTime", "nextPotentialRule")
                .containsExactlyInAnyOrderElementsOf(before);

        // Nothing left to do.
        assertThat(analysisService.fillNextPotential(engine)).isZero();
    }

    @Test
    @DisplayName("saving a signal loaded before its value was worked out does not erase the value")
    void staleSaveKeepsTheValue() {
        MarketEngine engine = replayExample("NEXTPOTSTALEUSDT");
        long marketId = engine.market().getId();
        jdbc.update("UPDATE signal SET next_potential_price = NULL, next_potential_time = NULL, "
                + "next_potential_rule = NULL WHERE market_id = ?", marketId);
        SignalEntity stale = signalsOf(marketId).stream()
                .filter(s -> s.getType() == LevelType.RESISTANCE).findFirst().orElseThrow();

        analysisService.fillNextPotential(engine);
        // e.g. the delivery retry sweeper marking it sent with the copy it loaded a moment earlier
        stale.setNotified(true);
        signals.save(stale);

        assertThat(signals.findById(stale.getId())).hasValueSatisfying(s -> {
            assertThat(s.isNotified()).isTrue();
            assertThat(s.getNextPotentialPrice()).isEqualByComparingTo("101500");
        });
    }

    @Test
    @DisplayName("signals of a market that is no longer watched are filled in too")
    void fillsInUnwatchedMarkets() {
        // Analysed earlier and stored, but no engine runs for it now (e.g. its timeframe was
        // taken off the watch list).
        MarketKey key = new MarketKey("NEXTPOTGONEUSDT", TF);
        MarketEntity market = store.resolveMarket("BINANCE", key);
        List<Candle> series = example();
        candles.upsert(market.getId(), series);
        AnalysisConfig config = analysisService.configFor(key);
        LevelAnalysisEngineFactory factory = new LevelAnalysisEngineFactory();
        LevelAnalysisEngine engine = factory.create(key, config, false);
        for (Candle candle : series) {
            store.apply(market, engine.onClosedCandle(candle),
                    factory.detectionStrategyName(), config.fingerprint());
        }
        assertThat(signalsOf(market.getId())).isNotEmpty()
                .allSatisfy(s -> assertThat(s.getNextPotentialRule()).isNull());

        analysisService.fillNextPotentialOfUnwatchedMarkets();

        assertThat(signalsOf(market.getId())).allSatisfy(s ->
                assertThat(s.getNextPotentialRule()).isNotNull());
        assertThat(signalsOf(market.getId())).filteredOn(s -> s.getType() == LevelType.RESISTANCE)
                .singleElement().satisfies(s ->
                        assertThat(s.getNextPotentialPrice()).isEqualByComparingTo("101500"));
    }

    private List<SignalEntity> signalsOf(MarketEngine engine) {
        return signalsOf(engine.market().getId());
    }

    private List<SignalEntity> signalsOf(long marketId) {
        return signals.findAll().stream()
                .filter(s -> s.getMarketId().equals(marketId))
                .toList();
    }
}
