/** Report definition save/read integration; replaces the retired dual-write contract. */
import { readFile } from 'node:fs/promises';
import { expect, test } from '../../tests/fixtures';

test.use({
  storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
  locale: 'zh-CN',
});

test('report menu supports save, reopen, version rollback and canonical JSON download', async ({
  page,
}) => {
  await page.goto('/home');
  const entry = page.locator('a[href="/p/c/report_management"]').first();
  await expect(entry).toBeVisible();
  await entry.click();
  await page.getByRole('button', { name: /新建报表|New report/ }).click();
  await expect(page.getByTestId('report-canvas')).toBeVisible();
  const title = `Analytics report ${Date.now()}`;
  await page.getByPlaceholder('Report Title').fill(title);
  await expect(page.getByPlaceholder('Report Title')).toHaveValue(title);
  await page.getByRole('button', { name: /Rich Text/ }).click();
  await page.getByPlaceholder('Enter text content...').fill('Original analysis conclusion');
  await expect(page.getByTestId('report-canvas')).toContainText('Original analysis conclusion');
  const pageWrites: string[] = [];
  page.on('request', (request) => {
    if (
      /\/api\/pages(?:\/|$)/.test(new URL(request.url()).pathname) &&
      ['POST', 'PUT'].includes(request.method())
    )
      pageWrites.push(request.url());
  });
  const savedResponse = page.waitForResponse(
    (r) =>
      r.request().method() === 'POST' && new URL(r.url()).pathname === '/api/report-definitions',
  );
  await page.getByRole('button', { name: /^(保存|Save)$/ }).click();
  const saved = await savedResponse;
  expect(saved.status()).toBe(200);
  const written = saved.request().postDataJSON();
  expect(written.dsl.title).toBe(title);
  const created = (await saved.json()).data;
  expect(created.pid).toBeTruthy();
  expect(pageWrites).toEqual([]);
  const read = await page.request.get(`/api/report-definitions/${created.pid}`);
  expect(read.status()).toBe(200);
  expect((await read.json()).data.dsl).toEqual(written.dsl);
  await page.goto('/home');
  await page.locator('a[href="/p/c/report_management"]').first().click();
  const row = page.getByRole('row').filter({ hasText: title });
  await expect(row).toBeVisible();
  await expect(page.getByTestId('list-toolbar')).toHaveCount(0);
  await page.screenshot({
    path: `${process.env.AURA_EVIDENCE_DIR}/report-list.png`,
    fullPage: true,
  });
  await row.getByRole('button', { name: /打开|Open/ }).click();
  await expect(page).toHaveURL(new RegExp(`/report-designer/${created.pid}`));
  await expect(page.getByPlaceholder('Report Title')).toHaveValue(title);
  await page.screenshot({
    path: `${process.env.AURA_EVIDENCE_DIR}/report-reopened.png`,
    fullPage: true,
  });
  await page.getByPlaceholder('Report Title').fill(`${title} revised`);
  await page
    .getByTestId('report-canvas')
    .getByText('Original analysis conclusion', { exact: true })
    .click();
  await page.getByPlaceholder('Enter text content...').fill('Revised analysis conclusion');
  const updatedResponse = page.waitForResponse(
    (r) =>
      r.request().method() === 'PUT' &&
      new URL(r.url()).pathname === `/api/report-definitions/${created.pid}`,
  );
  await page.getByRole('button', { name: /^(保存|Save)$/ }).click();
  expect((await updatedResponse).status()).toBe(200);
  await page.getByPlaceholder('Report Title').fill(`${title} unsaved`);
  await page.getByRole('button', { name: 'Version History' }).click();
  await page.getByRole('button', { name: /^v1\b/ }).click();
  const historical = page.getByTestId('report-version-preview');
  await expect(historical).toContainText('Original analysis conclusion');
  await expect(historical).not.toContainText('Revised analysis conclusion');
  await expect(page.getByPlaceholder('Report Title')).toHaveCount(0);
  await page.keyboard.press('ControlOrMeta+s');
  const unchanged = await page.request.get(`/api/report-definitions/${created.pid}`);
  expect((await unchanged.json()).data.dsl.title).toBe(`${title} revised`);
  await historical.screenshot({
    path: `${process.env.AURA_EVIDENCE_DIR}/report-history-preview.png`,
  });
  await page.getByRole('button', { name: '返回当前报表', exact: true }).click();
  await expect(page.getByPlaceholder('Report Title')).toHaveValue(`${title} unsaved`);
  await expect(page.getByTestId('report-canvas')).toContainText('Revised analysis conclusion');
  await page.getByRole('button', { name: /^v1\b/ }).click();
  await page.getByRole('button', { name: /^(回滚|Rollback)$/ }).click();
  const rollbackResponse = page.waitForResponse(
    (r) => r.request().method() === 'POST' && r.url().includes('/rollback'),
  );
  await page.getByRole('button', { name: 'Confirm Rollback', exact: true }).click();
  expect((await rollbackResponse).status()).toBe(200);
  await expect(page.getByPlaceholder('Report Title')).toHaveValue(title);
  const restored = await page.request.get(`/api/report-definitions/${created.pid}`);
  expect((await restored.json()).data.dsl).toEqual(written.dsl);
  await page.getByRole('button', { name: /关闭版本面板|Close version/ }).click();
  const downloadEvent = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Export JSON', exact: true }).click();
  const download = await downloadEvent;
  const exportedPath = `${process.env.AURA_EVIDENCE_DIR}/${created.pid}.report.json`;
  await download.saveAs(exportedPath);
  const exported = JSON.parse(await readFile(exportedPath, 'utf8'));
  expect(exported.reportPid).toBe(created.pid);
  expect(exported.reportDsl).toEqual(written.dsl);
  await page.screenshot({
    path: `${process.env.AURA_EVIDENCE_DIR}/report-saved.png`,
    fullPage: true,
  });
});

