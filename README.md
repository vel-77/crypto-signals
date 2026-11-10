# crypto-signals

Automatically discovers support and resistance levels in cryptocurrency markets and raises an alert
when a level has been tested three separate times without breaking.

**You do not tell it which price to watch.** It finds the levels itself from historical candles,
tracks every interaction with them, and signals on the third valid touch.

> Analysis and alerting only. This system places no orders and has no exchange keys.

```
🔥 NEW 3-TOUCH SIGNAL

Symbol: ETHUSDT
Timeframe: 5m

Type: SUPPORT

Level: 2,567.798
Zone: 2,565.2302 - 2,570.3658

Touches:
1. 2026-09-18 15:10 @ 2,565.59
2. 2026-09-20 03:00 @ 2,564.33
3. 2026-09-20 11:00 @ 2,567.52

Breakout: NO
Strength: 0.80

Signal generated:
2026-09-20 11:00 UTC
```

That example is real output, from live Binance data — touches 428 and 92 candles apart.

---

## Contents

| | |
|---|---|
| [**docs/ALGORITHM.md**](docs/ALGORITHM.md) | What the detection actually does, every parameter, and the measured calibration guide. **Start here.** |
| [docs/API.md](docs/API.md) | REST reference with worked examples |
| [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) | Running on a VPS, hardening, backups, upgrades |

---

## Quick start

Requires Docker with Compose. Nothing else.

```bash
cp .env.example .env          # works unedited; edit to add Telegram or change markets
docker compose up -d --build
```

| | |
|---|---|
| UI | <http://localhost:3000> |
| API | <http://localhost:8080/api> |
| Swagger | <http://localhost:8080/swagger-ui.html> |
| Health | <http://localhost:8080/actuator/health> |

First start downloads history and replays it, which takes a minute or two. Watch progress at
`GET /api/status`; it reports `bootstrap: READY` when live streaming has taken over.

### Running without Docker

```bash
# database
docker run -d --name cs-pg -p 5432:5432 \
  -e POSTGRES_DB=cryptosignals -e POSTGRES_USER=cryptosignals -e POSTGRES_PASSWORD=cryptosignals \
  postgres:16-alpine

# backend (Java 21)
cd backend && ./mvnw spring-boot:run     # or: mvn spring-boot:run

# frontend (Node 20+), proxies /api to localhost:8080
cd frontend && npm install && npm run dev
```

---

## How it works

```
Binance REST  ─┐
               ├─→  market-data  ─→  candles (PostgreSQL)  ─→  analysis engine  ─→  signals
Binance WS    ─┘    backfill,          upsert-keyed,            swing → cluster →     dedup,
                    gap healing        streamed, never          touch → breakout      persist,
                                       fully in memory          → signal              notify
```

1. **Backfill.** Historical candles are downloaded and stored, holes are detected and repaired.
2. **Replay.** Stored candles run through a fresh engine, one at a time, discovering levels and
   their historical touches. Signals found here are marked `historical` and are not sent to chat.
3. **Live.** A websocket takes over. Only *closed* candles reach the engine; partial ones are
   dropped. A reconnect that missed candles is healed from REST before processing resumes.
4. **Signal.** On a level's third valid touch, a signal is persisted and dispatched — once.

The engine holds no state that is not derived from candles. A restart rebuilds it by replaying, and
because every identifier is content-derived, the replay converges on the existing rows rather than
duplicating them.

---

## Configuration

Everything lives in [`backend/src/main/resources/application.yml`](backend/src/main/resources/application.yml)
and can be overridden by environment variable. Nothing is hardcoded.

```yaml
crypto-signals:
  markets:
    symbols: [BTCUSDT, ETHUSDT]   # always watched
    volume-selection:             # ... plus the most traded other coins (on in docker-compose)
      enabled: false
      min-daily-volume: 5000000   # USDT a day, averaged over the last 7 days
      max-symbols: 50             # coins in all, fixed ones included
      keep-percent: 70            # a watched coin stays until it falls below 70% of the threshold
    timeframes: [15m, 30m, 1h]    # each coin x timeframe is an independent analysis
    lookback: 30d                 # 24h / 7d / 30d / 365d; 0 = all available history

  analysis:
    min-touches: 3                # the "3" in 3-touch
    touch-tolerance-percent: 0.1  # 100000 -> zone 99900..100100
    breakout-tolerance-percent: 0.2
    min-candles-between-touches: 10
    min-departure-percent: 0.3    # how far price must travel away between touches
    confirmation-candles: 1
    close-beyond-level: true      # a lone wick does not break a level
    wick-break-allowed: false
    emit-signal-on-every-touch-after-min: true  # also signal on touch 4, 5, ...
    supersede-enabled: true                     # both supersede rules
    supersede-nearby-levels: true               # keep only the outermost of close-together levels
    supersede-on-price-pass: true               # the "nearest level" rule
    supersede-max-distance-percent: "15m:0.5,30m:0.6,1h:0.75,4h:1.5"
  next-potential:
    min-distance-percent: 1.0     # next potential low/high: ignore swings closer than this
    lookback: 30d                 # how far back before the signal to look
```

