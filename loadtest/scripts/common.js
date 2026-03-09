import http from 'k6/http';
import { sleep } from 'k6';

export const BASE_URL = __ENV.BASE_URL || 'https://polypulse.up.railway.app';

export function randomItem(arr) {
  if (!arr || arr.length === 0) {
    return null;
  }
  return arr[Math.floor(Math.random() * arr.length)];
}

export function randomSleep(min, max) {
  sleep(min + Math.random() * (max - min));
}

export function fetchMarketIds() {
  const res = http.get(`${BASE_URL}/api/markets`, {
    tags: { name: 'GET /api/markets (setup)' },
  });

  if (res.status === 200) {
    try {
      const markets = JSON.parse(res.body);
      const ids = Array.isArray(markets)
        ? markets
            .map((m) => m && m.id)
            .filter((id) => typeof id === 'number')
        : [];

      if (ids.length > 0) {
        return ids;
      }
    } catch (e) {
      // fall through to fallback IDs
    }
  }

  return [1, 2, 3];
}
