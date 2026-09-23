package com.cryptosignals.persistence;

import com.cryptosignals.analysis.AnalysisConfig;
import com.cryptosignals.analysis.LevelAnalysisEngineFactory;
import com.cryptosignals.analysis.engine.EngineUpdate;
import com.cryptosignals.analysis.engine.LevelAnalysisEngine;
import com.cryptosignals.domain.Candle;
import com.cryptosignals.domain.LevelStatus;
import com.cryptosignals.domain.LevelType;
import com.cryptosignals.domain.MarketKey;
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

    /** Replays a series through a fresh engine, persisting every update as live code would. */
    private void runAndPersist(List<Candle> series) {
        AnalysisConfig config = AnalysisConfig.defaults();
        LevelAnalysisEngineFactory factory = new LevelAnalysisEngineFactory();
        LevelAnalysisEngine engine = factory.create(market.toKey(), config, false);

        candles.upsert(market.getId(), series);
        for (Candle candle : series) {
            EngineUpdate update = engine.onClosedCandle(candle);
            store.apply(market, update, factory.detectionStrategyName(), config.fingerprint());
        }
    }
}
