import { test, expect, type APIRequestContext } from '@playwright/test';
import fs from 'node:fs/promises';
import { createHash, randomUUID } from 'node:crypto';
import pg from 'pg';
import type { SavedViewConfigWire } from '../../../app/framework/smart/types/savedViewWire';
import { PG_CONN } from '../../helpers/environments';
import { provisionInternalApiUser } from '../../helpers/internal-api-owned-user';
import { createResponseShapeValidator } from '../../../../scripts/internal-api/response-shape.mjs';

const document = JSON.parse(
  await fs.readFile(
    new URL('../../../../contracts/internal-web-admin/openapi.json', import.meta.url),
    'utf8',
  ),
);
const catalog = JSON.parse(
  await fs.readFile(
    new URL('../../../../scripts/internal-api/catalog.json', import.meta.url),
    'utf8',
  ),
);
const validate = createResponseShapeValidator(document);

test('generated SavedView operations preserve owned configuration and public sharing boundaries', async ({
  browser,
}, info) => {
  test.setTimeout(150_000);
  const baseURL = info.project.use.baseURL;
  if (!baseURL) throw new Error('Managed internal API base URL is required');
  const actor = await provisionInternalApiUser(browser, baseURL);
  const anonymous = await browser.newContext({
    baseURL,
    storageState: { cookies: [], origins: [] },
  });
  const db = new pg.Client(PG_CONN);
  await db.connect();
  const owned = new Set<string>(),
    observed = new Set<string>();
  const rows: object[] = [],
    cleanupErrors: unknown[] = [];
  const name = `Contract ${randomUUID().slice(0, 8)}`;
  const modelCode = 'ab_team';
  const query = `modelCode=${modelCode}`;
  const headers = { Origin: actor.origin };
  let journeyError: unknown;

  async function call(
    method: string,
    path: string,
    url: string,
    options: {
      body?: unknown;
      status?: number;
      negative?: boolean;
      context?: APIRequestContext;
    } = {},
  ) {
    const response = await (options.context ?? actor.context.request).fetch(url, {
      method: method.toUpperCase(),
      headers,
      ...(options.body === undefined ? {} : { data: options.body }),
    });
    const payload = await response.json();
    // Track all created resources before checking the contract, not only expected successes.
    if (
      method === 'post' &&
      typeof payload.data?.pid === 'string' &&
      [
        '/api/views',
        '/api/views/auto-save',
        '/api/views/{pid}/duplicate',
        '/api/views/{pid}/copy-to-personal',
      ].includes(path)
    )
      owned.add(payload.data.pid);
    const shape = validate({
      method,
      path,
      status: response.status(),
      contentType: response.headers()['content-type'],
      payload,
    });
    rows.push({
      method,
      path,
      status: response.status(),
      payloadSha256: createHash('sha256').update(JSON.stringify(payload)).digest('hex'),
      shape,
    });
    expect(response.status(), `${method} ${path}`).toBe(options.status ?? 200);
    expect(shape.errors, `${method} ${path}`).toEqual([]);
    expect(shape.valid).toBe(true);
    if (options.negative) expect(payload.code).not.toBe('0');
    else expect(payload.code).toBe('0');
    observed.add(`${method} ${path}`);
    return payload.data;
  }
  const get = (path: string, url = path) => call('get', path, url);
  try {
    expect(await get('/api/views/default', `/api/views/default?${query}`)).toBeNull();
    expect(await get('/api/views/personal', `/api/views/personal?${query}`)).toEqual([]);
    expect(await get('/api/views/chip-pins', `/api/views/chip-pins?${query}`)).toEqual([]);
    expect(
      await get(
        '/api/views/check-name',
        `/api/views/check-name?${query}&name=${encodeURIComponent(name)}`,
      ),
    ).toBe(true);
    const config = {
      columns: [{ fieldCode: 'name', visible: true, order: 0 }],
      filters: [{ fieldCode: 'name', operator: 'eq', value: null, logic: 'and' }],
      sorts: [{ fieldCode: 'name', direction: 'ASC' }],
      pagination: { pageSize: 10 },
      meta: { capabilityStatus: 'available', capabilityReasonCodes: [] },
    } satisfies SavedViewConfigWire;
    const created = await call('post', '/api/views', '/api/views', {
      body: {
        name,
        modelCode,
        scope: 'personal',
        viewType: 'table',
        sortOrder: 0,
        isDefault: false,
        allowFullModel: false,
        viewConfig: config,
      },
    });
    const pid: string = created.pid;
    expect(created).toMatchObject({
      name,
      modelCode,
      scope: 'personal',
      sortOrder: 0,
      isDefault: false,
      allowFullModel: false,
    });
    expect(created).not.toHaveProperty('id');
    expect(created).not.toHaveProperty('tenantId');
    expect(created.viewConfig.filters[0].value).toBeNull();
    expect((await get('/api/views/{pid}', `/api/views/${pid}`)).pid).toBe(pid);
    expect(
      await get(
        '/api/views/check-name',
        `/api/views/check-name?${query}&name=${encodeURIComponent(name)}`,
      ),
    ).toBe(false);
    expect(
      await get(
        '/api/views/check-name',
        `/api/views/check-name?${query}&name=${encodeURIComponent(name)}&excludePid=${pid}`,
      ),
    ).toBe(true);
    expect(await get('/api/views/accessible', `/api/views/accessible?${query}`)).toEqual(
      expect.arrayContaining([expect.objectContaining({ pid })]),
    );
    expect(await get('/api/views/team', `/api/views/team?${query}`)).toEqual(expect.any(Array));
    expect(await get('/api/views/my-teams')).toEqual(expect.any(Array));
    const members = await call('post', '/api/tenant/members/search', '/api/tenant/members/search', {
      body: {
        pageNum: 1,
        pageSize: 10,
        status: 'active',
        keyword: actor.username,
      },
    });
    expect(members.records).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          user: expect.objectContaining({
            pid: actor.userPid,
            username: actor.username,
            email: null,
          }),
        }),
      ]),
    );
    const available = await call(
      'post',
      '/api/views/capability-check',
      '/api/views/capability-check',
      { body: { modelCode, viewType: 'table', viewConfig: config } },
    );
    expect(available).toMatchObject({ status: 'available', missingFields: [], reasons: [] });
    const blocked = await call(
      'post',
      '/api/views/capability-check',
      '/api/views/capability-check',
      { body: { modelCode, viewType: 'kanban', viewConfig: {} } },
    );
    expect(blocked.status).toBe('blocked');
    expect(blocked.missingFields).toEqual(expect.arrayContaining(['groupByField', 'titleField']));
    await call('put', '/api/views/{pid}', `/api/views/${pid}`, {
      body: { description: 'Owned contract update', viewConfig: config },
    });
    expect((await get('/api/views/{pid}', `/api/views/${pid}`)).description).toBe(
      'Owned contract update',
    );
    expect(
      (
        await call('post', '/api/views/{pid}/duplicate', `/api/views/${pid}/duplicate`, {
          body: { name: `${name} copy` },
        })
      ).scope,
    ).toBe('personal');
    expect(
      (
        await call(
          'post',
          '/api/views/{pid}/copy-to-personal',
          `/api/views/${pid}/copy-to-personal`,
          { body: { name: `${name} personal` } },
        )
      ).scope,
    ).toBe('personal');
    expect(
      (await call('post', '/api/views/{pid}/set-default', `/api/views/${pid}/set-default`))
        .isDefault,
    ).toBe(true);
    expect((await get('/api/views/default', `/api/views/default?${query}`)).pid).toBe(pid);
    await call('post', '/api/views/{viewPid}/pin', `/api/views/${pid}/pin`, { body: { order: 0 } });
    expect(await get('/api/views/chip-pins', `/api/views/chip-pins?${query}`)).toEqual(
      expect.arrayContaining([expect.objectContaining({ viewPid: pid, order: 0 })]),
    );
    await call('delete', '/api/views/{viewPid}/pin', `/api/views/${pid}/pin`);
    expect(await get('/api/views/chip-pins', `/api/views/chip-pins?${query}`)).toEqual([]);
    const implicit = await call('post', '/api/views/auto-save', '/api/views/auto-save', {
      body: { modelCode, viewConfig: { density: 'compact' } },
    });
    const resaved = await call('post', '/api/views/auto-save', '/api/views/auto-save', {
      body: { modelCode, viewConfig: { rowHeight: 'short' } },
    });
    expect(resaved.pid).toBe(implicit.pid);
    expect(resaved.viewConfig).toMatchObject({ density: 'compact', rowHeight: 'short' });
    const global = await call('post', '/api/views', '/api/views', {
      body: {
        name: `${name} shared`,
        modelCode,
        scope: 'global',
        viewType: 'table',
        viewConfig: config,
      },
    });
    expect(await get('/api/views/global', `/api/views/global?${query}`)).toEqual(
      expect.arrayContaining([expect.objectContaining({ pid: global.pid })]),
    );
    expect(
      await get('/api/views/{pid}/audit-events', `/api/views/${global.pid}/audit-events`),
    ).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ operationType: 'CREATE', entityPid: global.pid }),
      ]),
    );
    expect(
      await get('/api/views/{viewPid}/share/status', `/api/views/${global.pid}/share/status`),
    ).toMatchObject({ shared: false, token: null, expiresAt: null, passwordProtected: null });
    const password = `Share-${randomUUID()}`;
    const share = await call(
      'post',
      '/api/views/{viewPid}/share',
      `/api/views/${global.pid}/share`,
      { body: { password, expireHours: 1 } },
    );
    expect(share).toMatchObject({
      passwordProtected: true,
      token: expect.any(String),
      expiresAt: expect.any(String),
    });
    expect(
      await get('/api/views/{viewPid}/share/status', `/api/views/${global.pid}/share/status`),
    ).toMatchObject({ shared: true, token: share.token, passwordProtected: true });
    await call('get', '/api/views/shared/{shareToken}', `/api/views/shared/${share.token}`, {
      context: anonymous.request,
      status: 422,
      negative: true,
    });
    const publicView = await call(
      'get',
      '/api/views/shared/{shareToken}',
      `/api/views/shared/${share.token}?password=${encodeURIComponent(password)}`,
      { context: anonymous.request },
    );
    expect(publicView).toMatchObject({ name: `${name} shared`, modelCode, viewType: 'table' });
    expect(publicView.viewConfig).not.toHaveProperty('__share');
    expect(publicView).not.toHaveProperty('records');
    expect(publicView).not.toHaveProperty('tenantId');
    await call('delete', '/api/views/{viewPid}/share', `/api/views/${global.pid}/share`);
    await call('get', '/api/views/shared/{shareToken}', `/api/views/shared/${share.token}`, {
      context: anonymous.request,
      status: 404,
      negative: true,
    });
    const expired = await call(
      'post',
      '/api/views/{viewPid}/share',
      `/api/views/${global.pid}/share`,
      { body: { expireHours: -1 } },
    );
    await call('get', '/api/views/shared/{shareToken}', `/api/views/shared/${expired.token}`, {
      context: anonymous.request,
      status: 422,
      negative: true,
    });
    await call('post', '/api/views', '/api/views', {
      body: { name: '', modelCode },
      status: 400,
      negative: true,
    });
    await call('delete', '/api/views/{pid}', `/api/views/${pid}`);
    const stored = await db.query(
      'SELECT deleted_flag FROM ab_saved_view WHERE pid=$1 AND owner_id=$2',
      [pid, actor.userPid],
    );
    expect(stored.rows).toEqual([{ deleted_flag: true }]);
    const registered = catalog.profiles.savedViewWire.operations.map(
      (row: { method: string; path: string }) => `${row.method} ${row.path}`,
    );
    expect([...observed].sort()).toEqual([...registered].sort());
  } catch (error) {
    journeyError = error;
  } finally {
    for (const pid of owned) {
      try {
        const identity = await db.query(
          'SELECT deleted_flag FROM ab_saved_view WHERE pid=$1 AND owner_id=$2',
          [pid, actor.userPid],
        );
        expect(identity.rowCount).toBe(1);
        if (!identity.rows[0].deleted_flag) {
          const response = await actor.context.request.delete(`/api/views/${pid}`, { headers });
          expect(response.status()).toBe(200);
          expect((await response.json()).code).toBe('0');
        }
        const active = await db.query(
          'SELECT count(*)::int AS count FROM ab_saved_view WHERE pid=$1 AND owner_id=$2 AND deleted_flag=false',
          [pid, actor.userPid],
        );
        expect(active.rows[0].count).toBe(0);
      } catch (error) {
        cleanupErrors.push(error);
      }
    }
    try {
      await actor.dispose();
    } catch (error) {
      cleanupErrors.push(error);
    }
    await anonymous.close();
    await db.end();
    await info.attach('saved-view-wire-observations', {
      body: JSON.stringify(
        {
          claim: 'registered-operation-structures-and-listed-persistence-only',
          formatValidation: 'not-performed',
          registered: catalog.profiles.savedViewWire.operations.length,
          observed: [...observed].sort(),
          owned: [...owned],
          rows,
          sessionCleanup: actor.sessionCleanup, cleanupErrors: cleanupErrors.map(String),
          journeyError: journeyError ? String(journeyError) : null,
        },
        null,
        2,
      ),
      contentType: 'application/json',
    });
  }
  if (journeyError || cleanupErrors.length)
    throw new AggregateError(
      [...(journeyError ? [journeyError] : []), ...cleanupErrors],
      'SavedView wire journey or cleanup failed',
    );
});
