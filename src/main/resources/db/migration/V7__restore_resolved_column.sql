ALTER TABLE markets ADD COLUMN IF NOT EXISTS resolved BOOLEAN;
UPDATE markets SET resolved = FALSE WHERE resolved IS NULL;
ALTER TABLE markets ALTER COLUMN resolved SET DEFAULT FALSE;
ALTER TABLE markets ALTER COLUMN resolved SET NOT NULL;
CREATE INDEX IF NOT EXISTS idx_markets_active_resolved ON markets(active, resolved);
