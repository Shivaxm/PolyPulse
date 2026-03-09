import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';
import { BASE_URL, fetchMarketIds, randomItem } from './common.js';

const sseSuccess = new Rate('sse_connection_success');

export const options = {
  stages: [
    { duration: '30s', target: 100 },
    { duration: '1m', target: 100 },
    { duration: '30s', target: 500 },
    { duration: '1m', target: 500 },
    { duration: '30s', target: 1000 },
    { duration: '1m', target: 1000 },
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    http_req_failed: ['rate<0.10'],
    http_req_duration: ['p(95)<12000'],
  },
};

export function setup() {
  return { marketIds: fetchMarketIds() };
}

export default function (data) {
  const marketId = randomItem(data.marketIds) || 1;
  const useMarketStream = Math.random() < 0.35;

  const path = useMarketStream
    ? `/api/stream/markets/${marketId}`
    : '/api/stream/live';

  const res = http.get(`${BASE_URL}${path}`, {
    timeout: '10s',
    headers: { Accept: 'text/event-stream' },
    tags: {
      name: useMarketStream
        ? 'SSE /api/stream/markets/{id}'
        : 'SSE /api/stream/live',
    },
  });

  const statusOk = res.status === 200;
  const hasSseFrame = typeof res.body === 'string' && (res.body.includes('event:') || res.body.includes('data:'));

  sseSuccess.add(statusOk);

  check(res, {
    'sse status is 200': () => statusOk,
    'sse response includes event framing': () => hasSseFrame,
  });

  // Simulate keeping listener active before reconnecting.
  sleep(5 + Math.random() * 5);
}
