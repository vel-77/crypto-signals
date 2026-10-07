package com.cryptosignals.persistence.entity;

import com.cryptosignals.domain.LevelStatus;
import com.cryptosignals.domain.LevelType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A discovered support/resistance level.
 *
 * <p>The primary key is the engine's deterministic id, so re-running the analysis over the same
 * candles updates the row in place rather than creating a duplicate.
 */
@Entity
@Table(name = "price_level")
public class PriceLevelEntity {

    @Id
    private UUID id;

    @Column(name = "market_id", nullable = false)
    private Long marketId;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private String timeframe;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LevelType type;

    @Column(nullable = false, precision = 38, scale = 18)
    private BigDecimal price;

    @Column(name = "zone_lower", nullable = false, precision = 38, scale = 18)
    private BigDecimal zoneLower;

    @Column(name = "zone_upper", nullable = false, precision = 38, scale = 18)
    private BigDecimal zoneUpper;

    @Column(nullable = false)
    private double strength;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LevelStatus status;

    @Column(name = "swing_count", nullable = false)
    private int swingCount;

    @Column(name = "touch_count", nullable = false)
    private int touchCount;

    @Column(name = "created_at_index", nullable = false)
    private int createdAtIndex;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_touch_at")
    private Instant lastTouchAt;

    @Column(name = "superseded_by")
    private UUID supersededBy;

    @Column(name = "superseded_by_price", precision = 38, scale = 18)
    private BigDecimal supersededByPrice;

    @Column(name = "superseded_at")
    private Instant supersededAt;

    @Column(nullable = false)
    private String strategy;

    @Column(name = "config_fingerprint", nullable = false)
    private String configFingerprint;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected PriceLevelEntity() {
    }

    public PriceLevelEntity(UUID id) {
        this.id = id;
    }

    @PreUpdate
    void touchUpdatedAt() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Long getMarketId() {
        return marketId;
    }

    public void setMarketId(Long marketId) {
        this.marketId = marketId;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getTimeframe() {
        return timeframe;
    }

    public void setTimeframe(String timeframe) {
        this.timeframe = timeframe;
    }

    public LevelType getType() {
        return type;
    }

    public void setType(LevelType type) {
        this.type = type;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public BigDecimal getZoneLower() {
        return zoneLower;
    }

    public void setZoneLower(BigDecimal zoneLower) {
        this.zoneLower = zoneLower;
    }

    public BigDecimal getZoneUpper() {
        return zoneUpper;
    }

    public void setZoneUpper(BigDecimal zoneUpper) {
        this.zoneUpper = zoneUpper;
    }

    public double getStrength() {
        return strength;
    }

    public void setStrength(double strength) {
        this.strength = strength;
    }

    public LevelStatus getStatus() {
        return status;
    }

    public void setStatus(LevelStatus status) {
        this.status = status;
    }

    public int getSwingCount() {
        return swingCount;
    }

    public void setSwingCount(int swingCount) {
        this.swingCount = swingCount;
    }

    public int getTouchCount() {
        return touchCount;
    }

    public void setTouchCount(int touchCount) {
        this.touchCount = touchCount;
    }

    public int getCreatedAtIndex() {
        return createdAtIndex;
    }

    public void setCreatedAtIndex(int createdAtIndex) {
        this.createdAtIndex = createdAtIndex;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public void setFirstSeenAt(Instant firstSeenAt) {
        this.firstSeenAt = firstSeenAt;
    }

    public Instant getLastTouchAt() {
        return lastTouchAt;
    }

    public void setLastTouchAt(Instant lastTouchAt) {
        this.lastTouchAt = lastTouchAt;
    }

    public UUID getSupersededBy() { return supersededBy; }
    public void setSupersededBy(UUID v) { this.supersededBy = v; }
    public BigDecimal getSupersededByPrice() { return supersededByPrice; }
    public void setSupersededByPrice(BigDecimal v) { this.supersededByPrice = v; }
    public Instant getSupersededAt() { return supersededAt; }
    public void setSupersededAt(Instant v) { this.supersededAt = v; }

    public String getStrategy() {
        return strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }

    public String getConfigFingerprint() {
        return configFingerprint;
    }

    public void setConfigFingerprint(String configFingerprint) {
        this.configFingerprint = configFingerprint;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
