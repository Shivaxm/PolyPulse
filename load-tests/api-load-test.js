import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  stages: [
    { duration: '1m', target: 10 },
    { duration: '3m', target: 50 },
    { duration: '3m', target: 100 },
    { duration: '2m', target: 50 },
    { duration: '1m', target: 0 },
  ],
  thresholds: {
    http_req_duration: ['p(95)<500'],
    http_req_failed: ['rate<0.01'],
  },
};

export default function () {
  const rand = Math.random();

  if (rand < 0.4) {
    // 40% - GET /api/markets
    const res = http.get(`${BASE_URL}/api/markets`);
    check(res, {
      'markets status 200': (r) => r.status === 200,
      'markets has data': (r) => JSON.parse(r.body).length > 0,
    });
  } else if (rand < 0.8) {
    // 40% - GET /api/markets/{id}/prices
    const marketsRes = http.get(`${BASE_URL}/api/markets`);
    const markets = JSON.parse(marketsRes.body);
    if (markets.length > 0) {
      const market = markets[Math.floor(Math.random() * markets.length)];
      const res = http.get(`${BASE_URL}/api/markets/${market.id}/prices?range=24h`);
      check(res, {
        'prices status 200': (r) => r.status === 200,
      });
    }
  } else {
    // 20% - GET /api/correlations/recent
    const res = http.get(`${BASE_URL}/api/correlations/recent`);
    check(res, {
      'correlations status 200': (r) => r.status === 200,
    });
  }

  sleep(1);
}
