# Deployment

Running crypto-signals on a server. The whole stack is three containers and one volume, so a small
VPS is plenty.

---

## Sizing

| Resource | Minimum | Comfortable | Notes |
|---|---|---|---|
| vCPU | 1 | 2 | Analysis is cheap — 4,400 candles replay in ~200 ms. Backfill is I/O bound. |
| RAM | 1 GB | 2 GB | The JVM is given 75% of the container limit. Engine memory is flat regardless of history length. |
| Disk | 10 GB | 25 GB+ | Candles dominate. See below. |

**Disk estimate.** Roughly 120 bytes per candle including index overhead.

| Watch list | Per year |
|---|---|
| 2 symbols × 5m | ~25 MB |
| 2 symbols × (5m, 15m, 1h) | ~35 MB |
| 20 symbols × 1m | ~1.3 GB |

One-minute candles across many symbols is the only combination that gets large. If you go there,
partition `candle` by range on `open_time` — the application does not depend on it being a single
relation.

---

## Deploy

```bash
git clone <your-repo> crypto-signals && cd crypto-signals
cp .env.example .env
nano .env                       # set POSTGRES_PASSWORD, markets, Telegram
docker compose up -d --build
docker compose logs -f backend
```

Confirm it is live:

```bash
curl -s localhost:8080/api/status | jq '.bootstrap, .liveStream.connected'
# "READY"
# true
```

### Minimum edits before exposing it

1. **`POSTGRES_PASSWORD`** — change it.
2. **`POSTGRES_PORT`** — remove the published port entirely unless you need external database
   access. In `docker-compose.yml`, delete the `ports:` block under `postgres`; the backend reaches
   it over the internal network regardless.
3. **`ALLOWED_ORIGINS`** — set to your actual UI origin. The API exposes full analysis state and has
   no authentication of its own.

---

## Putting it behind TLS

The API has **no authentication**. Do not expose port 8080 to the internet directly. Terminate TLS
at a reverse proxy and restrict access there.

```nginx
server {
    listen 443 ssl http2;
    server_name signals.example.com;

    ssl_certificate     /etc/letsencrypt/live/signals.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/signals.example.com/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:3000;     # the UI container
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }

    # The UI already proxies /api internally; expose the API directly only if you need to,
    # and put auth in front of it.
    location /api/ {
        auth_basic "crypto-signals";
        auth_basic_user_file /etc/nginx/.htpasswd;
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
    }
}
```

Then bind the containers to localhost only, in `docker-compose.yml`:

```yaml
ports:
  - "127.0.0.1:8080:8080"
  - "127.0.0.1:3000:80"
```

Certificates:

```bash
sudo certbot --nginx -d signals.example.com
```

### Firewall

```bash
sudo ufw allow OpenSSH
sudo ufw allow 80,443/tcp
sudo ufw enable
```

---

## Operating

### Logs

```bash
docker compose logs -f backend
docker compose logs --since 1h backend | grep SIGNAL
```

Every signal is logged regardless of whether any external channel is configured, so nothing is ever
produced without a trace.

### Health and metrics

| | |
|---|---|
| `GET /actuator/health` | used by the container healthcheck |
| `GET /actuator/metrics` | JVM, HTTP, connection pool |
| `GET /api/status` | ingestion and analysis progress per market |

Worth alerting on: `liveStream.connected` false for more than a few minutes, `bootstrap` equal to
`FAILED`, or `gapsSeen` climbing steadily.

### Backups

Candles are re-downloadable from the exchange, so the irreplaceable data is the analysis history —
levels, touches, breakouts, signals and their delivery state.

```bash
# everything
docker exec cs-postgres pg_dump -U cryptosignals cryptosignals | gzip > backup-$(date +%F).sql.gz

# analysis only, far smaller
docker exec cs-postgres pg_dump -U cryptosignals -t price_level -t level_touch \
  -t breakout_event -t signal -t market cryptosignals | gzip > analysis-$(date +%F).sql.gz
```

Restore:

```bash
gunzip -c backup-2026-09-20.sql.gz | docker exec -i cs-postgres psql -U cryptosignals cryptosignals
```

A nightly cron:

```cron
0 3 * * * cd /opt/crypto-signals && docker exec cs-postgres pg_dump -U cryptosignals cryptosignals | gzip > /var/backups/cs-$(date +\%F).sql.gz && find /var/backups -name 'cs-*.sql.gz' -mtime +14 -delete
```

### Upgrades

```bash
git pull
docker compose up -d --build
```

Flyway applies new migrations on startup. The backend replays history into fresh engines, which
takes the usual minute or two; the API stays up throughout.

### Changing detection rules

Edit `.env` or `application.yml`, then restart the backend:

```bash
docker compose restart backend
```

Changed rules mean a changed `configFingerprint`, and because level ids are derived from content,
new levels get new ids. Old rows remain under their old fingerprint — traceable, not silently
migrated. To start clean:

```sql
TRUNCATE price_level CASCADE;   -- cascades to touches, breakouts and signals; candles are kept
```

Validate a rule change with a backtest **before** restarting live:

```bash
curl -X POST 'http://localhost:8080/api/backtest?symbol=BTCUSDT&timeframe=4h&from=2025-01-01T00:00:00Z&format=report'
```

---

## Scaling up the watch list

The watch list is the cross product of symbols and timeframes, and each pair is an independent
engine. Practical limits:

- **Websocket streams.** Markets are spread across connections, 200 streams each, each with its own
  reconnect loop. Hundreds of markets are fine.
- **REST weight.** Backfill is metered by a weight-aware limiter (`maxWeightPerMinute`, default
  2400, against Binance's published 6000). A large first backfill simply takes longer; it will not
  get the IP banned.
- **First start.** 20 symbols × 3 timeframes × 1 year is a long initial download. Start with a
  short `lookback`, let it settle, then widen it — the backfiller only fetches what is missing.
- **Level cap.** `maxActiveLevelsPerMarket` (default 250) bounds memory per market. If a backtest
  reports level counts near the cap, raise it.

---

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `bootstrap` stuck on `RUNNING` | A long first backfill. Watch `candlesStored` in `/api/status` climb. |
| `bootstrap: FAILED` | Check `docker compose logs backend`. Usually the database or a symbol the venue does not list. |
| A market is missing entirely | The exchange does not list that symbol; the log says so at startup and the others continue. |
| `liveStream.connected` false | Reconnect is automatic with backoff. If it persists, check egress to `stream.binance.com:9443`. |
| No signals at all | Expected on a short lookback — a level needs three *separated* touches. Verify with a backtest over a longer range before suspecting a bug. |
| Far too many signals, levels dying instantly | Tolerances too tight for the timeframe. See [ALGORITHM.md §12](ALGORITHM.md#12--calibration--measured-not-guessed). |
| Telegram silent | `GET /api/config` → `notifications.telegramEnabled`. Note that historical signals are suppressed by default, and signals older than `maxSignalAge` (1h) are never delivered. |
| Schema validation fails on startup | Migrations and entities disagree — an upgrade problem. The integration suite catches this in CI. |
