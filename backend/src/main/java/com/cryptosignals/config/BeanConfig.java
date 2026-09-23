package com.cryptosignals.config;

import com.cryptosignals.analysis.LevelAnalysisEngineFactory;
import com.cryptosignals.exchange.ExchangeClient;
import com.cryptosignals.exchange.binance.BinanceExchangeClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BeanConfig {

    /**
     * Selects the active venue.
     *
     * <p>Adding Bybit, OKX or Coinbase is one new {@link ExchangeClient} implementation and one
     * case here; nothing else in the system knows which venue it is talking to.
     */
    @Bean
    public ExchangeClient exchangeClient(ExchangeProperties properties, ObjectMapper mapper) {
        String name = properties.getName() == null ? "" : properties.getName().trim().toUpperCase();
        return switch (name) {
            case BinanceExchangeClient.NAME -> new BinanceExchangeClient(properties.toSettings(), mapper);
            default -> throw new IllegalStateException(
                    "Unsupported exchange '" + properties.getName() + "'. Supported: BINANCE");
        };
    }

    /**
     * The engine factory with its default strategies. Supplying different constructor arguments
     * here — a different level-discovery strategy, touch rule or breakout rule — swaps the
     * algorithm for the whole application without touching the engine.
     */
    @Bean
    public LevelAnalysisEngineFactory levelAnalysisEngineFactory() {
        return new LevelAnalysisEngineFactory();
    }
}
