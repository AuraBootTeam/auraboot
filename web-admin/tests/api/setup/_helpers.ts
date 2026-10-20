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
 * Select an explicit business-space session for setup writes after admin login (via vite
 * proxy / BFF / backend chain — `request` fixture's baseURL is
 * PLAYWRIGHT_BASE_URL). Returns the selected business JWT, never an onboarding JWT.
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
  const headers = authHeaders(body.data.jwt as string);
  const spacesResponse = await request.get('/api/tenant-selection/my-spaces', { headers });
  if (!spacesResponse.ok()) throw new Error(`[setup] administrator spaces failed (${spacesResponse.status()})`);
  const spacesBody = await spacesResponse.json();
  if (spacesBody.code !== '0' || !Array.isArray(spacesBody.data)) {
    throw new Error('[setup] administrator spaces returned no inventory');
  }
  const target = process.env.AURA_ADMIN_TENANT_ID;
  const business = spacesBody.data.filter((space: { spaceType?: string; tenantId?: string | number }) =>
    space.spaceType === 'business' && space.tenantId != null && (!target || String(space.tenantId) === target));
  if (business.length !== 1) throw new Error('[setup] select exactly one business space with AURA_ADMIN_TENANT_ID');
  const selectedResponse = await request.post('/api/tenant-selection/process', {
    headers, data: { action: 'select', tenantId: business[0].tenantId },
  });
  if (!selectedResponse.ok()) throw new Error(`[setup] business space selection failed (${selectedResponse.status()})`);
  const selected = await selectedResponse.json();
  if (selected.code !== '0' || !selected.data?.jwt
      || String(selected.data.tenantId) !== String(business[0].tenantId)) {
    throw new Error('[setup] business space selection returned no matching session');
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
