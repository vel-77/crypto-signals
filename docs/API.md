# REST API

Base URL `http://localhost:8080/api`. Interactive docs at `/swagger-ui.html`, OpenAPI JSON at
`/v3/api-docs`.

All timestamps are ISO-8601 UTC. Prices are JSON numbers with full stored precision
(`NUMERIC(38,18)`), so a client that needs exactness on a nine-decimal asset gets it.

---

## Conventions

**Filters are optional and combine.** An omitted or empty parameter contributes no constraint.
List filters take several values, comma-separated or repeated (`symbol=BTCUSDT,SOLUSDT` or
`symbol=BTCUSDT&symbol=SOLUSDT`). Values **within** one filter are OR-ed; **different** filters are
AND-ed.

**Listings are paged** and return:

```json
{ "items": [ ... ], "page": 0, "size": 50, "totalItems": 137, "totalPages": 3 }
```

Paging and sorting use standard Spring Data parameters: `page`, `size`, `sort=field,asc|desc`.

**Errors** are structured, never stack traces:

```json
{ "error": "bad_request", "message": "Unsupported timeframe '7q'. Supported: [1m, 3m, ...]",
  "timestamp": "2026-09-20T11:45:02.117Z" }
```

| Status | Meaning |
|---|---|
| `400` | invalid parameter |
| `404` | unknown id, or a market that has never been analysed |
| `502` | the exchange was unreachable (upstream problem, not a client error) |

---

## Signals

### `GET /api/signals`

| Parameter | Values |
|---|---|
| `symbol` | one or more, e.g. `BTCUSDT,SOLUSDT` |
| `timeframe` | one or more, e.g. `15m,1h` |
| `type` | `SUPPORT` and/or `RESISTANCE` |
| `status` | one or more of `ACTIVE`, `SUPERSEDED`, `INVALIDATED`, `EXPIRED` |
| `touches` | exact touch counts, e.g. `4,5` |
| `touchesAtLeast` | also include anything with at least this many touches (OR-ed with `touches`) |
| `historical` | `true` \| `false` — found during replay rather than live |
| `from`, `to` | ISO instants, filter on `detectedAt` |

Default sort is `detectedAt` descending. Touches are omitted from list responses to avoid a query
per row; fetch a single signal for those.

```bash
curl 'http://localhost:8080/api/signals?symbol=BTCUSDT,SOLUSDT&timeframe=15m&status=ACTIVE,SUPERSEDED&touches=4,5'
```

### `GET /api/signals/{id}`

One signal with every touch that produced it.

```json
{
  "id": "5f3c...", "levelId": "9b4e...",
  "symbol": "ETHUSDT", "timeframe": "5m", "type": "SUPPORT",
  "price": 2567.798, "zoneLower": 2565.2302, "zoneUpper": 2570.3658,
  "touchCount": 3, "strength": 0.80, "status": "ACTIVE",
  "detectedAt": "2026-09-20T11:00:00Z", "historical": false, "notified": true,
  "touches": [
    { "touchNumber": 1, "touchAt": "2026-09-18T15:10:00Z", "price": 2565.59,
      "distancePercent": 0.0, "candlesSincePrevious": -1, "rejectionPercent": 1.25, "closed": true },
    { "touchNumber": 2, "touchAt": "2026-09-20T03:00:00Z", "price": 2564.33,
      "distancePercent": 0.117, "candlesSincePrevious": 428, "rejectionPercent": 0.97, "closed": true },
    { "touchNumber": 3, "touchAt": "2026-09-20T11:00:00Z", "price": 2567.52,
      "distancePercent": 0.011, "candlesSincePrevious": 92, "rejectionPercent": 0.0, "closed": false }
  ]
}
```

`candlesSincePrevious` is `-1` for the first touch. `closed: false` means the interaction is still
in progress — its extreme and rejection may still move.

---

## Levels

### `GET /api/levels`

Same filters as signals, plus:

| Parameter | Values |
|---|---|
| `status` | one or more of `ACTIVE`, `SUPERSEDED`, `BROKEN`, `EXPIRED` |
| `touches`, `touchesAtLeast` | as for signals |
| `minTouches` | integer — e.g. `3` for levels that reached the threshold |
| `minStrength` | 0..1 quality floor |

