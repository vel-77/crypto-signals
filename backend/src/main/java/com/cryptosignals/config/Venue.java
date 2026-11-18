package com.cryptosignals.config;

import com.cryptosignals.analysis.LevelAnalysisEngineFactory;
import com.cryptosignals.exchange.ExchangeClient;
import com.cryptosignals.exchange.ExchangeSettings;

import java.util.Objects;

/**
 * One market the application analyses, e.g. Binance spot or Binance USDⓈ-M futures. Each runs on
 * its own: its own coins, candles, levels, signals, live stream and request budget.
 *
 * @param name          what configuration and the API call it, and what its stored markets are
 *                      filed under: BINANCE or BINANCE_FUTURES
 * @param label         the market in words, for people: "Binance USDⓈ-M futures"
 * @param client        talks to the market
 * @param settings      the addresses and budget the client was built with
 * @param engineFactory builds its engines; level ids carry the market, so the same coin on two
 *                      markets never shares a level
 */
public record Venue(String name, String label, ExchangeClient client, ExchangeSettings settings,
                    LevelAnalysisEngineFactory engineFactory) {

    public Venue {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(engineFactory, "engineFactory");
        label = label == null || label.isBlank() ? name : label;
    }

    @Override
    public String toString() {
        return name;
    }
}
