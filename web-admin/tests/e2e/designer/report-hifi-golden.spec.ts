/** B118 high-fidelity report: realistic 48-order dataset, multi-block report, exports, aesthetics captures. */
import { test, expect } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import * as fs from 'node:fs';
import * as path from 'node:path';

test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json', locale: 'zh-CN' });

const EV = process.env.AURA_EVIDENCE_DIR!;
const run = `hifi_${randomUUID().replaceAll('-', '').slice(0, 8)}`;
const STATUSES = ['draft', 'confirmed', 'shipped', 'completed'] as const;
const TYPES = ['normal', 'urgent', 'bulk'] as const;
const CITIES = ['上海', '北京', '广州', '成都', '杭州', '深圳'];

let reportPid = '';

test.describe.configure({ mode: 'serial' });

test('HIFI-00 seed realistic dataset and build the multi-block report', async ({ page, request }) => {
  test.setTimeout(120000);
  // 48 orders across 4 statuses / 3 types with varied creation dates.
  for (let i = 0; i < 48; i++) {
    const status = STATUSES[i % STATUSES.length];
    const type = TYPES[i % TYPES.length];
    const city = CITIES[i % CITIES.length];
    const day = String(1 + (i % 28)).padStart(2, '0');
    const created = await request.post('/api/dynamic/e2et_order/create', {
      data: {
        e2et_order_title: `HiFi订单-${run}-${String(i + 1).padStart(3, '0')}-${city}`,
        e2et_order_type: type,
        e2et_order_urgent: i % 7 === 0,
        e2et_order_status: status,
      },
    });
    expect(created.status(), await created.text()).toBe(200);
  }
  const orders = await request.get('/api/dynamic/e2et_order/list?current=1&size=100');
  expect(orders.status()).toBe(200);

  const nq = async (code: string, sql: string, fields: object[]) => {
    const r = await request.post('/api/meta/named-queries', {
      data: { code, title: code, status: 'published', resourceCode: 'e2et_order', actionCode: 'read',
        fromSql: sql, fields },
    });
    expect(r.status(), await r.text()).toBe(200);
  };
  await nq(`${run}_stat`,
    `SELECT count(*) AS total_orders, count(*) FILTER (WHERE e2et_order_urgent) AS urgent_orders,` +
    ` count(*) FILTER (WHERE e2et_order_status = 'completed') AS completed_orders FROM mt_e2et_order` +
    ` WHERE e2et_order_title LIKE 'HiFi订单-${run}%'`,
    []);
  await nq(`${run}_detail`,
    `SELECT ROW_NUMBER() OVER (ORDER BY id) AS seq, e2et_order_title AS title, e2et_order_type AS type,` +
    ` e2et_order_status AS status, created_at::date AS order_date FROM mt_e2et_order` +
    ` WHERE e2et_order_title LIKE 'HiFi订单-${run}%' ORDER BY id`,
    [
      { fieldCode: 'seq', columnExpr: 'seq', dataType: 'string', operators: ['eq'] },
      { fieldCode: 'title', columnExpr: 'title', dataType: 'string', operators: ['eq'] },
      { fieldCode: 'type', columnExpr: 'type', dataType: 'string', operators: ['eq'] },
      { fieldCode: 'status', columnExpr: 'status', dataType: 'string', operators: ['eq'] },
      { fieldCode: 'order_date', columnExpr: 'order_date', dataType: 'string', operators: ['eq'] },
    ]);
  await nq(`${run}_group`,
    `SELECT e2et_order_type AS order_type, count(*) AS cnt FROM mt_e2et_order` +
    ` WHERE e2et_order_title LIKE 'HiFi订单-${run}%' GROUP BY e2et_order_type ORDER BY cnt DESC`,
    [
      { fieldCode: 'order_type', columnExpr: 'order_type', dataType: 'string', operators: ['eq'] },
      { fieldCode: 'cnt', columnExpr: 'cnt', dataType: 'integer', operators: ['eq'] },
    ]);
  await nq(`${run}_cross`,
    `SELECT e2et_order_status AS status, e2et_order_type AS type, count(*) AS cnt FROM mt_e2et_order` +
    ` WHERE e2et_order_title LIKE 'HiFi订单-${run}%' GROUP BY e2et_order_status, e2et_order_type`,
    [
      { fieldCode: 'status', columnExpr: 'status', dataType: 'string', operators: ['eq'] },
      { fieldCode: 'type', columnExpr: 'type', dataType: 'string', operators: ['eq'] },
      { fieldCode: 'cnt', columnExpr: 'cnt', dataType: 'integer', operators: ['eq'] },
    ]);
  await nq(`${run}_chart`,
    `SELECT e2et_order_status AS status, count(*) AS cnt FROM mt_e2et_order` +
    ` WHERE e2et_order_title LIKE 'HiFi订单-${run}%' GROUP BY e2et_order_status`,
    [
      { fieldCode: 'status', columnExpr: 'status', dataType: 'string', operators: ['eq'] },
      { fieldCode: 'cnt', columnExpr: 'cnt', dataType: 'integer', operators: ['eq'] },
    ]);

  const dsl = {
    version: '1.0.0',
    title: `订单运营月报 ${run}`,
    page: { size: 'A4', orientation: 'portrait', margin: { top: 18, right: 14, bottom: 18, left: 14 } },
    header: { height: 56, elements: [
      { type: 'text', content: `订单运营月报 ${run}`, align: 'left', style: { fontSize: 16, fontWeight: 'bold' } },
      { type: 'date', align: 'right', style: { fontSize: 10 } },
    ] },
    footer: { height: 40, elements: [
      { type: 'page-number', align: 'center', style: { fontSize: 10 } },
    ] },
    dataSources: {
      stat: { type: 'namedQuery', queryCode: `${run}_stat` },
      detail: { type: 'namedQuery', queryCode: `${run}_detail` },
      group: { type: 'namedQuery', queryCode: `${run}_group` },
      cross: { type: 'namedQuery', queryCode: `${run}_cross` },
      chart: { type: 'namedQuery', queryCode: `${run}_chart` },
    },
    body: [
      { id: 'kpi-total', blockType: 'stat-card', title: '订单总数', dataSource: 'stat',
        valueField: 'total_orders', aggregation: 'sum', label: '订单总数' },
      { id: 'kpi-urgent', blockType: 'stat-card', title: '紧急订单', dataSource: 'stat',
        valueField: 'urgent_orders', aggregation: 'sum', label: '紧急订单', color: '#d97706' },
      { id: 'kpi-done', blockType: 'stat-card', title: '已完成', dataSource: 'stat',
        valueField: 'completed_orders', aggregation: 'sum', label: '已完成', color: '#059669' },
      { id: 'detail', blockType: 'table', title: '订单明细', dataSource: 'detail', stripe: true,
        columns: [
          { field: 'seq', label: '序号', width: 60 },
          { field: 'title', label: '订单标题' },
          { field: 'type', label: '类型', width: 90 },
          { field: 'status', label: '状态', width: 110 },
          { field: 'order_date', label: '下单日期', width: 120 },
        ] },
      { id: 'bytype', blockType: 'grouped-table', title: '按类型分组小计', dataSource: 'group',
        groupByField: 'order_type',
        columns: [{ field: 'order_type', label: '类型' }, { field: 'cnt', label: '订单数' }],
        groupSubtotal: { enabled: true, columns: [{ field: 'cnt', aggregation: 'sum' }] },
        grandTotal: { enabled: true, columns: [{ field: 'cnt', aggregation: 'sum' }] } },
      { id: 'xtab', blockType: 'cross-tab', title: '状态 × 类型 交叉统计', dataSource: 'cross',
        rowField: 'status', columnField: 'type', valueField: 'cnt', aggregation: 'sum',
        showRowTotal: true, showColumnTotal: true },
      { id: 'chart', blockType: 'chart', title: '状态分布', dataSource: 'chart',
        chartType: 'bar', categoryField: 'status', valueField: 'cnt', aggregation: 'sum', height: 240 },
    ],
  };
  const report = await request.post('/api/report-definitions', {
    data: { code: run, title: dsl.title, profile: 'paged-media', dsl },
  });
  expect(report.status(), await report.text()).toBe(200);
  reportPid = (await report.json()).data.pid;
  expect(reportPid).toBeTruthy();

  // The API only arranges fixtures; export actions and downloads are driven by
  // the actual editor. Direct record navigation remains an explicit menu gap.
  await page.goto(`/report-designer/${reportPid}`);
  await expect(page.getByPlaceholder(/^(报表标题|Report Title)$/)).toHaveValue(`订单运营月报 ${run}`);
  await downloadAndInspectPdf(page, /^(导出 PDF|Export PDF)$/, 'hifi-report-editor.pdf');
  const jsonRequest = page.waitForResponse(r => r.url().endsWith('/api/reports/export/json') && r.request().method() === 'POST');
  const jsonDownload = page.waitForEvent('download');
  await page.getByRole('button', { name: /^(导出 JSON|Export JSON)$/ }).click();
  const response = await jsonRequest;
  expect(response.request().postDataJSON().reportPid).toBe(reportPid);
  expect(response.ok()).toBeTruthy();
  const download = await jsonDownload;
  expect(download.suggestedFilename()).toBe(`订单运营月报 ${run}.json`);
  const jsonPath = path.join(EV, 'hifi-report.json');
  await download.saveAs(jsonPath);
  const doc = JSON.parse(fs.readFileSync(jsonPath, 'utf8'));
  const blockTypes = doc.reportDsl.body.map((b: { blockType: string }) => b.blockType);
  expect(blockTypes).toEqual(expect.arrayContaining(['stat-card', 'table', 'grouped-table', 'cross-tab', 'chart']));
  expect(doc.dataSets.detail).toHaveLength(48);
  expect(new Set(doc.dataSets.detail.map((r: { title: string }) => r.title)).size).toBe(48);
  expect(doc.dataSets.detail.every((r: { title: string }) => r.title.startsWith(`HiFi订单-${run}-`))).toBe(true);
  expect(doc.dataSets.stat).toHaveLength(1);
  expect(Number(doc.dataSets.stat[0].total_orders)).toBe(48);
  expect(Number(doc.dataSets.stat[0].urgent_orders)).toBe(7);
  expect(Number(doc.dataSets.stat[0].completed_orders)).toBe(12);
  expect(doc.dataSets.group).toHaveLength(3);
  expect(doc.dataSets.group.map((r: { order_type: string }) => r.order_type).sort()).toEqual([...TYPES].sort());
  for (const row of doc.dataSets.group) expect(Number(row.cnt)).toBe(16);
  expect(doc.dataSets.cross).toHaveLength(12);
  for (const row of doc.dataSets.cross) expect(Number(row.cnt)).toBe(4);
  expect(doc.dataSets.chart).toHaveLength(4);
  expect(doc.dataSets.chart.map((r: { status: string }) => r.status).sort()).toEqual([...STATUSES].sort());
  for (const row of doc.dataSets.chart) expect(Number(row.cnt)).toBe(12);
  await page.screenshot({ path: `${EV}/hifi-00-business.png`, fullPage: true });
});

