/** Stored artifacts must reauthorize their source before streaming bytes. */
import { test, expect, request as requestFactory } from '@playwright/test';
import { randomUUID } from 'node:crypto';

test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

const boundary: string = 'inferred';
test(`config masking rechecks export applicability and exemptions`, async ({
  request,
}, testInfo) => {
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
  const grants = ['meta.query.read', 'model.e2et_order.read', 'model.e2et_customer.read'].map(
    (key) => {
      expect(permissions.get(key), key).toBeTruthy();
      return { permissionId: permissions.get(key), granted: true };
    },
  );
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
  let configId: number | undefined;
  try {
    const existing = await request.get('/api/field-mask/config', {
      params: { modelCode: 'e2et_order' },
    });
    expect(existing.status(), await existing.text()).toBe(200);
    expect(
      (await existing.json()).data.filter((config: any) => config.fieldCode === 'e2et_order_title'),
    ).toEqual([]);
    const base = {
      modelCode: 'e2et_order',
      fieldCode: 'e2et_order_title',
      maskType: 'FULL',
      replacementChar: '*',
      applyToExport: true,
      applyToList: false,
      applyToDetail: false,
      enabled: true,
      exemptRoles: '',
      exemptPermissionCodes: '',
    };
    const save = async (changes: Record<string, unknown>) => {
      const saved = await request.post('/api/field-mask/config', { data: { ...base, ...changes } });
      expect(saved.status(), await saved.text()).toBe(200);
      const value = (await saved.json()).data;
      expect(value.id).toBeTruthy();
      configId = value.id;
    };
    const exportValue = async (expected: string | null) => {
      const exported = await owner.post(`/api/meta/named-queries/${code}/export-data`, {
        data: { format: 'JSON', parameters: { marker: code } },
      });
      expect(exported.status(), await exported.text()).toBe(200);
      expect((await exported.json()).data.recordCount).toBe(1);
      const url = (await exported.json()).data.downloadUrl;
      const file = await owner.get(url);
      expect(file.status(), await file.text()).toBe(200);
      expect(JSON.parse(await file.text())).toEqual([
        { record_key: fixturePid, display_title: expected },
      ]);
      return url;
    };
    const denied = async (url: string) => {
      const file = await owner.get(url);
      expect(file.status(), await file.text()).toBe(403);
      expect(await file.text()).toContain('Export query definition has changed');
    };
    const original = await exportValue(code);
    await save({ exemptRoles: code });
    const roleExempt = await exportValue(code);
    const stillOriginal = await owner.get(original);
    expect(stillOriginal.status()).toBe(200);
    await save({});
    await denied(roleExempt);
    const masked = await exportValue('**********');
    const detail = await owner.get(`/api/dynamic/e2et_order/${fixturePid}`);
    expect(detail.status()).toBe(200);
    expect((await detail.json()).data.e2et_order_title).toBe(code);
    await save({ applyToExport: false });
    await denied(masked);
    const exportDisabled = await exportValue(code);
    await save({ exemptPermissionCodes: 'model.e2et_customer.read' });
    const permissionExempt = await exportValue(code);
    const revoke = await request.put(`/api/permissions/matrix/${rolePid}/batch`, {
      data: [{ permissionId: permissions.get('model.e2et_customer.read'), granted: false }],
    });
    expect(revoke.status(), await revoke.text()).toBe(200);
    await denied(permissionExempt);
    await denied(exportDisabled);
    await exportValue('**********');
    const restore = await request.put(`/api/permissions/matrix/${rolePid}/batch`, {
      data: [{ permissionId: permissions.get('model.e2et_customer.read'), granted: true }],
    });
    expect(restore.status(), await restore.text()).toBe(200);
    await exportValue(code);
    await save({});
    const beforePolicy = await exportValue('**********');
    const hidden = await request.post('/api/meta/data-permissions', {
      data: {
        name: `Hide configured field ${code}`,
        modelCode: 'e2et_order',
        policyType: 'column',
        fieldCode: 'e2et_order_title',
        maskType: 'hide',
        enabled: true,
      },
    });
    expect(hidden.status(), await hidden.text()).toBe(200);
    const binding = await request.post(
      `/api/meta/data-permissions/${(await hidden.json()).data.pid}/roles/${rolePid}`,
    );
    expect(binding.status(), await binding.text()).toBe(200);
    await denied(beforePolicy);
    await exportValue(null);
    await testInfo.attach('config-mask-observation', {
      contentType: 'application/json',
      body: JSON.stringify({
        roleExemption: true,
        permissionRevocation: true,
        restored: true,
        exportApplicability: true,
      }),
    });
  } finally {
    if (configId !== undefined) {
      const deleted = await request.delete(`/api/field-mask/config/${configId}`);
      expect(deleted.status(), await deleted.text()).toBe(200);
      const remaining = await request.get('/api/field-mask/config', {
        params: { modelCode: 'e2et_order' },
      });
      expect(remaining.status()).toBe(200);
      expect((await remaining.json()).data.some((config: any) => config.id === configId)).toBe(
        false,
      );
    }
    await owner.dispose();
  }
});
