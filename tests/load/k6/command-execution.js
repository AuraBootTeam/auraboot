import http from 'k6/http';
import { sleep } from 'k6';
import { BASE_URL, assertApiSuccess, jsonHeaders, login } from './lib/http.js';

export const options = {
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1500'],
  },
};

const COMMAND_CODE = __ENV.COMMAND_CODE || 'announcement:create_announcement';

export default function () {
  const token = login();
  const now = Date.now();
  const response = http.post(
    `${BASE_URL}/api/meta/commands/execute/${encodeURIComponent(COMMAND_CODE)}`,
    JSON.stringify({
      clientRequestId: `perf-${__VU}-${__ITER}-${now}`,
      dryRun: true,
      operationType: 'CREATE',
      payload: {
        title: `Perf smoke ${now}`,
        content: 'Created by k6 dry-run benchmark.',
        priority: 'normal',
        pinned: false,
      },
    }),
    { headers: jsonHeaders(token) },
  );

  assertApiSuccess(response, 'command dry-run');
  sleep(1);
}
