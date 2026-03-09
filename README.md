# PolyPulse

A real-time dashboard that watches 600+ Polymarket prediction markets, ingests breaking news, and uses an LLM to detect when a news event moves a market's price. Think Bloomberg Terminal meets prediction markets.

**Live:** https://polypulse.up.railway.app

## How It Works

1. A WebSocket connection streams live prices from Polymarket's orderbook for every active market
2. A news poller pulls headlines from NewsAPI every 15 minutes
3. When a new headline arrives, a two-stage pipeline finds affected markets:
   - **Stage 1 (instant):** Keyword extraction filters 600+ markets down to ~15 candidates
   - **Stage 2 (LLM):** A single Claude Haiku batch call determines which markets are *actually* affected, with reasoning (e.g., distinguishing "Florida" in a SpaceX launch vs. a Panthers game)
4. For each validated match, it checks if the market price moved within a time window around the article and records the correlation with confidence score
5. Everything streams to the frontend via Server-Sent Events in real time

## Architecture

```
    Browser (React + SSE)
            |
    +-------+-------+
    |  Spring Boot   |
    |  REST + SSE    |-----> /api/markets, /api/correlations, /api/stream/live
    +---+---+---+---+
        |   |   |
        |   |   +-- MarketCacheService -------> ConcurrentHashMap (refreshed every 60s)
        |   |
        |   +------ Correlation Engine
        |           1. Keyword pre-filter (O(n), instant)
        |           2. Claude Haiku batch call (single API request)
        |           3. Price delta check (before/after news timestamp)
        |
        +---------- Price Ingestion
        |           WebSocket -> in-memory cache (instant)
        |                     -> 50k bounded queue -> batch INSERT every 1s
        |
        +---------- News Ingestion
                    REST poll every 15min -> keyword extraction -> correlation trigger

    External:       Polymarket WS    NewsAPI    Claude Haiku    PostgreSQL    Redis
```

## Performance

Tested with [k6](https://k6.io/) against the live Railway deployment.

| Metric | Before (no cache) | After (in-memory cache) |
|--------|-------------------|-------------------------|
| Concurrent users | 500 (crashed) | **1,000 (sustained)** |
| p95 Latency | 15,020 ms | **90 ms** |
| Error Rate | 100% | **0%** |
| Throughput | 13 req/s | **121 req/s** |

**What happened without caching:** Every page load queried PostgreSQL for 600+ markets. At 500 concurrent users, all 15 HikariCP connections were permanently occupied. New requests waited for a connection, timed out after 5 seconds, and the cascade took down the entire app (Railway returned 502s).

**What the cache does:** `MarketCacheService` runs a background thread that queries the database once every 60 seconds and stores the result in a `ConcurrentHashMap`. All API reads serve from memory. The database connection pool never sees user traffic for the heaviest endpoint.

**Horizontal scaling:** Redis Pub/Sub sits between event producers (price ingestion, correlation engine) and SSE consumers (browser connections). Each app instance subscribes to Redis channels and broadcasts to its local clients. This means you can run N instances behind a load balancer without duplicating events or splitting connections. Enabled with `REDIS_ENABLED=true`, falls back to in-process broadcast when off.

## Design Decisions

**Why sample price writes at 15-second intervals instead of writing every tick?**
Polymarket's WebSocket sends thousands of ticks per second across 600 markets. Writing all of them would require ~3,000 INSERT/s sustained, which is expensive and unnecessary for historical charts. Instead, ticks update an in-memory cache instantly (so the live dashboard is always current), and only one tick per market per 15 seconds goes to the database via a batched native JDBC INSERT. This cuts write volume by 95% while keeping the live view real-time.

**Why a two-stage correlation pipeline instead of sending everything to the LLM?**
Cost and latency. Running 600 markets through Claude for every headline would cost ~$50/month and take seconds. The keyword pre-filter runs in microseconds and eliminates 97% of candidates. The LLM only sees ~15 plausible matches per headline, keeping costs under $2/month and latency under 2 seconds. The keyword stage catches obvious matches; the LLM stage catches false positives the keywords can't (like "Turkey" matching both geopolitics and cooking markets).

**Why SSE instead of WebSocket for the frontend?**
The frontend only receives data, never sends it. SSE is simpler (just HTTP), auto-reconnects natively in the browser, works through corporate proxies that block WebSocket upgrades, and doesn't require a separate protocol upgrade handshake. One less thing to debug in production.

**Why Redis Pub/Sub instead of Kafka?**
This is an event fanout problem, not a durable message queue problem. If a price update is missed during a brief disconnect, the next one arrives in seconds. Redis Pub/Sub is operationally simple (one `docker-compose` service), has sub-millisecond latency, and solves the actual problem: letting multiple app instances broadcast the same events to their local SSE clients. Kafka would add ZooKeeper, partitions, consumer groups, and offset management for a problem that doesn't need any of that.

**Why one JAR instead of separate frontend/backend deploys?**
Maven builds the React app during `package` and copies the dist into the JAR's static resources. One container, one deploy, one health check. No nginx, no CORS in production, no separate CDN config. For a project of this size, the operational simplicity outweighs any scaling benefit of splitting them.

## Tech Stack

Java 21, Spring Boot 3.4, PostgreSQL 16, Redis 7, React 19, TypeScript, Vite, Tailwind CSS, Recharts, Docker, Railway

## Quick Start

```bash
# Run everything
NEWS_API_KEY=your-key ANTHROPIC_API_KEY=your-key docker compose up --build

# Open http://localhost:8080
```

Both API keys are optional. Without `NEWS_API_KEY`, news ingestion is disabled. Without `ANTHROPIC_API_KEY`, correlations use keyword matching only (no LLM validation).

## API

| Endpoint | Description |
|----------|-------------|
| `GET /api/markets` | Active markets with live prices and sparklines |
| `GET /api/markets/{id}/prices?range=24h` | Price history (1h, 6h, 24h, 7d) |
| `GET /api/markets/{id}/correlations` | News correlations for a market |
| `GET /api/correlations/recent` | Latest correlations across all markets |
| `GET /api/stream/live` | SSE stream of price updates and new correlations |
| `GET /api/health` | Ingestion metrics, connection status, uptime |

## Tests

```bash
mvn test
```

Unit tests cover the correlation pipeline (keyword extraction, LLM prompt/response parsing, candidate filtering, price delta calculation, confidence scoring, deduplication), WebSocket message parsing for both Polymarket schema versions, SSE connection lifecycle, and price cache consistency. Integration test runs the full pipeline against a Testcontainers PostgreSQL instance.

## Load Tests

```bash
brew install k6
./loadtest/run-all.sh https://polypulse.up.railway.app
```

Three test profiles: API stress (500 VUs ramping), SSE connection saturation (1,000 VUs), and a realistic mixed scenario with a spike test injection.
