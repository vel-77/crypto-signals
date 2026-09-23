-- ---------------------------------------------------------------------------------------------
-- crypto-signals initial schema
--
-- Design notes
--   * Prices are NUMERIC(38,18): exact, and wide enough for both 0.000000001 meme coins and
--     seven-figure indices. The analysis engine works in double (every comparison there is
--     tolerance-based) and converts at this boundary.
--   * Levels and signals use the engine's own deterministic UUIDs as primary keys. Re-running the
--     analysis over the same candles therefore upserts in place instead of duplicating, which is
--     what makes restart and re-backfill safe.
--   * Timestamps are TIMESTAMPTZ and always stored in UTC.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE market (
    id          BIGSERIAL PRIMARY KEY,
    exchange    VARCHAR(32)  NOT NULL DEFAULT 'BINANCE',
    symbol      VARCHAR(32)  NOT NULL,
    timeframe   VARCHAR(8)   NOT NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_market UNIQUE (exchange, symbol, timeframe)
);

-- ---------------------------------------------------------------------------------------------
-- Candles: by far the highest-volume table.
--
-- The primary key is (market_id, open_time). Every read the system performs is "give me the
-- candles for this market between these two instants, in order", which that key serves as a
-- forward index scan with no sort step. Inserts are append-mostly, so the index stays dense.
--
-- For very large deployments this table is the natural candidate for declarative RANGE
-- partitioning on open_time; the application code does not depend on it being a single relation.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE candle (
    market_id   BIGINT         NOT NULL REFERENCES market (id) ON DELETE CASCADE,
    open_time   TIMESTAMPTZ    NOT NULL,
    close_time  TIMESTAMPTZ    NOT NULL,
    open        NUMERIC(38,18) NOT NULL,
    high        NUMERIC(38,18) NOT NULL,
    low         NUMERIC(38,18) NOT NULL,
    close       NUMERIC(38,18) NOT NULL,
    volume      NUMERIC(38,18) NOT NULL,
    PRIMARY KEY (market_id, open_time)
);

