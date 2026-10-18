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
export async function loginAdmin(request: APIRequestContext, baseURL = ''): Promise<string> {
  const resp = await request.post(`${baseURL}/api/auth/login`, {
    data: {
      email: DEFAULT_TEST_ACCOUNT.email,
      password: DEFAULT_TEST_ACCOUNT.password,
    },
  });
  if (!resp.ok()) {
    throw new Error(`[setup] admin login failed (${resp.status()}): ${await resp.text()}`);
  }
  const body = await resp.json();
  if (body.code !== '0' || !body.data?.jwt) {
    throw new Error(`[setup] admin login returned no JWT (code=${String(body.code)})`);
  }
  const token = body.data.jwt as string;
  if (body.data.tenantId == null && body.data.nextAction !== 'SELECT_SCHOOL') {
    throw new Error('[setup] administrator login did not enter or offer an existing space');
  }
  const spacesResponse = await request.get(`${baseURL}/api/tenant-selection/my-spaces`, {
    headers: authHeaders(token),
  });
  const spaces = await spacesResponse.json();
  if (!spacesResponse.ok() || spaces.code !== '0' || !Array.isArray(spaces.data)) {
    throw new Error('[setup] administrator spaces could not be read');
  }
  const business = spaces.data.filter(
    (space: { spaceType?: string }) => space.spaceType === 'business',
  );
  if (business.length !== 1 || !business[0].tenantId) {
    throw new Error(
      '[setup] requires exactly one existing business space; refusing ambiguous selection',
    );
  }
  if (String(body.data.tenantId) === String(business[0].tenantId)) return token;
  const selectedResponse = await request.post(`${baseURL}/api/tenant-selection/process`, {
    headers: authHeaders(token),
    data: { action: 'select', tenantId: business[0].tenantId },
  });
  const selected = await selectedResponse.json();
  if (
    !selectedResponse.ok() ||
    selected.code !== '0' ||
    typeof selected.data?.jwt !== 'string' ||
    !selected.data.jwt
  ) {
    throw new Error('[setup] business space selection returned no session');
  }
  return selected.data.jwt;
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
