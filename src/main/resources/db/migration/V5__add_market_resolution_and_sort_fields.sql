ALTER TABLE markets ADD COLUMN IF NOT EXISTS resolved BOOLEAN DEFAULT FALSE;
ALTER TABLE markets ADD COLUMN IF NOT EXISTS created_at_source TIMESTAMPTZ;
ALTER TABLE markets ADD COLUMN IF NOT EXISTS liquidity DECIMAL(18,2);

UPDATE markets
SET created_at_source = created_at
WHERE created_at_source IS NULL;

UPDATE markets
SET resolved = TRUE
WHERE outcome_yes_price IS NOT NULL
  AND (outcome_yes_price >= 0.95 OR outcome_yes_price <= 0.05);

UPDATE markets
SET resolved = FALSE
WHERE resolved IS NULL;

CREATE INDEX IF NOT EXISTS idx_markets_active_resolved ON markets(active, resolved);
