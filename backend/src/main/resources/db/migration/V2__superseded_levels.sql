-- ---------------------------------------------------------------------------------------------
-- "Nearest level" rule: a level is SUPERSEDED once price has moved past it and a stronger level of
-- the same role, close by on the far side, has become nearer to price.
--
-- The status itself needs no schema change (status is free text, and 'SUPERSEDED' fits). These
-- columns record which level took over, at what price, and when, so the UI can explain it.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE price_level
    ADD COLUMN superseded_by       UUID,
    ADD COLUMN superseded_by_price NUMERIC(38,18),
    ADD COLUMN superseded_at       TIMESTAMPTZ;
