/** Stored artifacts must reauthorize their source before streaming bytes. */
import { test, expect, request as requestFactory } from '@playwright/test';
import { randomUUID } from 'node:crypto';

test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

test('column policy hides aliased export fields and invalidates old files', async ({
  request,
}, testInfo) => {
  const boundary: string = 'resource';
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
  const exportPolicy =
    boundary === 'root'
      ? { rootAccess: { modelCode: 'e2et_order', pidParam: 'rootPid', actionCode: 'read' } }
      : {};
  const query = await request.post('/api/meta/named-queries', {
    data: {
      code,
      title: 'Export source revocation',
      status: 'published',
      ...(boundary === 'resource' ? { resourceCode: 'e2et_order', actionCode: 'read' } : {}),
      policy: exportPolicy,
      fromSql:
        'SELECT pid, created_by, e2et_order_title FROM mt_e2et_order WHERE e2et_order_title = #{params.marker}',
      fields: [
        { fieldCode: 'record_key', columnExpr: 'pid', dataType: 'string', operators: ['eq'] },
        {
          fieldCode: 'display_title',
          columnExpr: 'e2et_order_title',
          dataType: 'string',
          operators: ['eq'],
        },
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
      data: { format: 'CSV', parameters: { marker: code, rootPid: fixturePid } },
    });
    expect(exported.status(), await exported.text()).toBe(200);
    expect((await exported.json()).data.recordCount).toBe(1);
    const url = (await exported.json()).data.downloadUrl;
    const original = await owner.get(url);
    expect(original.status(), await original.text()).toBe(200);
    expect((await original.text()).trim().split(/\r?\n/)).toHaveLength(2);
    expect(await original.text()).toContain(fixturePid);
    const policy = await request.post('/api/meta/data-permissions', {
      data: {
        name: `Hide title ${code}`,
        modelCode: 'e2et_order',
        policyType: 'column',
        fieldCode: 'e2et_order_title',
        maskType: 'hide',
        enabled: true,
      },
    });
    expect(policy.status(), await policy.text()).toBe(200);
    const policyPid = (await policy.json()).data.pid;
    const binding = await request.post(`/api/meta/data-permissions/${policyPid}/roles/${rolePid}`);
    expect(binding.status(), await binding.text()).toBe(200);
    const detail = await owner.get(`/api/dynamic/e2et_order/${fixturePid}`);
    expect(detail.status(), await detail.text()).toBe(200);
    expect((await detail.json()).data.e2et_order_title).toBeNull();
    const oldFile = await owner.get(url);
    const maskedExport = await owner.post(`/api/meta/named-queries/${code}/export-data`, {
      data: { format: 'CSV', parameters: { marker: code, rootPid: fixturePid } },
    });
    expect(maskedExport.status(), await maskedExport.text()).toBe(200);
    const newFile = await owner.get((await maskedExport.json()).data.downloadUrl);
    expect(newFile.status(), await newFile.text()).toBe(200);
    await testInfo.attach('column-policy-observation', {
      contentType: 'application/json',
      body: JSON.stringify({
        dynamicTitleHidden: (await detail.json()).data.e2et_order_title === null,
        oldFileStatus: oldFile.status(),
        oldFileContainsTitle: (await oldFile.text()).includes(code),
        newFileContainsTitle: (await newFile.text()).includes(code),
      }),
    });
    expect
      .soft(oldFile.status(), 'A newly protected field must invalidate the unmasked artifact')
      .toBe(403);
    expect(
      await newFile.text(),
      'Export aliases must preserve physical field masking',
    ).not.toContain(code);
  } finally {
    await owner.dispose();
  }
});
