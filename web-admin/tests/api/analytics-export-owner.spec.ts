/** Real export ownership: a same-tenant administrator cannot retrieve another user's artifact. */
import { test, expect, request as requestFactory } from '@playwright/test';
import { randomUUID } from 'node:crypto';

test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

test('async export task and file belong to their creator', async ({ request }) => {
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
    for (const target of [path, `${path}/download`]) {
      const denied = await peer.get(target);
      expect(denied.status(), await denied.text()).toBe(400);
      expect(await denied.text()).toContain('Export task not found');
      expect(await denied.text()).not.toContain('fileKey');
    }
    const ownerAgain = await request.get(`${path}/download`);
    expect(ownerAgain.status()).toBe(200);
    expect(await ownerAgain.body()).toEqual(await download.body());
  } finally {
    await peer.dispose();
  }
});
