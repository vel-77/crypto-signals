package com.cryptosignals.api.dto;

import com.cryptosignals.domain.Candle;
import com.cryptosignals.domain.LevelStatus;
import com.cryptosignals.domain.LevelType;
import com.cryptosignals.domain.SignalStatus;
import com.cryptosignals.domain.SupersedeReason;
import com.cryptosignals.persistence.entity.BreakoutEventEntity;
import com.cryptosignals.persistence.entity.LevelTouchEntity;
import com.cryptosignals.persistence.entity.PriceLevelEntity;
import com.cryptosignals.persistence.entity.SignalEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * API response shapes.
 *
 * <p>Separate from the persistence entities on purpose: the wire format is a contract with the UI
 * and with any client, and it must not change silently because a column was renamed. Prices cross
 * the wire as {@link BigDecimal} so a client that cares about exactness — a chart drawing a zone on
 * a nine-decimal asset — is not handed a lossy float.
 */
public final class Dtos {

    private Dtos() {
    }

    public record TouchDto(
            long id,
            int touchNumber,
            Instant touchAt,
            Instant startedAt,
            Instant endedAt,
            BigDecimal price,
            BigDecimal levelPriceAtTouch,
            BigDecimal distanceFromLevel,
            double distancePercent,
            int candlesSincePrevious,
            long millisSincePrevious,
            BigDecimal priceDeltaFromPrevious,
            double rejectionPercent,
            boolean closed) {

        public static TouchDto from(LevelTouchEntity entity) {
            return new TouchDto(entity.getId(), entity.getTouchNumber(), entity.getTouchAt(),
                    entity.getStartedAt(), entity.getEndedAt(), entity.getPrice(),
                    entity.getLevelPriceAtTouch(), entity.getDistanceFromLevel(),
                    entity.getDistancePercent(), entity.getCandlesSincePrevious(),
                    entity.getMillisSincePrevious(), entity.getPriceDeltaFromPrevious(),
                    entity.getRejectionPercent(), entity.isClosed());
        }
    }

    public record BreakoutDto(
            UUID levelId,
            LevelType levelType,
            BigDecimal levelPrice,
            BigDecimal thresholdPrice,
            BigDecimal confirmationPrice,
            Instant confirmedAt,
            int confirmationCandles,
            int touchCountAtBreak) {

        public static BreakoutDto from(BreakoutEventEntity entity) {
            return new BreakoutDto(entity.getLevelId(), entity.getLevelType(),
                    entity.getLevelPrice(), entity.getThresholdPrice(),
                    entity.getConfirmationPrice(), entity.getConfirmedAt(),
                    entity.getConfirmationCandles(), entity.getTouchCountAtBreak());
        }
    }

    public record LevelDto(
            UUID id,
            String symbol,
            String timeframe,
            LevelType type,
            BigDecimal price,
            BigDecimal zoneLower,
            BigDecimal zoneUpper,
            double strength,
            LevelStatus status,
            int swingCount,
            int touchCount,
            Instant firstSeenAt,
            Instant lastTouchAt,
            String strategy,
            String configFingerprint,
            UUID supersededBy,
            BigDecimal supersededByPrice,
            Instant supersededAt,
            SupersedeReason supersededReason,
            List<TouchDto> touches,
            BreakoutDto breakout) {

        public static LevelDto from(PriceLevelEntity entity) {
            return from(entity, null, null);
        }

        public static LevelDto from(PriceLevelEntity entity, List<TouchDto> touches, BreakoutDto breakout) {
            return new LevelDto(entity.getId(), entity.getSymbol(), entity.getTimeframe(),
                    entity.getType(), entity.getPrice(), entity.getZoneLower(), entity.getZoneUpper(),
                    entity.getStrength(), entity.getStatus(), entity.getSwingCount(),
                    entity.getTouchCount(), entity.getFirstSeenAt(), entity.getLastTouchAt(),
                    entity.getStrategy(), entity.getConfigFingerprint(), entity.getSupersededBy(),
                    entity.getSupersededByPrice(), entity.getSupersededAt(),
                    entity.getSupersededReason(), touches, breakout);
        }
    }

