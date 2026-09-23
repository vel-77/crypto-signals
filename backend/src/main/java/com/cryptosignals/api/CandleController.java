package com.cryptosignals.api;

import com.cryptosignals.api.dto.Dtos;
import com.cryptosignals.config.ExchangeProperties;
import com.cryptosignals.domain.Candle;
import com.cryptosignals.domain.MarketKey;
import com.cryptosignals.domain.Timeframe;
import com.cryptosignals.persistence.entity.MarketEntity;
import com.cryptosignals.persistence.jdbc.CandleJdbcRepository;
import com.cryptosignals.persistence.repository.MarketRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/candles")
@Tag(name = "Candles", description = "Stored OHLCV data, for charting")
public class CandleController {

    /**
     * Hard ceiling per request. The chart only ever needs a window, and an unbounded query against
     * the largest table in the system is the easiest way to take the service down.
     */
    private static final int MAX_LIMIT = 5_000;

    private final CandleJdbcRepository candles;
    private final MarketRepository markets;
    private final ExchangeProperties exchangeProperties;

    public CandleController(CandleJdbcRepository candles, MarketRepository markets,
                            ExchangeProperties exchangeProperties) {
        this.candles = candles;
        this.markets = markets;
        this.exchangeProperties = exchangeProperties;
    }

    @GetMapping
    @Operation(summary = "Stored candles for a market",
            description = "Defaults to the most recent 500 candles when no range is given.")
    public ResponseEntity<List<Dtos.CandleDto>> list(
            @Parameter(required = true, description = "e.g. BTCUSDT") @RequestParam String symbol,
            @Parameter(required = true, description = "e.g. 5m") @RequestParam String timeframe,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "500") int limit) {

        MarketKey key = new MarketKey(symbol, Timeframe.fromCode(timeframe));
        Optional<MarketEntity> market = markets.findByExchangeAndSymbolAndTimeframe(
                exchangeProperties.getName(), key.symbol(), key.timeframe().code());
        if (market.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        int effectiveLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);

        // With no range given, return the most recent candles that actually exist rather than a
        // wall-clock window, which comes back empty whenever ingestion is behind or the market
        // has gone quiet.
        List<Candle> found = (from == null && to == null)
                ? candles.findLatest(market.get().getId(), effectiveLimit)
                : candles.findRange(market.get().getId(),
                        from != null ? from : Instant.EPOCH,
                        to != null ? to : Instant.now(),
                        effectiveLimit);

        return ResponseEntity.ok(found.stream().map(Dtos.CandleDto::from).toList());
    }
}
