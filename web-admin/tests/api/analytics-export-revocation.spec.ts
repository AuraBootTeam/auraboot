/** Stored artifacts must reauthorize their source before streaming bytes. */
import { test, expect, request as requestFactory } from '@playwright/test';
import { randomUUID } from 'node:crypto';

test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

for (const boundary of ['resource', 'root'] as const) {
  test(`revoked ${boundary} permission blocks an existing export and restoration allows it`, async ({
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
          'SELECT pid FROM mt_e2et_order WHERE e2et_order_title = #{params.marker}',
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
        data: { format: 'CSV', parameters: { marker: code, rootPid: fixturePid } },
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
      expect(await denied.text()).toContain(`Access denied for named query ${boundary}`);
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
      const deprecated = await request.put(`/api/meta/named-queries/${queryPid}/status`, {
        data: { status: 'deprecated' },
      });
      expect(deprecated.status(), await deprecated.text()).toBe(200);
      const unavailable = await owner.get(url);
      expect(unavailable.status(), await unavailable.text()).toBe(403);
      expect(await unavailable.text()).toContain('Export query is no longer available');
      const republished = await request.put(`/api/meta/named-queries/${queryPid}/status`, {
        data: { status: 'published' },
      });
      expect(republished.status(), await republished.text()).toBe(200);
      const activeAgain = await owner.get(url);
      expect(activeAgain.status()).toBe(200);
      expect(await activeAgain.body()).toEqual(await original.body());
      const changed = await request.put(`/api/meta/named-queries/${queryPid}`, {
        data: { policy: { exportMaxRows: 1 } },
      });
      expect(changed.status(), await changed.text()).toBe(200);
      const stale = await owner.get(url);
      expect(stale.status(), await stale.text()).toBe(403);
      expect(await stale.text()).toContain('Export query definition has changed');
      const fresh = await owner.post(`/api/meta/named-queries/${code}/export-data`, {
        data: { format: 'CSV', parameters: { marker: code, rootPid: fixturePid } },
      });
      expect(fresh.status(), await fresh.text()).toBe(200);
      const freshFile = await owner.get((await fresh.json()).data.downloadUrl);
      expect(freshFile.status()).toBe(200);
      expect(await freshFile.body()).toEqual(await original.body());
      const resetPolicy = await request.put(`/api/meta/named-queries/${queryPid}`, {
        data: { policy: exportPolicy },
      });
      expect(resetPolicy.status(), await resetPolicy.text()).toBe(200);
      if (boundary === 'resource') {
        const scoped = await request.put(`/api/permissions/matrix/${rolePid}/default-scope`, {
          data: { scopeType: 'self' },
        });
        expect(scoped.status(), await scoped.text()).toBe(200);
        const oldScope = await owner.get(url);
        expect(oldScope.status(), await oldScope.text()).toBe(403);
        expect(await oldScope.text()).toContain('Export query definition has changed');
        const restricted = await owner.post(`/api/meta/named-queries/${code}/export-data`, {
          data: { format: 'CSV', parameters: { marker: code, rootPid: fixturePid } },
        });
        expect(restricted.status(), await restricted.text()).toBe(200);
        expect((await restricted.json()).data.recordCount).toBe(0);
        const restrictedFile = await owner.get((await restricted.json()).data.downloadUrl);
        expect(restrictedFile.status(), await restrictedFile.text()).toBe(200);
        expect((await restrictedFile.text()).trim().split(/\r?\n/)).toHaveLength(1);
        expect(await restrictedFile.text()).not.toContain(fixturePid);
        const unscoped = await request.put(`/api/permissions/matrix/${rolePid}/default-scope`, {
          data: { scopeType: 'all' },
        });
        expect(unscoped.status(), await unscoped.text()).toBe(200);
        const allAgain = await owner.get(url);
        expect(allAgain.status(), await allAgain.text()).toBe(200);
        expect(await allAgain.body()).toEqual(await original.body());
      }
      if (boundary === 'root') {
        const deleted = await request.delete(`/api/dynamic/e2et_order/${fixturePid}`);
        expect(deleted.status(), await deleted.text()).toBe(200);
        const orphaned = await owner.get(url);
        expect(orphaned.status(), await orphaned.text()).toBe(400);
        expect(await orphaned.text()).toContain('Record not found');
        expect(orphaned.headers()['content-type']).toContain('application/json');
      }
    } finally {
      await owner.dispose();
    }
  });
}