`from` / `to` filter on `firstSeenAt`.

```bash
# every still-valid resistance on BTCUSDT 1h with at least three touches
curl 'http://localhost:8080/api/levels?symbol=BTCUSDT&timeframe=1h&type=RESISTANCE&status=ACTIVE&minTouches=3'
```

### `GET /api/levels/{id}`

One level with its touches and its breakout, if it has one. A superseded level also carries
`supersededBy` (the id of the level that took over), `supersededByPrice` and `supersededAt`.

### `GET /api/levels/{id}/touches`

Just the touches, in order.

### `GET /api/levels/{id}/breakout`

The breakout that invalidated the level; `404` if it is still standing.

```json
{ "levelId": "9b4e...", "levelType": "RESISTANCE",
  "levelPrice": 100000, "thresholdPrice": 100200, "confirmationPrice": 100266.67,
  "confirmedAt": "2026-09-20T13:15:00Z", "confirmationCandles": 1, "touchCountAtBreak": 3 }
```

---

## Candles

### `GET /api/candles`

| Parameter | |
|---|---|
| `symbol` | **required** |
| `timeframe` | **required** |
| `from`, `to` | optional ISO instants |
| `limit` | default 500, capped at 5000 |

With no range, returns the most recent stored candles — not a wall-clock window, which would come
back empty whenever ingestion is behind.

```bash
curl 'http://localhost:8080/api/candles?symbol=BTCUSDT&timeframe=5m&limit=300'
```

---

## Backtest

### `POST /api/backtest`

Runs the live detection engine over stored candles. **Writes no levels or signals**, so it is safe
against production data.

| Parameter | |
|---|---|
| `symbol`, `timeframe` | **required** |
| `from` | **required** ISO instant |
| `to` | defaults to now |
| `fetch` | `true` downloads any candles the database lacks first |
| `format` | `json` (default) \| `csv` \| `report` |

```bash
curl -X POST 'http://localhost:8080/api/backtest?symbol=BTCUSDT&timeframe=5m&from=2025-01-01T00:00:00Z&format=report'
```

The JSON summary:

```json
{
  "levelsDiscovered": 589, "qualifiedLevels": 71, "signals": 71,
  "resistanceSignals": 39, "supportSignals": 32,
  "signalsWhoseLevelLaterBroke": 69, "signalsStillUnbroken": 2,
  "p25CandlesHeldAfterSignal": 4,
  "medianCandlesHeldAfterSignal": 17,
  "p75CandlesHeldAfterSignal": 45,
  "brokenLevels": 545, "activeLevels": 44, "breakoutEvents": 545, "candlesAnalysed": 4417
}
```

`signalsWhoseLevelLaterBroke` is not a false-positive count — see
[ALGORITHM.md §12](ALGORITHM.md#reading-the-backtest-numbers).

`csv` returns one row per signal with its first three touches flattened into columns, full
precision and no thousands separators.

---

## Configuration and status

### `GET /api/config`

Exactly what the running engine is using, including the `configFingerprint` that every stored level
and signal is tagged with. Credentials are never included — only whether a channel is usable.

### `GET /api/status`

Pipeline health: whether startup backfill finished, whether the websocket is connected, and how far
each market has been analysed.

```json
{
  "bootstrap": "READY",
  "liveStream": { "running": true, "connected": true, "reconnects": 0,
                  "closedCandlesReceived": 412, "partialCandlesDropped": 8317 },
  "markets": [
    { "market": "BTCUSDT:5m", "candlesStored": 2016, "candlesAnalysed": 2015,
      "gapsSeen": 0, "duplicatesIgnored": 0, "activeLevels": 23 }
  ]
}
```

`bootstrap` is `PENDING` → `RUNNING` → `READY`, or `FAILED`. The API serves requests throughout;
startup runs on a background thread so a long first backfill never makes the service look dead to a
health check.

### `GET /actuator/health`

Standard Spring Boot health, used by the container healthcheck.
