package com.cryptosignals.exchange;

import com.cryptosignals.domain.Candle;
import com.cryptosignals.domain.MarketKey;

/** Receives live candle updates. */
public interface CandleStreamListener {

    /**
     * @param closed true when the exchange marks this candle final. The analysis engine is only
     *               ever fed closed candles; in-progress updates are useful for a live chart but
     *               would make detection non-deterministic, since the same candle would be
     *               evaluated many times with different values.
     */
    void onCandle(MarketKey market, Candle candle, boolean closed);

    default void onConnected() {
    }

    default void onDisconnected(Throwable cause) {
    }
}
