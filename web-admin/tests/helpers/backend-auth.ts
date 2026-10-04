/**
 * Backend-direct authentication for API-driven specs.
 *
 * A raw `POST /api/auth/login` token pins whatever space the server last
 * remembered for the account — historically the System tenant, where the admin
 * is only platform_admin. The command endpoint gate (`meta.command.execute`,
 * deny-by-default since #2041) rejects such tokens with 403 even though the
 * same command succeeds through the browser session's business-space context.
 * API-driven specs therefore log in, explicitly select the account's business
 * space, and execute commands with the re-minted tenant-scoped token.
 */
import { BACKEND_URL } from './environments';
import { DEFAULT_TEST_ACCOUNT } from './test-accounts';

export interface BackendCommandSession {
  jwt: string;
  tenantId: string;
  tenantName: string;
}

export async function backendLoginForCommands(
  account = DEFAULT_TEST_ACCOUNT,
): Promise<BackendCommandSession> {
  const loginResp = await fetch(`${BACKEND_URL}/api/auth/login`, {
    method: 'post',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: account.email, password: account.password }),
  });
  const login = (await loginResp.json()) as {
    data?: { jwt?: string; tenantId?: string | number };
  };
  const initialJwt = login?.data?.jwt ?? '';
  if (!initialJwt) {
    throw new Error(`backend login failed: HTTP ${loginResp.status}`);
  }

  const spacesResp = await fetch(`${BACKEND_URL}/api/tenant-selection/my-spaces`, {
    headers: { Authorization: `Bearer ${initialJwt}` },
  });
  const spacesBody = (await spacesResp.json().catch(() => ({}))) as {
    data?: Array<Record<string, unknown>>;
  };
  const spaces = Array.isArray(spacesBody?.data) ? spacesBody.data : [];
  const business = spaces.find((space) => space.spaceType === 'business');
  if (!business || typeof business.tenantId !== 'string') {
    // No business space on this account: the raw token is the best we can do.
    return {
      jwt: initialJwt,
      tenantId: String(login?.data?.tenantId ?? ''),
      tenantName: account.email,
    };
  }

  const selectResp = await fetch(`${BACKEND_URL}/api/tenant-selection/process`, {
    method: 'post',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${initialJwt}` },
    body: JSON.stringify({ action: 'select', tenantId: business.tenantId }),
  });
  const selected = (await selectResp.json().catch(() => ({}))) as {
    data?: { jwt?: string };
  };
  const scopedJwt = selected?.data?.jwt ?? '';
  if (!scopedJwt) {
    throw new Error(
      `tenant selection for ${business.tenantId} failed: HTTP ${selectResp.status}`,
    );
  }
  return {
    jwt: scopedJwt,
    tenantId: business.tenantId,
    tenantName: String(business.tenantName ?? ''),
  };
}
