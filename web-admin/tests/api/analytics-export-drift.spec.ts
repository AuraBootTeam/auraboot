/** Stored exports must track record-ownership drift and complex SQL must respect scopes. */
import { test, expect, request as requestFactory, type APIRequestContext } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { Client } from 'pg';
import { PG_CONN } from '../helpers/environments';

test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

test('department drift invalidates stored exports and complex SQL respects the scope', async ({
  request,
}) => {
  test.setTimeout(120000);
  const code = `export_drift_${randomUUID().replaceAll('-', '').slice(0, 10)}`;
  const marker = `drift_${code}`;

  const createRow = async (ctx: APIRequestContext, model: string, data: Record<string, unknown>) => {
    const response = await ctx.post(`/api/dynamic/${model}/create`, { data });
    expect(response.status(), await response.text()).toBe(200);
    const body = await response.json();
    expect(String(body.code), JSON.stringify(body)).toBe('0');
    expect(body.data.pid).toBeTruthy();
    return body.data.pid as string;
  };

  const deptA = await createRow(request, 'org_department', { org_dept_code: `${code}_a`, org_dept_name: `${code} A` });
  const deptB = await createRow(request, 'org_department', { org_dept_code: `${code}_b`, org_dept_name: `${code} B` });
  const position = await createRow(request, 'org_position', { org_pos_code: code, org_pos_name: code, org_pos_level: '1', org_pos_dept_id: deptA });

  const role = await request.post('/api/roles', {
    data: { code, name: code, type: 'custom', status: 'active', scopeType: 'tenant', defaultDataScopeType: 'all' },
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
  const grants = ['meta.query.read', 'model.e2et_order.read', 'model.e2et_order.create'].map((key) => {
    expect(permissions.get(key), key).toBeTruthy();
    return { permissionId: permissions.get(key), granted: true };
  });
  expect((await request.put(`/api/permissions/matrix/${rolePid}/batch`, { data: grants })).status()).toBe(200);
  expect(
    (
      await request.put(`/api/permissions/matrix/${rolePid}/scope`, {
        data: { resourceCode: 'e2et_order', actionCode: 'read', scopeType: 'dept', mergeStrategy: 'MIN' },
      })
    ).status(),
  ).toBe(200);

  const db = new Client(PG_CONN);
  await db.connect();
  const makeUser = async (name: string) => {
    const email = `${name.replaceAll(' ', '-')}-${randomUUID()}@e2e.local`;
    const password = `Aa7!${randomUUID()}`;
    const created = await request.post('/api/admin/users', {
      data: { email, displayName: name, initialPassword: password, roleCodes: [code], sendInviteEmail: false },
    });
    expect(created.status(), await created.text()).toBe(200);
    const login = await request.post('/api/auth/login', { data: { email, password } });
    expect(login.status(), await login.text()).toBe(200);
    const identity = await db.query(
      'SELECT u.id AS user_id, m.pid AS member_pid FROM ab_user u JOIN ab_tenant_member m ON m.user_id=u.id WHERE u.email=$1',
      [email],
    );
    expect(identity.rows).toHaveLength(1);
    const ctx = await requestFactory.newContext({
      baseURL: process.env.BACKEND_URL,
      extraHTTPHeaders: { Authorization: `Bearer ${(await login.json()).data.jwt}` },
    });
    return { ctx, email, userId: String(identity.rows[0].user_id), memberPid: String(identity.rows[0].member_pid) };
  };
  const reader = await makeUser('Drift reader');
  const creator = await makeUser('Drift creator');
  try {
    const readerEmployee = await createRow(request, 'org_employee', {
      org_emp_code: `${code}_reader`, org_emp_name: 'Drift reader', org_emp_dept_id: deptA,
      org_emp_position_id: position, org_emp_status: 'active', org_emp_type: 'human',
      org_emp_member_id: reader.memberPid, org_emp_user_id: reader.userId,
    });
    const creatorEmployee = await createRow(request, 'org_employee', {
      org_emp_code: `${code}_creator`, org_emp_name: 'Drift creator', org_emp_dept_id: deptA,
      org_emp_position_id: position, org_emp_status: 'active', org_emp_type: 'human',
      org_emp_member_id: creator.memberPid, org_emp_user_id: creator.userId,
    });

    const modelResponse = await request.get('/api/meta/models/code/e2et_order');
    expect(modelResponse.status()).toBe(200);
    const model = (await modelResponse.json()).data;
    const originalExtension = model.extension ?? {};
    const saveExtension = async (extension: Record<string, unknown>) => {
      const response = await request.put(`/api/meta/models/${model.pid}`, {
        data: { extension, displayName: model.displayName, description: model.description, modelType: model.modelType },
      });
      expect(response.status(), await response.text()).toBe(200);
    };
    await saveExtension({ ...originalExtension, dataScope: { ...(originalExtension.dataScope ?? {}), departmentOwnerField: 'created_by' } });

    const recordPid = await (async () => {
      const created = await creator.ctx.post('/api/dynamic/e2et_order/create', {
        data: { e2et_order_title: marker, e2et_order_type: 'normal', e2et_order_urgent: false, e2et_order_status: 'draft' },
      });
      expect(created.status(), await created.text()).toBe(200);
      return ((await created.json()).data.pid) as string;
    })();

    const plain = await request.post('/api/meta/named-queries', {
      data: {
        code, title: 'Export ownership drift', status: 'published', resourceCode: 'e2et_order', actionCode: 'read',
        fromSql: 'SELECT pid, e2et_order_title AS title, created_by FROM mt_e2et_order WHERE e2et_order_title = #{params.marker}',
        fields: [
          { fieldCode: 'record_key', columnExpr: 'pid', dataType: 'string', operators: ['eq'] },
          { fieldCode: 'title', columnExpr: 'title', dataType: 'string', operators: ['eq'] },
        ],
      },
    });
    expect(plain.status(), await plain.text()).toBe(200);
    const complexCode = `${code}_cte`;
    const complex = await request.post('/api/meta/named-queries', {
      data: {
        code: complexCode, title: 'Export drift CTE join', status: 'published', resourceCode: 'e2et_order', actionCode: 'read',
        fromSql:
          `WITH base AS (SELECT pid, e2et_order_title AS title, created_by, e2et_order_status AS status ` +
          `FROM mt_e2et_order WHERE e2et_order_title = #{params.marker}) ` +
          `SELECT b.pid, b.title, b.created_by, s.status_label FROM base b ` +
          `JOIN (SELECT 'draft' AS status_label, 'draft' AS k) s ON s.k = b.status`,
        fields: [
          { fieldCode: 'record_key', columnExpr: 'pid', dataType: 'string', operators: ['eq'] },
          { fieldCode: 'title', columnExpr: 'title', dataType: 'string', operators: ['eq'] },
          { fieldCode: 'status_label', columnExpr: 'status_label', dataType: 'string', operators: ['eq'] },
        ],
      },
    });
    expect(complex.status(), await complex.text()).toBe(200);

    const exportAs = async (ctx: APIRequestContext, queryCode: string) => {
      const response = await ctx.post(`/api/meta/named-queries/${queryCode}/export-data`, {
        data: { format: 'CSV', parameters: { marker } },
      });
      expect(response.status(), await response.text()).toBe(200);
      const result = (await response.json()).data;
      const download = await ctx.get(result.downloadUrl);
      expect(download.status(), await download.text()).toBe(200);
      return { recordCount: result.recordCount as number, url: result.downloadUrl as string, body: await download.body() };
    };

    const original = await exportAs(reader.ctx, code);
    expect(original.recordCount).toBe(1);
    expect(original.body.toString()).toContain(recordPid);
    const complexOriginal = await exportAs(reader.ctx, complexCode);
    expect(complexOriginal.recordCount).toBe(1);
    expect(complexOriginal.body.toString()).toContain(recordPid);

    const moveCreator = async (department: string) => {
      const moved = await request.put(`/api/dynamic/org_employee/${creatorEmployee}`, { data: { org_emp_dept_id: department } });
      expect(moved.status(), await moved.text()).toBe(200);
      expect(String((await moved.json()).code)).toBe('0');
    };
    await moveCreator(deptB);

    const drifted = await reader.ctx.get(original.url);
    expect(drifted.status(), 'ownership drift must invalidate the stored artifact').toBe(403);
    expect(await drifted.text()).toContain('Export data no longer matches current permissions');
    expect(await drifted.text()).not.toContain(recordPid);
    const complexDrifted = await reader.ctx.get(complexOriginal.url);
    expect(complexDrifted.status()).toBe(403);

    const restricted = await exportAs(reader.ctx, code);
    expect(restricted.recordCount).toBe(0);
    const complexRestricted = await exportAs(reader.ctx, complexCode);
    expect(complexRestricted.recordCount, 'CTE/join projection must respect the dept scope').toBe(0);

    await moveCreator(deptA);

    const restored = await reader.ctx.get(original.url);
    expect(restored.status(), 'returning the creator restores the original artifact').toBe(200);
    expect(await restored.body()).toEqual(original.body);
    const complexRestored = await reader.ctx.get(complexOriginal.url);
    expect(complexRestored.status()).toBe(200);
    expect(await complexRestored.body()).toEqual(complexOriginal.body);
    const again = await exportAs(reader.ctx, code);
    expect(again.recordCount).toBe(1);

    await saveExtension(originalExtension);
    expect(readerEmployee).toBeTruthy();
  } finally {
    await reader.ctx.dispose();
    await creator.ctx.dispose();
    await db.end();
  }
});
