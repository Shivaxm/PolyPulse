import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';
import { BASE_URL, fetchMarketIds, randomItem, randomSleep } from './common.js';

const sseConnectionSuccess = new Rate('sse_connection_success');

export const options = {
  scenarios: {
    api_browsers: {
      executor: 'ramping-vus',
      exec: 'apiBrowsing',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 100 },
        { duration: '2m', target: 200 },
        { duration: '30s', target: 0 },
      ],
    },
    sse_listeners: {
      executor: 'ramping-vus',
      exec: 'sseListening',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 200 },
        { duration: '2m', target: 500 },
        { duration: '30s', target: 0 },
      ],
    },
    spike_test: {
      executor: 'ramping-vus',
      exec: 'apiBrowsing',
      startVUs: 0,
      startTime: '1m',
      stages: [
        { duration: '10s', target: 300 },
        { duration: '30s', target: 300 },
        { duration: '10s', target: 0 },
      ],
    },
  },
  thresholds: {
    'http_req_duration{scenario:api_browsers}': ['p(95)<3000'],
    'http_req_duration{scenario:spike_test}': ['p(95)<5000'],
    http_req_failed: ['rate<0.10'],
  },
};

export function setup() {
  return { marketIds: fetchMarketIds() };
}

function parseMarketIds(body) {
  try {
    const markets = JSON.parse(body);
    if (!Array.isArray(markets)) {
      return [];
    }
    return markets
      .map((m) => m && m.id)
      .filter((id) => typeof id === 'number');
  } catch (e) {
    return [];
  }
}

export function apiBrowsing(data) {
  const marketsRes = http.get(`${BASE_URL}/api/markets`, {
    tags: { name: 'GET /api/markets' },
  });
  check(marketsRes, { 'api browsing markets status 200': (r) => r.status === 200 });

  const dynamicIds = parseMarketIds(marketsRes.body);
  const marketIds = dynamicIds.length > 0 ? dynamicIds : data.marketIds;
  const marketId = randomItem(marketIds) || 1;

  randomSleep(1, 3);

  const detailRes = http.get(`${BASE_URL}/api/markets/${marketId}`, {
    tags: { name: 'GET /api/markets/{id}' },
  });
  check(detailRes, { 'api browsing detail status 200': (r) => r.status === 200 });

  randomSleep(1, 3);

  const pricesRes = http.get(`${BASE_URL}/api/markets/${marketId}/prices?range=24h`, {
    tags: { name: 'GET /api/markets/{id}/prices' },
  });
  check(pricesRes, { 'api browsing prices status 200': (r) => r.status === 200 });

  randomSleep(1, 3);
}

export function sseListening(data) {
  const marketId = randomItem(data.marketIds) || 1;
  const path = Math.random() < 0.5
    ? '/api/stream/live'
    : `/api/stream/markets/${marketId}`;

  const res = http.get(`${BASE_URL}${path}`, {
    timeout: '10s',
    headers: { Accept: 'text/event-stream' },
    tags: {
      name: path.includes('/markets/')
        ? 'SSE /api/stream/markets/{id}'
        : 'SSE /api/stream/live',
    },
  });

  const ok = res.status === 200;
  sseConnectionSuccess.add(ok);

  check(res, {
    'sse listener status 200': () => ok,
    'sse listener has event/data frame':
      (r) => typeof r.body === 'string' && (r.body.includes('event:') || r.body.includes('data:')),
  });

  // Simulate holding an SSE listener and reconnecting.
  sleep(10);
}
