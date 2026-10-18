import { expect, type APIRequestContext } from '@playwright/test';

interface BusinessSpace {
  tenantId: string;
  tenantName?: string;
  spaceType: string;
}

/** Test setup uses the actual BFF session boundary, without reading or signing a JWT/cookie. */
export async function authenticateBffSession(
  request: APIRequestContext,
  baseURL: string,
  identifier: string,
  password: string,
): Promise<{ tenantId: string; userPid: string }> {
  const origin = new URL(baseURL).origin;
  const login = await request.post(`${origin}/login`, {
    form: { identifier, password, channelCode: 'email_password', remember: 'on', redirectTo: '/' },
    headers: { Origin: origin },
    maxRedirects: 0,
  });
  expect(login.status(), 'BFF login must issue its successful redirect').toBe(302);
  expect(login.headers()['set-cookie'], 'BFF must issue its own session cookie').toMatch(
    /__session=/,
  );

  const spacesResponse = await request.get(`${origin}/api/tenant-selection/my-spaces`);
  expect(spacesResponse.status(), 'The issued cookie must authorize space discovery').toBe(200);
  const spacesBody = await spacesResponse.json();
  expect(spacesBody.code).toBe('0');
  expect(Array.isArray(spacesBody.data)).toBe(true);
  const business = (spacesBody.data as BusinessSpace[]).filter(
    (space) => space.spaceType === 'business',
  );
  // This named fixture is explicit suite configuration. Never select an arbitrary first tenant
  // when another suite has left multiple memberships in the same development database.
  const canonical = business.filter((space) => space.tenantName === 'AuraBoot Demo');
  const candidates = canonical.length ? canonical : business;
  expect(candidates, 'Auth setup requires one unambiguous business fixture').toHaveLength(1);
  const tenantId = String(candidates[0].tenantId);
  expect(tenantId).toMatch(/^\d+$/);

  // The normal route action commits the replacement httpOnly cookie. A raw backend JWT
  // returned by /api/tenant-selection/process is not a browser session cookie.
  const selected = await request.post(`${origin}/tenant-selection`, {
    form: { action: 'select', tenantId },
    headers: { Origin: origin },
    maxRedirects: 0,
  });
  expect(selected.status(), 'The real space-selection action must redirect').toBe(302);
  expect(selected.headers()['set-cookie'], 'Space selection must replace the BFF session').toMatch(
    /__session=/,
  );
  const identityResponse = await request.get(`${origin}/api/auth/me`);
  expect(identityResponse.status(), 'Stored authentication must work through the BFF').toBe(200);
  const identity = await identityResponse.json();
  expect(identity.code).toBe('0');
  expect(String(identity.data?.user?.tenantId)).toBe(tenantId);
  expect(identity.data?.user?.pid).toEqual(expect.any(String));
  expect(identity.data.user.pid).not.toBe('');
  return { tenantId, userPid: identity.data.user.pid };
}
