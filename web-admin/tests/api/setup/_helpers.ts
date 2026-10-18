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
import { BACKEND_URL, BOOTSTRAP_COMPANY_NAME } from '../../helpers/environments';

/**
 * Log in against the explicit backend and select the declared bootstrap business
 * space. Returns the selected JWT, never an unbound onboarding session.
 *
 * Throws if the backend is not yet bootstrapped — call 00-bootstrap.spec.ts
 * before any spec that uses this helper.
 */
export async function loginAdmin(
  request: APIRequestContext,
  companyName = BOOTSTRAP_COMPANY_NAME,
): Promise<string> {
  const resp = await request.post(`${BACKEND_URL}/api/auth/login`, {
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
  // Login may be ONBOARDING when the admin belongs to both platform and business
  // spaces. Always explicitly bind setup work to its declared business tenant.
  const loginToken = body.data.jwt as string;
  const spaces = await request.get(`${BACKEND_URL}/api/tenant-selection/my-spaces`, {
    headers: authHeaders(loginToken),
  });
  if (!spaces.ok()) throw new Error(`[setup] list spaces failed (${spaces.status()})`);
  const space = resolveSetupBusinessSpace((await spaces.json()).data, companyName);
  const selection = await request.post(`${BACKEND_URL}/api/tenant-selection/process`, {
    headers: authHeaders(loginToken),
    data: { action: 'select', tenantId: space.tenantId },
  });
  if (!selection.ok()) throw new Error(`[setup] select business space failed (${selection.status()})`);
  const selected = await selection.json();
  if (selected.code !== '0' || typeof selected.data?.jwt !== 'string' || !selected.data.jwt) {
    throw new Error('[setup] business-space selection returned no usable JWT');
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

/** Select only the declared bootstrap tenant, never the first available space. */
export function resolveSetupBusinessSpace(
  spaces: unknown,
  companyName: string,
): { tenantId: string | number } {
  if (!companyName.trim() || !Array.isArray(spaces)) {
    throw new Error('[setup] expected a named business space list');
  }
  const matches = spaces.filter((space) =>
    space && typeof space === 'object' && space.spaceType === 'business'
    && space.tenantName === companyName,
  );
  if (matches.length !== 1) {
    throw new Error(`[setup] expected exactly one business space named ${companyName}`);
  }
  const tenantId = matches[0].tenantId;
  if (!(typeof tenantId === 'string' && tenantId.trim())
      && !(typeof tenantId === 'number' && Number.isSafeInteger(tenantId) && tenantId > 0)) {
    throw new Error('[setup] selected business space has no safe tenant identity');
  }
  return { tenantId };
}
