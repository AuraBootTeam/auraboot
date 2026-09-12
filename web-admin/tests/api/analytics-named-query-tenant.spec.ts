/** Tenant-scoped identities and colliding fixture values must remain isolated. */
import { test, expect, request as requestFactory } from '@playwright/test';
import { randomUUID } from 'node:crypto';

test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

for (const shape of ['select', 'cte', 'view'] as const) {
  test(`named query consumers isolate populated tenants without SQL tenant predicates (${shape})`, async ({
    request,
  }) => {
    test.setTimeout(120000);
    const marker = `tenant_query_${randomUUID().replaceAll('-', '')}`;
    const seed = await request.post('/api/test/seed', {
      params: { testRunId: marker },
      timeout: 90000,
    });
    expect(seed.status(), await seed.text()).toBe(200);
    const identity = await seed.json();
    expect(identity.jwt).toBeTruthy();
    const other = await requestFactory.newContext({
      baseURL: process.env.BACKEND_URL,
      extraHTTPHeaders: { Authorization: `Bearer ${identity.jwt}` },
    });
    const scopedReaders: Awaited<ReturnType<typeof requestFactory.newContext>>[] = [];
    try {
      const spaces = await request.get('/api/tenant-selection/my-spaces');
      expect(spaces.status()).toBe(200);
      expect(
        (await spaces.json()).data.some(
          (space: any) => String(space.tenantId) === String(identity.tenantId),
        ),
      ).toBe(false);
      const clients = [request, other];
      const readers = [...clients];
      const pids: string[] = [];
      for (const [clientIndex, client] of clients.entries()) {
        const created = await client.post('/api/dynamic/e2et_order/create', {
          data: {
            e2et_order_title: marker,
            e2et_order_type: 'normal',
            e2et_order_urgent: false,
            e2et_order_status: 'draft',
          },
        });
        expect(created.status(), await created.text()).toBe(200);
        pids.push((await created.json()).data.pid);
        if (shape === 'view') {
          const viewCode = 'e2et_analytics_order_view';
          const existing = await client.get(`/api/meta/models/code/${viewCode}`);
          if (existing.status() === 200 && (await existing.json()).data?.status === 'draft') {
            const published = await client.post(
              `/api/meta/models/${(await existing.json()).data.pid}/publish`,
            );
            expect(published.status(), await published.text()).toBe(200);
            expect((await published.json()).code, await published.text()).toBe('0');
          }
          if (existing.status() !== 200 || !(await existing.json()).data) {
            const model = await client.post('/api/meta/models', {
              data: {
                code: viewCode,
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
            expect(model.status(), await model.text()).toBe(200);
            expect((await model.json()).code, await model.text()).toBe('0');
            const published = await client.post(
              `/api/meta/models/${(await model.json()).data.pid}/publish`,
            );
            expect(published.status(), await published.text()).toBe(200);
            expect((await published.json()).code, await published.text()).toBe('0');
          }
        }
        if (shape === 'view') {
          const roleCode = `view_reader_${randomUUID().replaceAll('-', '')}`;
          const role = await client.post('/api/roles', {
            data: {
              code: roleCode,
              name: roleCode,
              type: 'custom',
              status: 'active',
              scopeType: 'tenant',
              defaultDataScopeType: 'all',
            },
          });
          expect(role.status(), await role.text()).toBe(200);
          const tree = await client.get('/api/permissions/tree');
          expect(tree.status()).toBe(200);
          const permissions = new Map<string, number>();
          const collect = (nodes: any[]) => {
            for (const node of nodes) {
              permissions.set(node.code, node.id);
              collect(node.children ?? []);
            }
          };
          collect((await tree.json()).data);
          const grants = [
            'meta.query.read',
            'model.e2et_order.read',
            'model.e2et_analytics_order_view.read',
          ].map((code) => {
            expect(permissions.get(code), code).toBeTruthy();
            return { permissionId: permissions.get(code), granted: true };
          });
          expect(
            (
              await client.put(`/api/permissions/matrix/${(await role.json()).data.pid}/batch`, {
                data: grants,
              })
            ).status(),
          ).toBe(200);
          const email = `${marker}_${clientIndex}@e2e.local`;
          const password = `Aa7!${randomUUID()}`;
          const user = await client.post('/api/admin/users', {
            data: {
              email,
              displayName: 'Tenant view reader',
              initialPassword: password,
              roleCodes: [roleCode],
              sendInviteEmail: false,
            },
          });
          expect(user.status(), await user.text()).toBe(200);
          const login = await client.post('/api/auth/login', { data: { email, password } });
          expect(login.status(), await login.text()).toBe(200);
          const reader = await requestFactory.newContext({
            baseURL: process.env.BACKEND_URL,
            extraHTTPHeaders: { Authorization: `Bearer ${(await login.json()).data.jwt}` },
          });
          scopedReaders.push(reader);
          readers[clientIndex] = reader;
        }
        const query = await client.post('/api/meta/named-queries', {
          data: {
            code: marker,
            title: 'Tenant-isolated named source',
            status: 'published',
            fromSql:
              shape === 'view'
                ? 'SELECT pid FROM v_e2et_analytics_order WHERE label = #{params.marker}'
                : shape === 'cte'
                  ? 'WITH mt_e2et_order AS (SELECT pid, e2et_order_title FROM mt_e2et_order WHERE e2et_order_title = #{params.marker}), renamed(record_id, label) AS (SELECT pid, e2et_order_title FROM mt_e2et_order) SELECT record_id AS pid, label FROM renamed'
                  : 'SELECT pid, e2et_order_title FROM mt_e2et_order WHERE e2et_order_title = #{params.marker}',
            fields: [
              { fieldCode: 'record_key', columnExpr: 'pid', dataType: 'string', operators: ['eq'] },
            ],
          },
        });
        expect(query.status(), await query.text()).toBe(200);
      }
      expect(pids[0]).not.toBe(pids[1]);
      for (const [index, client] of readers.entries()) {
        const expected = [{ record_key: pids[index] }];
        const parameters = { marker, tenantId: -1 };
        const listed = await client.post(`/api/meta/named-queries/${marker}/execute`, {
          data: { page: 1, size: 5, parameters },
        });
        expect(listed.status(), await listed.text()).toBe(200);
        expect.soft((await listed.json()).data.records).toEqual(expected);
        expect.soft((await listed.json()).data.total).toBe(1);
        const chart = await client.post('/api/meta/chart-data', {
          data: {
            type: 'namedQuery',
            queryCode: marker,
            dimensions: ['record_key'],
            metrics: [],
            parameters,
          },
        });
        expect(chart.status(), await chart.text()).toBe(200);
        expect.soft((await chart.json()).data.rows).toEqual(expected);
        const exported = await client.post(`/api/meta/named-queries/${marker}/export-data`, {
          data: { format: 'JSON', parameters },
        });
        expect(exported.status(), await exported.text()).toBe(200);
        expect.soft((await exported.json()).data.recordCount).toBe(1);
        const file = await client.get((await exported.json()).data.downloadUrl);
        expect(file.status(), await file.text()).toBe(200);
        expect.soft(JSON.parse(await file.text())).toEqual(expected);
        const foreignFile = await readers[1 - index].get((await exported.json()).data.downloadUrl);
        expect(foreignFile.status(), await foreignFile.text()).toBe(400);
        expect(await foreignFile.text()).toContain('Export task not found');
        const submitted = await client.post(`/api/meta/named-queries/${marker}/export-async`, {
          data: { format: 'JSON', parameters },
        });
        expect(submitted.status(), await submitted.text()).toBe(200);
        const taskPath = `/api/meta/named-queries/export-tasks/${(await submitted.json()).data.pid}`;
        await expect
          .poll(
            async () => {
              const status = await client.get(taskPath);
              expect(status.status(), await status.text()).toBe(200);
              const task = (await status.json()).data;
              expect(task.status, task.errorMessage).not.toBe('failed');
              if (task.status === 'completed') expect(task.processedRows).toBe(1);
              return task.status;
            },
            { timeout: 15000 },
          )
          .toBe('completed');
        const asyncFile = await client.get(`${taskPath}/download`);
        expect(asyncFile.status(), await asyncFile.text()).toBe(200);
        expect(JSON.parse(await asyncFile.text())).toEqual(expected);
      }
    } finally {
      for (const reader of scopedReaders) await reader.dispose();
      await other.dispose();
    }
  });
}
