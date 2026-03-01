CREATE TABLE markets (
    id              BIGSERIAL PRIMARY KEY,
    condition_id    VARCHAR(128) UNIQUE NOT NULL,
    clob_token_id   VARCHAR(128) NOT NULL,
    question        TEXT NOT NULL,
    slug            VARCHAR(256),
    category        VARCHAR(64),
    active          BOOLEAN DEFAULT TRUE,
    outcome_yes_price DECIMAL(10,6),
    outcome_no_price  DECIMAL(10,6),
    volume_24h      DECIMAL(18,2),
    last_synced_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_markets_active ON markets(active) WHERE active = TRUE;
CREATE INDEX idx_markets_condition ON markets(condition_id);

CREATE TABLE price_ticks (
    id          BIGSERIAL PRIMARY KEY,
    market_id   BIGINT NOT NULL REFERENCES markets(id),
    price       DECIMAL(10,6) NOT NULL,
    timestamp   TIMESTAMPTZ NOT NULL,
    volume      DECIMAL(18,2)
);

CREATE INDEX idx_ticks_market_time ON price_ticks(market_id, timestamp DESC);
CREATE INDEX idx_ticks_time ON price_ticks(timestamp DESC);

CREATE TABLE news_events (
    id              BIGSERIAL PRIMARY KEY,
    headline        TEXT NOT NULL,
    source          VARCHAR(128),
    url             TEXT UNIQUE,
    published_at    TIMESTAMPTZ NOT NULL,
    ingested_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    keywords        TEXT[],
    category        VARCHAR(64)
);

CREATE INDEX idx_news_published ON news_events(published_at DESC);
CREATE INDEX idx_news_keywords ON news_events USING GIN(keywords);

CREATE TABLE correlations (
    id              BIGSERIAL PRIMARY KEY,
    market_id       BIGINT NOT NULL REFERENCES markets(id),
    news_event_id   BIGINT NOT NULL REFERENCES news_events(id),
    price_before    DECIMAL(10,6) NOT NULL,
    price_after     DECIMAL(10,6) NOT NULL,
    price_delta     DECIMAL(10,6) NOT NULL,
    time_window_ms  INTEGER NOT NULL,
    confidence      DECIMAL(4,3) NOT NULL,
    detected_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_corr_market ON correlations(market_id, detected_at DESC);
CREATE INDEX idx_corr_confidence ON correlations(confidence DESC);
