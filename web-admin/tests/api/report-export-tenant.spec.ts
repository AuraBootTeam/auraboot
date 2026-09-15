/** Real report export isolation: each business tenant exports its own report and cannot export another tenant's. */
import { test, expect, request as requestFactory, type APIRequestContext } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { resolve } from 'node:path';

test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

const EXPORT_FORMATS = ['json', 'excel', 'pdf'] as const;

test('report exports stay tenant-scoped across real business tenants', async ({ request }) => {
  test.setTimeout(120000);
  const marker = `report_tenant_${randomUUID().replaceAll('-', '').slice(0, 10)}`;
  const createRow = async (ctx: APIRequestContext, amount: number) => {
    const row = await ctx.post('/api/dynamic/e2et_order/create', {
      data: {
        e2et_order_title: marker,
        e2et_order_type: 'normal',
        e2et_order_urgent: false,
        e2et_order_status: 'draft',
      },
    });
    expect(row.status(), await row.text()).toBe(200);
    const query = await ctx.post('/api/meta/named-queries', {
      data: {
        code: marker,
        title: marker,
        resourceCode: 'e2et_order',
        actionCode: 'read',
        fromSql:
          `SELECT e2et_order_title AS title, ${amount} AS amount ` +
          `FROM mt_e2et_order WHERE e2et_order_title = '${marker}'`,
        fields: [
          { fieldCode: 'title', columnExpr: 'title', dataType: 'string' },
          { fieldCode: 'amount', columnExpr: 'amount', dataType: 'integer' },
        ],
      },
    });
    expect(query.status(), await query.text()).toBe(200);
    const report = await ctx.post('/api/report-definitions', {
      data: {
        code: marker,
        title: marker,
        profile: 'paged-media',
        dsl: {
          version: '1.0.0',
          title: marker,
          dataSources: { source: { type: 'namedQuery', queryCode: marker } },
          body: [
            {
              id: 'rows',
              blockType: 'table',
              dataSource: 'source',
              columns: [
                { field: 'title', label: 'Title' },
                { field: 'amount', label: 'Amount' },
              ],
            },
          ],
        },
      },
    });
    expect(report.status(), await report.text()).toBe(200);
    return (await report.json()).data.pid;
  };
  const exportReport = async (ctx: APIRequestContext, pid: string, format: string) =>
    ctx.post(`/api/reports/export/${format}`, { data: { reportPid: pid } });

  const reportPid = await createRow(request, 7);
  const ownJson = await exportReport(request, reportPid, 'json');
  expect(ownJson.status(), await ownJson.text()).toBe(200);
  const document = await ownJson.json();
  expect(document.format).toBe('auraboot.report.export.v1');
  expect(document.reportPid).toBe(reportPid);
  expect(document.dataSets.source).toEqual([{ title: marker, amount: 7 }]);
  const ownExcel = await exportReport(request, reportPid, 'excel');
  expect(ownExcel.status(), await ownExcel.text()).toBe(200);
  expect((await ownExcel.body()).subarray(0, 2).toString()).toBe('PK');
  expect(ownExcel.headers()['content-disposition']).toContain('attachment');
  const ownPdf = await exportReport(request, reportPid, 'pdf');
  expect(ownPdf.status(), await ownPdf.text()).toBe(200);
  expect((await ownPdf.body()).subarray(0, 4).toString()).toBe('%PDF');
  expect(ownPdf.headers()['content-disposition']).toContain('attachment');

  if (process.env.AURA_REPORT_CROSS_TENANT !== '1') return;

  const before = await request.get('/api/auth/me');
  expect(before.status()).toBe(200);
  const original = (await before.json()).data.user;
  const createdTenant = await request.post('/api/tenant-selection/process', {
    timeout: 30000,
    data: { action: 'create', tenantName: marker, displayName: 'Report isolation' },
  });
  expect(createdTenant.status(), await createdTenant.text()).toBe(200);
  const tenant = (await createdTenant.json()).data;
  expect(tenant.jwt).toBeTruthy();
  const foreign = await requestFactory.newContext({
    baseURL: process.env.BACKEND_URL,
    extraHTTPHeaders: { Authorization: `Bearer ${tenant.jwt}` },
  });
  try {
    const imported = await foreign.post('/api/plugins/import/import-directory-sync', {
      data: {
        path: resolve(process.cwd(), '../plugins/test-fixtures'),
        conflictStrategy: 'OVERWRITE',
        validateReferences: true,
        autoPublishPages: true,
      },
    });
    expect(imported.status(), await imported.text()).toBe(200);
    expect((await imported.json()).success).toBe(true);
    const me = await foreign.get('/api/auth/me');
    expect(me.status()).toBe(200);
    const identity = (await me.json()).data;
    expect(identity.user.id).toBe(original.id);
    expect(String(identity.user.tenantId)).not.toBe(String(original.tenantId));
    expect(String(identity.user.tenantId)).toBe(String(tenant.tenantId));
    for (const required of [
      'report.definition.view',
      'report.definition.manage',
      'report.export.execute',
      'model.e2et_order.read',
    ])
      expect(identity.permissions.permissionCodes, required).toContain(required);

    const foreignPid = await createRow(foreign, 9);
    const foreignJson = await exportReport(foreign, foreignPid, 'json');
    expect(foreignJson.status(), await foreignJson.text()).toBe(200);
    const foreignDocument = await foreignJson.json();
    expect(foreignDocument.reportPid).toBe(foreignPid);
    expect(foreignDocument.reportPid).not.toBe(reportPid);
    expect(foreignDocument.dataSets.source).toEqual([{ title: marker, amount: 9 }]);
    for (const format of EXPORT_FORMATS) {
      const own = await exportReport(foreign, foreignPid, format);
      expect(own.status(), `${format}: ${await own.text()}`).toBe(200);
    }

    for (const format of EXPORT_FORMATS) {
      const variants: Array<Record<string, string>> = [
        {},
        { tenantId: String(original.tenantId) },
      ];
      for (const params of variants) {
        const denied = await foreign.post(`/api/reports/export/${format}`, {
          params,
          data: { reportPid },
        });
        expect(denied.status(), `${format}: ${await denied.text()}`).toBe(422);
        const body = await denied.text();
        expect(body).toContain('Report not found');
        for (const leak of [marker, 'reportDsl', 'dataSets', 'fromSql', 'fileKey'])
          expect(body).not.toContain(leak);
        expect(denied.headers()['content-disposition']).toBeUndefined();
      }
    }
    const deniedRead = await foreign.get(`/api/report-definitions/${reportPid}`);
    expect(deniedRead.status(), await deniedRead.text()).toBe(404);
    const reverseDenied = await request.post('/api/reports/export/json', {
      data: { reportPid: foreignPid },
    });
    expect(reverseDenied.status(), await reverseDenied.text()).toBe(422);
    expect(await reverseDenied.text()).toContain('Report not found');

    const afterJson = await exportReport(request, reportPid, 'json');
    expect(afterJson.status(), await afterJson.text()).toBe(200);
    const afterDocument = await afterJson.json();
    expect(afterDocument.dataSets.source).toEqual([{ title: marker, amount: 7 }]);
  } finally {
    await foreign.dispose();
  }
});
