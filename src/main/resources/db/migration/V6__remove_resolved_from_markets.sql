DROP INDEX IF EXISTS idx_markets_active_resolved;
ALTER TABLE markets DROP COLUMN IF EXISTS resolved;
