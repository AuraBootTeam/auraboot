import { test, expect } from '@playwright/test';
import { randomUUID } from 'node:crypto';

test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

test('report source failures hide database details and recover after source correction', async ({ request }) => {
  const key = `report_error_${randomUUID().replaceAll('-', '').slice(0, 12)}`;
  const fixture = await request.post('/api/dynamic/e2et_order/create', {
    data: { e2et_order_title: key, e2et_order_type: 'normal', e2et_order_urgent: false, e2et_order_status: 'draft' },
  });
  expect(fixture.status(), await fixture.text()).toBe(200);
  const sql = (expression: string) => `SELECT ${expression} AS amount FROM mt_e2et_order WHERE e2et_order_title = '${key}'`;
  const query = await request.post('/api/meta/named-queries', {
    data: { code: key, title: key, resourceCode: 'e2et_order', actionCode: 'read',
      fromSql: sql('7'), fields: [{ fieldCode: 'amount', columnExpr: 'amount', dataType: 'integer' }] },
  });
  expect(query.status(), await query.text()).toBe(200);
  const queryPid = (await query.json()).data.pid;
  const report = await request.post('/api/report-definitions', {
    data: { code: key, title: key, profile: 'paged-media', dsl: {
      version: '1.0.0', title: key,
      dataSources: { source: { type: 'namedQuery', queryCode: key } },
      body: [{ id: 'rows', blockType: 'table', dataSource: 'source', columns: [{ field: 'amount', label: 'Amount' }] }],
    } },
  });
  expect(report.status(), await report.text()).toBe(200);
  const reportPid = (await report.json()).data.pid;
  const exportJson = () => request.post('/api/reports/export/json', { data: { reportPid } });
  const initial = await exportJson();
  expect(initial.status(), await initial.text()).toBe(200);
  expect((await initial.json()).dataSets.source).toEqual([{ amount: 7 }]);
  const broken = await request.put(`/api/meta/named-queries/${queryPid}`, { data: { fromSql: sql('1 / 0') } });
  expect(broken.status(), await broken.text()).toBe(200);
  try {
    for (const format of ['json', 'excel', 'pdf']) {
      const response = await request.post(`/api/reports/export/${format}`, { data: { reportPid } });
      expect(response.status(), await response.text()).toBe(422);
      const body = await response.text();
      expect(body).toContain('Report data could not be loaded');
      for (const internal of ['SELECT', 'mt_e2et_order', 'division by zero', 'PSQLException', '1 / 0'])
        expect(body).not.toContain(internal);
      expect(response.headers()['content-disposition']).toBeUndefined();
    }
  } finally {
    const corrected = await request.put(`/api/meta/named-queries/${queryPid}`, { data: { fromSql: sql('7') } });
    expect(corrected.status(), await corrected.text()).toBe(200);
  }
  const recovered = await exportJson();
  expect(recovered.status(), await recovered.text()).toBe(200);
  expect((await recovered.json()).dataSets.source).toEqual([{ amount: 7 }]);
});
