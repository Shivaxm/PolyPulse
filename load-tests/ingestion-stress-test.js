import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const healthLatency = new Trend('health_check_latency');

export const options = {
  stages: [
    { duration: '2m', target: 20 },
    { duration: '5m', target: 50 },
    { duration: '2m', target: 10 },
    { duration: '1m', target: 0 },
  ],
};

export default function () {
  // Mix of API requests while monitoring ingestion health
  const rand = Math.random();

  if (rand < 0.3) {
    // Check ingestion health
    const start = Date.now();
    const res = http.get(`${BASE_URL}/api/health`);
    healthLatency.add(Date.now() - start);

    if (res.status === 200) {
      const health = JSON.parse(res.body);
      check(res, {
        'ws connected': () => health.wsConnected === true,
        'no ticks dropped': () => health.ticksDropped === 0,
        'queue not backed up': () => health.queueDepth < 5000,
        'ticks flowing': () => health.ticksReceived > 0,
      });
    }
  } else if (rand < 0.6) {
    // Hit markets API
    http.get(`${BASE_URL}/api/markets`);
  } else if (rand < 0.8) {
    // SSE connection (short-lived)
    http.get(`${BASE_URL}/api/stream/live`, {
      timeout: '10s',
      headers: { Accept: 'text/event-stream' },
    });
  } else {
    // Correlations
    http.get(`${BASE_URL}/api/correlations/recent`);
  }

  sleep(2);
}
