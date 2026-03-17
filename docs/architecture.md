# PolyPulse Architecture

PolyPulse is a real-time prediction market dashboard that ingests live prices from Polymarket, polls breaking news, and uses an LLM to detect when a news event causally moves a market's price. This document explains how every subsystem works, how the data flows end-to-end from source to pixel, and why each design decision was made.

---

## End-to-End Data Flows

Three primary data flows drive the entire application:

### Flow A: Live Price Updates (Polymarket -> Browser, ~100ms)

```
Polymarket CLOB WebSocket
  -> PriceIngestionService.handleMessage()
  -> PriceMessageParser.parse() -> list of ParsedTick
  -> For each tick, PriceIngestionService.saveTick():
       1. PriceCacheService.updatePrice()          [in-memory ConcurrentHashMap, instant]
       2. eventPublisher.publishEvent(PriceTickEvent)  [Spring @Async event]
       3. If >15s since last DB write for this market:
            buffer.offer(PriceTick)                [LinkedBlockingQueue(50,000)]
  -> SseEventBridge.onPriceTick() receives PriceTickEvent
       -> Throttle: skip if <1s since last send for this marketId
       -> Build PriceUpdateDTO { marketId, price, timestamp }
       -> If Redis enabled: RedisEventPublisher.publishPriceUpdate(dto)
            -> Serializes to JSON, publishes to "polypulse:price_updates" channel
            -> RedisEventSubscriber.onMessage() on every instance deserializes it
       -> Else: direct call to SseConnectionManager.broadcastPriceUpdate(dto)
  -> SseConnectionManager iterates all connected SseEmitters
       -> Filters by market subscription (if client registered for a specific market)
       -> emitter.send(SseEmitter.event().name("price_update").data(dto))
  -> Browser EventSource receives event named "price_update"
  -> useEventStream hook buffers update in pendingPrices Map<marketId, PriceUpdate>
  -> Every 1000ms, hook flushes pending to React state
  -> Dashboard.tsx reads priceUpdates.get(market.id)?.price to overlay on each MarketCard
  -> MarketCard.tsx shows live price (green Yes / red No) with sparkline trend
```

### Flow B: News Correlation Detection (NewsAPI -> Browser, ~3-5s)

```
NewsAPI / GNews HTTP poll (every 15 minutes)
  -> NewsIngestionService.fetchAndStoreNews()
  -> For each article: deduplicate by URL, extract keywords, save NewsEvent to DB
  -> Publish NewsIngestedEvent { newsEventId, headline, keywords, publishedAt }
  -> CorrelationEngine.onNewsIngested() receives event synchronously
       -> Stage 1 (keyword pre-filter): scan all active markets from MarketCacheService,
          find markets sharing keywords with headline (~15 candidates from 600)
       -> Stage 2 (LLM): send headline + candidates to LlmRelevanceService
          -> Single batch call to Claude Haiku, returns relevanceScore per market
          -> Only scores >= 0.75 pass
       -> Stage 3 (price delta): for each validated market,
          query price_ticks before/after news publication
          -> Requires >1% price change
          -> confidence = 0.6 * llmScore + 0.4 * magnitudeScore
       -> Save Correlation to DB, publish CorrelationDetectedEvent
  -> SseEventBridge.onCorrelationDetected() receives event
       -> Build CorrelationDTO { id, market{id,question}, news{headline,source,url},
          priceBefore, priceAfter, priceDelta, confidence, reasoning }
       -> Broadcast via Redis or directly to SseConnectionManager
  -> SseConnectionManager sends SSE event named "correlation" to all clients
  -> useEventStream hook buffers in pendingCorrelations array (max 50 kept)
  -> Correlations.tsx renders: market question (clickable), price delta,
     confidence bar, news headline (linked), LLM reasoning (purple italic), time ago
```

### Flow C: Dashboard Page Load (REST, ~150ms)

