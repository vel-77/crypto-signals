package com.cryptosignals.analysis.strategy;

/**
 * Pluggable automatic level discovery — the abstraction required by sections 6 and 24 of the
 * specification.
 *
 * <p>Implementations decide <b>where</b> levels are. The engine decides <b>what happens to them</b>
 * (touches, breakouts, signals), identically for every strategy. A better discovery algorithm —
 * volume profile, fixed-range VWAP, round-number magnets, order-block detection — is therefore a
 * new implementation of this one method, not a rewrite.
 */
public interface LevelDetectionStrategy {

    /** Stable identifier persisted alongside produced levels, so results are traceable. */
    String name();

    /** Called once per closed candle, strictly in chronological order. */
    void onCandle(DetectionContext context);
}