test('report menu previews and exports the same filtered model rows', async ({ page }) => {
  test.setTimeout(120_000);
  const { executeCommandViaApi } = await import('./helpers');
  const title = `ReportData${Date.now()}`;
  const orderTitle = `${title} Order`;
  const createdOrder = await executeCommandViaApi(
    page,
    'e2et:create_order',
    {
      e2et_order_title: orderTitle,
      e2et_order_type: 'normal',
      e2et_order_customer: 'Report Export Customer',
      e2et_order_urgent: false,
    },
    undefined,
    'create',
  );
  expect(createdOrder.code).toBe('0');
  expect(createdOrder.recordId).toBeTruthy();
  const dsl = {
    $schema: 'auraboot://schemas/report/v1',
    version: '1.0.0',
    title,
    page: {
      size: 'A4',
      orientation: 'portrait',
      margin: { top: 20, right: 20, bottom: 20, left: 20 },
    },
    dataSources: {
      orders: {
        type: 'model',
        modelCode: 'e2et_order',
        filters: [{ field: 'e2et_order_title', operator: 'EQ', value: orderTitle }],
      },
    },
    body: [
      {
        id: 'orders',
        blockType: 'table',
        title: 'Orders',
        dataSource: 'orders',
        showHeader: true,
        columns: [
          { field: 'e2et_order_title', label: 'Title' },
          { field: 'e2et_order_customer', label: 'Customer' },
        ],
      },
    ],
  };
  const setup = await page.request.post('/api/report-definitions', {
    data: {
      code: title.toLowerCase(),
      title,
      profile: 'paged-media',
      dsl,
    },
  });
  expect(setup.status()).toBe(200);
  const { pid } = (await setup.json()).data;
  await page.goto('/home');
  await page.locator('a[href="/p/c/report_management"]').first().click();
  await page
    .getByRole('row')
    .filter({ hasText: title })
    .getByRole('button', { name: /打开|Open/ })
    .click();
  await expect(page).toHaveURL(new RegExp(`/report-designer/${pid}`));
  await page.getByRole('button', { name: 'Preview', exact: true }).click();
  await expect(page.getByRole('cell', { name: orderTitle, exact: true })).toBeVisible();
  await expect(page.getByRole('cell', { name: 'Report Export Customer', exact: true })).toHaveCount(
    1,
  );
  await page.screenshot({
    path: `${process.env.AURA_EVIDENCE_DIR}/report-model-preview.png`,
    fullPage: true,
  });
  const jsonResponse = page.waitForResponse(
    (r) => new URL(r.url()).pathname === '/api/reports/export/json',
  );
  const jsonDownload = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Export JSON', exact: true }).click();
  expect((await jsonResponse).status()).toBe(200);
  const json = await jsonDownload;
  const jsonPath = `${process.env.AURA_EVIDENCE_DIR}/${pid}.model.json`;
  await json.saveAs(jsonPath);
  const payload = JSON.parse(await readFile(jsonPath, 'utf8'));
  expect(payload.reportDsl).toEqual(dsl);
  expect(payload.dataSets.orders).toHaveLength(1);
  expect(payload.dataSets.orders[0]).toMatchObject({
    e2et_order_title: orderTitle,
    e2et_order_customer: 'Report Export Customer',
  });
  const excelDownload = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Export Excel', exact: true }).click();
  const excel = await excelDownload;
  expect(excel.suggestedFilename()).toBe(`${title}.xlsx`);
  const excelPath = `${process.env.AURA_EVIDENCE_DIR}/${pid}.model.xlsx`;
  await excel.saveAs(excelPath);
  const pdfDownload = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Export PDF', exact: true }).click();
  const pdf = await pdfDownload;
  expect(pdf.suggestedFilename()).toBe(`${title}.pdf`);
  const pdfPath = `${process.env.AURA_EVIDENCE_DIR}/${pid}.model.pdf`;
  await pdf.saveAs(pdfPath);
  expect((await readFile(pdfPath)).subarray(0, 5).toString()).toBe('%PDF-');
  const XLSX = await import('xlsx');
  const workbook = XLSX.read(await readFile(excelPath), { type: 'buffer' });
  expect(XLSX.utils.sheet_to_json(workbook.Sheets.Orders, { header: 1 })).toEqual([
    ['Orders'],
    ['Title', 'Customer'],
    [orderTitle, 'Report Export Customer'],
  ]);
});
