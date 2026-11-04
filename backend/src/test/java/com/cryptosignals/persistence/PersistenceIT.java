package com.cryptosignals.persistence;

import com.cryptosignals.analysis.AnalysisConfig;
import com.cryptosignals.analysis.LevelAnalysisEngineFactory;
import com.cryptosignals.analysis.engine.EngineUpdate;
import com.cryptosignals.analysis.engine.LevelAnalysisEngine;
import com.cryptosignals.domain.Candle;
import com.cryptosignals.domain.LevelStatus;
import com.cryptosignals.domain.LevelType;
import com.cryptosignals.domain.MarketKey;
import com.cryptosignals.domain.SignalStatus;
import com.cryptosignals.domain.SupersedeReason;
import com.cryptosignals.domain.Timeframe;
import com.cryptosignals.fixtures.Candles;
import com.cryptosignals.persistence.entity.MarketEntity;
import com.cryptosignals.persistence.entity.PriceLevelEntity;
import com.cryptosignals.persistence.entity.SignalEntity;
import com.cryptosignals.persistence.jdbc.CandleGap;
import com.cryptosignals.persistence.jdbc.CandleJdbcRepository;
import com.cryptosignals.persistence.repository.BreakoutEventRepository;
import com.cryptosignals.persistence.repository.LevelTouchRepository;
import com.cryptosignals.persistence.repository.PriceLevelRepository;
import com.cryptosignals.persistence.repository.SignalRepository;
import com.cryptosignals.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the persistence layer against a real PostgreSQL.
 *
 * <p>The context starting at all is itself an assertion: {@code ddl-auto: validate} fails the run
 * if the Flyway schema and the JPA mappings have drifted apart.
 */
class PersistenceIT extends PostgresIntegrationTest {

    private static final Timeframe TF = Timeframe.M5;
    private static final AnalysisConfig PRICE_RULE =
            AnalysisConfig.builder().supersedeNearbyLevels(false).build();

    @Autowired CandleJdbcRepository candles;
    @Autowired AnalysisResultStore store;
    @Autowired PriceLevelRepository levels;
    @Autowired LevelTouchRepository touches;
    @Autowired SignalRepository signals;
    @Autowired BreakoutEventRepository breakouts;

    private MarketEntity market;

    @BeforeEach
    void setUp() {
        market = store.resolveMarket("BINANCE", new MarketKey("BTCUSDT", TF));
        candles.deleteRange(market.getId(), Instant.EPOCH, Instant.now().plusSeconds(86_400_000L));
        // Level ids are derived from content, so different fixtures that share a prefix produce
        // the same ids. Without this reset, state from a previous test would leak into the next.
        levels.deleteAll();   // cascades to touches, breakouts and signals
    }

    @Test
    @DisplayName("resolving the same market twice returns the same row")
    void marketResolutionIsIdempotent() {
        MarketEntity again = store.resolveMarket("BINANCE", new MarketKey("BTCUSDT", TF));

        assertThat(again.getId()).isEqualTo(market.getId());
    }

    @Test
    @DisplayName("candles round-trip through the database unchanged")
    void candlesRoundTrip() {
        List<Candle> series = Candles.fromPath(TF, Candles.zigzag(99_000, 10, 100_000, 99_000));

        candles.upsert(market.getId(), series);
        List<Candle> loaded = candles.loadAll(market.getId(), Instant.EPOCH,
                Instant.ofEpochMilli(series.get(series.size() - 1).openTime()));

        assertThat(loaded).isEqualTo(series);
        assertThat(candles.count(market.getId())).isEqualTo(series.size());
    }

    @Test
    @DisplayName("an extremely small price survives the NUMERIC round trip exactly")
    void preservesMicroPrices() {
        Candle tiny = new Candle(Candles.T0, Candles.T0 + TF.durationMillis() - 1,
                0.000000012, 0.000000015, 0.000000011, 0.000000014, 1234.5);

        candles.upsert(market.getId(), List.of(tiny));
        List<Candle> loaded = candles.loadAll(market.getId(), Instant.EPOCH, Instant.now());

        assertThat(loaded).singleElement().isEqualTo(tiny);
    }