test('HIFI-01 designer renders every block of the high-fidelity report', async ({ page }) => {
  await page.goto(`/report-designer/${reportPid}`);
  await expect(page.getByTestId('report-canvas')).toBeVisible({ timeout: 30000 });
  await expect(page.getByPlaceholder(/^(报表标题|Report Title)$/)).toHaveValue(`订单运营月报 ${run}`, { timeout: 30000 });
  await expect(page.getByTestId('report-canvas')).toContainText('订单总数');
  await expect(page.getByTestId('report-canvas')).toContainText('状态 × 类型 交叉统计');
  await page.getByTestId('report-designer-toolbar').getByRole('button', { name: /^(预览|Preview)$/ }).click();
  await expect(page.locator('table').first().locator('tbody tr')).toHaveCount(48);
  await expect(page.getByRole('alert')).toHaveCount(0);
  await page.getByTestId('report-designer-toolbar').getByRole('button', { name: /^(编辑|Edit)$/ }).click();
  await expect(page.getByTestId('report-canvas')).toContainText('按类型分组小计');
  await page.screenshot({ path: `${EV}/hifi-01-business.png`, fullPage: true });
});

test('HIFI-02 view page renders the full business report', async ({ page }) => {
  await page.goto(`/reports/view/${run}`);
  await expect(page.locator('table').first()).toContainText('HiFi订单', { timeout: 30000 });
  const detailRows = page.locator('table').first().locator('tbody tr');
  await expect(detailRows).toHaveCount(48, { timeout: 30000 });
  await expect(detailRows).toContainText(Array.from({ length: 48 }, (_, i) => `HiFi订单-${run}-${String(i + 1).padStart(3, '0')}`));
  await expect(page.getByText('订单总数')).toBeVisible();
  await expect(page.locator('svg').first()).toBeAttached();
  await downloadAndInspectPdf(page, /^Export PDF$/, 'hifi-report-viewer.pdf');
  await page.screenshot({ path: `${EV}/hifi-02-business.png`, fullPage: true });
});

