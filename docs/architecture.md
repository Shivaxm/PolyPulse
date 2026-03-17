# PolyPulse Architecture

PolyPulse is a real-time prediction market dashboard that ingests live prices from Polymarket, polls breaking news, and uses an LLM to detect when a news event causally moves a market's price. This document explains how every subsystem works, how failures are handled, and why each design decision was made.

---

## System Overview

```
                          Browser (React + SSE)
                                 |
                          +------+------+
                          | Spring Boot  |
                          | REST + SSE   |
                          +--+--+--+--+-+
                             |  |  |  |
          +------------------+  |  |  +------------------+
          |                     |  |                      |
   Price Ingestion        News Ingestion         Correlation Engine
   (Polymarket WS)        (NewsAPI poll)         (keyword + LLM + price)
          |                     |                      |
          v                     v                      v
   PriceCacheService      news_events table      correlations table
   (ConcurrentHashMap)          |                      |
          |                     +----------+-----------+
          v                                |
   price_ticks table                  PostgreSQL
   (sampled writes)
```

When Redis is enabled, SSE events fan out through pub/sub so multiple backend instances can serve the same live stream:

```
   SseEventBridge
        |
   Redis Pub/Sub ──────> polypulse:price_updates
        |                polypulse:correlations
        v
   RedisEventSubscriber (on every instance)
        |
   SseConnectionManager.broadcast()
        |
   Browser EventSource
```

---

## 1. Price Ingestion

**Files:** `PriceIngestionService`, `PriceMessageParser`, `PriceTickBatchWriter`, `PriceCacheService`

### How prices enter the system

A single WebSocket connection to Polymarket's CLOB orderbook streams price changes for all tracked markets. The service subscribes with every known `clobTokenId` and receives two message types:

- **`last_trade_price`** — a trade executed, here's the price
- **`price_change`** — best bid/ask moved, we average to midpoint

### Why not one connection per market?

5,000+ markets would mean 5,000 WebSocket connections. Polymarket's API accepts bulk subscriptions on a single socket. One connection, one thread, minimal overhead.

### What happens when a tick arrives

```
tick arrives
  -> PriceCacheService.updatePrice()       # always, instant, in-memory
  -> publish PriceTickEvent                 # always, triggers SSE broadcast
  -> check 15-second sampling gate
       -> if >15s since last DB write for this market:
            buffer.offer(tick)              # goes to batch writer queue
       -> else: skip DB write
```

**Why sample at 15 seconds?** Polymarket can emit hundreds of ticks per second. Writing every tick would overwhelm PostgreSQL and balloon the `price_ticks` table. The in-memory cache already has the latest price for real-time display. Historical charts use 72-minute buckets — a 15-second sample rate is more than sufficient.

### Batch writing

A `@Scheduled` job runs every second, drains up to 2,000 ticks from a bounded `LinkedBlockingQueue(50,000)`, and batch-inserts them using a native multi-row `INSERT INTO ... VALUES` statement.

**Why native SQL instead of Hibernate `saveAll()`?** Hibernate's `IDENTITY` strategy forces one round-trip per row to retrieve generated IDs. Native batch insert sends one statement for the whole batch.

**What if the batch write fails?** The ticks are dropped, not re-queued. They already exist in the in-memory cache for real-time display. If historical data has gaps, `PriceBackfillService` fills them from Polymarket's CLOB REST API on demand.

### WebSocket resilience

- **Startup ordering:** `SmartLifecycle` with `phase = MAX_VALUE` ensures markets are synced before connecting
- **10-second startup delay:** Waits for `MarketSyncService` to populate the market list
- **Exponential backoff:** 1s, 2s, 4s, 8s, 16s, 30s cap on reconnection attempts
- **Gap detection:** On reconnect, if the gap is 2–60 minutes, triggers async backfill for all markets
- **Token remapping:** On every `MarketsSyncedEvent`, reloads the token-to-market mapping and re-subscribes on the existing session

### Buffer overflow

If the 50,000-entry queue fills up, new ticks are silently dropped. A counter tracks dropped ticks and logs every 1,000th drop. This is acceptable because:

1. The in-memory cache already has the latest price
2. The SSE event was already published
3. Only the DB historical record is lost, and it's recoverable via backfill

---

## 2. Market Sync

**File:** `MarketSyncService`

### How markets are fetched