The web UI adds per-viewer time zones (saved in each browser), multi-select filters including a
touch-count filter, and a live chart fed directly from Binance with a measuring tool. Every signal
shows its **next potential low** (support) or **high** (resistance): the nearest earlier swing at
least 1% beyond the signal price, as a percentage, in the list, in the signal's detail and on its
chart.

**Which coins.** The `symbols` are always watched. With volume selection on, the most traded other
Binance USDT coins are added: those averaging at least 5M USDT a day over the last 7 days, highest
first, up to 50 coins in all. Stablecoins, tokenized stocks, gold, fiat and wrapped coins are
skipped (Binance's own tags say which). The list is checked daily while running, so new coins are
started and fading ones stopped without a restart; a dropped coin's open levels are marked
`EXPIRED`, its history stays. `GET /api/watchlist` and the config tab show each coin and why it is
there. Each coin adds about three markets' worth of memory and start-up time, so raise
`MAX_SYMBOLS` with the server in mind.

`GET /api/config` returns exactly what the running engine is using, including a fingerprint that
every stored level and signal is tagged with. Secrets are never included.

Environment variables follow Spring's rule: `CRYPTO_SIGNALS_ANALYSIS_MIN_TOUCHES`, with every dash
of the property name as an underscore. A run-together `..._MINTOUCHES` is silently ignored, so
`EnvironmentBindingTest` checks every variable docker-compose passes.

> ⚠️ **The defaults suit ~5m candles and are too tight for 4h and above.** On BTC 4h they give a
> median hold of 2 candles; ATR-based tolerance gives 65. See the measured calibration table in
> [docs/ALGORITHM.md §12](docs/ALGORITHM.md#12--calibration--measured-not-guessed).

### Telegram

Create a bot with [@BotFather](https://t.me/BotFather), get your chat id from
[@userinfobot](https://t.me/userinfobot), then in `.env`:

```bash
TELEGRAM_ENABLED=true
TELEGRAM_BOT_TOKEN=123456:ABC-DEF...
TELEGRAM_CHAT_ID=123456789
```

Signals are written to the database *before* delivery is attempted and marked delivered only once a
channel accepts them, so an outage or a restart mid-send results in a retry rather than a lost
alert. Adding Discord, email or a webhook means implementing `Notifier`.

---

## Backtesting

The backtester runs the **same engine** over history, so a result predicts live behaviour rather
than approximating it. Nothing is written to the levels or signals tables.

```bash
java -jar backend/target/crypto-signals-1.0.0.jar --backtest \
  --symbol=BTCUSDT --timeframe=1h \
  --from=2025-03-01 --to=2025-09-01 --fetch \
  --format=json --out=./signals.json
```

```
BTCUSDT
1h
2025-03-01 00:00 -> 2025-09-01 00:00

Candles analysed:      4417
Levels discovered:     589
Valid 3-touch levels: 71
Signals:               71 (39 resistance, 32 support)
Levels broken later:   69 of 71 signalled levels eventually broke
Still unbroken at end: 2  (3%)
Candles held after signal (p25 / median / p75): 4 / 17 / 45
Broken levels:         545
```

Also available over HTTP, with `format=json|csv|report`:

```bash
curl -X POST 'http://localhost:8080/api/backtest?symbol=BTCUSDT&timeframe=5m&from=2025-01-01&format=csv'
```

Export the CSV and compare the levels against the same chart in your own tool. That comparison is
the point: it is the only way to tell whether the encoded rules match what you actually mean.

---

## Architecture

```
domain/         Candle, Timeframe, MarketKey, enums — pure, no framework
analysis/       the detection engine and its pluggable strategies — no Spring, no database
exchange/       ExchangeClient abstraction + Binance REST and websocket
persistence/    Flyway schema, JPA entities, JDBC candle store
marketdata/     backfill, gap healing, replay, live ingestion
notifications/  Notifier abstraction + Telegram and logging channels
backtest/       historical replay, reporting, JSON/CSV export
api/            REST controllers and DTOs
```

Two boundaries matter most, and both are **enforced by tests** rather than by convention
([`ArchitectureTest`](backend/src/test/java/com/cryptosignals/arch/ArchitectureTest.java)):

- **`analysis` depends on nothing but `domain`.** No Spring, no JPA, no exchange. The engine is
  exercisable from a plain unit test with a list of candles.
- **Nothing above `exchange` names Binance.** Everything goes through `ExchangeClient`; only the
  bean wiring mentions a concrete venue.

The rules also forbid package cycles — which caught a real one during development, where the
exchange client read Spring configuration properties that in turn constructed it.

### Swapping the algorithm

The detection engine is assembled from strategies. Supplying different ones in
[`BeanConfig`](backend/src/main/java/com/cryptosignals/config/BeanConfig.java) changes the
algorithm without touching the engine:

```java
new LevelAnalysisEngineFactory(
    new SwingClusterLevelStrategy(new FractalSwingDetector()),  // LevelDetectionStrategy
    new SeparatedInteractionTouchDetector(),                    // TouchDetector
    new ConfirmedBreakoutDetector(),                            // BreakoutDetector
    new ConfigurableToleranceCalculator(),                      // ToleranceCalculator
    new WeightedStrengthCalculator());                          // StrengthCalculator
```

`LevelDetectionStrategy` decides **where levels are**; the engine decides **what happens to them**,
identically for every strategy. A volume-profile or round-number discovery algorithm is a new
implementation of one method.

---

## Testing

```bash
cd backend
mvn test                                        # 186 unit tests, no Docker needed
mvn verify                                      # + 29 integration tests (real PostgreSQL)
mvn test -Dgroups=external -Dtest.excluded.groups=   # + 5 live Binance API tests
```

| Suite | Covers |
|---|---|
| Engine behaviour | discovery, support and resistance, touch separation, breakouts, duplicate suppression, determinism, **price scale independence across six orders of magnitude**, every timeframe, malformed input |
| Component units | swing detection incl. plateaus, the touch state machine, breakout confirmation runs, tolerance modes, config validation, ATR, ring buffer, id stability |
| Integration | Flyway/JPA agreement, candle round-trip at 1e-9 prices, upsert idempotency, cursor streaming, gap detection, replay safety, the full REST surface |
| Architecture | module boundaries and absence of cycles |
| External | live Binance REST paging and websocket delivery |

Integration tests use a real PostgreSQL via Testcontainers, not an in-memory substitute: the schema
relies on `NUMERIC(38,18)`, `JSONB`, `ON CONFLICT` upserts, a partial index and a window function.
Starting the context is itself an assertion — Hibernate runs with `ddl-auto: validate`, so the build
fails if the migrations and the entity mappings ever disagree.

---

## Edge cases handled

| Situation | Behaviour |
|---|---|
| Duplicate candle | Ignored; counted. Replaying an overlapping range is harmless. |
| Out-of-order candle | Rejected, state untouched — applying it would corrupt every state machine. |
| Missing candles | Detected in one indexed pass, refetched per gap rather than re-downloading history. |
| Websocket disconnect | Exponential backoff reconnect; the gap it left is healed from REST before processing resumes. |
| Websocket dies silently | A network cut can leave a socket "open" with no close or error ever arriving. A watchdog treats 90 s without data as dead and reconnects, and the 15-minute reconciliation feeds any candles the engine missed through the analysis. |
| Partial candle | Dropped at the boundary — the engine only ever sees closed candles. |
| Rate limits | Weight-aware sliding-window limiter, reconciled against Binance's own header; 429/418 honoured with `Retry-After`. |
| Telegram failure | Signal stays queued in the database and is retried; never delivered twice. |
| Application restart | Engine rebuilt by replay; content-derived ids make it converge, not duplicate. |
| Database restart | Connection pool reconnects; the next reconciliation sweep catches up. |
| Extreme prices | Every threshold is relative. Verified from 0.000099 to 1,000,000. |
| Level broken then reforms | Broken levels are terminal; a new structure at that price becomes a new level with its own count. |
| Two levels close together | Established levels (3+ touches) whose neighbouring gaps are within the timeframe's limit form one group; only the highest resistance / lowest support stays active, the rest are **superseded**. A level price passes on its way to a nearer one is superseded too (see docs/ALGORITHM.md). |
| Coin leaves the volume watch list | Its markets stop, their open levels become `EXPIRED`; a coin just under the threshold keeps its place, so the list does not flap. If Binance cannot be asked, the stored list is kept and retried in 15 minutes. |
| Restart with a moving lookback window | Stored levels the rebuilt engine no longer follows are marked `EXPIRED` instead of showing as active forever. |

---

## Licence

Provided as-is for analysis and research. Not financial advice.