    @Test
    @DisplayName("re-inserting the same range is harmless and corrects changed values")
    void upsertIsIdempotentAndCorrecting() {
        List<Candle> series = Candles.fromPath(TF, Candles.zigzag(100, 10, 110, 100));
        candles.upsert(market.getId(), series);
        candles.upsert(market.getId(), series);

        assertThat(candles.count(market.getId())).isEqualTo(series.size());

        // A candle first stored while still forming is later corrected by a refetch.
        Candle first = series.get(0);
        Candle corrected = new Candle(first.openTime(), first.closeTime(),
                first.open(), first.high() + 5, first.low(), first.close(), 999);
        candles.upsert(market.getId(), List.of(corrected));

        assertThat(candles.loadAll(market.getId(), Instant.EPOCH, Instant.now()).get(0))
                .isEqualTo(corrected);
        assertThat(candles.count(market.getId())).isEqualTo(series.size());
    }

    @Test
    @DisplayName("streaming does not materialise the whole range")
    void streamsWithoutLoadingEverything() {
        List<Candle> series = Candles.fromPath(TF, Candles.zigzag(100, 200, 110, 100, 110));
        candles.upsert(market.getId(), series);

        AtomicLong seen = new AtomicLong();
        long streamed = candles.stream(market.getId(), Instant.EPOCH, Instant.now().plusSeconds(1),
                candle -> seen.incrementAndGet());

        assertThat(streamed).isEqualTo(series.size());
        assertThat(seen.get()).isEqualTo(series.size());
    }

    @Test
    @DisplayName("missing candles are found by a single indexed pass")
    void detectsGaps() {
        List<Candle> series = new ArrayList<>(
                Candles.fromPath(TF, Candles.zigzag(100, 20, 110, 100)));
        Candle removedFirst = series.get(10);
        Candle removedLast = series.get(12);
        series.remove(12);
        series.remove(11);
        series.remove(10);
        candles.upsert(market.getId(), series);

        List<CandleGap> gaps = candles.findGaps(market.getId(), TF, Instant.EPOCH, Instant.now());

        assertThat(gaps).singleElement().satisfies(gap -> {
            assertThat(gap.missingCandles()).isEqualTo(3);
            assertThat(gap.afterOpenTime())
                    .isEqualTo(Instant.ofEpochMilli(removedFirst.openTime() - TF.durationMillis()));
            assertThat(gap.beforeOpenTime())
                    .isEqualTo(Instant.ofEpochMilli(removedLast.openTime() + TF.durationMillis()));
        });
    }

