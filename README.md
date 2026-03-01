# PolyPulse

Real-time prediction market dashboard that correlates news events with Polymarket price movements.

## Architecture

```
                    React Frontend (SSE)
                          |
                    Spring Boot API
                   /       |       \
        Price Ingestion  Correlation  News Ingestion
        (WebSocket)       Engine      (REST Poller)
              |              |              |
              v              v              v
         Polymarket    PostgreSQL       NewsAPI
         WS Feed       + Redis
```

**Data flow:** Polymarket WebSocket -> bounded queue -> batch writer -> Postgres. News API -> keyword extraction -> correlation engine -> SSE fan-out -> React charts.

## Tech Stack

- **Backend:** Java 21, Spring Boot 3.4, Spring Data JPA, Spring Events
- **Database:** PostgreSQL 16 (Flyway migrations), Redis 7 (caching)
- **Frontend:** React 18, TypeScript, Vite, Tailwind CSS, Recharts
- **Real-time:** WebSocket (upstream), Server-Sent Events (downstream)
- **Deployment:** Docker, Docker Compose

## Run Locally

### Prerequisites

- Java 21
- Maven
- Node.js 18+
- Docker & Docker Compose

### Quick Start (Docker Compose)

```bash
# Start everything
NEWS_API_KEY=your-key-here docker compose up --build

# Frontend: http://localhost:3000
# API: http://localhost:8080/api/health
```

### Development Mode

```bash
# Start databases
docker compose up postgres redis -d

# Start backend
export NEWS_API_KEY=your-key-here
export JAVA_HOME=/path/to/java-21
mvn spring-boot:run

# Start frontend (separate terminal)
cd polypulse-web
npm install
npm run dev
# Frontend: http://localhost:5173
```

## API Endpoints

| Endpoint | Description |
|---|---|
| `GET /api/health` | Service health + ingestion metrics |
| `GET /api/markets` | Active markets with latest prices |
| `GET /api/markets/{id}` | Single market detail |
| `GET /api/markets/{id}/prices?range=24h` | Price history (1h, 6h, 24h, 7d) |
| `GET /api/markets/{id}/correlations` | Correlations for a market |
| `GET /api/correlations/recent` | Latest correlations across all markets |
| `GET /api/stream/live` | SSE stream: price updates + correlations |
| `GET /api/stream/markets/{id}` | SSE stream for a specific market |

## Load Testing

```bash
# API load test
k6 run load-tests/api-load-test.js

# SSE connection test
k6 run load-tests/sse-load-test.js

# Full ingestion stress test
k6 run load-tests/ingestion-stress-test.js
```

## Key Design Decisions

- **Bounded queue (10k) with drop-on-full:** Connection stability > data completeness for high-frequency ticks
- **Batch writes every 2s:** Reduces Postgres write amplification; SSE reads from in-memory event bus for real-time
- **SSE over WebSocket for frontend:** Client only receives data; SSE is simpler, auto-reconnects, works through proxies
- **Spring Events as internal bus:** No Kafka needed for single-process event routing
- **Redis cache with per-key TTLs:** Market list (60s), market detail (5m); price history uncached (well-indexed)
- **Keyword matching over NLP:** Simple, fast, sufficient for Polymarket's explicit market questions

See [design doc](polypulse-design-doc.md) for full architectural rationale and scaling discussion.
