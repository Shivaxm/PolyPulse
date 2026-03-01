import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

const sseConnections = new Counter('sse_connections');
const sseErrors = new Counter('sse_errors');
const connectionTime = new Trend('sse_connection_time');

export const options = {
  stages: [
    { duration: '1m', target: 10 },
    { duration: '2m', target: 50 },
    { duration: '2m', target: 100 },
    { duration: '2m', target: 200 },
    { duration: '1m', target: 100 },
    { duration: '1m', target: 0 },
  ],
};

export default function () {
  const start = Date.now();
  const res = http.get(`${BASE_URL}/api/stream/live`, {
    timeout: '60s',
    headers: { Accept: 'text/event-stream' },
  });

  const elapsed = Date.now() - start;
  connectionTime.add(elapsed);

  if (res.status === 200) {
    sseConnections.add(1);
    check(res, {
      'SSE connected': (r) => r.status === 200,
    });
  } else {
    sseErrors.add(1);
  }

  // Hold connection open for 30s to simulate a real client
  sleep(30);
}