Every 5 minutes, the service queries Polymarket's Gamma API for active events ordered by 24-hour volume. It paginates up to 12 pages of 50 events (600 markets max, most liquid first).

**Why volume-ordered, limited to 600?** Polymarket has thousands of markets, most with zero activity. Volume ordering ensures we track the markets people actually trade on. 600 is a pragmatic ceiling that keeps the sparkline query fast and the dashboard useful.

### Market lifecycle

- **New market found:** Created in DB if not expired and has a valid price
- **Existing market updated:** Price, volume, liquidity, end date, active flag refreshed
- **Expiration detection:** Closed, not accepting orders, price settled (>97% or <3%), or past end date
- **Staleness cleanup:** Markets not seen in the latest sync AND not synced in >2 hours are deactivated. The 2-hour buffer prevents false deactivations from partial sync failures.

### Category normalization

Polymarket uses granular, inconsistent categories. `MarketSyncService` normalizes them to 8 dashboard categories (politics, geopolitics, sports, crypto, finance, tech, culture, science) using a keyword map. If nothing matches, it falls back to the raw Polymarket category.

---

## 3. News Ingestion & Correlation Pipeline

**Files:** `NewsIngestionService`, `KeywordExtractor`, `CorrelationEngine`, `LlmRelevanceService`

This is the core differentiator of the project. It answers: "Did this news headline move this prediction market?"

### Stage 1: News polling

Every 15 minutes, `NewsIngestionService` fetches headlines from NewsAPI (or GNews). It rotates categories (general, business, technology, science) to spread API usage across topics.

**Deduplication:** Each article's URL is checked against the `news_events` table's unique constraint before processing.

**Ordering dependency:** The service listens for `MarketsSyncedEvent` before its first poll. Without markets in the DB, no correlations can be detected, so polling early wastes API calls.

### Stage 2: Keyword pre-filter (instant, O(n))

`KeywordExtractor` tokenizes the headline into meaningful keywords (4+ characters, stopwords removed). `CorrelationEngine` scans all active markets, finding those sharing at least one keyword with the headline.

This narrows 5,000+ markets down to ~15 candidates. Markets are excluded if:

- Price is >97% or <3% (essentially settled)
- Past their end date
- Beyond the `maxCandidateMarkets` limit (30)

**Why not skip this and send everything to the LLM?** Cost and latency. Claude Haiku is fast and cheap, but sending 5,000 markets per headline would cost ~$0.50/call and take seconds. The keyword filter costs zero and runs in microseconds.

### Stage 3: LLM validation (Claude Haiku)

The surviving candidates go to `LlmRelevanceService` in a single batch call to Claude Haiku. The system prompt is deliberately strict — it explicitly lists false positive patterns to reject:

- Same country/location but different topics
- Same sector but unrelated events
- Shared keywords without semantic connection
- News about **consequences** of an outcome vs. the outcome itself (e.g., "oil prices surge" doesn't validate a war prediction market)

Each candidate gets a relevance score (0–1). Only scores >= 0.75 pass.

**Why 0.75?** Lower thresholds produced too many spurious correlations (e.g., any economic headline matching every finance market). 0.75 catches genuine causal links while filtering noise.

**What if the API key is missing?** `LlmRelevanceService` returns an empty list. No correlations are generated. The system degrades gracefully — markets and prices still work, just no AI analysis.

### Stage 4: Price delta check

For each LLM-validated match, the engine:

1. Fetches the market price **before** the article was published (15-minute window)
2. Fetches the market price **after** publication (45-minute window)
3. Requires >1% absolute price change (`min-price-delta: 0.01`)

**Confidence formula:**

```
confidence = 0.6 * llmScore + 0.4 * magnitudeScore
```

LLM opinion is weighted 60% because the model understands causation better than price magnitude alone. A 0.9x penalty is applied if tick data is sparse (relies on synced price instead).

### Deduplication and cooldown

- **Database constraint:** `UNIQUE(market_id, news_event_id)` prevents duplicate correlations at the DB level
- **Market cooldown:** 30 minutes between correlations for the same market, prevents the same story reported by multiple outlets from generating duplicate entries
- **Retroactive recheck:** Every 15 minutes, the engine re-evaluates news from the past 6 hours. This catches correlations where the price hadn't moved yet at initial detection but has since

---

## 4. Real-Time Delivery (SSE)

**Files:** `SseConnectionManager`, `SseEventBridge`, `RedisEventPublisher`, `RedisEventSubscriber`

### Why SSE over WebSocket?

SSE is simpler. The data flow is server-to-client only — browsers don't send price data back. SSE works over standard HTTP, survives proxy/CDN layers better, and auto-reconnects natively via `EventSource`. WebSocket would add bidirectional complexity for no benefit.

### Connection lifecycle

1. Client opens `EventSource` to `/api/stream/live`
2. Server creates `SseEmitter` (5-minute timeout), sends `connected` event
3. Server sends `heartbeat` every 30 seconds to keep connection alive through proxies
4. On any send failure (`Exception`, not just `IOException`), client is removed and emitter completed

**Why catch `Exception` and not just `IOException`?** `SseEmitter.send()` throws `IllegalStateException` when the emitter has timed out or been completed. Catching only `IOException` leaves dead emitters in the map. They crash on every broadcast tick and eventually exhaust the async thread pool.

### Market-scoped subscriptions

The `/api/stream/markets/{id}` endpoint registers a client with a market filter. Price updates for other markets are not sent. This reduces bandwidth for the market detail page.

### Throttling

`SseEventBridge` throttles to 1 price update per market per second. Without this, the Polymarket WebSocket's raw volume would flood clients with redundant updates (prices don't change meaningfully within a second).

