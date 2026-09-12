/** Tenant-scoped identities and colliding fixture values must remain isolated. */
import { test, expect, request as requestFactory } from '@playwright/test';
import { randomUUID } from 'node:crypto';

test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

for (const shape of ['select', 'cte'] as const) {
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
    try {
      const spaces = await request.get('/api/tenant-selection/my-spaces');
      expect(spaces.status()).toBe(200);
      expect(
        (await spaces.json()).data.some(
          (space: any) => String(space.tenantId) === String(identity.tenantId),
        ),
      ).toBe(false);
      const clients = [request, other];
      const pids: string[] = [];
      for (const client of clients) {
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
        const query = await client.post('/api/meta/named-queries', {
          data: {
            code: marker,
            title: 'Tenant-isolated named source',
            status: 'published',
            fromSql:
              shape === 'cte'
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
      for (const [index, client] of clients.entries()) {
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
        const foreignFile = await clients[1 - index].get((await exported.json()).data.downloadUrl);
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
      await other.dispose();
    }
  });
}