test('SQL and field edits invalidate old files and change new export content', async ({
  request,
}) => {
  const code = `export_edit_${randomUUID().replaceAll('-', '').slice(0, 10)}`;
  const fixture = await request.post('/api/dynamic/e2et_order/create', {
    data: {
      e2et_order_title: code,
      e2et_order_type: 'normal',
      e2et_order_urgent: false,
      e2et_order_status: 'draft',
    },
  });
  expect(fixture.status(), await fixture.text()).toBe(200);
  const fixturePid = (await fixture.json()).data.pid;
  const fromSql = 'SELECT pid, e2et_order_title FROM mt_e2et_order WHERE pid = #{params.rootPid}';
  const created = await request.post('/api/meta/named-queries', {
    data: {
      code,
      title: 'Export definition edits',
      status: 'draft',
      fromSql,
      fields: [
        { fieldCode: 'record_key', columnExpr: 'pid', dataType: 'string', operators: ['eq'] },
      ],
    },
  });
  expect(created.status(), await created.text()).toBe(200);
  const queryPid = (await created.json()).data.pid;
  const exportFile = async (expected: string) => {
    const response = await request.post(`/api/meta/named-queries/${code}/export-data`, {
      data: { format: 'CSV', parameters: { rootPid: fixturePid, marker: code } },
    });
    expect(response.status(), await response.text()).toBe(200);
    expect((await response.json()).data.recordCount).toBe(1);
    const url = (await response.json()).data.downloadUrl;
    const download = await request.get(url);
    expect(download.status(), await download.text()).toBe(200);
    const lines = (await download.text())
      .replace(/^\uFEFF/, '')
      .trim()
      .split(/\r?\n/);
    expect(lines).toHaveLength(2);
    expect(lines[1].replace(/^"|"$/g, '')).toBe(expected);
    return url;
  };
  const original = await exportFile(fixturePid);
  const revisedSql = `${fromSql} AND e2et_order_title = #{params.marker}`;
  const sqlUpdate = await request.put(`/api/meta/named-queries/${queryPid}`, {
    data: { fromSql: revisedSql },
  });
  expect(sqlUpdate.status(), await sqlUpdate.text()).toBe(200);
  const saved = await request.get(`/api/meta/named-queries/${queryPid}`);
  expect(saved.status()).toBe(200);
  expect((await saved.json()).data.fromSql).toBe(revisedSql);
  const staleSql = await request.get(original);
  expect(staleSql.status(), await staleSql.text()).toBe(403);
  expect(await staleSql.text()).toContain('Export query definition has changed');
  const afterSql = await exportFile(fixturePid);
  const fieldUpdate = await request.put(`/api/meta/named-queries/${code}/fields/record_key`, {
    data: {
      fieldCode: 'record_key',
      columnExpr: 'e2et_order_title',
      dataType: 'string',
      operators: ['eq'],
    },
  });
  expect(fieldUpdate.status(), await fieldUpdate.text()).toBe(200);
  const fields = await request.get(`/api/meta/named-queries/${code}/fields`);
  expect(fields.status()).toBe(200);
  expect(
    (await fields.json()).data.find((field: any) => field.fieldCode === 'record_key').columnExpr,
  ).toBe('e2et_order_title');
  const staleField = await request.get(afterSql);
  expect(staleField.status(), await staleField.text()).toBe(403);
  expect(await staleField.text()).toContain('Export query definition has changed');
  await exportFile(code);
});