### Horizontal scaling with Redis pub/sub

When `polypulse.redis.enabled=true`:

1. `SseEventBridge` publishes events to Redis channels instead of broadcasting locally
2. `RedisEventSubscriber` (implementing `MessageListener` directly) receives on all instances
3. Each instance broadcasts to its own SSE clients

**Why `MessageListener` instead of `MessageListenerAdapter`?** `MessageListenerAdapter` uses `JdkSerializationRedisSerializer` by default, but `StringRedisTemplate` publishes with `StringRedisSerializer`. This mismatch causes silent message drops. Implementing `MessageListener` directly and reading raw bytes avoids the serialization layer entirely.

**Why Redis pub/sub over Kafka?** Redis is already in the stack for caching. Pub/sub adds zero new infrastructure. The messages are ephemeral price updates — if one is lost, the next arrives in a second. Kafka's durability guarantees are unnecessary overhead.

**Fallback:** When Redis is disabled, `SseEventBridge` calls `SseConnectionManager.broadcastPriceUpdate()` directly. Single-instance only, but zero external dependencies.

---

## 5. Caching Strategy

### Layer 1: PriceCacheService (in-memory, real-time)

A `ConcurrentHashMap<Long, CachedPrice>` holds the latest price per market. Updated on every WebSocket tick. Never expires, never queries the database. The `/api/markets` endpoint reads from this for live prices — zero I/O per request.

**Why not Redis for this?** Latency. A ConcurrentHashMap lookup is nanoseconds. A Redis GET is milliseconds. For prices that update hundreds of times per second, in-memory wins.

### Layer 2: MarketCacheService (background refresh, 60s)

A `@Scheduled` job rebuilds three volatile caches every 60 seconds:

- **Active markets list** — `findByActiveTrue()`, one query
- **Sparkline data** — `date_bin` aggregation, batched 200 markets per query
- **Correlation market IDs** — `SELECT DISTINCT market_id FROM correlations`

**Why batch sparklines in groups of 200?** The original implementation sent all 5,000+ market IDs in a single `WHERE market_id IN (...)` clause. This generated a query with 5,000 bind parameters that consumed excessive memory in PostgreSQL's planner, filled the WAL log, and crashed the database when the disk was full. Batching at 200 keeps each query manageable.

**Why 60-second refresh?** Sparklines show 24-hour trends in 72-minute buckets. Refreshing more often wastes DB resources for no visible change. Less often and new markets take too long to appear.

### Layer 3: PostgreSQL (persistent, retention-managed)

`price_ticks` are purged after 7 days by a scheduled job running every 6 hours. The `deleteOlderThan` query uses the existing `idx_ticks_time` index for efficient deletion.

**Why 7 days?** Sparklines only show 24 hours. The price history page shows up to 7 days. Keeping more than that grows the table unboundedly — which is exactly what caused the database disk full crash.

---

## 6. Failure Handling

### Database connection exhaustion

HikariCP is configured with `maxPoolSize=15, minIdle=5, connectionTimeout=5000ms`. Under load, the pool fills and requests wait up to 5 seconds. If the pool is exhausted, requests fail fast rather than queueing indefinitely.

