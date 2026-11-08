-- Next potential low / high of a signal: the nearest earlier swing low below a support signal, or
-- swing high above a resistance signal, at least a configured distance away. Worked out from the
-- stored candles when the signal is saved.
--
-- next_potential_rule records the settings the value was worked out under. NULL means not worked
-- out yet; a value that differs from the running settings is recomputed. A NULL price with a
-- non-NULL rule means nothing qualified (shown as N/A).
ALTER TABLE signal
    ADD COLUMN next_potential_price NUMERIC(38,18),
    ADD COLUMN next_potential_time  TIMESTAMPTZ,
    ADD COLUMN next_potential_rule  VARCHAR(64);

CREATE INDEX idx_signal_market_detected ON signal (market_id, detected_at);
