package com.cryptosignals.exchange.binance;

import com.cryptosignals.exchange.ExchangeSettings;
import com.cryptosignals.domain.Candle;
import com.cryptosignals.domain.MarketKey;
import com.cryptosignals.domain.Timeframe;
import com.cryptosignals.exchange.StreamSubscription;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Talks to the live Binance API. Excluded from normal builds — a third party being slow or
 * unreachable must never fail this project's build. Run deliberately with:
 * {@code mvn test -Dgroups=external -Dtest.excluded.groups=}
 */
@Tag("external")
@DisplayName("live Binance API")
class BinanceLiveApiExternalTest {

        private final ObjectMapper mapper = new ObjectMapper();

    private BinanceExchangeClient client() {
            return new BinanceExchangeClient(ExchangeSettings.defaults(), mapper);
        }

        @Test
        @DisplayName("reports server time close to ours")
        void serverTime() {
            Instant serverTime = client().serverTime();

            assertThat(Duration.between(serverTime, Instant.now()).abs())
                    .isLessThan(Duration.ofMinutes(5));
        }

        @Test
        @DisplayName("reports 7-day volumes and tags of the USDT pairs")
        void tradingActivity() {
            List<com.cryptosignals.exchange.SymbolActivity> activity =
                    client().tradingActivity("USDT", Duration.ofDays(7));

            assertThat(activity).hasSizeGreaterThan(100);
            assertThat(activity).filteredOn(a -> a.symbol().equals("BTCUSDT")).singleElement()
                    .satisfies(btc -> {
                        assertThat(btc.baseAsset()).isEqualTo("BTC");
                        assertThat(btc.quoteVolume()).isGreaterThan(1e8);
                        assertThat(btc.priceRangePercent()).isPositive();
                    });
            assertThat(activity).filteredOn(a -> a.symbol().equals("USDCUSDT")).singleElement()
                    .satisfies(usdc -> assertThat(usdc.tags()).contains("stablecoin"));
        }

        @Test
        @DisplayName("knows which symbols are tradable")
        void symbolSupport() {
            BinanceExchangeClient client = client();

            assertThat(client.supportsSymbol("BTCUSDT")).isTrue();
            assertThat(client.supportsSymbol("NOTAREALCOIN123")).isFalse();
        }

        @Test
        @DisplayName("fetches ordered, closed, gap-free recent candles")
        void fetchesLatestCandles() {
            List<Candle> candles = client().fetchLatestCandles("BTCUSDT", Timeframe.M5, 50);

            assertThat(candles).hasSizeBetween(45, 50);
            long now = System.currentTimeMillis();
            for (int i = 0; i < candles.size(); i++) {
                assertThat(candles.get(i).closeTime()).isLessThan(now);
                if (i > 0) {
                    assertThat(candles.get(i).openTime())
                            .isEqualTo(candles.get(i - 1).openTime() + Timeframe.M5.durationMillis());
                }
            }
        }

        @Test
        @DisplayName("pages across a range larger than one request")
        void pagesLongRanges() {
            Instant to = Instant.now().minus(Duration.ofHours(1));
            Instant from = to.minus(Duration.ofDays(2));   // ~2880 one-minute candles: 3 pages

            List<Candle> candles = client().fetchCandles("BTCUSDT", Timeframe.M1, from, to, 5000);

            assertThat(candles).hasSizeGreaterThan(2500);
            assertThat(candles).extracting(Candle::openTime).doesNotHaveDuplicates();
            assertThat(candles).isSortedAccordingTo(
                    (a, b) -> Long.compare(a.openTime(), b.openTime()));
        }

        @Test
        @DisplayName("receives live candles over the websocket")
        void streamsLiveCandles() throws InterruptedException {
            CountDownLatch latch = new CountDownLatch(1);
            List<Candle> received = new ArrayList<>();

            try (StreamSubscription subscription = client().streamCandles(
                    List.of(MarketKey.of("BTCUSDT", "1m")), (market, candle, closed) -> {
                        synchronized (received) {
                            received.add(candle);
                        }
                        latch.countDown();
                    })) {

                assertThat(latch.await(30, TimeUnit.SECONDS))
                        .as("expected at least one live candle update within 30s")
                        .isTrue();
                assertThat(subscription.isConnected()).isTrue();
            }
        }
    }
