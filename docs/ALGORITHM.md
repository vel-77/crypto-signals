# Detection algorithm

This document is the specification of what the system actually does. Every rule here is
configurable, every default is stated, and every judgement call is called out as a judgement call.

---

## 1. The ambiguity, stated plainly

The business requirement is:

> price reached the same number three times without passing it

Read literally this is not implementable. Four separate things are underspecified:

| Question | Why it has no obvious answer |
|---|---|
| **What is "the same number"?** | Price is continuous. It never returns to exactly 100000.00000000. Some tolerance is unavoidable, and its width changes which structures exist at all. |
| **What is "reached"?** | A wick that pierces the level for two seconds, a candle that closes inside the zone, and a candle whose body sits on it are three different events. |
| **What makes two touches separate?** | Forty consecutive candles resting on a level is visually *one* test. Two tests a month apart are clearly *two*. The boundary between those is a choice. |
| **What is "without passing it"?** | A level pierced by 0.01% for one candle has not been broken. Pierced by 5% for a day, it has. |

There is no universally correct answer, so this implementation makes a **specific, documented,
configurable** choice for each, and structures the code so a different choice is a configuration
change or a strategy swap — never a rewrite.

---

## 2. The chosen interpretation (v1)

> A **level** is a price zone built from clustered swing highs or swing lows.
> A **touch** is one *interaction* — a run of candles entering the zone — that is separated from the
> previous interaction by distance, by candle count, and optionally by time.
> A level is **broken** when price closes beyond a threshold set outside the zone, for a configured
> number of consecutive candles.
> A **signal** fires on the Nth valid touch of a level that has not been broken, exactly once.

---

## 3. Execution model

Candles are fed to the engine **one closed candle at a time, in chronological order**, and the
engine never looks ahead. This single decision buys three properties at once:

- **Backtest equals live.** There is no separate historical code path to drift out of sync, so a
  backtest is a real prediction of live behaviour rather than an approximation of it.
- **Determinism.** The same candles and the same configuration produce identical levels, touches,
  signals and *identifiers*, every time.
- **Cheap restarts.** Engine state is never persisted. On startup it is rebuilt by replaying stored
  candles, so a restart can never resume from a half-written or version-skewed snapshot.

Per candle, in this order:

1. Reject duplicates and out-of-order candles.
2. Append to the bounded window; update ATR.
3. For each active level: re-derive its zone → test for breakout → advance its touch state machine
   → consider emitting a signal.
4. Run the discovery strategy, which may create new levels from swings this candle confirmed.
5. Expire and prune levels.

> **Why breakouts are evaluated before touches.** A candle that decisively clears a level should be
> recorded as the break, not as one more touch of a level that no longer exists.

---

## 4. Step 1 — Swing detection

Candle `p` is a **swing high** when its high dominates `swingLeftBars` candles before it and
`swingRightBars` candles after it. Symmetrically for swing lows.

The left comparison is **strict** (`>`) and the right is **non-strict** (`>=`). For a run of candles
sharing the same high this selects exactly the **first** one. Without that asymmetry a flat top
yields either no swing at all or several duplicates, and the result depends on tie-breaking order.

**Confirmation latency is real and deliberate.** A swing at index `p` is only knowable at index
`p + swingRightBars`. The engine reports it then, and records both indices. This is why live and
historical detection agree: neither is allowed to use information the other would not have had.

An outside bar can legitimately be both a swing high and a swing low; both are emitted.

---

## 5. Step 2 — Clustering swings into levels

A confirmed swing either joins an existing active level of the same role within
`clusterTolerancePercent` of its price, or creates a new level anchored at itself.

When several levels are in range the **nearest** wins, which makes assignment independent of level
creation order and therefore deterministic.

A level's price is re-derived from all its member swings (`MEAN` by default, or `MEDIAN` /
`EXTREME`). Repeated tests therefore *sharpen* the estimate instead of spawning near-duplicate
levels beside it.

**A newly created level is replayed** over the candles between its swing and the present. That
replay is what makes the swing itself count as the level's first touch. It is bounded by
`swingRightBars`, so it costs a handful of iterations.

> **Deliberate consequence.** Touches are evaluated against the zone *as it stood at the time*.
> When a later swing shifts the level slightly, already-recorded touches are not re-evaluated.
> Rewriting history would break determinism and could retract a signal that had already been sent.
> `swingCount` and `touchCount` are therefore separate numbers, and may legitimately differ.

---

## 6. Step 3 — The zone ("the same price")

```
zone = levelPrice ± halfWidth
```

| `toleranceMode` | `halfWidth` |
|---|---|
| `PERCENT` (default) | `levelPrice × touchTolerancePercent / 100` |
| `ATR` | `atr × atrToleranceMultiplier` |
| `MAX_OF_BOTH` | the larger of the two |

