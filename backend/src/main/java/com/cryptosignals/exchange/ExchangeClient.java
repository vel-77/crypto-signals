package com.cryptosignals.exchange;

import com.cryptosignals.domain.Candle;
import com.cryptosignals.domain.MarketKey;
import com.cryptosignals.domain.Timeframe;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Everything the system needs from a trading venue — the abstraction required by specification
 * section 11.
 *
 * <p>Nothing above this interface knows about Binance. Adding Bybit, OKX or Coinbase means one new
 * implementation and one configuration value; the market-data layer, the analysis engine and the
 * API are untouched.
 *
 * <p>Implementations are responsible for their own rate limiting, retry and reconnection, and must
 * return candles that are <b>closed, deduplicated and in ascending time order</b>.
 */
public interface ExchangeClient {

    /** Matched against {@code crypto-signals.exchange.name} to select the active venue. */
    String name();

    /**
     * Fetches closed candles with open times in {@code [from, to]}, paging internally as needed.
     *
     * @param maxCandles hard ceiling on the number returned; paging stops once it is reached
     */
    List<Candle> fetchCandles(String symbol, Timeframe timeframe, Instant from, Instant to, int maxCandles);

    /** Fetches the most recent closed candles, newest last. */
    List<Candle> fetchLatestCandles(String symbol, Timeframe timeframe, int limit);

    /**
     * Opens a live candle stream for every given market. Implementations reconnect on their own;
     * the returned handle only exists so the caller can stop it and observe its health.
     */
    StreamSubscription streamCandles(List<MarketKey> markets, CandleStreamListener listener);

    /** Venue clock, used to detect local clock skew before it corrupts candle alignment. */
    Instant serverTime();

    /** Whether the venue currently lists this symbol for trading. */
    boolean supportsSymbol(String symbol);

    /**
     * Every spot symbol currently trading against {@code quoteAsset}, with how much of it traded
     * over the trailing {@code window} and the venue's category tags. Used to choose which markets
     * to watch.
     *
     * @throws ExchangeException if any part of the answer could not be fetched; a partial list
     *                           would make coins look inactive when they are not
     */
    List<SymbolActivity> tradingActivity(String quoteAsset, Duration window);
}
