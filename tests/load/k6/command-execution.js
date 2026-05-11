import http from 'k6/http';
import { sleep } from 'k6';
import { BASE_URL, assertApiSuccess, jsonHeaders, login } from './lib/http.js';

export const options = {
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1500'],
  },
};

const COMMAND_CODE = __ENV.COMMAND_CODE || 'admin:create_scheduled_task';

export function setup() {
  return { token: login() };
}

export default function (data) {
  const now = Date.now();
  const response = http.post(
    `${BASE_URL}/api/meta/commands/execute/${encodeURIComponent(COMMAND_CODE)}`,
    JSON.stringify({
      clientRequestId: `perf-${__VU}-${__ITER}-${now}`,
      dryRun: true,
      operationType: 'CREATE',
      payload: {
        name: `Perf smoke ${now}`,
        description: 'Created by k6 dry-run benchmark.',
        task_type: 'noop',
        cron_expression: '0 0 * * * ?',
        interval_ms: 60000,
        handler_bean: 'noop',
        handler_method: 'run',
        params: '{}',
        max_retries: 0,
        timeout_ms: 1000,
        enabled: false,
      },
    }),
    { headers: jsonHeaders(data.token) },
  );

  assertApiSuccess(response, 'command dry-run');
  sleep(1);
}
