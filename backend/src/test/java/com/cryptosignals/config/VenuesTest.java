package com.cryptosignals.config;

import com.cryptosignals.analysis.LevelAnalysisEngineFactory;
import com.cryptosignals.exchange.ExchangeClient;
import com.cryptosignals.exchange.ExchangeSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VenuesTest {

    private static Venue venue(String name, String label) {
        return new Venue(name, label, Mockito.mock(ExchangeClient.class), ExchangeSettings.defaults(),
                new LevelAnalysisEngineFactory());
    }

    private final Venues venues = new Venues(List.of(
            venue("BINANCE_FUTURES", "Binance USDⓈ-M futures"), venue("BINANCE", "Binance spot")));

    @Test
    @DisplayName("a request naming no market gets the first configured one")
    void defaultIsFirst() {
        assertThat(venues.primary().name()).isEqualTo("BINANCE_FUTURES");
        assertThat(venues.resolve(null).name()).isEqualTo("BINANCE_FUTURES");
        assertThat(venues.resolve(" ").name()).isEqualTo("BINANCE_FUTURES");
        assertThat(venues.all()).extracting(Venue::name).containsExactly("BINANCE_FUTURES", "BINANCE");
    }

    @Test
    @DisplayName("markets are found by name in any case; one not running is refused")
    void lookup() {
        assertThat(venues.resolve("binance").label()).isEqualTo("Binance spot");
        assertThat(venues.find(" Binance_Futures ")).map(Venue::name).contains("BINANCE_FUTURES");
        assertThat(venues.find("BYBIT")).isEmpty();
        assertThatThrownBy(() -> venues.resolve("BYBIT"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BINANCE_FUTURES, BINANCE");
        assertThat(venues.labelOf("BINANCE_FUTURES")).isEqualTo("Binance USDⓈ-M futures");
        assertThat(venues.labelOf("BYBIT")).isEqualTo("BYBIT");
    }

    @Test
    @DisplayName("at least one market, each once")
    void validation() {
        assertThatThrownBy(() -> new Venues(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Venues(List.of(venue("BINANCE", null), venue("binance", null))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(venue("BINANCE", null).label()).isEqualTo("BINANCE");
    }
}