CREATE TABLE price_level (
    id                 UUID           PRIMARY KEY,
    market_id          BIGINT         NOT NULL REFERENCES market (id) ON DELETE CASCADE,
    symbol             VARCHAR(32)    NOT NULL,
    timeframe          VARCHAR(8)     NOT NULL,
    type               VARCHAR(16)    NOT NULL,
    price              NUMERIC(38,18) NOT NULL,
    zone_lower         NUMERIC(38,18) NOT NULL,
    zone_upper         NUMERIC(38,18) NOT NULL,
    strength           DOUBLE PRECISION NOT NULL,
    status             VARCHAR(16)    NOT NULL,
    swing_count        INTEGER        NOT NULL,
    touch_count        INTEGER        NOT NULL,
    created_at_index   INTEGER        NOT NULL,
    first_seen_at      TIMESTAMPTZ    NOT NULL,
    last_touch_at      TIMESTAMPTZ,
    strategy           VARCHAR(64)    NOT NULL,
    config_fingerprint VARCHAR(32)    NOT NULL,
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_price_level_market_status ON price_level (market_id, status);
CREATE INDEX idx_price_level_lookup        ON price_level (symbol, timeframe, type, status);
CREATE INDEX idx_price_level_first_seen    ON price_level (first_seen_at DESC);

CREATE TABLE level_touch (
    id                        BIGSERIAL PRIMARY KEY,
    level_id                  UUID           NOT NULL REFERENCES price_level (id) ON DELETE CASCADE,
    touch_number              INTEGER        NOT NULL,
    touch_at                  TIMESTAMPTZ    NOT NULL,
    started_at                TIMESTAMPTZ    NOT NULL,
    ended_at                  TIMESTAMPTZ    NOT NULL,
    candle_open_time          TIMESTAMPTZ    NOT NULL,
    price                     NUMERIC(38,18) NOT NULL,
    level_price_at_touch      NUMERIC(38,18) NOT NULL,
    distance_from_level       NUMERIC(38,18) NOT NULL,
    distance_percent          DOUBLE PRECISION NOT NULL,
    candles_since_previous    INTEGER        NOT NULL,
    millis_since_previous     BIGINT         NOT NULL,
    price_delta_from_previous NUMERIC(38,18) NOT NULL,
    rejection_percent         DOUBLE PRECISION NOT NULL,
    closed                    BOOLEAN        NOT NULL,
    start_index               INTEGER        NOT NULL,
    end_index                 INTEGER        NOT NULL,
    extreme_index             INTEGER        NOT NULL,
    CONSTRAINT uq_level_touch UNIQUE (level_id, touch_number)
);

CREATE INDEX idx_level_touch_level ON level_touch (level_id, touch_number);

CREATE TABLE breakout_event (
    id                   BIGSERIAL PRIMARY KEY,
    level_id             UUID           NOT NULL REFERENCES price_level (id) ON DELETE CASCADE,
    level_type           VARCHAR(16)    NOT NULL,
    level_price          NUMERIC(38,18) NOT NULL,
    threshold_price      NUMERIC(38,18) NOT NULL,
    confirmation_price   NUMERIC(38,18) NOT NULL,
    first_breach_index   INTEGER        NOT NULL,
    confirmed_at_index   INTEGER        NOT NULL,
    confirmed_at         TIMESTAMPTZ    NOT NULL,
    confirmation_candles INTEGER        NOT NULL,
    touch_count_at_break INTEGER        NOT NULL,
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    -- A level breaks at most once; after that it is terminal and a new structure gets a new id.
    CONSTRAINT uq_breakout_level UNIQUE (level_id)
);

CREATE TABLE signal (
    id           UUID           PRIMARY KEY,
    level_id     UUID           NOT NULL REFERENCES price_level (id) ON DELETE CASCADE,
    market_id    BIGINT         NOT NULL REFERENCES market (id) ON DELETE CASCADE,
    symbol       VARCHAR(32)    NOT NULL,
    timeframe    VARCHAR(8)     NOT NULL,
    type         VARCHAR(16)    NOT NULL,
    price        NUMERIC(38,18) NOT NULL,
    zone_lower   NUMERIC(38,18) NOT NULL,
    zone_upper   NUMERIC(38,18) NOT NULL,
    touch_count  INTEGER        NOT NULL,
    strength     DOUBLE PRECISION NOT NULL,
    detected_at  TIMESTAMPTZ    NOT NULL,
    status       VARCHAR(16)    NOT NULL,
    historical   BOOLEAN        NOT NULL DEFAULT FALSE,
    -- Notification bookkeeping: a signal that was persisted but never delivered (Telegram down,
    -- process killed between insert and send) is retried, and is never delivered twice.
    notified     BOOLEAN        NOT NULL DEFAULT FALSE,
    notified_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uq_signal_level_touch UNIQUE (level_id, touch_count)
);

CREATE INDEX idx_signal_detected   ON signal (detected_at DESC);
CREATE INDEX idx_signal_lookup     ON signal (symbol, timeframe, type, status);
CREATE INDEX idx_signal_pending    ON signal (notified) WHERE notified = FALSE;

-- Per-market progress, so a restart knows where to resume instead of refetching everything.
CREATE TABLE market_sync_state (
    market_id        BIGINT      PRIMARY KEY REFERENCES market (id) ON DELETE CASCADE,
    backfilled_from  TIMESTAMPTZ,
    last_candle_at   TIMESTAMPTZ,
    last_analyzed_at TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Named parameter sets. The active row is what the running engine uses; history is kept so a
-- stored level can always be traced back to the rules that produced it.
CREATE TABLE analysis_configuration (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(64) NOT NULL UNIQUE,
    fingerprint VARCHAR(32) NOT NULL,
    payload     JSONB       NOT NULL,
    active      BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
