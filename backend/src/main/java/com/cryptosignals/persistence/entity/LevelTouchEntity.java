package com.cryptosignals.persistence.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One separate interaction between price and a level's zone.
 *
 * <p>{@code touchAt} is the moment price reached furthest into the level, which is the timestamp a
 * trader would mark on a chart; {@code startedAt}/{@code endedAt} bound the whole interaction, so a
 * touch that lasted twelve candles is still one row with a visible duration.
 */
@Entity
@Table(name = "level_touch", uniqueConstraints =
        @UniqueConstraint(name = "uq_level_touch", columnNames = {"level_id", "touch_number"}))
public class LevelTouchEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "level_id", nullable = false) private UUID levelId;
    @Column(name = "touch_number", nullable = false) private int touchNumber;
    @Column(name = "touch_at", nullable = false) private Instant touchAt;
    @Column(name = "started_at", nullable = false) private Instant startedAt;
    @Column(name = "ended_at", nullable = false) private Instant endedAt;
    @Column(name = "candle_open_time", nullable = false) private Instant candleOpenTime;
    @Column(nullable = false, precision = 38, scale = 18) private BigDecimal price;
    @Column(name = "level_price_at_touch", nullable = false, precision = 38, scale = 18) private BigDecimal levelPriceAtTouch;
    @Column(name = "distance_from_level", nullable = false, precision = 38, scale = 18) private BigDecimal distanceFromLevel;
    @Column(name = "distance_percent", nullable = false) private double distancePercent;
    @Column(name = "candles_since_previous", nullable = false) private int candlesSincePrevious;
    @Column(name = "millis_since_previous", nullable = false) private long millisSincePrevious;
    @Column(name = "price_delta_from_previous", nullable = false, precision = 38, scale = 18) private BigDecimal priceDeltaFromPrevious;
    @Column(name = "rejection_percent", nullable = false) private double rejectionPercent;
    @Column(nullable = false) private boolean closed;
    @Column(name = "start_index", nullable = false) private int startIndex;
    @Column(name = "end_index", nullable = false) private int endIndex;
    @Column(name = "extreme_index", nullable = false) private int extremeIndex;

    public LevelTouchEntity() { }

    public Long getId() { return id; }
    public UUID getLevelId() { return levelId; }
    public void setLevelId(UUID v) { this.levelId = v; }
    public int getTouchNumber() { return touchNumber; }
    public void setTouchNumber(int v) { this.touchNumber = v; }
    public Instant getTouchAt() { return touchAt; }
    public void setTouchAt(Instant v) { this.touchAt = v; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant v) { this.startedAt = v; }
    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant v) { this.endedAt = v; }
    public Instant getCandleOpenTime() { return candleOpenTime; }
    public void setCandleOpenTime(Instant v) { this.candleOpenTime = v; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal v) { this.price = v; }
    public BigDecimal getLevelPriceAtTouch() { return levelPriceAtTouch; }
    public void setLevelPriceAtTouch(BigDecimal v) { this.levelPriceAtTouch = v; }
    public BigDecimal getDistanceFromLevel() { return distanceFromLevel; }
    public void setDistanceFromLevel(BigDecimal v) { this.distanceFromLevel = v; }
    public double getDistancePercent() { return distancePercent; }
    public void setDistancePercent(double v) { this.distancePercent = v; }
    public int getCandlesSincePrevious() { return candlesSincePrevious; }
    public void setCandlesSincePrevious(int v) { this.candlesSincePrevious = v; }
    public long getMillisSincePrevious() { return millisSincePrevious; }
    public void setMillisSincePrevious(long v) { this.millisSincePrevious = v; }
    public BigDecimal getPriceDeltaFromPrevious() { return priceDeltaFromPrevious; }
    public void setPriceDeltaFromPrevious(BigDecimal v) { this.priceDeltaFromPrevious = v; }
    public double getRejectionPercent() { return rejectionPercent; }
    public void setRejectionPercent(double v) { this.rejectionPercent = v; }
    public boolean isClosed() { return closed; }
    public void setClosed(boolean v) { this.closed = v; }
    public int getStartIndex() { return startIndex; }
    public void setStartIndex(int v) { this.startIndex = v; }
    public int getEndIndex() { return endIndex; }
    public void setEndIndex(int v) { this.endIndex = v; }
    public int getExtremeIndex() { return extremeIndex; }
    public void setExtremeIndex(int v) { this.extremeIndex = v; }
}
