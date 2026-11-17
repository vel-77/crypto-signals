package com.cryptosignals.config;

import com.cryptosignals.analysis.LevelAnalysisEngineFactory;
import com.cryptosignals.exchange.ExchangeClient;
import com.cryptosignals.exchange.ExchangeSettings;
import com.cryptosignals.exchange.binance.BinanceExchangeClient;
import com.cryptosignals.exchange.binance.BinanceMarket;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.stream.Collectors;

@Configuration
public class BeanConfig {

    /** The configured venue; unknown names stop the application at startup. */
    private static BinanceMarket venue(ExchangeProperties properties) {
        return BinanceMarket.byExchangeName(properties.getName()).orElseThrow(() -> new IllegalStateException(
                "Unsupported exchange '" + properties.getName() + "'. Supported: "
                        + Arrays.stream(BinanceMarket.values()).map(BinanceMarket::exchangeName)
                        .collect(Collectors.joining(", "))));
    }

    /** Connectivity settings, with the venue's own addresses and weight budget wherever none is set. */
    @Bean
    public ExchangeSettings exchangeSettings(ExchangeProperties properties) {
        BinanceMarket venue = venue(properties);
        return properties.toSettings(venue.defaultRestBaseUrl(), venue.defaultWsBaseUrl(),
                venue.defaultMaxWeightPerMinute());
    }

    /**
     * Selects the active venue: Binance spot or Binance USDⓈ-M futures.
     *
     * <p>Adding Bybit, OKX or Coinbase is one new {@link ExchangeClient} implementation and one
     * case here; nothing else in the system knows which venue it is talking to.
     */
    @Bean
    public ExchangeClient exchangeClient(ExchangeProperties properties, ExchangeSettings settings,
                                         ObjectMapper mapper) {
        return new BinanceExchangeClient(settings, mapper, venue(properties));
    }

    /**
     * The engine factory with its default strategies. Supplying different constructor arguments
     * here — a different level-discovery strategy, touch rule or breakout rule — swaps the
     * algorithm for the whole application without touching the engine.
     *
     * <p>Level ids carry the venue, so spot and futures levels never share an id. Spot levels keep
     * the ids they had before there was a choice of venue.
     */
    @Bean
    public LevelAnalysisEngineFactory levelAnalysisEngineFactory(ExchangeProperties properties) {
        BinanceMarket venue = venue(properties);
        return new LevelAnalysisEngineFactory()
                .withIdNamespace(venue == BinanceMarket.SPOT ? "" : venue.exchangeName());
    }
}