```
Browser navigates to Dashboard
  -> fetch GET /api/markets
  -> MarketController.getMarkets()
       -> marketCacheService.getActiveMarkets()         [volatile List<Market>, no DB query]
       -> priceCacheService.getAllLatestPrices()         [ConcurrentHashMap copy, no DB query]
       -> marketCacheService.getSparklines()             [volatile Map, no DB query]
       -> marketCacheService.getCorrelationMarketIds()   [volatile Set<Long>, no DB query]
       -> Filter: skip markets with price >97% or <3%, skip blank questions
       -> Map each market to MarketDTO:
            yesPrice = livePrice ?? market.outcomeYesPrice
            sparkline = cachedSparklines[marketId] ?? empty
            hasRecentCorrelation = marketId in correlationSet
  -> Browser receives ~600 MarketDTOs with prices, sparklines, correlation flags
  -> Dashboard.tsx renders MarketCard grid + stats bar:
       "Markets Tracked" = markets.length
       "Live Prices" = priceUpdates.size (unique markets with SSE updates)
       "Connection" = isConnected from SSE heartbeat
  -> Simultaneously, useEventStream opens EventSource to /api/stream/live
       -> Receives "connected" event, then "price_update" events start flowing
       -> Live prices overlay the REST-loaded data in real time
```

---

## 1. Price Ingestion

**Files:** `PriceIngestionService`, `PriceMessageParser`, `PriceTickBatchWriter`, `PriceCacheService`

### How prices enter the system

`PriceIngestionService` opens a single WebSocket to Polymarket's CLOB orderbook. On connect, it sends a subscribe message listing every known `clobTokenId` (loaded from the `markets` table via `MarketSyncService`). The socket streams two message types:

- **`last_trade_price`** — a trade executed at this price
- **`price_change`** — best bid/ask moved, averaged to midpoint

`PriceMessageParser.parse()` converts raw JSON into `ParsedTick` objects. Each tick maps back to a `marketId` using the `tokenToMarket` ConcurrentHashMap that `PriceIngestionService` maintains.

### Why not one connection per market?

600 active markets would mean 600 WebSocket connections. Polymarket's API accepts bulk subscriptions on a single socket. One connection, one thread, minimal overhead.

### What happens when a tick arrives

`PriceIngestionService.saveTick(marketId, assetId, price, timestamp)` does three things in order:

1. **Updates in-memory cache** — `PriceCacheService.updatePrice()` writes to a `ConcurrentHashMap<Long, CachedPrice>` using `.compute()` for atomic updates. Only updates if the new timestamp is newer. This cache is what `MarketController` reads for live prices on `/api/markets` — zero database I/O per request.

2. **Publishes a Spring event** — `PriceTickEvent` is picked up asynchronously by `SseEventBridge.onPriceTick()`, which converts it to a `PriceUpdateDTO { marketId, price, timestamp }` and pushes it through SSE to all connected browsers (see section 4).

3. **Conditionally buffers for DB write** — A per-market timestamp map tracks the last DB write. If >15 seconds have passed, the tick is offered to a `LinkedBlockingQueue(50,000)` for batch insertion.

**Why sample at 15 seconds?** Polymarket can emit hundreds of ticks per second. Writing every tick would overwhelm PostgreSQL and balloon the `price_ticks` table. The in-memory cache already has the latest price for real-time display. Historical charts use 72-minute buckets — a 15-second sample rate is more than sufficient.

### Batch writing

`PriceTickBatchWriter` runs on a `@Scheduled(fixedDelay = 1000)` loop. Every second, it drains up to 2,000 ticks from the queue and batch-inserts them using a native multi-row `INSERT INTO price_ticks (market_id, price, timestamp, volume) VALUES (...), (...), ...` statement.

**Why native SQL instead of Hibernate `saveAll()`?** Hibernate's `IDENTITY` strategy forces one round-trip per row to retrieve generated IDs. Native batch insert sends one statement for the whole batch.

**What if the batch write fails?** The ticks are dropped, not re-queued. They already exist in the in-memory cache for real-time display. If historical data has gaps, `PriceBackfillService` fills them from Polymarket's CLOB REST API when a user opens the market detail chart.

### WebSocket resilience

- **Startup ordering:** `SmartLifecycle` with `phase = MAX_VALUE` ensures `MarketSyncService` has populated the market list and token mappings before the WebSocket connects
- **10-second startup delay:** Extra buffer for `MarketSyncService` to finish its first sync
- **Exponential backoff:** 1s, 2s, 4s, 8s, 16s, 30s cap on reconnection attempts via a single daemon thread
- **Gap detection:** On reconnect, if the gap is 2–60 minutes, triggers async backfill for all markets via `PriceBackfillService`
- **Token remapping:** On every `MarketsSyncedEvent` (fired by `MarketSyncService` after each 5-minute sync), `PriceIngestionService` reloads the token-to-market mapping and re-subscribes on the existing session to pick up new markets

