# PolyPulse k6 Load Test Suite

This suite stress-tests the live PolyPulse deployment (`https://polypulse.up.railway.app`) across API, SSE, and mixed real-user traffic.

## Prerequisites

Install [k6](https://k6.io/docs/get-started/installation/):

```bash
brew install k6
```

Or use the official installers from the k6 docs for Linux/Windows.

## Test Layout

```text
loadtest/
  scripts/
    common.js
    api-load.js
    sse-connections.js
    realistic-scenario.js
  run-all.sh
  README.md
```

## Run All Tests

```bash
./loadtest/run-all.sh
```

Run against a custom target URL:

```bash
./loadtest/run-all.sh http://localhost:8080
```

Results are written to timestamped directories under `loadtest/results/` with:
- raw JSON metrics (`*.json`)
- summary exports (`*-summary.json`)
- console logs (`*.log`)

## Run Individual Tests

```bash
k6 run -e BASE_URL=https://polypulse.up.railway.app loadtest/scripts/api-load.js
k6 run -e BASE_URL=https://polypulse.up.railway.app loadtest/scripts/sse-connections.js
k6 run -e BASE_URL=https://polypulse.up.railway.app loadtest/scripts/realistic-scenario.js
```

## Interpreting Results

- **p50**: median request latency
- **p95**: 95% of requests complete below this latency
- **p99**: tail latency (slowest 1%)
- **http_req_failed**: request error rate
- **http_reqs**: throughput (requests/sec)

Thresholds in each script are pass/fail guards for acceptable performance under load.

## Results

Fill this after running the suite:

| Metric | API Load | SSE Connections | Realistic |
|---|---:|---:|---:|
| VUs (peak) | 500 | 1000 | 700 |
| p50 latency | - | - | - |
| p95 latency | - | - | - |
| p99 latency | - | - | - |
| Throughput | - | - | - |
| Error rate | - | - | - |