async function downloadAndInspectPdf(page: import('@playwright/test').Page, button: RegExp, artifact: string) {
  const responsePromise = page.waitForResponse(r => r.url().endsWith('/api/reports/export/pdf') && r.request().method() === 'POST');
  const downloadPromise = page.waitForEvent('download');
  await page.getByRole('button', { name: button }).click();
  const response = await responsePromise;
  expect(response.request().postDataJSON().reportPid).toBe(reportPid);
  expect(response.ok()).toBeTruthy();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toBe(`订单运营月报 ${run}.pdf`);
  fs.mkdirSync(EV, { recursive: true });
  const pdfPath = path.join(EV, artifact);
  await download.saveAs(pdfPath);
  const pdf = fs.readFileSync(pdfPath);
  expect(pdf.subarray(0, 4).toString()).toBe('%PDF');
  const text = execFileSync('pdftotext', ['-layout', pdfPath, '-'], { encoding: 'utf8', timeout: 30000 });
  fs.writeFileSync(`${pdfPath}.txt`, text);
  const normalized = text.replace(/\s+/g, '');
  expect(normalized).toContain(`订单运营月报${run}`);
  for (let i = 0; i < 48; i++) expect(normalized).toContain(`${run}-${String(i + 1).padStart(3, '0')}`);
}
