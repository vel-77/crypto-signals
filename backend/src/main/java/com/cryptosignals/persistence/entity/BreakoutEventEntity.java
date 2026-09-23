package com.cryptosignals.persistence.entity;

import com.cryptosignals.domain.LevelType;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A confirmed breakout. A level breaks at most once, hence the unique constraint on level_id. */
@Entity
@Table(name = "breakout_event", uniqueConstraints =
        @UniqueConstraint(name = "uq_breakout_level", columnNames = "level_id"))
public class BreakoutEventEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "level_id", nullable = false) private UUID levelId;
    @Enumerated(EnumType.STRING) @Column(name = "level_type", nullable = false) private LevelType levelType;
    @Column(name = "level_price", nullable = false, precision = 38, scale = 18) private BigDecimal levelPrice;
    @Column(name = "threshold_price", nullable = false, precision = 38, scale = 18) private BigDecimal thresholdPrice;
    @Column(name = "confirmation_price", nullable = false, precision = 38, scale = 18) private BigDecimal confirmationPrice;
    @Column(name = "first_breach_index", nullable = false) private int firstBreachIndex;
    @Column(name = "confirmed_at_index", nullable = false) private int confirmedAtIndex;
    @Column(name = "confirmed_at", nullable = false) private Instant confirmedAt;
    @Column(name = "confirmation_candles", nullable = false) private int confirmationCandles;
    @Column(name = "touch_count_at_break", nullable = false) private int touchCountAtBreak;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

    public BreakoutEventEntity() { }

    public Long getId() { return id; }
    public UUID getLevelId() { return levelId; }
    public void setLevelId(UUID v) { this.levelId = v; }
    public LevelType getLevelType() { return levelType; }
    public void setLevelType(LevelType v) { this.levelType = v; }
    public BigDecimal getLevelPrice() { return levelPrice; }
    public void setLevelPrice(BigDecimal v) { this.levelPrice = v; }
    public BigDecimal getThresholdPrice() { return thresholdPrice; }
    public void setThresholdPrice(BigDecimal v) { this.thresholdPrice = v; }
    public BigDecimal getConfirmationPrice() { return confirmationPrice; }
    public void setConfirmationPrice(BigDecimal v) { this.confirmationPrice = v; }
    public int getFirstBreachIndex() { return firstBreachIndex; }
    public void setFirstBreachIndex(int v) { this.firstBreachIndex = v; }
    public int getConfirmedAtIndex() { return confirmedAtIndex; }
    public void setConfirmedAtIndex(int v) { this.confirmedAtIndex = v; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(Instant v) { this.confirmedAt = v; }
    public int getConfirmationCandles() { return confirmationCandles; }
    public void setConfirmationCandles(int v) { this.confirmationCandles = v; }
    public int getTouchCountAtBreak() { return touchCountAtBreak; }
    public void setTouchCountAtBreak(int v) { this.touchCountAtBreak = v; }
    public Instant getCreatedAt() { return createdAt; }
}