### Buffer overflow

If the 50,000-entry queue fills up, new ticks are silently dropped. A counter tracks dropped ticks and logs every 1,000th drop. This is acceptable because:

1. The in-memory cache already has the latest price
2. The SSE event was already published
3. Only the DB historical record is lost, and it's recoverable via backfill

---

## 2. Market Sync

**File:** `MarketSyncService`

### How markets are fetched

`MarketSyncService.syncMarkets()` runs on `@Scheduled(fixedDelay = 300000)` — every 5 minutes. It queries Polymarket's Gamma API (`GET /events?active=true&closed=false&order=volume24hr&limit=50`) and paginates up to 12 pages of 50 events (600 markets max, most liquid first).

For each event, it extracts: `conditionId`, `clobTokenId`, `question`, `category`, `yesPrice`, `noPrice`, `volume24h`, `liquidity`, `endDate`. It upserts each market in the `markets` table using `conditionId` as the natural key.

**Why volume-ordered, limited to 600?** Polymarket has thousands of markets, most with zero activity. Volume ordering ensures we track the markets people actually trade on. 600 is a pragmatic ceiling that keeps the sparkline query fast and the dashboard useful.

### Where market data flows after sync

After syncing, `MarketSyncService` publishes `MarketsSyncedEvent(totalSynced)`. This triggers:

