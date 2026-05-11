import http from 'k6/http';
import { sleep } from 'k6';
import { BASE_URL, assertApiSuccess, jsonHeaders, login } from './lib/http.js';

export const options = {
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1000'],
  },
};

const PAGE_KEY = __ENV.LIST_PAGE_KEY || '';
const DEFAULT_LIST_PATH = '/api/meta/models?pageNum=1&pageSize=20';
const LIST_PATH = __ENV.LIST_PATH || DEFAULT_LIST_PATH;

export function setup() {
  return { token: login() };
}

export default function (data) {
  const path = PAGE_KEY
    ? `/api/dynamic/${PAGE_KEY}/list?pageNum=1&pageSize=20`
    : LIST_PATH;
  const response = http.get(
    `${BASE_URL}${path}`,
    { headers: jsonHeaders(data.token) },
  );

  assertApiSuccess(response, 'dynamic list query');
  sleep(1);
}