The worked example from the requirements:

```
level     = 100000
tolerance = 0.1%
zone      = 99900 .. 100100
```

**Every mode is scale-free.** A percentage is relative by definition and ATR is denominated in the
instrument's own units, so 0.1% behaves identically on a $0.0001 coin and on a $100,000 one. This is
verified by a parameterised test that runs the same pattern across six orders of magnitude and
asserts identical touch indices and signalling candles.

ATR modes fall back to the percentage until the ATR has warmed up, so startup never produces
zero-width zones.

---

## 7. Step 4 — Touches, and what makes them separate

**A touch is not a candle — it is an interaction.**

```
ARMED      --price enters zone-->  IN_TOUCH     (a new touch is recorded)
IN_TOUCH   --price stays in zone-> IN_TOUCH     (same touch; extreme updated)
IN_TOUCH   --price leaves zone---> DEPARTING    (same touch, still open)
DEPARTING  --price re-enters-----> IN_TOUCH     (same touch resumes — NOT a new touch)
DEPARTING  --separation met------> ARMED        (touch closed; next entry is a new touch)
```

Twelve consecutive candles resting on a level are therefore **one** touch, which is the rule the
requirements demand.

`touchMode` decides what "enters the zone" means:

| Mode | Condition | Character |
|---|---|---|
| `WICK` (default) | the candle's high–low range overlaps the zone | most permissive |
| `BODY` | the candle's open–close body overlaps | ignores long rejection wicks |
| `CLOSE` | the candle's close is inside the zone | most conservative |

Separation requires **all** configured criteria simultaneously:

- `minCandlesBetweenTouches` — candles spent *fully outside* the zone (default 10)
- `minDeparturePercent` — how far price travelled away from the level, as a percentage: downwards
  for resistance, upwards for support (default 0.3%)
- `minTimeBetweenTouches` — optional wall-clock separation (default off). Mostly redundant with the
  candle count, but it matters when the series contains gaps from exchange downtime.

Requiring all of them together is the conservative reading: a shallow drift out of the zone for a
few candles is one continuing test, not two.

**Touches do not have to be near each other in time.** Touch 1 a year ago and touches 2 and 3 today
is a perfectly valid signal — in live testing, a real ETHUSDT support signalled on touches 428 and
92 candles apart.

---

## 8. Step 5 — Breakouts

```
resistance: threshold = levelPrice × (1 + breakoutTolerancePercent / 100)
support:    threshold = levelPrice × (1 − breakoutTolerancePercent / 100)
```

A candle qualifies when `(wickBreakAllowed AND wick beyond) OR (closeBeyondLevel AND close beyond)`.
The breakout confirms only after `confirmationCandles` **consecutive** qualifying candles; the run
counter resets on the first candle that fails.

By default `closeBeyondLevel = true` and `wickBreakAllowed = false`, so **a lone wick past the level
is not a breakout** — the behaviour the requirements explicitly ask for. A verified example: a wick
0.9% above a level, well past the 0.2% threshold, closing back inside, leaves the level `ACTIVE` and
its signal valid.

Two configurations are rejected at startup rather than producing silently useless behaviour:

- `breakoutTolerancePercent < touchTolerancePercent` — every touch would instantly be a breakout.
- `closeBeyondLevel = false` **and** `wickBreakAllowed = false` — no level could ever break.

**After a breakout** the level is terminal (`BROKEN`) and stops accumulating touches. It is also
excluded from swing matching, so a new structure forming at that price becomes a genuinely new level
with its own identity and its own touch count. Setting `enableRoleFlip = true` additionally seeds an
opposite-role level at the same price immediately (broken resistance becomes support). That is off
by default because it is a trading opinion, not a mechanical consequence of the data.

---

## 9. Step 6 — Signals

A signal fires when a level reaches `minTouches` (default 3) valid touches, is `ACTIVE`, meets
`minLevelStrength`, and has not already signalled.

`signalTrigger` decides the moment:

| Value | Fires | Trade-off |
|---|---|---|
| `ON_TOUCH` (default) | as price enters the zone for the Nth time, provided no breakout confirms on that candle | timely; the touch may still fail immediately, in which case the signal becomes `INVALIDATED` |
| `ON_CONFIRMED_REJECTION` | once the Nth interaction has completed and price has demonstrably rejected away | confirmed, but later |

`ON_TOUCH` is the default because this is an alerting system: an alert that arrives after the move
has already happened is of limited use. The cost is explicit in the data — signals carry a status,
and one invalidated by a later breakout says so.

### Duplicate suppression

A level emits **once**. Touches 4, 5 and 6 do not re-emit, and neither does resting on the level for
thirty more candles. Enforced in three independent places:

