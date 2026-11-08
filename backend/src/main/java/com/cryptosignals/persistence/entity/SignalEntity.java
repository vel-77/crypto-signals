package com.cryptosignals.persistence.entity;

import com.cryptosignals.domain.LevelType;
import com.cryptosignals.domain.SignalStatus;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * An emitted alert.
 *
 * <p>{@code notified} is the delivery guard: the signal is persisted first and marked notified only
 * after a notifier accepts it. A crash or an outage between the two therefore leaves the signal
 * pending for redelivery rather than losing it, and the unique (level_id, touch_count) constraint
 * makes double delivery impossible even if the analysis is replayed.
 */
@Entity
@Table(name = "signal", uniqueConstraints =
        @UniqueConstraint(name = "uq_signal_level_touch", columnNames = {"level_id", "touch_count"}))
public class SignalEntity {

    @Id private UUID id;
    @Column(name = "level_id", nullable = false) private UUID levelId;
    @Column(name = "market_id", nullable = false) private Long marketId;
    @Column(nullable = false) private String symbol;
    @Column(nullable = false) private String timeframe;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private LevelType type;
    @Column(nullable = false, precision = 38, scale = 18) private BigDecimal price;
    @Column(name = "zone_lower", nullable = false, precision = 38, scale = 18) private BigDecimal zoneLower;
    @Column(name = "zone_upper", nullable = false, precision = 38, scale = 18) private BigDecimal zoneUpper;
    @Column(name = "touch_count", nullable = false) private int touchCount;
    @Column(nullable = false) private double strength;
    @Column(name = "detected_at", nullable = false) private Instant detectedAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private SignalStatus status;
    @Column(nullable = false) private boolean historical;
    @Column(nullable = false) private boolean notified;
    @Column(name = "notified_at") private Instant notifiedAt;
    // Written only by SignalRepository.storeNextPotential. Read-only here, so saving a signal for
    // another reason (status, delivery) can never put back a value loaded before it was worked out.
    @Column(name = "next_potential_price", precision = 38, scale = 18, insertable = false, updatable = false)
    private BigDecimal nextPotentialPrice;
    @Column(name = "next_potential_time", insertable = false, updatable = false)
    private Instant nextPotentialTime;
    @Column(name = "next_potential_rule", insertable = false, updatable = false)
    private String nextPotentialRule;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

    protected SignalEntity() { }

    public SignalEntity(UUID id) { this.id = id; }

    @PreUpdate void touchUpdatedAt() { this.updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getLevelId() { return levelId; }
    public void setLevelId(UUID v) { this.levelId = v; }
    public Long getMarketId() { return marketId; }
    public void setMarketId(Long v) { this.marketId = v; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String v) { this.symbol = v; }
    public String getTimeframe() { return timeframe; }
    public void setTimeframe(String v) { this.timeframe = v; }
    public LevelType getType() { return type; }
    public void setType(LevelType v) { this.type = v; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal v) { this.price = v; }
    public BigDecimal getZoneLower() { return zoneLower; }
    public void setZoneLower(BigDecimal v) { this.zoneLower = v; }
    public BigDecimal getZoneUpper() { return zoneUpper; }
    public void setZoneUpper(BigDecimal v) { this.zoneUpper = v; }
    public int getTouchCount() { return touchCount; }
    public void setTouchCount(int v) { this.touchCount = v; }
    public double getStrength() { return strength; }
    public void setStrength(double v) { this.strength = v; }
    public Instant getDetectedAt() { return detectedAt; }
    public void setDetectedAt(Instant v) { this.detectedAt = v; }
    public SignalStatus getStatus() { return status; }
    public void setStatus(SignalStatus v) { this.status = v; }
    public boolean isHistorical() { return historical; }
    public void setHistorical(boolean v) { this.historical = v; }
    public boolean isNotified() { return notified; }
    public void setNotified(boolean v) { this.notified = v; }
    public Instant getNotifiedAt() { return notifiedAt; }
    public void setNotifiedAt(Instant v) { this.notifiedAt = v; }
    public BigDecimal getNextPotentialPrice() { return nextPotentialPrice; }
    public Instant getNextPotentialTime() { return nextPotentialTime; }
    public String getNextPotentialRule() { return nextPotentialRule; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
