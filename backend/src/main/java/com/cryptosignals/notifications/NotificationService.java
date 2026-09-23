package com.cryptosignals.notifications;

import com.cryptosignals.analysis.model.TradingSignal;
import com.cryptosignals.config.NotificationProperties;
import com.cryptosignals.domain.MarketKey;
import com.cryptosignals.domain.SignalStatus;
import com.cryptosignals.domain.Timeframe;
import com.cryptosignals.persistence.AnalysisResultStore;
import com.cryptosignals.persistence.entity.SignalEntity;
import com.cryptosignals.persistence.repository.LevelTouchRepository;
import com.cryptosignals.persistence.repository.SignalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Fans a signal out to every enabled channel and tracks whether it actually got there.
 *
 * <h2>Delivery contract</h2>
 * A signal is written to the database <em>before</em> any delivery is attempted, with
 * {@code notified = false}. It is flipped to true only once dispatch has been resolved: either
 * every enabled channel accepted it, or it was deliberately suppressed. Anything else stays queued,
 * so a Telegram outage or a process kill mid-send results in a retry rather than a lost alert.
 *
 * <h2>Suppression</h2>
 * Two rules stop a first startup from flooding a chat: signals found while replaying history are
 * suppressed unless explicitly enabled, and signals older than {@code maxSignalAge} are suppressed
 * regardless. Suppressed signals are marked resolved rather than left pending, which also bounds
 * retries — a permanently undeliverable signal eventually ages out instead of looping forever.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final List<Notifier> notifiers;
    private final SignalFormatter formatter;
    private final NotificationProperties properties;
    private final SignalRepository signals;
    private final LevelTouchRepository touches;
    private final AnalysisResultStore store;

    public NotificationService(List<Notifier> notifiers, SignalFormatter formatter,
                               NotificationProperties properties, SignalRepository signals,
                               LevelTouchRepository touches, AnalysisResultStore store) {
        this.notifiers = notifiers;
        this.formatter = formatter;
        this.properties = properties;
        this.signals = signals;
        this.touches = touches;
        this.store = store;
        log.info("Notification channels: {}", notifiers.stream()
                .map(n -> n.name() + (n.isEnabled() ? "" : " (disabled)")).toList());
    }

    /** Dispatches a freshly emitted signal. Never throws into the analysis path. */
    public void dispatch(TradingSignal signal) {
        try {
            if (!shouldNotify(signal)) {
                log.debug("Suppressing signal {} ({})", signal.id(), suppressionReason(signal));
                store.markNotified(UUID.fromString(signal.id()));
                return;
            }
            deliver(signal);
        } catch (Exception ex) {
            log.error("Notification dispatch failed for signal {}; it stays queued for retry: {}",
                    signal.id(), ex.toString());
        }
    }

    private void deliver(TradingSignal signal) {
        String message = formatter.format(signal);
        boolean allAccepted = true;

        for (Notifier notifier : notifiers) {
            if (!notifier.isEnabled()) {
                continue;
            }
            try {
                if (!notifier.send(signal, message)) {
                    allAccepted = false;
                    log.warn("Channel {} did not accept signal {}", notifier.name(), signal.id());
                }
            } catch (Exception ex) {
                allAccepted = false;
                log.warn("Channel {} threw delivering signal {}: {}",
                        notifier.name(), signal.id(), ex.toString());
            }
        }

        if (allAccepted) {
            store.markNotified(UUID.fromString(signal.id()));
        }
    }

    /**
     * Retries signals that were persisted but never delivered — after a Telegram outage, a network
     * partition, or a restart that happened between the insert and the send.
     */
    @Scheduled(fixedDelayString = "${crypto-signals.notifications.retry-interval:60s}")
    public void retryPending() {
        List<SignalEntity> pending = signals.findByNotifiedFalseOrderByDetectedAtAsc();
        if (pending.isEmpty()) {
            return;
        }
        log.info("Retrying delivery of {} pending signal(s)", pending.size());
        for (SignalEntity entity : pending) {
            dispatch(toDomain(entity));
        }
    }

    private boolean shouldNotify(TradingSignal signal) {
        if (signal.historical() && !properties.isNotifyHistorical()) {
            return false;
        }
        Duration maxAge = properties.getMaxSignalAge();
        if (maxAge != null && !maxAge.isZero() && !maxAge.isNegative()) {
            Instant detectedAt = Instant.ofEpochMilli(signal.detectedAtTime());
            return !detectedAt.isBefore(Instant.EPOCH)
                    && Duration.between(detectedAt, Instant.now()).compareTo(maxAge) <= 0;
        }
        return true;
    }

    private String suppressionReason(TradingSignal signal) {
        if (signal.historical() && !properties.isNotifyHistorical()) {
            return "historical replay";
        }
        return "older than " + properties.getMaxSignalAge();
    }

    /** Rehydrates a persisted signal, including its touches, for retry formatting. */
    private TradingSignal toDomain(SignalEntity entity) {
        var touchList = touches.findByLevelIdOrderByTouchNumberAsc(entity.getLevelId()).stream()
                .map(t -> new com.cryptosignals.analysis.model.Touch(
                        t.getTouchNumber(), t.getStartIndex(), t.getEndIndex(), t.getExtremeIndex(),
                        t.getStartedAt().toEpochMilli(), t.getEndedAt().toEpochMilli(),
                        t.getTouchAt().toEpochMilli(), t.getPrice().doubleValue(),
                        t.getLevelPriceAtTouch().doubleValue(), t.getDistanceFromLevel().doubleValue(),
                        t.getDistancePercent(), t.getCandlesSincePrevious(), t.getMillisSincePrevious(),
                        t.getPriceDeltaFromPrevious().doubleValue(), t.getRejectionPercent(), t.isClosed()))
                .filter(t -> t.touchNumber() <= entity.getTouchCount())
                .toList();

        return new TradingSignal(entity.getId().toString(), entity.getLevelId().toString(),
                new MarketKey(entity.getSymbol(), Timeframe.fromCode(entity.getTimeframe())),
                entity.getType(), entity.getPrice().doubleValue(),
                entity.getZoneLower().doubleValue(), entity.getZoneUpper().doubleValue(),
                entity.getTouchCount(), touchList, entity.getStrength(), -1,
                entity.getDetectedAt().toEpochMilli(),
                entity.getStatus() == null ? SignalStatus.ACTIVE : entity.getStatus(),
                entity.isHistorical());
    }
}
