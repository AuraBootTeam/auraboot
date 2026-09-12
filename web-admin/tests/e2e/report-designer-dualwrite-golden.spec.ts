/** Report definition save/read integration; replaces the retired dual-write contract. */
import { readFile, writeFile } from 'node:fs/promises';
import { expect, test } from '../../tests/fixtures';

test.use({
  storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
  locale: 'zh-CN',
});

test('report menu supports save, reopen, version rollback and canonical JSON download', async ({
  page,
}) => {
  const actorResponse = await page.request.get('/api/auth/me');
  expect(actorResponse.status()).toBe(200);
  const actor = (await actorResponse.json()).data.user;
  expect(actor.name).toBeTruthy();
  expect(actor.pid).toBeTruthy();
  await page.goto('/home');
  const entry = page.locator('a[href="/p/c/report_management"]').first();
  await expect(entry).toBeVisible();
  await entry.click();
  await page.getByRole('button', { name: /新建报表|New report/ }).click();
  await expect(page.getByTestId('report-canvas')).toBeVisible();
  const title = `Analytics report ${Date.now()}`;
  await page.getByPlaceholder('报表标题').fill(title);
  await expect(page.getByPlaceholder('报表标题')).toHaveValue(title);
  await page
    .getByTestId('report-designer-toolbar')
    .getByRole('button', { name: '设置', exact: true })
    .click();
  await expect(page.getByRole('heading', { name: '页面设置' })).toBeVisible();
  await expect(page.getByText('页边距（毫米）', { exact: true })).toBeVisible();
  await page.screenshot({
    path: `${process.env.AURA_EVIDENCE_DIR}/report-settings.png`,
    fullPage: true,
  });
  await page.getByRole('button', { name: '取消', exact: true }).click();
  await expect(page.getByRole('heading', { name: '页面设置' })).toHaveCount(0);

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
  await expect(page.getByPlaceholder('报表标题')).toHaveValue(title);
  await page.screenshot({
    path: `${process.env.AURA_EVIDENCE_DIR}/report-reopened.png`,
    fullPage: true,
  });
  await page.getByPlaceholder('报表标题').fill(`${title} revised`);
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
  await page.getByPlaceholder('报表标题').fill(`${title} unsaved`);
  for (const format of ['PDF', 'Excel', 'JSON']) {
    await expect(page.getByRole('button', { name: `导出 ${format}`, exact: true })).toBeDisabled();
  }
  await expect(page.getByRole('status')).toContainText('请先保存当前修改');
  await page.screenshot({
    path: `${process.env.AURA_EVIDENCE_DIR}/report-draft-export-disabled.png`,
    fullPage: true,
  });

  await page.getByRole('button', { name: '版本历史' }).click();
  await page.getByRole('button', { name: /^v1\b/ }).click();
  const historical = page.getByTestId('report-version-preview');
  const versionListResponse = await page.request.get(
    `/api/report-definitions/${created.pid}/versions`,
  );
  expect(versionListResponse.status()).toBe(200);
  const versionEntries = (await versionListResponse.json()).data;
  expect(versionEntries.length).toBeGreaterThan(0);
  for (const version of versionEntries) {
    expect(version.operationBy).toBe(actor.pid);
    expect(version.operationByDisplayName).toBe(actor.name);
  }
  const historyPanel = page.getByTestId('version-history-panel');
  await expect(historyPanel).toContainText(actor.name);
  await expect(historyPanel).not.toContainText(actor.pid);

  await expect(historical).toContainText('Original analysis conclusion');
  await expect(historical).not.toContainText('Revised analysis conclusion');
  await expect(page.getByPlaceholder('报表标题')).toHaveCount(0);
  await page.keyboard.press('ControlOrMeta+s');
  const unchanged = await page.request.get(`/api/report-definitions/${created.pid}`);
  expect((await unchanged.json()).data.dsl.title).toBe(`${title} revised`);
  await historical.screenshot({
    path: `${process.env.AURA_EVIDENCE_DIR}/report-history-preview.png`,
  });
  await page.getByRole('button', { name: '返回当前报表', exact: true }).click();
  await expect(page.getByPlaceholder('报表标题')).toHaveValue(`${title} unsaved`);
  await expect(page.getByTestId('report-canvas')).toContainText('Revised analysis conclusion');
  await page.getByRole('button', { name: /^v1\b/ }).click();
  await page.getByRole('button', { name: /^(回滚|Rollback)$/ }).click();
  const rollbackResponse = page.waitForResponse(
    (r) => r.request().method() === 'POST' && r.url().includes('/rollback'),
  );
  await expect(
    page.getByText('确认回滚到此版本？回滚前会将当前状态保存为备份。', { exact: true }),
  ).toBeVisible();
  await page.screenshot({
    path: `${process.env.AURA_EVIDENCE_DIR}/report-rollback-dialog.png`,
    fullPage: true,
  });
  await page.getByRole('button', { name: '确认回滚', exact: true }).click();
  expect((await rollbackResponse).status()).toBe(200);
  await expect(page.getByPlaceholder('报表标题')).toHaveValue(title);
  const restored = await page.request.get(`/api/report-definitions/${created.pid}`);
  expect((await restored.json()).data.dsl).toEqual(written.dsl);
  await page.getByRole('button', { name: /关闭版本面板|Close version/ }).click();
  const downloadEvent = page.waitForEvent('download');
  await page.getByRole('button', { name: '导出 JSON', exact: true }).click();
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
  await page.getByRole('button', { name: '预览', exact: true }).click();
  await expect(page.getByRole('cell', { name: orderTitle, exact: true })).toBeVisible();
  await expect(page.getByRole('cell', { name: 'Report Export Customer', exact: true })).toHaveCount(
    1,
  );
  await page.screenshot({
    path: `${process.env.AURA_EVIDENCE_DIR}/report-model-preview.png`,
    fullPage: true,
  });
  await page.getByPlaceholder('报表标题').fill(`${title} current`);
  await expect(page.getByRole('button', { name: '导出 JSON', exact: true })).toBeDisabled();
  const savedDefinition = page.waitForResponse(
    (r) =>
      r.request().method() === 'PUT' &&
      new URL(r.url()).pathname === `/api/report-definitions/${pid}`,
  );
  await page.getByRole('button', { name: /^(保存|Save)$/ }).click();
  expect((await savedDefinition).status()).toBe(200);
  await expect(page.getByRole('button', { name: '导出 JSON', exact: true })).toBeEnabled();
  dsl.title = `${title} current`;
  const jsonResponse = page.waitForResponse(
    (r) => new URL(r.url()).pathname === '/api/reports/export/json',
  );
  const jsonDownload = page.waitForEvent('download');
  await page.getByRole('button', { name: '导出 JSON', exact: true }).click();
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
  await page.getByRole('button', { name: '导出 Excel', exact: true }).click();
  const excel = await excelDownload;
  expect(excel.suggestedFilename()).toBe(`${title} current.xlsx`);
  const excelPath = `${process.env.AURA_EVIDENCE_DIR}/${pid}.model.xlsx`;
  await excel.saveAs(excelPath);
  const pdfDownload = page.waitForEvent('download');
  await page.getByRole('button', { name: '导出 PDF', exact: true }).click();
  const pdf = await pdfDownload;
  expect(pdf.suggestedFilename()).toBe(`${title} current.pdf`);
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

test('report export API applies declared model parameters and rejects missing required values', async ({
  page,
}) => {
  const { executeCommandViaApi } = await import('./helpers');
  const title = `ReportParams${Date.now()}`;
  for (const suffix of ['A', 'B']) {
    const created = await executeCommandViaApi(
      page,
      'e2et:create_order',
      {
        e2et_order_title: `${title}${suffix}`,
        e2et_order_type: 'normal',
        e2et_order_customer: `Customer ${suffix}`,
        e2et_order_urgent: false,
      },
      undefined,
      'create',
    );
    expect(created.code).toBe('0');
  }
  const dsl = {
    $schema: 'auraboot://schemas/report/v1',
    version: '1.0.0',
    title,
    page: {
      size: 'A4',
      orientation: 'portrait',
      margin: { top: 20, right: 20, bottom: 20, left: 20 },
    },
    parameters: [
      {
        name: 'order',
        label: 'Order',
        type: 'text',
        required: true,
        defaultValue: `${title}A`,
        bindTo: { dataSource: 'orders', field: 'e2et_order_title', operator: 'EQ' },
      },
    ],
    dataSources: { orders: { type: 'model', modelCode: 'e2et_order' } },
    body: [
      {
        id: 'orders',
        blockType: 'table',
        title: 'Orders',
        dataSource: 'orders',
        showHeader: true,
        columns: [{ field: 'e2et_order_title', label: 'Title' }],
      },
    ],
  };
  const created = await page.request.post('/api/report-definitions', {
    data: { code: title.toLowerCase(), title, profile: 'paged-media', dsl },
  });
  expect(created.status()).toBe(200);
  const { pid } = (await created.json()).data;

  await page.goto('/home');
  await page.locator('a[href="/p/c/report_management"]').first().click();
  await page
    .getByRole('row')
    .filter({ hasText: title })
    .getByRole('button', { name: /打开|Open/ })
    .click();
  await page.getByRole('button', { name: '预览', exact: true }).click();
  await expect(page.getByRole('cell', { name: `${title}A`, exact: true })).toBeVisible();
  await expect(page.getByRole('cell', { name: `${title}B`, exact: true })).toHaveCount(0);
  await page.screenshot({
    path: `${process.env.AURA_EVIDENCE_DIR}/report-parameter-default.png`,
    fullPage: true,
  });

  const downloadCurrent = async (expected: string, label: string) => {
    const event = page.waitForEvent('download');
    await page.getByRole('button', { name: '导出 JSON', exact: true }).click();
    const artifact = await event;
    const path = `${process.env.AURA_EVIDENCE_DIR}/report-parameters-${label}.json`;
    await artifact.saveAs(path);
    const payload = JSON.parse(await readFile(path, 'utf8'));
    expect(payload.dataSets.orders).toHaveLength(1);
    expect(payload.dataSets.orders[0].e2et_order_title).toBe(expected);
  };
  await page.getByRole('textbox', { name: 'Order', exact: true }).fill(`${title}B`);
  await expect(page.getByRole('cell', { name: `${title}A`, exact: true })).toBeVisible();
  await downloadCurrent(`${title}A`, 'draft');
  await page.getByRole('button', { name: '应用', exact: true }).click();
  await expect(page.getByRole('cell', { name: `${title}B`, exact: true })).toBeVisible();
  await expect(page.getByRole('cell', { name: `${title}A`, exact: true })).toHaveCount(0);
  await downloadCurrent(`${title}B`, 'applied');
  await page.getByRole('textbox', { name: 'Order', exact: true }).fill('');
  await page.getByRole('button', { name: '应用', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('查询未成功');
  await expect(page.getByRole('textbox', { name: 'Order', exact: true })).toHaveValue('');
  await expect(page.getByRole('cell', { name: `${title}B`, exact: true })).toBeVisible();
  await downloadCurrent(`${title}B`, 'rejected');
  await page.screenshot({
    path: `${process.env.AURA_EVIDENCE_DIR}/report-parameter-error-recovery.png`,
    fullPage: true,
  });
  await page.getByRole('textbox', { name: 'Order', exact: true }).fill(`${title}A`);
  await page.getByRole('button', { name: '应用', exact: true }).click();
  await expect(page.getByRole('cell', { name: `${title}A`, exact: true })).toBeVisible();
  await expect(page.getByRole('alert')).toHaveCount(0);
  for (const [parameters, expected] of [
    [undefined, `${title}A`],
    [{ order: `${title}B` }, `${title}B`],
  ] as const) {
    const response = await page.request.post('/api/reports/export/json', {
      data: { reportPid: pid, parameters },
    });
    expect(response.status()).toBe(200);
    const payload = await response.json();
    expect(payload.reportDsl).toEqual(dsl);
    expect(payload.dataSets.orders).toHaveLength(1);
    expect(payload.dataSets.orders[0].e2et_order_title).toBe(expected);
  }
  const XLSX = await import('xlsx');
  const excel = await page.request.post('/api/reports/export/excel', {
    data: { reportPid: pid, parameters: { order: `${title}B` } },
  });
  expect(excel.status()).toBe(200);
  const workbook = XLSX.read(await excel.body(), { type: 'buffer' });
  expect(XLSX.utils.sheet_to_json(workbook.Sheets.Orders, { header: 1 })).toEqual([
    ['Orders'],
    ['Title'],
    [`${title}B`],
  ]);
  const pdf = await page.request.post('/api/reports/export/pdf', {
    data: { reportPid: pid, parameters: { order: `${title}B` } },
  });
  expect(pdf.status()).toBe(200);
  await writeFile(`${process.env.AURA_EVIDENCE_DIR}/report-parameter.pdf`, await pdf.body());
  await writeFile(`${process.env.AURA_EVIDENCE_DIR}/report-parameter-expected.txt`, `${title}B`);
  for (const format of ['json', 'excel', 'pdf']) {
    const missing = await page.request.post(`/api/reports/export/${format}`, {
      data: { reportPid: pid, parameters: { order: '' } },
    });
    expect(missing.status()).toBe(422);
    const unknown = await page.request.post(`/api/reports/export/${format}`, {
      data: { reportPid: pid, parameters: { unexpected: 'value' } },
    });
    expect(unknown.status()).toBe(422);
  }
  const stored = await page.request.get(`/api/report-definitions/${pid}`);
  expect((await stored.json()).data.dsl).toEqual(dsl);
});

test('report exports all 201 matching records and rejects an undersized export limit', async ({
  page,
}) => {
  test.setTimeout(240_000);
  const { executeCommandViaApi } = await import('./helpers');
  const title = `ReportRows${Date.now()}`;
  const titles = Array.from({ length: 201 }, (_, i) => `${title}-${String(i).padStart(3, '0')}`);
  for (let offset = 0; offset < titles.length; offset += 4) {
    await Promise.all(
      titles.slice(offset, offset + 4).map(async (orderTitle) => {
        const created = await executeCommandViaApi(
          page,
          'e2et:create_order',
          {
            e2et_order_title: orderTitle,
            e2et_order_type: 'normal',
            e2et_order_customer: title,
            e2et_order_urgent: Number(orderTitle.slice(-3)) % 2 === 0,
            e2et_order_remark: 'Report sorting verification',
          },
          undefined,
          'create',
        );
        expect(created.code).toBe('0');
        expect(created.recordId).toBeTruthy();
      }),
    );
  }
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
        sortBy: [
          { field: 'e2et_order_urgent', order: 'desc' },
          { field: 'e2et_order_title', order: 'asc' },
        ],
        filters: [{ field: 'e2et_order_customer', operator: 'EQ', value: title }],
      },
    },
    body: [
      {
        id: 'orders',
        blockType: 'table',
        title: 'Orders',
        dataSource: 'orders',
        showHeader: true,
        columns: [{ field: 'e2et_order_title', label: 'Title' }],
      },
    ],
  };
  const created = await page.request.post('/api/report-definitions', {
    data: { code: title.toLowerCase(), title, profile: 'paged-media', dsl },
  });
  expect(created.status()).toBe(200);
  const { pid } = (await created.json()).data;
  await page.goto('/home');
  await page.locator('a[href="/p/c/report_management"]').first().click();
  await page
    .getByRole('row')
    .filter({ hasText: title })
    .getByRole('button', { name: /打开|Open/ })
    .click();
  await page.getByRole('button', { name: '预览', exact: true }).click();
  await expect(page.getByRole('cell', { name: new RegExp(`^${title}-`) })).toHaveCount(201);
  const expectedOrder = [
    ...titles.filter((_, i) => i % 2 === 0),
    ...titles.filter((_, i) => i % 2 === 1),
  ];
  expect(
    await page.getByRole('cell', { name: new RegExp(`^${title}-`) }).allTextContents(),
  ).toEqual(expectedOrder);
  const downloads: Record<string, string> = {};
  for (const format of ['JSON', 'Excel', 'PDF']) {
    const event = page.waitForEvent('download');
    await page.getByRole('button', { name: `导出 ${format}`, exact: true }).click();
    const artifact = await event;
    downloads[format] =
      `${process.env.AURA_EVIDENCE_DIR}/report-201.${format === 'Excel' ? 'xlsx' : format.toLowerCase()}`;
    await artifact.saveAs(downloads[format]);
  }
  const json = JSON.parse(await readFile(downloads.JSON, 'utf8'));
  expect(
    json.dataSets.orders.map((row: { e2et_order_title: string }) => row.e2et_order_title),
  ).toEqual(expectedOrder);
  const XLSX = await import('xlsx');
  const workbook = XLSX.read(await readFile(downloads.Excel), { type: 'buffer' });
  const rows = XLSX.utils.sheet_to_json<string[]>(workbook.Sheets.Orders, { header: 1 });
  expect(rows.slice(2).map((row) => row[0])).toEqual(expectedOrder);
  await writeFile(
    `${process.env.AURA_EVIDENCE_DIR}/report-201-expected.json`,
    JSON.stringify(expectedOrder),
  );
  const limited = await page.request.put(`/api/report-definitions/${pid}`, {
    data: {
      title,
      profile: 'paged-media',
      dsl: {
        ...dsl,
        dataSources: { orders: { ...dsl.dataSources.orders, maxItems: 200 } },
      },
    },
  });
  expect(limited.status()).toBe(200);
  for (const format of ['json', 'excel', 'pdf']) {
    const response = await page.request.post(`/api/reports/export/${format}`, {
      data: { reportPid: pid },
    });
    expect(response.status()).toBe(422);
    expect(await response.text()).toContain('exceeds the export limit of 200');
  }
});
