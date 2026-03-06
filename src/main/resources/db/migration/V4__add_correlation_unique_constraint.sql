-- Prevent duplicate correlations for the same market + news article pair.
-- Application-level checks can race under concurrent processing.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'uq_correlation_market_news'
    ) THEN
        -- Remove duplicates first, keeping the highest-confidence row per pair.
        WITH ranked AS (
            SELECT id,
                   ROW_NUMBER() OVER (
                       PARTITION BY market_id, news_event_id
                       ORDER BY confidence DESC, detected_at DESC, id DESC
                   ) AS rn
            FROM correlations
        )
        DELETE FROM correlations c
        USING ranked r
        WHERE c.id = r.id
          AND r.rn > 1;

        ALTER TABLE correlations
            ADD CONSTRAINT uq_correlation_market_news UNIQUE (market_id, news_event_id);
    END IF;
END $$;
