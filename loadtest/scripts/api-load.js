import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, fetchMarketIds, randomItem, randomSleep } from './common.js';

export const options = {
  stages: [
    { duration: '30s', target: 50 },
    { duration: '1m', target: 50 },
    { duration: '30s', target: 200 },
    { duration: '1m', target: 200 },
    { duration: '30s', target: 500 },
    { duration: '1m', target: 500 },
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    http_req_duration: ['p(95)<2000', 'p(99)<5000'],
    http_req_failed: ['rate<0.05'],
    http_reqs: ['rate>50'],
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

export default function (data) {
  const healthRes = http.get(`${BASE_URL}/api/health`, {
    tags: { name: 'GET /api/health' },
  });
  check(healthRes, { 'health status is 200': (r) => r.status === 200 });
  randomSleep(1, 3);

  const marketsRes = http.get(`${BASE_URL}/api/markets`, {
    tags: { name: 'GET /api/markets' },
  });
  check(marketsRes, { 'markets status is 200': (r) => r.status === 200 });
  randomSleep(1, 3);

  const dynamicIds = parseMarketIds(marketsRes.body);
  const marketIds = dynamicIds.length > 0 ? dynamicIds : data.marketIds;
  const marketId = randomItem(marketIds) || 1;

  const detailRes = http.get(`${BASE_URL}/api/markets/${marketId}`, {
    tags: { name: 'GET /api/markets/{id}' },
  });
  check(detailRes, { 'detail status is 200': (r) => r.status === 200 });
  randomSleep(1, 3);

  const pricesRes = http.get(`${BASE_URL}/api/markets/${marketId}/prices?range=24h`, {
    tags: { name: 'GET /api/markets/{id}/prices' },
  });
  check(pricesRes, { 'prices status is 200': (r) => r.status === 200 });
  randomSleep(1, 3);

  const corrRes = http.get(`${BASE_URL}/api/markets/${marketId}/correlations`, {
    tags: { name: 'GET /api/markets/{id}/correlations' },
  });
  check(corrRes, { 'market correlations status is 200': (r) => r.status === 200 });
  randomSleep(1, 3);

  const recentRes = http.get(`${BASE_URL}/api/correlations/recent`, {
    tags: { name: 'GET /api/correlations/recent' },
  });
  check(recentRes, { 'recent correlations status is 200': (r) => r.status === 200 });
  randomSleep(1, 3);
}
