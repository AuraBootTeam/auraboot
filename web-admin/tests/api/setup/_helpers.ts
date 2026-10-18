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
 * PLAYWRIGHT_BASE_URL). Returns the explicitly selected business-space JWT.
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
  const initialJwt = body.data.jwt as string;
  const spacesResponse = await request.get('/api/tenant-selection/my-spaces', {
    headers: authHeaders(initialJwt),
  });
  const spacesBody = await spacesResponse.json();
  if (!spacesResponse.ok() || spacesBody.code !== '0' || !Array.isArray(spacesBody.data)) {
    throw new Error(`[setup] admin spaces unavailable (HTTP ${spacesResponse.status()})`);
  }
  const companyName = process.env.AURA_BOOTSTRAP_COMPANY ?? 'AuraBoot Dev';
  const spaces = spacesBody.data.filter((space: { spaceType?: string; tenantName?: string }) =>
    space.spaceType === 'business' && space.tenantName === companyName);
  if (spaces.length !== 1 || !spaces[0].tenantId) {
    throw new Error(`[setup] expected exactly one business space named ${companyName}`);
  }
  const selectedResponse = await request.post('/api/tenant-selection/process', {
    headers: authHeaders(initialJwt),
    data: { action: 'select', tenantId: spaces[0].tenantId },
  });
  const selected = await selectedResponse.json();
  if (!selectedResponse.ok() || selected.code !== '0' || !selected.data?.jwt
      || String(selected.data.tenantId) !== String(spaces[0].tenantId)) {
    throw new Error(`[setup] business-space selection failed (HTTP ${selectedResponse.status()})`);
  }
  return selected.data.jwt as string;
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
