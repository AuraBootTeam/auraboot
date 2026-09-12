/** Stored artifacts must reauthorize their source before streaming bytes. */
import { test, expect, request as requestFactory } from '@playwright/test';
import { randomUUID } from 'node:crypto';

test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

test('revoked source permission blocks an existing export and restoration allows it', async ({
  request,
}) => {
  const code = `export_revoke_${randomUUID().replaceAll('-', '').slice(0, 10)}`;
  const role = await request.post('/api/roles', {
    data: {
      code,
      name: code,
      type: 'custom',
      status: 'active',
      scopeType: 'tenant',
      defaultDataScopeType: 'all',
    },
  });
  expect(role.status(), await role.text()).toBe(200);
  const rolePid = (await role.json()).data.pid;
  const tree = await request.get('/api/permissions/tree');
  expect(tree.status()).toBe(200);
  const permissions = new Map<string, number>();
  const collect = (nodes: any[]) => {
    for (const node of nodes) {
      permissions.set(node.code, node.id);
      collect(node.children ?? []);
    }
  };
  collect((await tree.json()).data);
  const sourcePermission = permissions.get('model.e2et_order.read');
  expect(sourcePermission).toBeTruthy();
  const grants = ['meta.query.read', 'model.e2et_order.read'].map((key) => {
    expect(permissions.get(key), key).toBeTruthy();
    return { permissionId: permissions.get(key), granted: true };
  });
  expect(
    (await request.put(`/api/permissions/matrix/${rolePid}/batch`, { data: grants })).status(),
  ).toBe(200);
  const email = `${code}@e2e.local`;
  const password = `Aa7!${randomUUID()}`;
  const user = await request.post('/api/admin/users', {
    data: {
      email,
      displayName: 'Export owner',
      initialPassword: password,
      roleCodes: [code],
      sendInviteEmail: false,
    },
  });
  expect(user.status(), await user.text()).toBe(200);
  const fixture = await request.post('/api/dynamic/e2et_order/create', {
    data: {
      e2et_order_title: code,
      e2et_order_type: 'normal',
      e2et_order_urgent: false,
      e2et_order_status: 'draft',
    },
  });
  expect(fixture.status()).toBe(200);
  const fixturePid = (await fixture.json()).data.pid;
  const query = await request.post('/api/meta/named-queries', {
    data: {
      code,
      title: 'Export source revocation',
      status: 'published',
      resourceCode: 'e2et_order',
      actionCode: 'read',
      fromSql: 'SELECT pid FROM mt_e2et_order WHERE e2et_order_title = #{params.marker}',
      fields: [
        { fieldCode: 'record_key', columnExpr: 'pid', dataType: 'string', operators: ['eq'] },
      ],
    },
  });
  expect(query.status(), await query.text()).toBe(200);
  const queryPid = (await query.json()).data.pid;
  const login = await request.post('/api/auth/login', { data: { email, password } });
  expect(login.status()).toBe(200);
  const jwt = (await login.json()).data.jwt;
  expect(jwt).toBeTruthy();
  const owner = await requestFactory.newContext({
    baseURL: process.env.BACKEND_URL,
    extraHTTPHeaders: { Authorization: `Bearer ${jwt}` },
  });
  try {
    const exported = await owner.post(`/api/meta/named-queries/${code}/export-data`, {
      data: { format: 'CSV', parameters: { marker: code } },
    });
    expect(exported.status(), await exported.text()).toBe(200);
    expect((await exported.json()).data.recordCount).toBe(1);
    const url = (await exported.json()).data.downloadUrl;
    const original = await owner.get(url);
    expect(original.status(), await original.text()).toBe(200);
    expect((await original.text()).trim().split(/\r?\n/)).toHaveLength(2);
    expect(await original.text()).toContain(fixturePid);
    expect(
      (
        await request.put(`/api/permissions/matrix/${rolePid}/batch`, {
          data: [{ permissionId: sourcePermission, granted: false }],
        })
      ).status(),
    ).toBe(200);
    const surface = await owner.get(`/api/meta/named-queries/${queryPid}`);
    expect(surface.status(), await surface.text()).toBe(200);
    const denied = await owner.get(url);
    expect(denied.status(), await denied.text()).toBe(403);
    expect(await denied.text()).toContain('Access denied for named query resource');
    expect(await denied.text()).not.toContain(fixturePid);
    expect(
      (
        await request.put(`/api/permissions/matrix/${rolePid}/batch`, {
          data: [{ permissionId: sourcePermission, granted: true }],
        })
      ).status(),
    ).toBe(200);
    const restored = await owner.get(url);
    expect(restored.status(), await restored.text()).toBe(200);
    expect(await restored.body()).toEqual(await original.body());
  } finally {
    await owner.dispose();
  }
});
