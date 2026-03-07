# PolyPulse

Real-time prediction market dashboard that correlates news events with Polymarket price movements.

Live: https://polypulse.up.railway.app

## Architecture

```text
                    React Frontend (SSE)
                          |
                    Spring Boot API
                   /       |       \
        Price Ingestion  Correlation  News Ingestion
        (WebSocket)       Engine      (REST Poller)
              |              |              |
              v              v              v
         Polymarket      PostgreSQL      NewsAPI
         WS Feed
```

## Tech Stack

- Backend: Java 21, Spring Boot 3.4, Spring Data JPA, Spring Events
- Database: PostgreSQL 16+ (Flyway migrations)
- Frontend: React 18, TypeScript, Vite, Tailwind CSS, Recharts
- Real-time: WebSocket (upstream), Server-Sent Events (downstream)
- Deployment: Docker (single backend container serves API + built frontend)

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
# Start postgres
docker compose up postgres -d

# Start backend (project root)
NEWS_API_KEY=your-key ANTHROPIC_API_KEY=your-key ./mvnw spring-boot:run

# Start frontend (separate terminal)
cd polypulse-web
npm install
npm run dev
```

## Environment Variables

- `PGHOST`
- `PGPORT`
- `PGDATABASE`
- `PGUSER`
- `PGPASSWORD`
- `NEWS_API_KEY`
- `ANTHROPIC_API_KEY`
- `CORS_ALLOWED_ORIGINS` (optional, comma-separated)

## API Endpoints

| Endpoint | Description |
|---|---|
| `GET /api/health` | Service health + ingestion metrics |
| `GET /api/markets` | Active markets with latest prices |
| `GET /api/markets/{id}` | Single market detail |
| `GET /api/markets/{id}/prices?range=24h` | Price history (1h, 6h, 24h, 7d) |
| `GET /api/markets/{id}/correlations` | Correlations for one market |
| `GET /api/correlations/recent` | Latest correlations across markets |
| `GET /api/stream/live` | SSE stream: price updates + correlations |
| `GET /api/stream/markets/{id}` | SSE stream for one market |

## Key Design Decisions

- **50k bounded queue with 15-second sampling:** Real-time prices update in-memory instantly; DB writes are sampled to ~1 tick per market per 15 seconds via native JDBC batch INSERT, reducing write volume 95%
- **Two-stage correlation pipeline:** Cheap keyword pre-filter narrows 600+ markets to ~15 candidates, then a single Claude Haiku batch call validates semantic relevance. Keeps LLM costs under $2/month while eliminating false positives
- **In-memory background precomputation:** Market list and sparklines are computed every 60 seconds on a background thread. API reads from memory — zero DB queries per page load
- **SSE over WebSocket for frontend:** Client only receives data; SSE is simpler, auto-reconnects, works through proxies
- **Spring Events as internal bus:** No Kafka needed for single-process event routing
- **WebSocket dual-format parsing:** Handles both pre and post September 2025 Polymarket `price_change` schemas with automatic format detection
- **Single-container deploy:** Maven builds frontend + backend into one jar; no nginx needed

## Tests

```bash
mvn test
```

Unit tests cover:

- keyword extraction
- WebSocket message parsing (legacy + post-September 2025 schema)
- LLM prompt construction and response parsing
- correlation engine stages (candidate selection, LLM filtering, price delta, confidence, duplicate prevention)
- SSE emitter cleanup behavior
- in-memory price cache ordering guarantees
- DTO native-row mapping

Integration test covers full correlation pipeline with Testcontainers PostgreSQL.