**Why 15 connections?** Railway's PostgreSQL has a 97-connection limit. 15 leaves headroom for background jobs (cache refresh, retention purge, batch writer) and admin connections.

### Backfill circuit breaker

`PriceBackfillService` tracks failed API calls per market with a 300-second cooldown. If Polymarket's CLOB API is down, we don't hammer it. After the cooldown, the next request retries.

### LLM unavailability

If `ANTHROPIC_API_KEY` is empty, `LlmRelevanceService` logs a warning and returns zero matches. Correlations stop being generated, but everything else works. If the API returns an error or unparseable response, the batch is skipped (logged at ERROR) and retried on the next news cycle.

### SSE client disconnection

Three callbacks handle cleanup: `onCompletion`, `onTimeout`, `onError`. All call `removeClient()`, which removes from the map, removes market subscriptions, and calls `emitter.complete()` (wrapped in try/catch because it may already be completed).

### WebSocket disconnection

The `onClose` and `onError` handlers schedule reconnection via `scheduleReconnect()`. The reconnect runs on a single daemon thread with exponential backoff. If the executor is shut down (application stopping), reconnection is skipped.

### Batch write failures

Logged at ERROR, ticks dropped. No data loss for real-time display (in-memory cache). Historical gaps are filled by backfill on the next chart request for the affected market.

### Startup ordering

| Phase | Service | Depends On |
|-------|---------|------------|
| Default | `MarketSyncService` | Database |
| After sync event | `NewsIngestionService` | Markets in DB |
| `MAX_VALUE` | `PriceIngestionService` | Market token mappings |

This prevents the WebSocket from connecting before markets exist, and news from being fetched before markets can be correlated against.

---

## 7. Database Schema

```
markets
  id (PK), condition_id (UNIQUE), clob_token_id, question, category,
  active, outcome_yes_price, outcome_no_price, volume_24h, liquidity,
  end_date, last_synced_at, created_at
  Indexes: active (partial WHERE TRUE), condition_id

price_ticks
  id (PK), market_id (FK), price, timestamp, volume
  Indexes: (market_id, timestamp DESC), (timestamp DESC)

news_events
  id (PK), headline, source, url (UNIQUE), published_at, keywords (GIN),
  category
  Indexes: published_at DESC, keywords (GIN)

correlations
  id (PK), market_id (FK), news_event_id (FK), price_before, price_after,
  price_delta, time_window_ms, confidence, detected_at, reasoning
  Indexes: (market_id, detected_at DESC), confidence DESC
  Constraints: UNIQUE(market_id, news_event_id)
```

**Why GIN index on keywords?** Array containment queries (`@>`) are the core of the keyword pre-filter. GIN makes these O(log n) instead of sequential scans.

**Why partial index on active?** Only ~20% of markets are active at any time. A partial index is smaller and faster than indexing the full table.

---

## 8. Configuration Reference

```yaml
polypulse:
  sync:
    market-interval-ms: 300000       # Market sync every 5 minutes
  news:
    poll-interval-ms: 900000         # News poll every 15 minutes
    provider: newsapi                # newsapi or gnews
  correlation:
    min-price-delta: 0.01            # 1% minimum price change
    before-window-minutes: 15        # Look for price 15min before news
    after-window-minutes: 45         # Look for price 45min after news
    min-confidence: 0.3              # Minimum correlation confidence
    max-candidate-markets: 30        # Cap keyword-filtered candidates
    recheck-delay-minutes: 15        # Retroactive recheck interval
    cooldown-minutes: 30             # Per-market correlation cooldown
  redis:
    enabled: false                   # Feature flag for Redis pub/sub

spring:
  datasource:
    hikari:
      maximum-pool-size: 15
      minimum-idle: 5
      connection-timeout: 5000
  jpa:
    hibernate:
      ddl-auto: validate             # Flyway-only migrations
      batch_size: 50
```

### Feature flags

| Flag | Default | Effect |
|------|---------|--------|
| `polypulse.redis.enabled` | `false` | Enables Redis pub/sub for SSE fanout |
| `ANTHROPIC_API_KEY` | empty | Enables LLM correlation validation |
| `NEWS_API_KEY` | empty | Enables news polling |

All three are optional. The system degrades gracefully: no Redis = local broadcast, no API key = no correlations, no news key = no news ingestion.
