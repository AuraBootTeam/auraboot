/** Deterministic tool execution over a real model. This is not a real-LLM reasoning test. */
import { test, expect } from '../../fixtures';
import { readFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { Client } from 'pg';
import { PG_CONN } from '../../helpers/environments';

test.use({
  storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
  locale: 'zh-CN',
  viewport: { width: 1440, height: 1000 },
});

test('AuraBot analysis saves an executable report and reopens its data', async ({ page }) => {
  test.setTimeout(120000);
  page.on('pageerror', (error) => console.log('[analytics-page-error]', error.message));
  page.on('console', (message) => {
    if (message.type() === 'error') console.log('[analytics-console-error]', message.text());
  });
  const funnelFrom = new Date().toISOString();
  const title = `订单分析验证 ${Date.now()}`;
  const fixture = await page.request.post('/api/dynamic/e2et_order/create', {
    data: {
      e2et_order_title: title,
      e2et_order_type: 'normal',
      e2et_order_urgent: false,
      e2et_order_status: 'draft',
    },
  });
  expect(fixture.status()).toBe(200);
  expect(String((await fixture.json()).code)).toMatch(/^(0|200)$/);
  await page.goto('/home', { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => {
    const toggle = document.querySelector('[data-testid="ai-panel-toggle"]');
    return toggle && Object.keys(toggle).some((key) => key.startsWith('__reactProps$'));
  });
  const panel = page.getByTestId('aurabot-panel');
  if (!(await panel.isVisible())) await page.getByTestId('ai-panel-toggle').click();
  await expect(panel).toBeVisible();
  await panel.getByTestId('aurabot-history-trigger').click();
  await panel.getByTestId('aurabot-new-session').click();
  await expect(panel.getByTestId('aurabot-history-dropdown')).toHaveCount(0);
  const matrix = JSON.parse(
    await readFile(`${process.env.AURA_EVIDENCE_DIR}/acceptance-matrix.json`, 'utf8'),
  );
  const shot = async (id: string) => {
    const scenario = matrix.scenarios.find((row: { id: string }) => row.id === id);
    expect(scenario).toBeDefined();
    await page.screenshot({
      path: `${process.env.AURA_EVIDENCE_DIR}/${scenario.screenshot}`,
      fullPage: true,
    });
  };
  const query = {
    modelCode: 'e2et_order',
    dimensions: ['e2et_order_title'],
    metrics: [{ field: 'pid', aggregation: 'count', alias: 'cnt' }],
    filters: [{ field: 'e2et_order_title', operator: 'eq', value: title }],
    orderBy: [{ field: 'cnt', direction: 'desc' }],
    limit: 5,
  };
  const input = panel.locator('textarea').first();
  await expect(input).toBeEnabled();
  await input.fill(
    '@@AURABOOT_STUB_TOOL_USE@@ ' +
      JSON.stringify({
        name: 'aurabot_chat-bi',
        input: { ...query, chartType: 'table', interpretation: '订单数量分析' },
      }),
  );
  await input.press('Enter');
  const card = panel.getByTestId('chatbi-result-card');
  await expect(card).toBeVisible({ timeout: 45000 });
  await expect(card).toHaveAttribute('data-row-count', '1');
  await expect(card).toContainText(title);
  await expect(card.locator('thead')).toContainText('数量');
  await expect(card.locator('thead')).toContainText('订单标题');
  await expect(card.getByText('还没有已记录的建议。', { exact: false })).toBeVisible();
  await card.scrollIntoViewIfNeeded();
  await shot('analysis');
  const createdPromise = page.waitForResponse(
    (r) =>
      r.request().method() === 'POST' && new URL(r.url()).pathname === '/api/report-definitions',
  );
  await card.getByTestId('chatbi-save-report').click();
  const created = await createdPromise;
  expect(created.status()).toBe(200);
  const analysisId = await card.getAttribute('data-analysis-id');
  expect(created.request().postDataJSON().sourceAnalysisId).toBe(analysisId);
  const persistedQuery = created.request().postDataJSON().dsl.dataSources.analysis.aggregateQuery;
  expect(persistedQuery).toMatchObject({ type: 'aggregate', ...query });
  const columns = created.request().postDataJSON().dsl.body[0].columns;
  expect(columns).toEqual(
    expect.arrayContaining([
      { field: 'cnt', label: '数量' },
      { field: 'e2et_order_title', label: '订单标题' },
    ]),
  );
  const saved = (await created.json()).data;
  const db = new Client(PG_CONN);
  await db.connect();
  try {
    await expect
      .poll(async () => {
        const outbox = await db.query(
          "SELECT target_key, interaction_id, payload, status FROM ab_behavior_outcome_outbox WHERE event_name = 'analytics_report_saved' AND target_key = $1",
          [saved.pid],
        );
        return outbox.rows;
      })
      .toEqual([
        {
          target_key: saved.pid,
          interaction_id: analysisId,
          payload: { queryHash: expect.stringMatching(/^[0-9a-f]{64}$/) },
          status: 'published',
        },
      ]);
  } finally {
    await db.end();
  }
  const forged = structuredClone(created.request().postDataJSON());
  forged.code += '_forged';
  forged.dsl.dataSources.analysis.aggregateQuery.limit = 4;
  const rejected = await page.request.post('/api/report-definitions', { data: forged });
  expect(rejected.status()).toBe(400);
  const absent = await page.request.get(`/api/report-definitions/by-code/${forged.code}`);
  expect(absent.status()).toBe(404);

  await expect(card.getByTestId('chatbi-saved-report')).toHaveAttribute(
    'href',
    `/report-designer/${saved.pid}`,
  );
  await card.scrollIntoViewIfNeeded();
  await shot('saved');
  await card.getByTestId('chatbi-saved-report').click();
  await expect(page).toHaveURL(new RegExp(`/report-designer/${saved.pid}$`));
  await page.waitForFunction(() => {
    const toolbar = document.querySelector('[data-testid="report-designer-toolbar"]');
    const button =
      toolbar &&
      Array.from(toolbar.querySelectorAll('button')).find(
        (item) => item.textContent?.trim() === '预览',
      );
    return button && Object.keys(button).some((key) => key.startsWith('__reactProps$'));
  });
  const queryPromise = page.waitForResponse(
    (r) =>
      new URL(r.url()).pathname === '/api/reports/query/aggregate' &&
      r.request().method() === 'POST',
  );
  await page
    .getByTestId('report-designer-toolbar')
    .getByRole('button', { name: '预览', exact: true })
    .click();
  const queried = await queryPromise;
  expect(queried.status()).toBe(200);
  expect(queried.request().postDataJSON()).toEqual(persistedQuery);
  expect((await queried.json()).data.rows).toEqual([{ cnt: 1, e2et_order_title: title }]);
  await expect(page.locator('main').getByText(title, { exact: true }).first()).toBeVisible();
  await shot('preview');
  const reloadDefinition = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === `/api/report-definitions/${saved.pid}` &&
      response.request().method() === 'GET',
  );
  await page.reload();
  expect((await reloadDefinition).status()).toBe(200);
  await page.waitForFunction(() => {
    const toolbar = document.querySelector('[data-testid="report-designer-toolbar"]');
    const button =
      toolbar &&
      Array.from(toolbar.querySelectorAll('button')).find(
        (item) => item.textContent?.trim() === '预览',
      );
    return button && Object.keys(button).some((key) => key.startsWith('__reactProps$'));
  });
  const previewButton = page
    .getByTestId('report-designer-toolbar')
    .getByRole('button', { name: '预览', exact: true });
  await expect(previewButton).toBeVisible();
  await previewButton.click();
  await expect(
    page.getByTestId('report-designer-toolbar').getByRole('button', { name: '编辑', exact: true }),
  ).toBeVisible();
  await expect(page.locator('main').getByText(title, { exact: true }).first()).toBeVisible();
  await expect(page.getByTestId('report-aggregate-limit-hint')).toContainText(
    '预览与导出使用相同查询',
  );
  await expect(page.getByText('模型和命名查询预览最多 500 行', { exact: false })).toHaveCount(0);
  const exportResponse = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/reports/export/json' &&
      response.request().method() === 'POST',
  );
  const downloadPromise = page.waitForEvent('download');
  await page.getByRole('button', { name: /导出 JSON|Export JSON/ }).click();
  const download = await downloadPromise;
  await download.saveAs(`${process.env.AURA_EVIDENCE_DIR}/report-data.json`);
  const exportRequest = (await exportResponse).request().postDataJSON();
  expect(exportRequest.usageId).toMatch(/^[0-9a-f-]{36}$/);
  const path = await download.path();
  expect(path).toBeTruthy();
  const artifact = JSON.parse(await readFile(path!, 'utf8'));
  expect(artifact.dataSets.analysis).toEqual([{ cnt: 1, e2et_order_title: title }]);
  expect(artifact.reportDsl.dataSources.analysis.aggregateQuery).toEqual(persistedQuery);
  expect(artifact.reportDsl.body[0].columns).toEqual(columns);
  await shot('reopened');
  await expect
    .poll(async () => {
      const response = await page.request.get('/api/analytics/behavior/analysis-funnel', {
        params: { from: funnelFrom, to: new Date().toISOString() },
      });
      expect(response.status()).toBe(200);
      const funnel = (await response.json()).data;
      expect(funnel.definitionVersion).toBe('analysis-task-funnel-v2');
      return funnel.records.map((stage: { tasks: number }) => stage.tasks);
    })
    .toEqual([1, 1, 1, 1, 1]);

  const usageDb = new Client(PG_CONN);
  await usageDb.connect();
  try {
    await expect
      .poll(async () => {
        const result = await usageDb.query(
          "SELECT props FROM ab_behavior_event WHERE event_name = 'analytics_report_used' AND interaction_id = $1 AND props->>'targetKey' = $2",
          [analysisId, saved.pid],
        );
        return result.rows;
      })
      .toEqual([
        {
          props: {
            targetType: 'report',
            targetKey: saved.pid,
            queryHash: expect.stringMatching(/^[0-9a-f]{64}$/),
            originalQuery: true,
            usageKind: 'export_generated',
            format: 'json',
          },
        },
      ]);
  } finally {
    await usageDb.end();
  }
  const retention = await page.request.get('/api/analytics/behavior/retention', {
    params: { unit: 'artifact', from: funnelFrom, to: new Date().toISOString() },
  });
  expect(retention.status()).toBe(200);
  const points = (await retention.json()).data.records;
  expect(points).toHaveLength(3);
  for (const point of points) {
    expect(point.cohortSize).toBe(1);
    expect(point.status).toBe('immature');
    expect(point.retentionRate ?? null).toBeNull();
  }

  // API adversarial checks complement the browser-driven export above.
  const replay = await page.request.post('/api/reports/export/json', { data: exportRequest });
  expect(replay.status()).toBe(200);
  expect((await replay.json()).dataSets.analysis).toEqual(artifact.dataSets.analysis);
  const changedDsl = structuredClone(artifact.reportDsl);
  changedDsl.dataSources.analysis.aggregateQuery.limit = 4;
  const changedSave = await page.request.put(`/api/report-definitions/${saved.pid}`, {
    data: { dsl: changedDsl },
  });
  expect(changedSave.status()).toBe(200);
  const changedExport = await page.request.post('/api/reports/export/json', {
    data: { reportPid: saved.pid, usageId: randomUUID() },
  });
  expect(changedExport.status()).toBe(200);
  expect((await changedExport.json()).reportDsl.dataSources.analysis.aggregateQuery.limit).toBe(4);
  const boundaryDb = new Client(PG_CONN);
  await boundaryDb.connect();
  try {
    await expect
      .poll(async () => {
        const result = await boundaryDb.query(
          "SELECT props->>'originalQuery' AS original FROM ab_behavior_event WHERE event_name = 'analytics_report_used' AND interaction_id = $1 AND props->>'targetKey' = $2 ORDER BY occurred_at, id",
          [analysisId, saved.pid],
        );
        return result.rows.map((row) => row.original);
      })
      .toEqual(['true', 'false']);
    changedDsl.dataSources.analysis.aggregateQuery.limit = 1001;
    expect(
      (
        await page.request.put(`/api/report-definitions/${saved.pid}`, {
          data: { dsl: changedDsl },
        })
      ).status(),
    ).toBe(200);
    const failedExport = await page.request.post('/api/reports/export/json', {
      data: { reportPid: saved.pid, usageId: randomUUID() },
    });
    expect(failedExport.status()).toBeGreaterThanOrEqual(400);
    expect(failedExport.status()).toBeLessThan(500);
    const remaining = await boundaryDb.query(
      "SELECT count(*)::int AS count FROM ab_behavior_event WHERE event_name = 'analytics_report_used' AND interaction_id = $1 AND props->>'targetKey' = $2",
      [analysisId, saved.pid],
    );
    expect(remaining.rows).toEqual([{ count: 2 }]);
  } finally {
    await boundaryDb.end();
  }
  // Keep the retained fixture usable after testing an invalid definition.
  expect(
    (
      await page.request.put(`/api/report-definitions/${saved.pid}`, {
        data: { dsl: artifact.reportDsl },
      })
    ).status(),
  ).toBe(200);
  for (const format of ['excel', 'pdf'] as const) {
    const responsePromise = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname === `/api/reports/export/${format}` &&
        response.request().method() === 'POST',
    );
    const filePromise = page.waitForEvent('download');
    await page
      .getByRole('button', { name: format === 'excel' ? '导出 Excel' : '导出 PDF', exact: true })
      .click();
    const response = await responsePromise;
    expect(response.status()).toBe(200);
    expect(response.request().postDataJSON().reportPid).toBe(saved.pid);
    const file = await filePromise;
    const extension = format === 'excel' ? 'xlsx' : 'pdf';
    expect(file.suggestedFilename()).toBe(`订单数量分析.${extension}`);
    const artifactPath = `${process.env.AURA_EVIDENCE_DIR}/report-data.${extension}`;
    await file.saveAs(artifactPath);
    const bytes = await readFile(artifactPath);
    if (format === 'excel') {
      const XLSX = await import('xlsx');
      const workbook = XLSX.read(bytes, { type: 'buffer' });
      expect(workbook.SheetNames).toHaveLength(1);
      const rows = XLSX.utils.sheet_to_json(workbook.Sheets[workbook.SheetNames[0]], { header: 1 });
      expect(rows).toContainEqual(['数量', '订单标题']);
      expect(rows).toContainEqual([1, title]);
    } else {
      expect(bytes.subarray(0, 5).toString()).toBe('%PDF-');
    }
    await shot(format);
  }
});
