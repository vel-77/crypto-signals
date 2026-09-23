package com.cryptosignals.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** Per-market progress so a restart resumes instead of refetching everything. */
@Entity
@Table(name = "market_sync_state")
public class MarketSyncStateEntity {

    @Id @Column(name = "market_id") private Long marketId;
    @Column(name = "backfilled_from") private Instant backfilledFrom;
    @Column(name = "last_candle_at") private Instant lastCandleAt;
    @Column(name = "last_analyzed_at") private Instant lastAnalyzedAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

    protected MarketSyncStateEntity() { }

    public MarketSyncStateEntity(Long marketId) { this.marketId = marketId; }

    @PreUpdate void touchUpdatedAt() { this.updatedAt = Instant.now(); }

    public Long getMarketId() { return marketId; }
    public Instant getBackfilledFrom() { return backfilledFrom; }
    public void setBackfilledFrom(Instant v) { this.backfilledFrom = v; }
    public Instant getLastCandleAt() { return lastCandleAt; }
    public void setLastCandleAt(Instant v) { this.lastCandleAt = v; }
    public Instant getLastAnalyzedAt() { return lastAnalyzedAt; }
    public void setLastAnalyzedAt(Instant v) { this.lastAnalyzedAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
}
