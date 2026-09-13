/** A view must preserve its own authorization and every underlying source boundary. */
import { test, expect, request as requestFactory } from '@playwright/test';
import { randomUUID } from 'node:crypto';

test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

test('view aliases preserve underlying read, row and field protection', async ({ request }) => {
  test.setTimeout(150000);
  const marker = `view_${randomUUID().replaceAll('-', '')}`;
  const seed = await request.post('/api/test/seed', {
    params: { testRunId: marker },
    timeout: 90000,
  });
  expect(seed.status(), await seed.text()).toBe(200);
  const viewModel = 'e2et_analytics_order_view';
  const existing = await request.get(`/api/meta/models/code/${viewModel}`);
  if (existing.status() !== 200 || !(await existing.json()).data) {
    const created = await request.post('/api/meta/models', {
      data: {
        code: viewModel,
        displayName: 'Analytics order view',
        modelType: 'virtual',
        sourceType: 'sqlView',
        sourceRef: 'v_e2et_analytics_order',
        primaryKey: 'pid',
        fields: ['pid', 'tenant_id', 'created_by', 'label'].map((code) => ({
          code,
          name: code,
          columnName: code,
          displayName: code,
          dataType: 'string',
        })),
      },
    });
    expect(created.status(), await created.text()).toBe(200);
    expect((await created.json()).code, await created.text()).toBe('0');
    const published = await request.post(
      `/api/meta/models/${(await created.json()).data.pid}/publish`,
    );
    expect(published.status(), await published.text()).toBe(200);
    expect((await published.json()).code, await published.text()).toBe('0');
  }
  const currentModel = await request.get(`/api/meta/models/code/${viewModel}`);
  expect(currentModel.status(), await currentModel.text()).toBe(200);
  if ((await currentModel.json()).data.status === 'draft') {
    const published = await request.post(
      `/api/meta/models/${(await currentModel.json()).data.pid}/publish`,
    );
    expect(published.status(), await published.text()).toBe(200);
    expect((await published.json()).code, await published.text()).toBe('0');
  }
  const role = await request.post('/api/roles', {
    data: {
      code: marker,
      name: marker,
      type: 'custom',
      status: 'active',
      scopeType: 'tenant',
      defaultDataScopeType: 'all',
    },
  });
  expect(role.status(), await role.text()).toBe(200);
  const rolePid = (await role.json()).data.pid;
  const tree = await request.get('/api/permissions/tree');
  const permissions = new Map<string, number>();
  const collect = (nodes: any[]) => {
    for (const node of nodes) {
      permissions.set(node.code, node.id);
      collect(node.children ?? []);
    }
  };
  collect((await tree.json()).data);
  const setRead = async (model: string, granted: boolean) => {
    const permissionId = permissions.get(`model.${model}.read`);
    expect(permissionId).toBeTruthy();
    const response = await request.put(`/api/permissions/matrix/${rolePid}/batch`, {
      data: [{ permissionId, granted }],
    });
    expect(response.status(), await response.text()).toBe(200);
  };
  const queryPermission = permissions.get('meta.query.read');
  expect(queryPermission).toBeTruthy();
  expect(
    (
      await request.put(`/api/permissions/matrix/${rolePid}/batch`, {
        data: [{ permissionId: queryPermission, granted: true }],
      })
    ).status(),
  ).toBe(200);
  await setRead(viewModel, true);
  await setRead('e2et_order', true);
  const password = `Aa7!${randomUUID()}`;
  const email = `${marker}@e2e.local`;
  const user = await request.post('/api/admin/users', {
    data: {
      email,
      displayName: 'View analyst',
      initialPassword: password,
      roleCodes: [marker],
      sendInviteEmail: false,
    },
  });
  expect(user.status(), await user.text()).toBe(200);
  const created = await request.post('/api/dynamic/e2et_order/create', {
    data: {
      e2et_order_title: marker,
      e2et_order_type: 'normal',
      e2et_order_urgent: false,
      e2et_order_status: 'draft',
    },
  });
  expect(created.status(), await created.text()).toBe(200);
  const pid = (await created.json()).data.pid;
  const query = await request.post('/api/meta/named-queries', {
    data: {
      code: marker,
      title: 'Governed view',
      status: 'published',
      fromSql: 'SELECT pid, label FROM v_e2et_analytics_order WHERE label = #{params.marker}',
      fields: [
        { fieldCode: 'record_key', columnExpr: 'pid', dataType: 'string', operators: ['eq'] },
        { fieldCode: 'title', columnExpr: 'label', dataType: 'string', operators: ['eq'] },
      ],
    },
  });
  expect(query.status(), await query.text()).toBe(200);
  const login = await request.post('/api/auth/login', { data: { email, password } });
  expect(login.status()).toBe(200);
  const owner = await requestFactory.newContext({
    baseURL: process.env.BACKEND_URL,
    extraHTTPHeaders: { Authorization: `Bearer ${(await login.json()).data.jwt}` },
  });
  try {
    const parameters = { marker, tenantId: -1 };
    const exported = await owner.post(`/api/meta/named-queries/${marker}/export-data`, {
      data: { format: 'JSON', parameters },
    });
    expect(exported.status(), await exported.text()).toBe(200);
    expect((await exported.json()).code, await exported.text()).toBe('0');
    const originalUrl = (await exported.json()).data.downloadUrl;
    const original = await owner.get(originalUrl);
    expect(original.status()).toBe(200);
    expect(JSON.parse(await original.text())).toEqual([{ record_key: pid, title: marker }]);
    const shadowCode = `${marker}_shadow`;
    const shadowQuery = await request.post('/api/meta/named-queries', {
      data: {
        code: shadowCode,
        title: 'View relation identity',
        status: 'published',
        fromSql:
          'WITH mt_e2et_order AS (SELECT * FROM mt_e2et_order WHERE 1=0) SELECT pid, label FROM v_e2et_analytics_order WHERE label = #{params.marker}',
        fields: [
          { fieldCode: 'record_key', columnExpr: 'pid', dataType: 'string', operators: ['eq'] },
        ],
      },
    });
    expect(shadowQuery.status(), await shadowQuery.text()).toBe(200);
    const shadow = await owner.post(`/api/meta/named-queries/${shadowCode}/execute`, {
      data: { parameters },
    });
    expect(shadow.status(), await shadow.text()).toBe(200);
    expect((await shadow.json()).data.records).toEqual([{ record_key: pid }]);
    for (const model of ['e2et_order', viewModel]) {
      await setRead(model, false);
      const denied = await owner.post(`/api/meta/named-queries/${marker}/execute`, {
        data: { parameters },
      });
      expect(denied.status(), await denied.text()).toBe(403);
      expect(await denied.text()).toContain(`Access denied for named query source: ${model}`);
      expect((await owner.get(originalUrl)).status()).toBe(403);
      await setRead(model, true);
    }
    const policy = await request.post('/api/meta/data-permissions', {
      data: {
        name: marker,
        modelCode: 'e2et_order',
        policyType: 'column',
        fieldCode: 'e2et_order_title',
        maskType: 'hide',
        enabled: true,
      },
    });
    expect(policy.status(), await policy.text()).toBe(200);
    expect(
      (
        await request.post(
          `/api/meta/data-permissions/${(await policy.json()).data.pid}/roles/${rolePid}`,
        )
      ).status(),
    ).toBe(200);
    expect((await owner.get(originalUrl)).status()).toBe(403);
    const listed = await owner.post(`/api/meta/named-queries/${marker}/execute`, {
      data: { parameters },
    });
    expect(listed.status(), await listed.text()).toBe(200);
    expect((await listed.json()).data.records).toEqual([{ record_key: pid, title: null }]);
    const chart = await owner.post('/api/meta/chart-data', {
      data: {
        type: 'namedQuery',
        queryCode: marker,
        dimensions: ['record_key', 'title'],
        metrics: [],
        parameters,
      },
    });
    expect(chart.status(), await chart.text()).toBe(200);
    expect((await chart.json()).data.rows).toEqual([{ record_key: pid, title: null }]);
    const submitted = await owner.post(`/api/meta/named-queries/${marker}/export-async`, {
      data: { format: 'JSON', parameters },
    });
    expect(submitted.status(), await submitted.text()).toBe(200);
    const taskPid = (await submitted.json()).data.pid;
    let task: any;
    await expect
      .poll(
        async () => {
          const response = await owner.get(`/api/meta/named-queries/export-tasks/${taskPid}`);
          expect(response.status()).toBe(200);
          task = (await response.json()).data;
          expect(task.status, task.errorMessage).not.toBe('failed');
          return task.status;
        },
        { timeout: 15000 },
      )
      .toBe('completed');
    expect(task.processedRows).toBe(1);
    const file = await owner.get(task.downloadUrl);
    expect(file.status()).toBe(200);
    expect(JSON.parse(await file.text())).toEqual([{ record_key: pid, title: null }]);
    const scoped = await request.put(`/api/permissions/matrix/${rolePid}/scope`, {
      data: {
        resourceCode: 'e2et_order',
        actionCode: 'read',
        scopeType: 'self',
        mergeStrategy: 'MAX',
      },
    });
    expect(scoped.status(), await scoped.text()).toBe(200);
    const hidden = await owner.post(`/api/meta/named-queries/${marker}/execute`, {
      data: { parameters },
    });
    expect(hidden.status(), await hidden.text()).toBe(200);
    expect((await hidden.json()).data.records).toEqual([]);
    expect((await hidden.json()).data.total).toBe(0);
    expect((await owner.get(task.downloadUrl)).status()).toBe(403);
  } finally {
    await owner.dispose();
  }
});
