package com.cryptosignals;

import com.cryptosignals.config.AnalysisProperties;
import com.cryptosignals.config.ExchangeProperties;
import com.cryptosignals.config.MarketProperties;
import com.cryptosignals.config.NotificationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({
        AnalysisProperties.class,
        ExchangeProperties.class,
        MarketProperties.class,
        NotificationProperties.class
})
public class CryptoSignalsApplication {

    public static void main(String[] args) {
        SpringApplication.run(CryptoSignalsApplication.class, args);
    }
}
