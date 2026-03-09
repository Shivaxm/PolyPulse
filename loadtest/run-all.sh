#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${1:-https://polypulse.up.railway.app}"
RESULTS_DIR="loadtest/results/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$RESULTS_DIR"

if ! command -v k6 >/dev/null 2>&1; then
  echo "k6 is not installed. Install it first (see loadtest/README.md)."
  exit 1
fi

echo "=== PolyPulse Load Test Suite ==="
echo "Target: $BASE_URL"
echo "Results: $RESULTS_DIR"
echo ""

echo "--- Test 1: API Load Test ---"
k6 run --out json="$RESULTS_DIR/api-load.json" \
       --summary-export="$RESULTS_DIR/api-load-summary.json" \
       -e BASE_URL="$BASE_URL" \
       loadtest/scripts/api-load.js 2>&1 | tee "$RESULTS_DIR/api-load.log"

echo ""
echo "--- Test 2: SSE Connection Test ---"
k6 run --out json="$RESULTS_DIR/sse-connections.json" \
       --summary-export="$RESULTS_DIR/sse-connections-summary.json" \
       -e BASE_URL="$BASE_URL" \
       loadtest/scripts/sse-connections.js 2>&1 | tee "$RESULTS_DIR/sse-connections.log"

echo ""
echo "--- Test 3: Realistic Scenario Test ---"
k6 run --out json="$RESULTS_DIR/realistic-scenario.json" \
       --summary-export="$RESULTS_DIR/realistic-scenario-summary.json" \
       -e BASE_URL="$BASE_URL" \
       loadtest/scripts/realistic-scenario.js 2>&1 | tee "$RESULTS_DIR/realistic-scenario.log"

echo ""
echo "=== All tests complete. Results in $RESULTS_DIR ==="
