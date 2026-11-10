-- Coins the watch list chose by trading volume (crypto-signals.markets.volume-selection), so a
-- restart watches the same coins even when the exchange cannot be asked, and a coin chosen earlier
-- keeps its lower threshold for staying. The fixed symbols from the configuration are not stored.
CREATE TABLE watched_symbol (
    exchange      VARCHAR(32)    NOT NULL,
    symbol        VARCHAR(32)    NOT NULL,
    daily_volume  NUMERIC(38,8)  NOT NULL,   -- average traded value per day, in the quote asset
    selected_at   TIMESTAMPTZ    NOT NULL,   -- when the coin joined the list
    updated_at    TIMESTAMPTZ    NOT NULL,   -- when its volume was last checked
    PRIMARY KEY (exchange, symbol)
);