    public record SignalDto(
            UUID id,
            UUID levelId,
            String symbol,
            String timeframe,
            LevelType type,
            BigDecimal price,
            BigDecimal zoneLower,
            BigDecimal zoneUpper,
            int touchCount,
            double strength,
            Instant detectedAt,
            SignalStatus status,
            boolean historical,
            boolean notified,
            Instant notifiedAt,
            NextPotentialDto nextPotential,
            List<TouchDto> touches) {

        public static SignalDto from(SignalEntity entity, List<TouchDto> touches) {
            return new SignalDto(entity.getId(), entity.getLevelId(), entity.getSymbol(),
                    entity.getTimeframe(), entity.getType(), entity.getPrice(),
                    entity.getZoneLower(), entity.getZoneUpper(), entity.getTouchCount(),
                    entity.getStrength(), entity.getDetectedAt(), entity.getStatus(),
                    entity.isHistorical(), entity.isNotified(), entity.getNotifiedAt(),
                    NextPotentialDto.from(entity), touches);
        }
    }

    /**
     * A signal's next potential low (support) or high (resistance): the nearest earlier swing
     * beyond the signal price by at least the configured minimum distance.
     *
     * @param evaluated false until it has been worked out; true with a null price means nothing
     *                  qualified (N/A)
     * @param price     the swing's price, or null when nothing qualified
     * @param percent   signed distance from the signal price, in percent (negative below)
     * @param swingAt   when the swing printed
     */
    public record NextPotentialDto(boolean evaluated, BigDecimal price, Double percent, Instant swingAt) {

        static NextPotentialDto from(SignalEntity entity) {
            if (entity.getNextPotentialRule() == null) {
                return new NextPotentialDto(false, null, null, null);
            }
            BigDecimal price = entity.getNextPotentialPrice();
            if (price == null || entity.getPrice().signum() <= 0) {
                return new NextPotentialDto(true, null, null, null);
            }
            double signal = entity.getPrice().doubleValue();
            double percent = (price.doubleValue() - signal) / signal * 100.0;
            return new NextPotentialDto(true, price, percent, entity.getNextPotentialTime());
        }
    }

    public record CandleDto(
            Instant openTime,
            Instant closeTime,
            double open,
            double high,
            double low,
            double close,
            double volume) {

        public static CandleDto from(Candle candle) {
            return new CandleDto(Instant.ofEpochMilli(candle.openTime()),
                    Instant.ofEpochMilli(candle.closeTime()), candle.open(), candle.high(),
                    candle.low(), candle.close(), candle.volume());
        }
    }

    /** A slice of a larger result set. */
    /**
     * The watched symbols and how they were chosen.
     *
     * @param mode        VOLUME when coins are chosen by trading volume on top of the fixed ones,
     *                    FIXED when only the configured symbols are watched
     * @param source      where the current list came from: FIXED, STORED (loaded at startup, the
     *                    exchange not asked yet) or EXCHANGE
     * @param symbols     fixed symbols first, in configured order, then the chosen ones by volume
     * @param rule        the selection settings; absent in FIXED mode
     * @param lastError   why the last refresh failed; absent after a successful one
     */
    public record WatchListDto(String mode, String source, int count, List<WatchedSymbolDto> symbols,
                               VolumeRuleDto rule, Instant refreshedAt, Instant nextRefreshAt,
                               String lastError) {
    }

    /**
     * @param source      FIXED (from the configuration) or VOLUME (chosen by volume)
     * @param dailyVolume average traded value per day over the volume window, in the quote asset;
     *                    absent when not known yet
     * @param selectedAt  when a VOLUME coin joined the list
     */
    public record WatchedSymbolDto(String symbol, String source, BigDecimal dailyVolume, Instant selectedAt) {
    }

    public record VolumeRuleDto(String quoteAsset, BigDecimal minDailyVolume, String window, int maxSymbols,
                                double keepPercent, double minPriceRangePercent, List<String> excludeTags,
                                List<String> excludeAssets, String refreshInterval) {
    }

    public record PageDto<T>(List<T> items, int page, int size, long totalItems, int totalPages) {

        public static <T> PageDto<T> of(org.springframework.data.domain.Page<T> page) {
            return new PageDto<>(page.getContent(), page.getNumber(), page.getSize(),
                    page.getTotalElements(), page.getTotalPages());
        }
    }

    public record ErrorDto(String error, String message, Instant timestamp) {

        public static ErrorDto of(String error, String message) {
            return new ErrorDto(error, message, Instant.now());
        }
    }
}
