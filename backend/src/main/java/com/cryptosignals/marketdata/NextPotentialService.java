package com.cryptosignals.marketdata;

import com.cryptosignals.analysis.AnalysisConfig;
import com.cryptosignals.analysis.target.NextPotential;
import com.cryptosignals.analysis.target.NextPotentialFinder;
import com.cryptosignals.config.NextPotentialProperties;
import com.cryptosignals.domain.MarketKey;
import com.cryptosignals.persistence.entity.SignalEntity;
import com.cryptosignals.persistence.jdbc.CandleJdbcRepository;
import com.cryptosignals.persistence.repository.SignalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Stores each signal's next potential low / high (see {@link NextPotentialFinder}).
 *
 * <p>Worked out from the stored candles rather than from the engine's memory. The engine only
 * holds the candles since it last started, so a signal analysed shortly after a restart would see
 * less history than one analysed later. The candle table holds the same history whenever it is
 * read, so every signal gets the same answer: when it is first saved, when it is filled in for a
 * signal stored before this existed, and when it is recomputed after a setting changed.
 */
@Service
public class NextPotentialService {

    private static final Logger log = LoggerFactory.getLogger(NextPotentialService.class);

    /** Signals handled per round trip; bounds the candle range held in memory at once. */
    private static final int BATCH_SIZE = 200;

    private final SignalRepository signals;
    private final CandleJdbcRepository candles;
    private final NextPotentialProperties properties;

    public NextPotentialService(SignalRepository signals, CandleJdbcRepository candles,
                                NextPotentialProperties properties) {
        this.signals = signals;
        this.candles = candles;
        this.properties = properties;
    }

    /**
     * Works out the next potential low / high of every signal of one market that has none under the
     * current settings: new signals, signals stored before this existed, and signals worked out
     * under settings that have since changed.
     *
     * @return how many signals were updated
     */
    public int fillMissing(long marketId, MarketKey key, AnalysisConfig config) {
        String rule = properties.ruleKey(config.swingLeftBars(), config.swingRightBars());
        long lookback = properties.getLookback().toMillis();
        long step = key.timeframe().durationMillis();
        Set<UUID> seen = new HashSet<>();
        int updated = 0;

        while (true) {
            List<SignalEntity> batch = signals.findNextPotentialPending(marketId, rule,
                    PageRequest.of(0, BATCH_SIZE));
            if (batch.isEmpty()) {
                return updated;
            }
            if (!seen.add(batch.get(0).getId())) {
                // Stored but still reported as pending: never loop on it.
                log.warn("Next potential of signal {} did not persist; giving up for {}",
                        batch.get(0).getId(), key);
                return updated;
            }

            Instant first = batch.get(0).getDetectedAt();
            Instant last = batch.get(batch.size() - 1).getDetectedAt();
            // A few candles more than the lookback, so a swing right at its start still has the
            // left-hand candles it is compared with.
            Instant from = lookback > 0
                    ? first.minusMillis(lookback + (config.swingLeftBars() + 1L) * step)
                    : Instant.EPOCH;
            List<NextPotentialFinder.Swing> swings =
                    NextPotentialFinder.swings(candles.loadAll(marketId, from, last), config);

            for (SignalEntity signal : batch) {
                seen.add(signal.getId());
                Optional<NextPotential> found = NextPotentialFinder.find(swings, signal.getType(),
                        signal.getPrice().doubleValue(), signal.getDetectedAt().toEpochMilli(),
                        lookback, properties.getMinDistancePercent());
                signals.storeNextPotential(signal.getId(),
                        found.map(n -> BigDecimal.valueOf(n.price())).orElse(null),
                        found.map(n -> Instant.ofEpochMilli(n.swingTime())).orElse(null),
                        rule);
            }
            updated += batch.size();
            if (batch.size() < BATCH_SIZE) {
                return updated;
            }
        }
    }

    public NextPotentialProperties properties() {
        return properties;
    }
}
