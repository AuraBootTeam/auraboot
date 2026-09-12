/** Named query execution must inspect physical sources, not only declared resource codes. */
import { test, expect } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { resolve } from 'node:path';
test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

test('private physical sources cannot be listed or aggregated through named queries', async ({
  request,
}) => {
  const imported = await request.post('/api/plugins/import/import-directory-sync', {
    data: {
      path: resolve(process.cwd(), '../plugins/core-dashboard'),
      conflictStrategy: 'OVERWRITE',
      validateReferences: true,
      autoPublishPages: true,
    },
  });
  expect(imported.status(), await imported.text()).toBe(200);
  const marker = `named_source_${randomUUID()}`;
  const fixture = await request.post('/api/dynamic/e2et_order/create', {
    data: {
      e2et_order_title: marker,
      e2et_order_type: 'normal',
      e2et_order_urgent: false,
      e2et_order_status: 'draft',
    },
  });
  expect(fixture.status(), await fixture.text()).toBe(200);
  const fixturePid = (await fixture.json()).data.pid;
  for (const table of [
    'mt_e2et_order',
    'mt_core_dashboard_suggestion',
    'mt_core_dashboard_adoption',
  ]) {
    const code = `source_${randomUUID().replaceAll('-', '').slice(0, 10)}`;
    const created = await request.post('/api/meta/named-queries', {
      data: {
        code,
        title: 'Physical source boundary',
        fromSql: `SELECT pid FROM public."${table}"${table === 'mt_e2et_order' ? ' WHERE e2et_order_title = #{params.marker} AND tenant_id = #{params.tenantId} AND created_by = CAST(#{params.currentUserId} AS bigint)' : ''}`,
        status: 'published',
        fields: [
          { fieldCode: 'record_key', columnExpr: 'pid', dataType: 'string', operators: ['eq'] },
        ],
      },
    });
    expect(created.status(), await created.text()).toBe(200);
    const listed = await request.post(`/api/meta/named-queries/${code}/execute`, {
      data: { page: 1, size: 5, parameters: { marker, tenantId: -1, currentUserId: '0' } },
    });
    const chart = await request.post('/api/meta/chart-data', {
      data: {
        type: 'namedQuery',
        queryCode: code,
        dimensions: ['record_key'],
        metrics: [],
        limit: 5,
        parameters: { marker, tenantId: -1, currentUserId: '0' },
      },
    });
    const exported = await request.post(`/api/meta/named-queries/${code}/export-data`, {
      data: { format: 'CSV', parameters: { marker, tenantId: -1, currentUserId: '0' } },
    });
    const privateSource = table !== 'mt_e2et_order';
    for (const response of [listed, chart, exported]) {
      expect(response.status(), await response.text()).toBe(privateSource ? 403 : 200);
      if (privateSource)
        expect(await response.text()).toContain(
          'Raw analytics records require the authorized analytics service',
        );
    }
    if (!privateSource) {
      expect((await listed.json()).data.records).toEqual([{ record_key: fixturePid }]);
      expect((await chart.json()).data.rows).toEqual([{ record_key: fixturePid }]);
      expect((await exported.json()).data).toMatchObject({ success: true, recordCount: 1 });
      const download = await request.get((await exported.json()).data.downloadUrl);
      expect(download.status()).toBe(200);
      const csv = (await download.text())
        .replace(/^\uFEFF/, '')
        .trim()
        .split(/\r?\n/);
      expect(csv).toHaveLength(2);
      expect(csv[1]).toContain(fixturePid);
    }
  }
});
