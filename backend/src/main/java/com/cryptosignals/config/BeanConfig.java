package com.cryptosignals.config;

import com.cryptosignals.analysis.LevelAnalysisEngineFactory;
import com.cryptosignals.exchange.ExchangeSettings;
import com.cryptosignals.exchange.binance.BinanceExchangeClient;
import com.cryptosignals.exchange.binance.BinanceMarket;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Configuration
public class BeanConfig {

    /**
     * The engine factory with its default strategies. Supplying different constructor arguments
     * here — a different level-discovery strategy, touch rule or breakout rule — swaps the
     * algorithm for the whole application without touching the engine.
     */
    @Bean
    public LevelAnalysisEngineFactory levelAnalysisEngineFactory() {
        return new LevelAnalysisEngineFactory();
    }

    /**
     * The markets to run, each with its own client: Binance spot and / or Binance USDⓈ-M futures.
     * Unknown names stop the application at startup.
     *
     * <p>Level ids carry the market, so spot and futures levels of the same coin never share an
     * id. Spot levels keep the ids they had before there was a choice of market.
     *
     * <p>Adding Bybit, OKX or Coinbase is one new {@link com.cryptosignals.exchange.ExchangeClient}
     * implementation and one case here; nothing else in the system knows which venue it is
     * talking to.
     */
    @Bean
    public Venues venues(ExchangeProperties properties, ObjectMapper mapper,
                         LevelAnalysisEngineFactory engineFactory) {
        List<Venue> venues = new ArrayList<>();
        for (String name : properties.getVenues()) {
            BinanceMarket market = BinanceMarket.byExchangeName(name).orElseThrow(() -> new IllegalStateException(
                    "Unsupported exchange '" + name + "'. Supported: "
                            + Arrays.stream(BinanceMarket.values()).map(BinanceMarket::exchangeName)
                            .collect(Collectors.joining(", "))));
            ExchangeSettings settings = properties.toSettings(market.defaultRestBaseUrl(),
                    market.defaultWsBaseUrl(), market.defaultMaxWeightPerMinute());
            venues.add(new Venue(market.exchangeName(), market.label(),
                    new BinanceExchangeClient(settings, mapper, market), settings,
                    engineFactory.withIdNamespace(market == BinanceMarket.SPOT ? "" : market.exchangeName())));
        }
        if (venues.isEmpty()) {
            throw new IllegalStateException("No market configured: set crypto-signals.exchange.venues, "
                    + "e.g. BINANCE_FUTURES,BINANCE");
        }
        return new Venues(venues);
    }
}
