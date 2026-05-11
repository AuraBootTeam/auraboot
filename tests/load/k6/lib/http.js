import http from 'k6/http';
import { check, fail } from 'k6';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:6443';
export const USERNAME = __ENV.USERNAME || 'admin@auraboot.com';
export const PASSWORD = __ENV.PASSWORD || 'Test2026x';

export function login() {
  const response = http.post(
    `${BASE_URL}/api/auth/login`,
    JSON.stringify({ email: USERNAME, password: PASSWORD }),
    { headers: jsonHeaders() },
  );

  assertOk(response, 'login');

  const body = parseJson(response, 'login');
  const token = body?.data?.jwt || body?.jwt || body?.data?.token || body?.token;
  if (!token) {
    fail(`login response did not include jwt/token: ${response.body}`);
  }

  return token;
}

export function jsonHeaders(token) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  return headers;
}

export function assertOk(response, label) {
  check(response, {
    [`${label}: status is 2xx`]: (r) => r.status >= 200 && r.status < 300,
    [`${label}: body is present`]: (r) => Boolean(r.body),
  });

  if (response.status < 200 || response.status >= 300) {
    fail(`${label} failed with status ${response.status}: ${response.body}`);
  }
}

export function assertApiSuccess(response, label) {
  assertOk(response, label);
  const body = parseJson(response, label);

  check(body, {
    [`${label}: API success flag/code is acceptable`]: (json) => {
      if (json.success === true) {
        return true;
      }
      if (json.code === 0 || json.code === 200 || json.code === 'SUCCESS') {
        return true;
      }
      return json.data !== undefined;
    },
  });
}

export function parseJson(response, label) {
  try {
    return response.json();
  } catch (error) {
    fail(`${label} response was not JSON: ${error.message}`);
  }
  return {};
}