    @Test
    @DisplayName("a full analysis persists levels, touches and signals, and replay does not duplicate")
    void persistsAnalysisAndIsReplaySafe() {
        List<Candle> series = Candles.fromPath(TF, Candles.zigzag(99_000, 15,
                100_000, 99_000, 100_000, 99_000, 100_000, 101_000));

        runAndPersist(series);

        List<PriceLevelEntity> stored = levels.findAll();
        PriceLevelEntity resistance = stored.stream()
                .filter(l -> l.getType() == LevelType.RESISTANCE).findFirst().orElseThrow();

        assertThat(resistance.getPrice().doubleValue()).isEqualTo(100_000.0);
        assertThat(resistance.getTouchCount()).isEqualTo(3);
        assertThat(resistance.getStatus()).isEqualTo(LevelStatus.BROKEN);
        assertThat(resistance.getStrategy()).contains("swing-cluster");
        assertThat(touches.findByLevelIdOrderByTouchNumberAsc(resistance.getId()))
                .hasSize(3)
                .extracting(t -> t.getTouchNumber()).containsExactly(1, 2, 3);
        assertThat(breakouts.findByLevelId(resistance.getId())).isPresent();

        List<SignalEntity> emitted = signals.findAll();
        assertThat(emitted).hasSize(1);
        assertThat(emitted.get(0).getTouchCount()).isEqualTo(3);
        assertThat(emitted.get(0).isNotified()).isFalse();

        long levelCount = levels.count();
        long touchCount = touches.count();

        // Replaying the identical series must converge, not duplicate: ids are content-derived.
        runAndPersist(series);

        assertThat(levels.count()).isEqualTo(levelCount);
        assertThat(touches.count()).isEqualTo(touchCount);
        assertThat(signals.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a superseded level and its signal are stored with who took over")
    void persistsSupersededLevels() {
        // Resistance at 100 300 forms first, then one at 100 000; price then rises through the lower
        // one, without breaking it, until it is nearer the upper one. The price rule alone: with the
        // proximity rule on, the two would be grouped before price moved.
        runAndPersist(PRICE_RULE, Candles.fromPath(TF, Candles.zigzag(99_300, 15,
                100_300, 99_300, 100_300, 99_300, 100_300, 99_300,
                99_000, 100_000, 99_000, 100_000, 99_000, 100_000, 99_000, 100_180, 99_700)));

        PriceLevelEntity passed = levels.findAll().stream()
                .filter(l -> l.getType() == LevelType.RESISTANCE
                        && l.getPrice().doubleValue() == 100_000.0)
                .findFirst().orElseThrow();

        assertThat(passed.getStatus()).isEqualTo(LevelStatus.SUPERSEDED);
        assertThat(passed.getSupersededReason()).isEqualTo(SupersedeReason.PRICE_PASSED);
        assertThat(passed.getSupersededBy()).isNotNull();
        assertThat(levels.findById(passed.getSupersededBy())).isPresent()
                .get().extracting(PriceLevelEntity::getStatus).isEqualTo(LevelStatus.ACTIVE);
        assertThat(passed.getSupersededByPrice().doubleValue()).isEqualTo(100_300.0);
        assertThat(passed.getSupersededAt()).isNotNull();
        assertThat(signals.findAll()).filteredOn(s -> s.getLevelId().equals(passed.getId()))
                .singleElement()
                .extracting(SignalEntity::getStatus).isEqualTo(SignalStatus.SUPERSEDED);
    }

    @Test
    @DisplayName("re-running under the proximity rule corrects rows written under the old rules")
    void rerunUnderNewRulesCorrectsStoredRows() {
        // Resistance 100 400 with three touches, then 100 000 (0.4% below) with four.
        List<Candle> series = Candles.fromPath(TF, Candles.zigzag(99_000, 15,
                100_400, 99_000, 100_400, 99_000, 100_400, 99_000,
                100_000, 99_000, 100_000, 99_000, 100_000, 99_000, 100_000, 99_000));

        // Old rules: both levels active, the lower one with four touches and a signal.
        runAndPersist(PRICE_RULE, series);
        PriceLevelEntity lower = resistanceAt(100_000);
        assertThat(lower.getStatus()).isEqualTo(LevelStatus.ACTIVE);
        assertThat(touches.findByLevelIdOrderByTouchNumberAsc(lower.getId())).hasSize(4);
        assertThat(signals.findAll()).filteredOn(s -> s.getLevelId().equals(lower.getId())).isNotEmpty();

        // New rules: the lower level is folded into 100 400 on its third touch.
        LevelAnalysisEngine engine = runAndPersist(AnalysisConfig.defaults(), series);
        AnalysisResultStore.Reconciliation outcome =
                store.reconcileWithEngine(market.getId(), engine.activeLevelIds());

        PriceLevelEntity folded = levels.findById(lower.getId()).orElseThrow();
        assertThat(folded.getStatus()).isEqualTo(LevelStatus.SUPERSEDED);
        assertThat(folded.getSupersededReason()).isEqualTo(SupersedeReason.NEARBY_LEVEL);
        assertThat(folded.getSupersededByPrice().doubleValue()).isEqualTo(100_400.0);
        assertThat(folded.getTouchCount()).isEqualTo(3);
        // The fourth touch only existed under the old rules.
        assertThat(touches.findByLevelIdOrderByTouchNumberAsc(folded.getId()))
                .extracting(t -> t.getTouchNumber()).containsExactly(1, 2, 3);
        // The new run never signals the folded level, so its old signal had to be aligned.
        assertThat(outcome.signalsRetired()).isPositive();
        assertThat(signals.findAll()).filteredOn(s -> s.getLevelId().equals(folded.getId()))
                .extracting(SignalEntity::getStatus).containsOnly(SignalStatus.SUPERSEDED);
        assertThat(resistanceAt(100_400).getStatus()).isEqualTo(LevelStatus.ACTIVE);
    }

    @Test
    @DisplayName("stored levels the engine does not track any more are retired, with their signals")
    void retiresLevelsTheEngineNoLongerTracks() {
        LevelAnalysisEngine engine = runAndPersist(AnalysisConfig.defaults(),
                Candles.fromPath(TF, Candles.zigzag(99_000, 15,
                        100_000, 99_000, 100_000, 99_000, 100_000, 99_500)));
        PriceLevelEntity level = resistanceAt(100_000);
        assertThat(level.getStatus()).isEqualTo(LevelStatus.ACTIVE);

        // Everything stored is tracked: nothing to do.
        AnalysisResultStore.Reconciliation tracked =
                store.reconcileWithEngine(market.getId(), engine.activeLevelIds());
        assertThat(tracked.changedAnything()).isFalse();

        // An engine rebuilt from a later window no longer knows the level: it must not stay ACTIVE.
        AnalysisResultStore.Reconciliation untracked =
                store.reconcileWithEngine(market.getId(), Set.of());
        assertThat(untracked.levelsRetired()).isPositive();
        assertThat(levels.findById(level.getId()).orElseThrow().getStatus()).isEqualTo(LevelStatus.EXPIRED);
        assertThat(signals.findAll()).filteredOn(s -> s.getLevelId().equals(level.getId()))
                .isNotEmpty()
                .extracting(SignalEntity::getStatus).containsOnly(SignalStatus.EXPIRED);
        assertThat(levels.findByMarketIdAndStatus(market.getId(), LevelStatus.ACTIVE)).isEmpty();
    }

    @Test
    @DisplayName("undelivered signals are queued for retry and marked once delivered")
    void tracksNotificationDelivery() {
        runAndPersist(Candles.fromPath(TF, Candles.zigzag(99_000, 15,
                100_000, 99_000, 100_000, 99_000, 100_000, 99_500)));

        List<SignalEntity> pending = signals.findByNotifiedFalseOrderByDetectedAtAsc();
        assertThat(pending).hasSize(1);

        UUID id = pending.get(0).getId();
        store.markNotified(id);

        assertThat(signals.findByNotifiedFalseOrderByDetectedAtAsc()).isEmpty();
        assertThat(signals.findById(id).orElseThrow().getNotifiedAt()).isNotNull();
    }

    private PriceLevelEntity resistanceAt(double price) {
        return levels.findAll().stream()
                .filter(l -> l.getType() == LevelType.RESISTANCE && l.getPrice().doubleValue() == price)
                .findFirst().orElseThrow();
    }

    /** Replays a series through a fresh engine, persisting every update as live code would. */
    private LevelAnalysisEngine runAndPersist(List<Candle> series) {
        return runAndPersist(AnalysisConfig.defaults(), series);
    }

    private LevelAnalysisEngine runAndPersist(AnalysisConfig config, List<Candle> series) {
        LevelAnalysisEngineFactory factory = new LevelAnalysisEngineFactory();
        LevelAnalysisEngine engine = factory.create(market.toKey(), config, false);

        candles.upsert(market.getId(), series);
        for (Candle candle : series) {
            EngineUpdate update = engine.onClosedCandle(candle);
            store.apply(market, update, factory.detectionStrategyName(), config.fingerprint());
        }
        return engine;
    }
}
