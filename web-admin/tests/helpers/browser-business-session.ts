import type { APIRequestContext } from '@playwright/test';

interface Credentials {
  identifier: string;
  password: string;
  tenantId?: string;
}

/** Obtain and validate cookies through the same BFF routes used by the browser. */
export async function establishBrowserBusinessSession(
  request: APIRequestContext,
  baseURL: string,
  credentials: Credentials,
): Promise<void> {
  const login = await request.post(`${baseURL}/login`, {
    form: { identifier: credentials.identifier, password: credentials.password, remember: 'on', redirectTo: '/' },
    maxRedirects: 0,
  });
  if (login.status() !== 302 || !login.headers()['set-cookie']?.includes('__session=')) {
    throw new Error(`BFF login did not establish a session (${login.status()})`);
  }

  const inventory = await request.get(`${baseURL}/api/tenant-selection/my-spaces`);
  const body = await inventory.json();
  if (!inventory.ok() || String(body.code) !== '0' || !Array.isArray(body.data)) {
    throw new Error('Business-space inventory is unavailable');
  }
  const business = body.data.filter((space: any) => space.spaceType === 'business');
  const preferred = credentials.tenantId
    ? business.filter((space: any) => String(space.tenantId) === credentials.tenantId)
    : business.filter((space: any) => space.tenantName === 'AuraBoot Demo');
  const candidates = preferred.length ? preferred : (credentials.tenantId ? [] : business);
  if (candidates.length !== 1 || !candidates[0].tenantId) {
    throw new Error('Exactly one target business space is required');
  }
  const tenantId = String(candidates[0].tenantId);
  const selection = await request.post(`${baseURL}/_action/switch-space`, {
    form: { tenantId, redirectTo: '/' }, maxRedirects: 0,
  });
  if (selection.status() !== 302 || !selection.headers()['set-cookie']?.includes('__session=')) {
    throw new Error(`BFF space selection did not establish a session (${selection.status()})`);
  }
  const identity = await request.get(`${baseURL}/api/auth/me`);
  const selected = await identity.json();
  if (!identity.ok() || String(selected.code) !== '0' || String(selected.data?.user?.tenantId) !== tenantId) {
    throw new Error('BFF session does not match the selected business space');
  }
}
