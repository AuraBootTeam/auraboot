/**
 * Shared helpers for tests/api/setup/ Playwright specs.
 *
 * These helpers run BEFORE auth.setup.ts (the setup project is the first
 * project in playwright.oss.config.ts, no dependencies). They cannot use
 * the storageState-based admin context — they have to log in inline and
 * use the resulting JWT as a Bearer token.
 */

import type { APIRequestContext } from '@playwright/test';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';

/**
 * Log in as the default admin user against the running backend (via vite
 * proxy / BFF / backend chain — `request` fixture's baseURL is
 * PLAYWRIGHT_BASE_URL). Returns the JWT.
 *
 * Throws if the backend is not yet bootstrapped — call 00-bootstrap.spec.ts
 * before any spec that uses this helper.
 */
export async function loginAdmin(request: APIRequestContext): Promise<string> {
  const resp = await request.post('/api/auth/login', {
    data: {
      email: DEFAULT_TEST_ACCOUNT.email,
      password: DEFAULT_TEST_ACCOUNT.password,
    },
  });
  if (!resp.ok()) {
    throw new Error(
      `[setup] admin login failed (${resp.status()}): ${await resp.text()}`,
    );
  }
  const body = await resp.json();
  if (body.code !== '0' || !body.data?.jwt) {
    throw new Error(
      `[setup] admin login returned no JWT: ${JSON.stringify(body)}`,
    );
  }
  return body.data.jwt as string;
}

/**
 * Build the standard `Authorization: Bearer <jwt>` + `Content-Type: application/json`
 * header set used by every setup-phase API call.
 */
export function authHeaders(jwt: string): Record<string, string> {
  return {
    Authorization: `Bearer ${jwt}`,
    'Content-Type': 'application/json',
  };
}
