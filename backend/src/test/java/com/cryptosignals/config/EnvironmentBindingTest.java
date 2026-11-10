package com.cryptosignals.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every setting docker-compose passes as an environment variable actually reaches the application.
 *
 * <p>Spring matches an environment variable to a property only if every dash of the property name
 * is an underscore ({@code MIN_TOUCHES}) or every dash is dropped ({@code CRYPTOSIGNALS_..._MINTOUCHES}).
 * A mix of both, such as {@code CRYPTO_SIGNALS_ANALYSIS_MINTOUCHES}, is silently ignored, and the
 * setting quietly stays at its default.
 */
class EnvironmentBindingTest {

    /** A non-default value for every variable docker-compose sets. */
    private static final Map<String, String> SAMPLES = new LinkedHashMap<>();

    static {
        SAMPLES.put("CRYPTO_SIGNALS_MARKETS_SYMBOLS", "AUSDT,BUSDT");
        SAMPLES.put("CRYPTO_SIGNALS_MARKETS_TIMEFRAMES", "4h");
        SAMPLES.put("CRYPTO_SIGNALS_MARKETS_LOOKBACK", "7d");
        SAMPLES.put("CRYPTO_SIGNALS_MARKETS_VOLUME_SELECTION_ENABLED", "true");
        SAMPLES.put("CRYPTO_SIGNALS_MARKETS_VOLUME_SELECTION_MIN_DAILY_VOLUME", "123");
        SAMPLES.put("CRYPTO_SIGNALS_MARKETS_VOLUME_SELECTION_MAX_SYMBOLS", "7");
        SAMPLES.put("CRYPTO_SIGNALS_ANALYSIS_MIN_TOUCHES", "4");
        SAMPLES.put("CRYPTO_SIGNALS_ANALYSIS_TOUCH_TOLERANCE_PERCENT", "0.12");
        SAMPLES.put("CRYPTO_SIGNALS_ANALYSIS_BREAKOUT_TOLERANCE_PERCENT", "0.34");
        SAMPLES.put("CRYPTO_SIGNALS_ANALYSIS_MIN_CANDLES_BETWEEN_TOUCHES", "12");
        SAMPLES.put("CRYPTO_SIGNALS_ANALYSIS_CONFIRMATION_CANDLES", "2");
        SAMPLES.put("CRYPTO_SIGNALS_ANALYSIS_EMIT_SIGNAL_ON_EVERY_TOUCH_AFTER_MIN", "false");
        SAMPLES.put("CRYPTO_SIGNALS_ANALYSIS_SUPERSEDE_ENABLED", "false");
        SAMPLES.put("CRYPTO_SIGNALS_ANALYSIS_SUPERSEDE_NEARBY_LEVELS", "false");
        SAMPLES.put("CRYPTO_SIGNALS_ANALYSIS_SUPERSEDE_ON_PRICE_PASS", "false");
        SAMPLES.put("CRYPTO_SIGNALS_ANALYSIS_SUPERSEDE_MAX_DISTANCE_PERCENT", "15m:0.9");
        SAMPLES.put("CRYPTO_SIGNALS_NEXT_POTENTIAL_MIN_DISTANCE_PERCENT", "2.5");
        SAMPLES.put("CRYPTO_SIGNALS_NEXT_POTENTIAL_LOOKBACK", "10d");
        SAMPLES.put("CRYPTO_SIGNALS_API_ALLOWED_ORIGINS", "http://example.test");
    }

    private static StandardEnvironment environment(Map<String, Object> variables) {
        StandardEnvironment environment = new StandardEnvironment();
        // Spring's environment-variable name mapping only applies to the source of this name.
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        variables));
        ConfigurationPropertySources.attach(environment);
        return environment;
    }

    @Test
    @DisplayName("every CRYPTO_SIGNALS_ variable in docker-compose.yml is covered here")
    void coversCompose() throws IOException {
        String compose = Files.readString(Path.of("../docker-compose.yml"));
        Matcher m = Pattern.compile("(?m)^\\s+(CRYPTO_SIGNALS_[A-Z0-9_]+):").matcher(compose);
        Set<String> names = new LinkedHashSet<>();
        while (m.find()) {
            names.add(m.group(1));
        }

        assertThat(names).isNotEmpty();
        assertThat(SAMPLES.keySet()).containsAll(names);
    }

    @Test
    @DisplayName("each variable docker-compose sets changes its setting")
    void bindsEveryVariable() {
        StandardEnvironment environment = environment(Map.copyOf(SAMPLES));
        Binder binder = Binder.get(environment);

        MarketProperties markets = binder.bindOrCreate("crypto-signals.markets", MarketProperties.class);
        assertThat(markets.getSymbols()).containsExactly("AUSDT", "BUSDT");
        assertThat(markets.getTimeframes()).containsExactly("4h");
        assertThat(markets.getLookback()).isEqualTo(Duration.ofDays(7));
        assertThat(markets.getVolumeSelection().isEnabled()).isTrue();
        assertThat(markets.getVolumeSelection().getMinDailyVolume()).isEqualTo(123);
        assertThat(markets.getVolumeSelection().getMaxSymbols()).isEqualTo(7);

        AnalysisProperties analysis = binder.bindOrCreate("crypto-signals.analysis", AnalysisProperties.class);
        assertThat(analysis.getMinTouches()).isEqualTo(4);
        assertThat(analysis.getTouchTolerancePercent()).isEqualTo(0.12);
        assertThat(analysis.getBreakoutTolerancePercent()).isEqualTo(0.34);
        assertThat(analysis.getMinCandlesBetweenTouches()).isEqualTo(12);
        assertThat(analysis.getConfirmationCandles()).isEqualTo(2);
        assertThat(analysis.isEmitSignalOnEveryTouchAfterMin()).isFalse();
        assertThat(analysis.isSupersedeEnabled()).isFalse();
        assertThat(analysis.isSupersedeNearbyLevels()).isFalse();
        assertThat(analysis.isSupersedeOnPricePass()).isFalse();
        assertThat(analysis.getSupersedeMaxDistancePercent()).isEqualTo("15m:0.9");

        NextPotentialProperties nextPotential =
                binder.bindOrCreate("crypto-signals.next-potential", NextPotentialProperties.class);
        assertThat(nextPotential.getMinDistancePercent()).isEqualTo(2.5);
        assertThat(nextPotential.getLookback()).isEqualTo(Duration.ofDays(10));

        assertThat(environment.getProperty("crypto-signals.api.allowed-origins")).isEqualTo("http://example.test");
    }

    @Test
    @DisplayName("a run-together name is ignored, which is why the names above spell out every dash")
    void runTogetherNamesAreIgnored() {
        Binder binder = Binder.get(environment(Map.of(
                "CRYPTO_SIGNALS_ANALYSIS_MINTOUCHES", "4",
                "CRYPTO_SIGNALS_MARKETS_VOLUMESELECTION_ENABLED", "true")));

        assertThat(binder.bindOrCreate("crypto-signals.analysis", AnalysisProperties.class).getMinTouches())
                .isEqualTo(new AnalysisProperties().getMinTouches());
        assertThat(binder.bindOrCreate("crypto-signals.markets", MarketProperties.class)
                .getVolumeSelection().isEnabled()).isFalse();
    }
}