1. **`PriceIngestionService`** — reloads the `tokenToMarket` mapping and re-subscribes on the WebSocket to include any new markets
2. **`NewsIngestionService`** — on the very first event only, starts its first news poll (won't poll before markets exist)
3. **`MarketCacheService`** — on its next 60-second refresh cycle, picks up the new markets from DB

### Market lifecycle

- **New market found:** Created in DB if not expired and has a valid price
- **Existing market updated:** Price, volume, liquidity, end date, active flag refreshed
- **Expiration detection:** Closed, not accepting orders, price settled (>97% or <3%), or past end date → marked `active = false`
- **Staleness cleanup:** Markets not seen in the latest sync AND not synced in >2 hours are deactivated. The 2-hour buffer prevents false deactivations from partial sync failures.

### Category normalization

Polymarket uses granular, inconsistent categories. `MarketSyncService` normalizes them to 8 dashboard categories (politics, geopolitics, sports, crypto, finance, tech, culture, science) using a static `CATEGORY_KEYWORDS` map. The frontend `Dashboard.tsx` renders filter pills for these categories, and each `MarketCard` shows the category as a colored badge. If nothing matches, it falls back to the raw Polymarket category.

---

## 3. News Ingestion & Correlation Pipeline

**Files:** `NewsIngestionService`, `KeywordExtractor`, `CorrelationEngine`, `LlmRelevanceService`

This is the core differentiator of the project. It answers: "Did this news headline move this prediction market?"

### Stage 1: News polling

`NewsIngestionService.fetchAndStoreNews()` runs on `@Scheduled(fixedDelay = 900000)` — every 15 minutes. It fetches headlines from NewsAPI (or GNews, configurable via `polypulse.news.provider`). It rotates categories (general, business, technology, science) across calls to spread API usage.

For each article, the service:
1. Checks `newsEventRepository.existsByUrl()` — skips duplicates
2. Calls `KeywordExtractor.extract(headline)` — tokenizes into meaningful keywords (4+ characters, stopwords removed)
3. Saves a `NewsEvent` to the `news_events` table with: headline, source, url, publishedAt, keywords (stored as a PostgreSQL text array for GIN-indexed queries)
4. Publishes `NewsIngestedEvent { newsEventId, headline, keywords, publishedAt }`

**Ordering dependency:** The service listens for `MarketsSyncedEvent` before its first poll. Without markets in the DB, no correlations can be detected, so polling early wastes API calls.

### Stage 2: Keyword pre-filter (instant, O(n))

`CorrelationEngine.onNewsIngested()` receives the event and calls `checkCorrelations(newsEvent)`. The first step loads all active markets from `MarketCacheService.getActiveMarkets()` (the in-memory volatile cache, not the DB) and scans each market's `question` field for shared keywords with the headline.

This narrows 600 markets down to ~15 candidates. Markets are excluded if:

- Price is >97% or <3% (essentially settled)
- Past their end date
- Beyond the `maxCandidateMarkets` limit (30)

**Why not skip this and send everything to the LLM?** Cost and latency. Claude Haiku is fast and cheap, but sending 600 markets per headline would cost ~$0.10/call and take seconds. The keyword filter costs zero and runs in microseconds.

### Stage 3: LLM validation (Claude Haiku)

The surviving candidates go to `LlmRelevanceService.checkRelevance()` in a single batch call to Claude Haiku. The prompt includes the headline and all candidate market questions. The system prompt is deliberately strict — it explicitly lists false positive patterns to reject:

- Same country/location but different topics
- Same sector but unrelated events
- Shared keywords without semantic connection
- News about **consequences** of an outcome vs. the outcome itself (e.g., "oil prices surge" doesn't validate a war prediction market)

Claude returns a JSON array of `RelevantMarket { marketId, reasoning, relevanceScore }`. Only scores >= 0.75 pass.

**Why 0.75?** Lower thresholds produced too many spurious correlations (e.g., any economic headline matching every finance market). 0.75 catches genuine causal links while filtering noise.

**What if the API key is missing?** `LlmRelevanceService` returns an empty list. No correlations are generated. The system degrades gracefully — markets and prices still work, just no AI analysis.

### Stage 4: Price delta check

For each LLM-validated match, `CorrelationEngine.evaluateAndSave()`:

1. Queries `price_ticks` for the market price **before** the article was published (15-minute window, falling back to 24h)
2. Queries for the price **after** publication (45-minute window, or uses the current synced price if >30min have passed)
3. Computes `priceDelta = priceAfter - priceBefore`
4. Requires >1% absolute price change (`min-price-delta: 0.01`)

**Confidence formula:**

```
magnitudeScore = min(abs(priceDelta) / 0.10, 1.0)
confidence = 0.6 * llmScore + 0.4 * magnitudeScore
```

LLM opinion is weighted 60% because the model understands causation better than price magnitude alone. A 0.9x penalty is applied if tick data is sparse (relies on synced price instead of actual ticks).

If confidence passes the minimum threshold, the correlation is saved to the `correlations` table and a `CorrelationDetectedEvent` is published. `SseEventBridge` picks this up and broadcasts a `CorrelationDTO` to all SSE clients. The browser's `useEventStream` hook prepends it to the correlation feed in `Correlations.tsx`.

### Deduplication and cooldown

- **Database constraint:** `UNIQUE(market_id, news_event_id)` prevents duplicate correlations at the DB level
- **Market cooldown:** 30 minutes between correlations for the same market, prevents the same story reported by multiple outlets from generating duplicate entries
- **Retroactive recheck:** `CorrelationEngine` runs `@Scheduled(fixedDelay = 900000)` — every 15 minutes, re-evaluates news from the past 6 hours. This catches correlations where the price hadn't moved yet at initial detection but has since shifted

---

## 4. Real-Time Delivery (SSE)

**Files:** `SseConnectionManager`, `SseEventBridge`, `RedisEventPublisher`, `RedisEventSubscriber`

### What data flows through SSE

The SSE connection carries four event types, each as a named Server-Sent Event:

| Event Name | Data Shape | Source | Consumer |
|------------|-----------|--------|----------|
| `connected` | `{ clientId, connections }` | On connection | `useEventStream` sets `isConnected = true` |
| `price_update` | `{ marketId, price, timestamp }` | Every price tick (throttled 1/sec/market) | `Dashboard.tsx` overlays live prices on `MarketCard` |
| `correlation` | `{ id, market, news, priceDelta, confidence, reasoning }` | On correlation detection | `Correlations.tsx` prepends to feed, `Dashboard.tsx` shows badge |
| `heartbeat` | `{ timestamp, connections }` | Every 30 seconds | `useEventStream` keeps `isConnected = true` |

### Why SSE over WebSocket?

SSE is simpler. The data flow is server-to-client only — browsers don't send price data back. SSE works over standard HTTP, survives proxy/CDN layers better, and auto-reconnects natively via `EventSource`. WebSocket would add bidirectional complexity for no benefit.

### Connection lifecycle

1. `Dashboard.tsx` mounts and calls `useEventStream('/api/stream/live')`, which opens a browser `EventSource`
2. The request hits `StreamController`, which calls `SseConnectionManager.registerClient()`
3. Server creates `SseEmitter` with 5-minute (300,000ms) timeout, generates a UUID `clientId`, stores both in `ConcurrentHashMap<String, SseEmitter> clients`
4. Server sends `connected` event with clientId and current connection count
5. `@Scheduled(fixedDelay = 30000)` sends `heartbeat` events every 30 seconds to keep the connection alive through proxies and load balancers
6. On any send failure (`Exception`, not just `IOException`), `removeClient()` is called: removes from `clients` map, removes from `marketSubscriptions` map, calls `emitter.complete()`

**Why catch `Exception` and not just `IOException`?** `SseEmitter.send()` throws `IllegalStateException` when the emitter has timed out or been completed. Catching only `IOException` leaves dead emitters in the map. They crash on every broadcast tick and eventually exhaust the async thread pool.

### Frontend buffering

`useEventStream` does not flush every SSE event directly to React state. Instead:

- Price updates are deduplicated by `marketId` in a `pendingPrices` Map — only the latest price per market is kept
- Correlations accumulate in a `pendingCorrelations` array (max 50, newest first)
- A `setInterval(1000ms)` flushes both pending buffers to React state, then clears them

This prevents React from re-rendering hundreds of times per second when price ticks are flowing at high volume.

### Market-scoped subscriptions

The `MarketDetail.tsx` page opens a separate SSE connection to `/api/stream/markets/{id}`. `SseConnectionManager.registerMarketClient(marketId)` stores the subscription in `ConcurrentHashMap<String, Long> marketSubscriptions`. During broadcast, `broadcastPriceUpdate()` checks: if a client has a market subscription, only send if `update.marketId` matches. This reduces bandwidth for the detail page — it only receives ticks for the market being viewed.

### Throttling

`SseEventBridge` maintains a `ConcurrentHashMap<Long, Instant> lastSentTimes` — one entry per market. On each `PriceTickEvent`, it checks if <1 second has passed since the last send for that market. If so, the event is dropped. Without this, the Polymarket WebSocket's raw volume would flood clients with redundant updates.

### Horizontal scaling with Redis pub/sub

When `polypulse.redis.enabled=true`, `SseEventBridge` changes its broadcast target:

1. Instead of calling `SseConnectionManager.broadcastPriceUpdate()` directly, it calls `RedisEventPublisher.publishPriceUpdate(dto)`, which serializes the DTO to JSON and publishes to Redis channel `polypulse:price_updates`
2. `RedisEventSubscriber` (implementing `MessageListener` directly, registered in `RedisConfig`) runs on every backend instance. It receives the raw message bytes, deserializes with Jackson `ObjectMapper`, and calls `SseConnectionManager.broadcastPriceUpdate()` locally
3. Each instance broadcasts only to its own connected SSE clients
4. Same pattern for correlations on channel `polypulse:correlations`

**Why `MessageListener` instead of `MessageListenerAdapter`?** `MessageListenerAdapter` uses `JdkSerializationRedisSerializer` by default, but `StringRedisTemplate` publishes with `StringRedisSerializer`. This mismatch causes silent message drops — the subscriber receives bytes it can't deserialize and discards them. Implementing `MessageListener` directly and reading raw bytes with `new String(message.getBody(), UTF_8)` avoids the serialization layer entirely.

**Why Redis pub/sub over Kafka?** Redis is already in the stack for caching. Pub/sub adds zero new infrastructure. The messages are ephemeral price updates — if one is lost, the next arrives in a second. Kafka's durability guarantees are unnecessary overhead for this use case.

**Fallback:** When Redis is disabled, `SseEventBridge` checks `Optional<RedisEventPublisher>`. If empty, it calls `SseConnectionManager.broadcastPriceUpdate()` directly. Single-instance only, but zero external dependencies.

---

## 5. Caching Strategy

### Layer 1: PriceCacheService (in-memory, real-time)

A `ConcurrentHashMap<Long, CachedPrice>` holds the latest price per market. `CachedPrice` is a record containing `{ BigDecimal price, Instant timestamp, String conditionId }`. Updated atomically via `.compute()` on every WebSocket tick — only if the new timestamp is newer.

**Who reads this cache:**
- `MarketController.getMarkets()` calls `priceCacheService.getAllLatestPrices()` to overlay live prices on the REST response
- The cache is never queried from the database. It starts empty on boot and fills as WebSocket ticks arrive

**Why not Redis for this?** Latency. A ConcurrentHashMap lookup is nanoseconds. A Redis GET is milliseconds. For prices that update hundreds of times per second, in-memory wins.

### Layer 2: MarketCacheService (background refresh, 60s)

`MarketCacheService.refreshCaches()` runs on `@Scheduled(fixedDelay = 60000, initialDelay = 5000)`. It rebuilds three `volatile` caches that `MarketController` reads on every `/api/markets` request:

- **`cachedMarkets: List<Market>`** — `marketRepository.findByActiveTrue()`, one query
- **`cachedSparklines: Map<Long, List<SparklinePoint>>`** — `priceTickRepository.findSparklineData()` using `date_bin` aggregation, batched 200 markets per query to avoid oversized `IN` clauses
- **`cachedCorrelationMarketIds: Set<Long>`** — `correlationRepository.findAllMarketIdsWithCorrelations()`, tells `MarketCard` whether to show the lightning bolt "NEWS" badge

**Why batch sparklines in groups of 200?** The original implementation sent all market IDs in a single `WHERE market_id IN (...)` clause. With 5,000+ bind parameters, PostgreSQL's query planner consumed excessive memory, filled the WAL log, and crashed the database when the 8GB disk ran out. Batching at 200 keeps each query manageable.

**Why 60-second refresh?** Sparklines show 24-hour trends in 72-minute buckets. Refreshing more often wastes DB resources for no visible change. Less often and new markets take too long to appear on the dashboard.

### Layer 3: PostgreSQL (persistent, retention-managed)

`price_ticks` are purged after 7 days by `MarketCacheService.purgeOldPriceTicks()`, which runs on `@Scheduled(fixedDelay = 6 * 60 * 60 * 1000)` — every 6 hours. It calls `priceTickRepository.deleteOlderThan(Instant cutoff)` which uses the existing `idx_ticks_time` index for efficient deletion.

**Why 7 days?** Sparklines only show 24 hours. The market detail price chart shows up to 7 days. Keeping more than that grows the table unboundedly — which is exactly what caused the database disk full crash.

---

## 6. Failure Handling

### Database connection exhaustion

HikariCP is configured with `maxPoolSize=15, minIdle=5, connectionTimeout=5000ms`. Under load, the pool fills and requests wait up to 5 seconds. If the pool is exhausted, requests fail fast rather than queueing indefinitely.

**Why 15 connections?** Railway's PostgreSQL has a 97-connection limit. 15 leaves headroom for background jobs (`MarketCacheService` refresh, retention purge, `PriceTickBatchWriter`) and admin connections.

### Backfill circuit breaker

`PriceBackfillService` tracks failed API calls per market with a 300-second cooldown. If Polymarket's CLOB API is down, we don't hammer it. After the cooldown, the next request retries. Backfill is triggered by `MarketController` when a user opens the price chart (`GET /api/markets/{id}/prices`) and the requested time range has sparse data.

### LLM unavailability

If `ANTHROPIC_API_KEY` is empty, `LlmRelevanceService` logs a warning and returns zero matches. Correlations stop being generated, but markets, prices, and SSE all continue working. If the API returns an error or unparseable response, the batch is skipped (logged at ERROR) and retried on the next 15-minute news cycle.

### SSE client disconnection

`SseEmitter` provides three callbacks: `onCompletion`, `onTimeout`, `onError`. All three call `removeClient(clientId)`, which: removes the emitter from the `clients` map, removes any entry from `marketSubscriptions`, and calls `emitter.complete()` (wrapped in try/catch because it may already be completed). On the browser side, `EventSource` auto-reconnects natively — the hook sets `isConnected = false` on error and the connection re-establishes itself.

### WebSocket disconnection

`PriceIngestionService`'s `onClose` and `onError` handlers call `scheduleReconnect()`. This runs on a single-threaded `ScheduledExecutorService` with exponential backoff (1s, 2s, 4s, 8s, 16s, 30s cap). On reconnect, if the gap was 2–60 minutes, async backfill is triggered for all active markets. If the executor is shut down (application stopping), reconnection is skipped.

### Batch write failures

Logged at ERROR, ticks dropped. No data loss for real-time display (in-memory cache already updated, SSE event already published). Historical gaps are filled by `PriceBackfillService` when a user opens the market detail chart.

### Startup ordering

| Phase | Service | Depends On | Triggered By |
|-------|---------|------------|-------------|
| Default (initialDelay 5s) | `MarketSyncService` | Database | Boot |
| After first sync | `NewsIngestionService` | Markets in DB | `MarketsSyncedEvent` |
| `SmartLifecycle MAX_VALUE` | `PriceIngestionService` | Market token mappings | `MarketsSyncedEvent` reloads mappings |

This prevents the WebSocket from connecting before markets exist (no token mappings = nothing to subscribe to), and news from being fetched before markets can be correlated against (no candidates = wasted API calls).

---

## 7. Database Schema

```
markets
  id (PK), condition_id (UNIQUE), clob_token_id, question, category,
  active, outcome_yes_price, outcome_no_price, volume_24h, liquidity,
  end_date, last_synced_at, created_at
  Indexes: active (partial WHERE TRUE), condition_id
  Written by: MarketSyncService (every 5 min)
  Read by: MarketCacheService (every 60s), PriceIngestionService (token mapping)

price_ticks
  id (PK), market_id (FK), price, timestamp, volume
  Indexes: (market_id, timestamp DESC), (timestamp DESC)
  Written by: PriceTickBatchWriter (every 1s, sampled at 15s per market)
  Read by: MarketCacheService (sparklines), MarketController (price history charts),
           CorrelationEngine (before/after price lookup)
  Purged by: MarketCacheService.purgeOldPriceTicks() (every 6h, >7 days)

news_events
  id (PK), headline, source, url (UNIQUE), published_at, keywords (GIN),
  category
  Indexes: published_at DESC, keywords (GIN)
  Written by: NewsIngestionService (every 15 min)
  Read by: CorrelationEngine (correlation detection + retroactive rechecks)

correlations
  id (PK), market_id (FK), news_event_id (FK), price_before, price_after,
  price_delta, time_window_ms, confidence, detected_at, reasoning
  Indexes: (market_id, detected_at DESC), confidence DESC
  Constraints: UNIQUE(market_id, news_event_id)
  Written by: CorrelationEngine
  Read by: MarketCacheService (correlation market IDs for badges),
           MarketController (market detail correlations page),
           CorrelationController (paginated correlation feed)
```

**Why GIN index on keywords?** Array containment queries (`@>`) are the core of the keyword pre-filter. GIN makes these O(log n) instead of sequential scans.

**Why partial index on active?** Only ~20% of markets are active at any time. A partial index is smaller and faster than indexing the full table.

---

## 8. Configuration Reference

```yaml
polypulse:
  sync:
    market-interval-ms: 300000       # MarketSyncService sync every 5 minutes
  news:
    poll-interval-ms: 900000         # NewsIngestionService poll every 15 minutes
    provider: newsapi                # newsapi or gnews
  correlation:
    min-price-delta: 0.01            # 1% minimum price change for correlation
    before-window-minutes: 15        # CorrelationEngine: price lookup before news
    after-window-minutes: 45         # CorrelationEngine: price lookup after news
    min-confidence: 0.3              # Minimum confidence to save correlation
    max-candidate-markets: 30        # Cap keyword-filtered candidates per headline
    recheck-delay-minutes: 15        # Retroactive recheck interval
    cooldown-minutes: 30             # Per-market correlation cooldown
  redis:
    enabled: false                   # SseEventBridge: Redis pub/sub vs direct broadcast

spring:
  datasource:
    hikari:
      maximum-pool-size: 15          # Railway PG limit is 97, leave headroom
      minimum-idle: 5
      connection-timeout: 5000
  jpa:
    hibernate:
      ddl-auto: validate             # Flyway-only migrations, never auto-DDL
      batch_size: 50
```

### Feature flags

| Flag | Default | Effect When Missing |
|------|---------|-----------|
| `polypulse.redis.enabled` | `false` | `SseEventBridge` broadcasts directly via `SseConnectionManager` (single instance only) |
| `ANTHROPIC_API_KEY` | empty | `LlmRelevanceService` returns empty list, no correlations generated |
| `NEWS_API_KEY` | empty | `NewsIngestionService` skips polling, no news ingested |

All three are optional. The system degrades gracefully: no Redis = local broadcast, no API key = no correlations, no news key = no news ingestion. Markets and live prices work regardless.