1. in-memory, by the level's `lastSignalledTouchCount`;
2. in the database, by a unique constraint on `(level_id, touch_count)`;
3. by deriving the signal id from `(levelId, touchCount)`, so a replay produces the *same* id and
   upserts rather than inserting.

Setting `emitSignalOnEveryTouchAfterMin = true` re-arms it for each additional distinct touch.

---

## 10. Strength

A 0..1 score blending three scale-free components with configurable weights:

| Component | Meaning | Saturates at |
|---|---|---|
| Touch count | more tests, more respected | `minTouches + 2` |
| Rejection quality | mean distance price travelled away after its touches | 3 zone half-widths |
| Time span | candles from creation to the latest touch | 500 candles |

Used as an optional gate via `minLevelStrength`, and exposed on every level and signal for ranking.

---

## 11. Determinism

Guaranteed by construction, and asserted by tests:

- Single-threaded per market, strictly sequential, no look-ahead.
- No floating-point equality anywhere; every comparison is tolerance-based.
- `double` arithmetic is deterministic for a fixed operation order, which sequential processing
  guarantees. `double` carries ~15–16 significant digits — ample for 1e-9 to 1e7 prices. Storage
  uses `NUMERIC(38,18)`; conversion happens only at the persistence boundary.
- Identifiers are content-derived (UUIDv3 over a canonical string), never counters or random.
- Level pruning uses a total ordering with the id as final tie-break.

A test asserts that two runs over the same series produce byte-identical results including ids, and
another asserts that feeding candles one at a time matches a batch run exactly.

---

## 12. Calibration — measured, not guessed

**The shipped defaults are calibrated for roughly 5-minute candles and are too tight for high
timeframes.** This was measured, not assumed. BTCUSDT 4h, one year of real Binance data:

| Configuration | Levels | Signals | Median candles held after signal |
|---|---|---|---|
| `PERCENT` 0.1 / 0.2 (shipped default) | 370 | 24 | **2** |
| `PERCENT` 0.5 / 1.5 | 203 | 36 | 23 |
| `MAX_OF_BOTH` atr 0.5, breakout 1.5 | 203 | 38 | 36 |
| `MAX_OF_BOTH` atr 0.75, breakout 2.0, confirm 2 | 156 | 43 | **65** |

A 0.2% breakout tolerance is meaningless on a 4h BTC candle — price routinely moves that far inside
a single bar, so almost every level "breaks" immediately and levels barely exist before dying.

### Suggested starting points

| Timeframe | touch % | breakout % | cluster % | departure % | confirm | mode |
|---|---|---|---|---|---|---|
| 1m – 5m | 0.10 | 0.20 | 0.15 | 0.30 | 1 | `PERCENT` |
| 15m – 1h | 0.20 | 0.60 | 0.30 | 0.60 | 1 | `MAX_OF_BOTH` |
| 4h – 1d | 0.50 | 2.00 | 0.75 | 2.00 | 2 | `MAX_OF_BOTH` |

`MAX_OF_BOTH` is recommended above 15m precisely because ATR adapts to the timeframe automatically,
which is the problem these numbers are working around.

**Calibrate against your own eye.** Run a backtest, export the CSV, and check the levels against the
same chart. That comparison is the only thing that can tell you whether the encoded rules match the
intent behind them, and it is why the backtester exists.

### Reading the backtest numbers

`signalsWhoseLevelLaterBroke` is **not** a false-positive count. A support respected for two days
that then gives way did its job. Over a long window almost every level eventually breaks, so that
count says more about the window length than about the algorithm. The percentile fields
(`p25 / median / p75 candles held after signal`) are the honest measure.

---

## 13. Known limitations

Stated openly rather than discovered later:

- **Swing-based discovery only.** Volume profile, round-number magnets, session highs and lows and
  order blocks are not considered. This is the intended extension point: implement
  `LevelDetectionStrategy`.
- **Confirmation lag.** A level cannot be discovered until `swingRightBars` candles after its swing
  printed. Unavoidable without look-ahead; the trade for backtest/live equivalence.
- **Levels never merge.** Two levels that drift within a zone-width of each other stay separate.
- **No cross-timeframe confluence.** A 1h and a 5m level at the same price are unrelated, by design
  (the requirements ask for independent signals per timeframe).
- **Pruning can affect results.** `maxActiveLevelsPerMarket` (default 250) bounds memory. If a
  market legitimately has more simultaneous levels, the weakest are dropped and results differ from
  an unbounded run. Raise it if a backtest reports levels near the cap.
- **Historical replay uses the current config.** Changing a rule changes level ids, so old rows
  remain under their old `configFingerprint`. They are traceable, not automatically migrated.
