# PolyPulse

Real-time prediction market dashboard that correlates news events with Polymarket price movements using a two-stage ML pipeline (keyword pre-filter + Claude Haiku LLM validation).

**Live:** https://polypulse.up.railway.app

## Architecture

```text
                     React Frontend (SSE)
                           |
                     Load Balancer
                      /         \
               Instance A    Instance B
                    |              |
              Spring Boot     Spring Boot
                    \             /
                   Redis Pub/Sub
                  (event fanout)
                   /           \
     Price Ingestion    News Ingestion
      (WebSocket)       (REST Poller)
           |                  |
           v                  v
      Polymarket           NewsAPI
       WS Feed
           \                /
            \              /
         Correlation Engine
        (keyword + LLM pipeline)
                |
                v
           PostgreSQL
```

## Tech Stack

- **Backend:** Java 21, Spring Boot 3.4, Spring Data JPA, Spring Events
- **Database:** PostgreSQL 16+ (Flyway migrations, HikariCP connection pool)
- **Cache:** In-memory ConcurrentHashMap with 60s background refresh
- **Message Bus:** Redis Pub/Sub for cross-instance SSE event fanout
- **Frontend:** React 19, TypeScript, Vite, Tailwind CSS, Recharts
- **Real-time:** WebSocket (upstream from Polymarket), Server-Sent Events (downstream to clients)
- **Observability:** Micrometer percentile histograms, custom /api/metrics endpoint
- **Load Testing:** k6 (API stress, SSE connections, realistic scenario)
- **Deployment:** Docker (single container), Railway

## Scale & Performance

Load tested with [k6](https://k6.io/) against the live Railway deployment — 1,000 concurrent users (mixed API requests + SSE connections), **90ms p95 latency, 0% error rate, 121 req/s sustained throughput.**

### Before vs After: In-Memory Caching

| Metric | Before (no cache) | After (in-memory cache) |
|--------|-------------------|-------------------------|
| Concurrent users | 500 (crashed) | **1,000 (sustained)** |
| p95 Latency | 15,020 ms | **90 ms** |
| Error Rate | 100% | **0%** |
| Throughput | 13 req/s | **121 req/s** |

Without caching, 500 concurrent users exhaust the HikariCP connection pool (15 connections). Requests queue, timeouts cascade, and the app crashes with 502s. `MarketCacheService` fixes this by precomputing market data every 60 seconds on a background thread — API reads serve from a `ConcurrentHashMap`, eliminating all DB queries per page load.

### Horizontal Scalability

Redis Pub/Sub decouples event producers from SSE consumers. Price updates and correlation events publish to Redis channels; every app instance subscribes and fans out to its local SSE clients. This enables N instances behind a load balancer, each serving thousands of SSE connections independently.

Enabled via `REDIS_ENABLED=true` with graceful fallback to local broadcast when disabled.

### Load Test Suite

```bash
# Install k6
brew install k6

# Run all tests against live deployment
./loadtest/run-all.sh https://polypulse.up.railway.app

# Run individual tests
k6 run -e BASE_URL=https://polypulse.up.railway.app loadtest/scripts/api-load.js
k6 run -e BASE_URL=https://polypulse.up.railway.app loadtest/scripts/sse-connections.js
k6 run -e BASE_URL=https://polypulse.up.railway.app loadtest/scripts/realistic-scenario.js
```

## Key Design Decisions

- **50k bounded queue with 15-second sampling:** Real-time prices update in-memory instantly; DB writes are sampled to ~1 tick per market per 15 seconds via native JDBC batch INSERT, reducing write volume 95%
- **Two-stage correlation pipeline:** Cheap keyword pre-filter narrows 600+ markets to ~15 candidates, then a single Claude Haiku batch call validates semantic relevance. Keeps LLM costs under $2/month while eliminating false positives
- **In-memory background precomputation:** Market list and sparklines are computed every 60 seconds on a background thread. API reads from memory — zero DB queries per page load
- **Redis Pub/Sub over Kafka:** Lightweight event fanout for SSE broadcasting without the operational overhead of a message broker. Feature-flagged for single-instance deployments
- **SSE over WebSocket for frontend:** Client only receives data; SSE is simpler, auto-reconnects, works through proxies
- **Spring Events as internal bus:** Loose coupling between ingestion, correlation, and broadcast without external dependencies
- **WebSocket dual-format parsing:** Handles both pre and post September 2025 Polymarket `price_change` schemas with automatic format detection
- **Single-container deploy:** Maven builds frontend + backend into one jar; no nginx needed

## Quick Start

### Prerequisites

- Java 21
- Docker & Docker Compose

### Run with Docker Compose

```bash
NEWS_API_KEY=your-key ANTHROPIC_API_KEY=your-key docker compose up --build
```

Open http://localhost:8080

### Development Mode

```bash
# Start postgres + redis
docker compose up postgres redis -d

# Start backend (project root)
NEWS_API_KEY=your-key ANTHROPIC_API_KEY=your-key ./mvnw spring-boot:run

# Start frontend (separate terminal)
cd polypulse-web
npm install
npm run dev
```

## Environment Variables

| Variable | Required | Description |
|----------|----------|-------------|
| `PGHOST` | Yes | PostgreSQL host |
| `PGPORT` | Yes | PostgreSQL port |
| `PGDATABASE` | Yes | PostgreSQL database name |
| `PGUSER` | Yes | PostgreSQL user |
| `PGPASSWORD` | Yes | PostgreSQL password |
| `NEWS_API_KEY` | No | NewsAPI key (news ingestion disabled if blank) |
| `ANTHROPIC_API_KEY` | No | Anthropic API key (LLM validation disabled if blank) |
| `REDIS_HOST` | No | Redis host (defaults to localhost) |
| `REDIS_PORT` | No | Redis port (defaults to 6379) |
| `REDIS_ENABLED` | No | Enable Redis Pub/Sub (defaults to false) |

## API Endpoints

| Endpoint | Description |
|----------|-------------|
| `GET /api/health` | Service health + ingestion metrics |
| `GET /api/metrics` | Server-side latency percentiles + connection pool stats |
| `GET /api/markets` | Active markets with live prices and sparklines |
| `GET /api/markets/{id}` | Single market detail |
| `GET /api/markets/{id}/prices?range=24h` | Price history (1h, 6h, 24h, 7d) |
| `GET /api/markets/{id}/correlations` | Correlations for one market |
| `GET /api/correlations/recent` | Latest correlations across markets |
| `GET /api/stream/live` | SSE stream: price updates + correlations |
| `GET /api/stream/markets/{id}` | SSE stream for one market |

## Tests

```bash
mvn test
```

Unit tests cover:

- Keyword extraction and stopword filtering
- WebSocket message parsing (legacy + post-September 2025 schema)
- LLM prompt construction and response parsing
- Correlation engine stages (candidate selection, LLM filtering, price delta, confidence, duplicate prevention)
- SSE emitter cleanup behavior
- In-memory price cache ordering guarantees
- DTO native-row mapping

Integration test covers full correlation pipeline with Testcontainers PostgreSQL.
