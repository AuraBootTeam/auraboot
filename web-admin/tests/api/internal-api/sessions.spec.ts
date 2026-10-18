import { test, expect, type BrowserContext } from '@playwright/test';
import fs from 'node:fs/promises';
import { provisionInternalApiUser } from '../../helpers/internal-api-owned-user';
import { createResponseShapeValidator } from '../../../../scripts/internal-api/response-shape.mjs';
const document = JSON.parse(
  await fs.readFile(
    new URL('../../../../contracts/internal-web-admin/openapi.json', import.meta.url),
    'utf8',
  ),
);
const validate = createResponseShapeValidator(document);

test('generated session operations revoke only owned sessions and preserve other users', async ({
  browser,
  baseURL,
}, info) => {
  test.setTimeout(150_000);
  if (!baseURL) throw new Error('A managed runtime is required');
  const owner = await provisionInternalApiUser(browser, baseURL);
  let other: Awaited<ReturnType<typeof provisionInternalApiUser>> | undefined,
    journeyError: unknown;
  const rows: object[] = [],
    cleanupErrors: unknown[] = [],
    operations = new Set<string>();
  const anonymous = await browser.newContext({
    baseURL,
    storageState: { cookies: [], origins: [] },
  });
  async function call(context: BrowserContext, method: string, path: string, url = path) {
    const response = await context.request.fetch(url, {
      method,
      headers: { Origin: owner.origin },
    });
    const payload = await response.json();
    const shape = validate({
      method,
      path,
      status: response.status(),
      contentType: response.headers()['content-type'],
      payload,
    });
    rows.push({ method, path, status: response.status(), shape });
    expect(response.status()).toBe(200);
    expect(payload.code).toBe('0');
    expect(shape.errors).toEqual([]);
    operations.add(`${method} ${path}`);
    return payload.data;
  }
  try {
    const initial = await call(owner.context, 'get', '/api/user/sessions');
    expect(initial.length).toBeGreaterThan(0);
    for (const session of initial) {
      expect(Object.keys(session).sort()).toEqual([
        'createdAt',
        'deviceInfo',
        'ipAddress',
        'lastActiveAt',
        'pid',
      ]);
      expect(Number.isFinite(Date.parse(session.createdAt))).toBe(true);
      expect(Number.isFinite(Date.parse(session.lastActiveAt))).toBe(true);
    }
    const first = await owner.createAdditionalSession();
    const sessions = await call(owner.context, 'get', '/api/user/sessions');
    const added = sessions.filter(
      (s: { pid: string }) => !initial.some((old: { pid: string }) => s.pid === old.pid),
    );
    expect(added).toHaveLength(1);
    await call(first.context, 'delete', '/api/user/sessions/current');
    expect((await first.context.request.get('/api/auth/me')).status()).toBe(401);
    expect((await owner.context.request.get('/api/auth/me')).status()).toBe(200);
    expect(
      (await call(owner.context, 'get', '/api/user/sessions')).some(
        (s: { pid: string }) => s.pid === added[0].pid,
      ),
    ).toBe(false);

    other = await provisionInternalApiUser(browser, baseURL, null);
    const otherSessions = await call(other.context, 'get', '/api/user/sessions');
    expect(otherSessions.length).toBeGreaterThan(0);
    const refused = await owner.context.request.delete(
      `/api/user/sessions/${otherSessions[0].pid}`,
      { headers: { Origin: owner.origin } },
    );
    expect(refused.status()).toBe(404);
    expect((await refused.json()).code).toBe('404');
    expect((await other.context.request.get('/api/auth/me')).status()).toBe(200);

    const before = await call(owner.context, 'get', '/api/user/sessions');
    const second = await owner.createAdditionalSession();
    const next = await call(owner.context, 'get', '/api/user/sessions');
    const target = next.filter(
      (s: { pid: string }) => !before.some((old: { pid: string }) => s.pid === old.pid),
    );
    expect(target).toHaveLength(1);
    await call(
      owner.context,
      'delete',
      '/api/user/sessions/{sessionPid}',
      `/api/user/sessions/${target[0].pid}`,
    );
    expect((await second.context.request.get('/api/auth/me')).status()).toBe(401);
    expect((await owner.context.request.get('/api/auth/me')).status()).toBe(200);
    const third = await owner.createAdditionalSession();
    await call(owner.context, 'delete', '/api/user/sessions');
    expect((await owner.context.request.get('/api/auth/me')).status()).toBe(401);
    expect((await third.context.request.get('/api/auth/me')).status()).toBe(401);
    expect((await other.context.request.get('/api/auth/me')).status()).toBe(200);
    expect([...operations].sort()).toEqual(
      [
        'delete /api/user/sessions',
        'delete /api/user/sessions/current',
        'delete /api/user/sessions/{sessionPid}',
        'get /api/user/sessions',
      ].sort(),
    );
    for (const [method, url] of [
      ['get', '/api/user/sessions'],
      ['delete', '/api/user/sessions'],
      ['delete', '/api/user/sessions/current'],
      ['delete', `/api/user/sessions/${initial[0].pid}`],
    ]) {
      expect((await anonymous.request.fetch(url, { method })).status()).toBe(401);
    }
  } catch (error) {
    journeyError = error;
  } finally {
    for (const actor of [other, owner])
      if (actor) {
        try {
          await actor.dispose();
        } catch (error) {
          cleanupErrors.push(error);
        }
      }
    await anonymous.close();
    await info.attach('session-operation-observations', {
      body: JSON.stringify({
        rows,
        operations: [...operations],
        journeyError: journeyError ? String(journeyError) : null,
        cleanupErrors: cleanupErrors.map(String),
        sessionCleanup: [other, owner].filter(Boolean).map((actor) => actor!.sessionCleanup),
      }),
      contentType: 'application/json',
    });
  }
  if (journeyError || cleanupErrors.length)
    throw new AggregateError(
      [...(journeyError ? [journeyError] : []), ...cleanupErrors],
      'Session journey or cleanup failed',
    );
});
