-- Which supersede rule retired a level:
--   NEARBY_LEVEL  proximity grouping: another established level of the same role, further out,
--                 sits within the timeframe's grouping distance
--   PRICE_PASSED  price moved past the level towards a nearer established level just beyond it
ALTER TABLE price_level
    ADD COLUMN superseded_reason VARCHAR(16);

-- Until now the price rule was the only supersede rule.
UPDATE price_level SET superseded_reason = 'PRICE_PASSED' WHERE status = 'SUPERSEDED';
