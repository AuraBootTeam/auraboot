import http from 'k6/http';
import { sleep } from 'k6';
import { BASE_URL, assertApiSuccess, jsonHeaders, login } from './lib/http.js';

export const options = {
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1000'],
  },
};

const PAGE_KEY = __ENV.LIST_PAGE_KEY || 'ab_announcement';

export default function () {
  const token = login();
  const response = http.get(
    `${BASE_URL}/api/dynamic/${PAGE_KEY}/list?pageNum=1&pageSize=20`,
    { headers: jsonHeaders(token) },
  );

  assertApiSuccess(response, 'dynamic list query');
  sleep(1);
}
