package com.cryptosignals.backtest;

import com.cryptosignals.analysis.AnalysisConfig;
import com.cryptosignals.domain.MarketKey;
import com.cryptosignals.domain.Timeframe;

import java.time.Instant;

/**
 * One backtest run.
 *
 * @param market       symbol and timeframe to analyse
 * @param from         inclusive start
 * @param to           inclusive end
 * @param config       the rules to run with; lets a run compare parameter sets without redeploying
 * @param fetchMissing pull any candles the database lacks from the exchange before analysing
 */
public record BacktestRequest(
        MarketKey market,
        Instant from,
        Instant to,
        AnalysisConfig config,
        boolean fetchMissing) {

    public BacktestRequest {
        if (from == null || to == null || !from.isBefore(to)) {
            throw new IllegalArgumentException("'from' must be before 'to'");
        }
        if (config == null) {
            config = AnalysisConfig.defaults();
        }
    }

    public static BacktestRequest of(String symbol, String timeframe, Instant from, Instant to) {
        return new BacktestRequest(new MarketKey(symbol, Timeframe.fromCode(timeframe)),
                from, to, AnalysisConfig.defaults(), false);
    }
}
