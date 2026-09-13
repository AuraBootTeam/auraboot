/** Real export ownership: a same-tenant administrator cannot retrieve another user's artifact. */
import { test, expect, request as requestFactory } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { Client } from 'pg';
import { PG_CONN } from '../helpers/environments';

test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

test('async export task and file belong to their creator', async ({ request }) => {
  test.setTimeout(120000);
  const code = `export_owner_${randomUUID().replaceAll('-', '').slice(0, 10)}`;
  const created = await request.post('/api/meta/named-queries', {
    data: {
      code,
      title: 'Export ownership',
      fromSql: 'mt_e2et_order',
      status: 'published',
      fields: [
        { fieldCode: 'record_key', columnExpr: 'pid', dataType: 'string', operators: ['eq'] },
      ],
    },
  });
  expect(created.status(), await created.text()).toBe(200);
  const queryPid = (await created.json()).data.pid;
  const submitted = await request.post(`/api/meta/named-queries/${code}/export-async`, {
    data: { format: 'EXCEL' },
  });
  expect(submitted.status(), await submitted.text()).toBe(200);
  const pid = (await submitted.json()).data.pid;
  expect(pid).toBeTruthy();
  const path = `/api/meta/named-queries/export-tasks/${pid}`;
  await expect
    .poll(
      async () => {
        const status = await request.get(path);
        expect(status.status(), await status.text()).toBe(200);
        const state = (await status.json()).data.status;
        expect(state).not.toBe('failed');
        return state;
      },
      { timeout: 15000 },
    )
    .toBe('completed');
  const download = await request.get(`${path}/download`);
  expect(download.status(), await download.text()).toBe(200);
  expect((await download.body()).subarray(0, 2).toString()).toBe('PK');

  const synchronous = await request.post(`/api/meta/named-queries/${code}/export-data`, {
    data: { format: 'CSV' },
  });
  expect(synchronous.status(), await synchronous.text()).toBe(200);
  const syncUrl = (await synchronous.json()).data.downloadUrl;
  expect(syncUrl).toMatch(/^\/api\/meta\/named-queries\/export-tasks\/[^/]+\/download$/);
  const syncFile = await request.get(syncUrl);
  expect(syncFile.status()).toBe(200);
  const db = new Client(PG_CONN);
  await db.connect();
  try {
    const stored = await db.query('SELECT file_key FROM ab_export_task WHERE pid = $1', [
      syncUrl.split('/').at(-2),
    ]);
    expect(stored.rows).toHaveLength(1);
    const fileKey = stored.rows[0].file_key;
    const rawNamed = await request.get(`/api/meta/named-queries/${code}/download`, {
      params: { file: fileKey },
    });
    expect(rawNamed.status()).toBe(404);
    const rawDynamic = await request.get('/api/dynamic/e2et_order/download', {
      params: { file: fileKey },
    });
    expect(rawDynamic.status()).toBe(403);
  } finally {
    await db.end();
  }

  if (process.env.AURA_EXPORT_CROSS_TENANT === '1') {
    const before = await request.get('/api/auth/me');
    expect(before.status()).toBe(200);
    const original = (await before.json()).data.user;
    const createdTenant = await request.post('/api/tenant-selection/process', {
      timeout: 30000,
      data: { action: 'create', tenantName: code, displayName: 'Export isolation' },
    });
    expect(createdTenant.status()).toBe(200);
    const tenant = (await createdTenant.json()).data;
    expect(tenant.jwt).toBeTruthy();
    const foreign = await requestFactory.newContext({
      baseURL: process.env.BACKEND_URL,
      extraHTTPHeaders: { Authorization: `Bearer ${tenant.jwt}` },
    });
    try {
      const me = await foreign.get('/api/auth/me');
      expect(me.status()).toBe(200);
      const identity = (await me.json()).data;
      expect(identity.user.id).toBe(original.id);
      expect(String(identity.user.tenantId)).not.toBe(String(original.tenantId));
      expect(String(identity.user.tenantId)).toBe(String(tenant.tenantId));
      expect(identity.permissions.permissionCodes).toContain('meta.query.read');
      const paramVariants: Array<Record<string, string>> = [
        {},
        { tenantId: String(original.tenantId) },
      ];
      for (const target of [path, `${path}/download`, syncUrl]) {
        for (const params of paramVariants) {
          const denied = await foreign.get(target, { params });
          expect(denied.status(), await denied.text()).toBe(400);
          const body = await denied.text();
          expect(body).toContain('Export task not found');
          expect(body).not.toContain('fileKey');
          expect(denied.headers()['content-disposition']).toBeUndefined();
        }
      }
      const unchangedSync = await request.get(syncUrl);
      expect(unchangedSync.status()).toBe(200);
      expect(await unchangedSync.body()).toEqual(await syncFile.body());
      const unchangedAsync = await request.get(`${path}/download`);
      expect(unchangedAsync.status()).toBe(200);
      expect(await unchangedAsync.body()).toEqual(await download.body());
    } finally {
      await foreign.dispose();
    }
  }

  const email = `export-${randomUUID()}@e2e.local`;
  const password = `Aa7!${randomUUID()}`;
  const user = await request.post('/api/admin/users', {
    data: {
      email,
      displayName: 'Export peer',
      initialPassword: password,
      roleCodes: ['tenant_admin'],
      sendInviteEmail: false,
    },
  });
  expect(user.status(), await user.text()).toBe(200);
  const login = await request.post('/api/auth/login', { data: { email, password } });
  expect(login.status()).toBe(200);
  const jwt = (await login.json()).data.jwt;
  expect(jwt).toBeTruthy();
  const peer = await requestFactory.newContext({
    baseURL: process.env.BACKEND_URL,
    extraHTTPHeaders: { Authorization: `Bearer ${jwt}` },
  });
  try {
    const allowed = await peer.get(`/api/meta/named-queries/${queryPid}`);
    expect(allowed.status(), await allowed.text()).toBe(200);
    for (const target of [path, `${path}/download`, syncUrl]) {
      const denied = await peer.get(target);
      expect(denied.status(), await denied.text()).toBe(400);
      expect(await denied.text()).toContain('Export task not found');
      expect(await denied.text()).not.toContain('fileKey');
    }
    const syncAgain = await request.get(syncUrl);
    expect(syncAgain.status()).toBe(200);
    expect(await syncAgain.body()).toEqual(await syncFile.body());
    const ownerAgain = await request.get(`${path}/download`);
    expect(ownerAgain.status()).toBe(200);
    expect(await ownerAgain.body()).toEqual(await download.body());
  } finally {
    await peer.dispose();
  }
});
