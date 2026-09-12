/** Deterministic tool execution over a real model. This is not a real-LLM reasoning test. */
import { test, expect } from '../../fixtures';
import { readFile } from 'node:fs/promises';

test.use({
  storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
  locale: 'zh-CN',
});

test('AuraBot analysis saves an executable report and reopens its data', async ({ page }) => {
  test.setTimeout(120000);
  page.on('pageerror', (error) => console.log('[analytics-page-error]', error.message));
  page.on('console', (message) => {
    if (message.type() === 'error') console.log('[analytics-console-error]', message.text());
  });
  const title = `Analytics fixture ${Date.now()}`;
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
  await page.addInitScript(() => localStorage.removeItem('aurabot:last-conversation-id'));
  await page.goto('/home', { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => {
    const toggle = document.querySelector('[data-testid="ai-panel-toggle"]');
    return toggle && Object.keys(toggle).some((key) => key.startsWith('__reactProps$'));
  });
  const panel = page.getByTestId('aurabot-panel');
  if (!(await panel.isVisible())) await page.getByTestId('ai-panel-toggle').click();
  await expect(panel).toBeVisible();
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
        input: { ...query, chartType: 'table', interpretation: 'Filtered orders' },
      }),
  );
  await input.press('Enter');
  const card = panel.getByTestId('chatbi-result-card');
  await expect(card).toBeVisible({ timeout: 45000 });
  await expect(card).toHaveAttribute('data-row-count', '1');
  await expect(card).toContainText(title);
  const createdPromise = page.waitForResponse(
    (r) =>
      r.request().method() === 'POST' && new URL(r.url()).pathname === '/api/report-definitions',
  );
  await card.getByTestId('chatbi-save-report').click();
  const created = await createdPromise;
  expect(created.status()).toBe(200);
  const persistedQuery = created.request().postDataJSON().dsl.dataSources.analysis.aggregateQuery;
  expect(persistedQuery).toMatchObject({ type: 'aggregate', ...query });
  const saved = (await created.json()).data;
  await expect(card.getByTestId('chatbi-saved-report')).toHaveAttribute(
    'href',
    `/report-designer/${saved.pid}`,
  );
  await page.screenshot({ path: `${process.env.AURA_EVIDENCE_DIR}/saved.png`, fullPage: true });
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
  const reloadDefinition = page.waitForResponse(response =>
    new URL(response.url()).pathname === `/api/report-definitions/${saved.pid}` && response.request().method() === 'GET');
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
  await expect(page.getByTestId('report-designer-toolbar').getByRole('button', { name: '编辑', exact: true })).toBeVisible();
  await expect(page.locator('main').getByText(title, { exact: true }).first()).toBeVisible();
  await expect(page.getByTestId('report-aggregate-limit-hint')).toContainText('预览与导出使用相同查询');
  await expect(page.getByText('模型和命名查询预览最多 500 行', { exact: false })).toHaveCount(0);
  const downloadPromise = page.waitForEvent('download');
  await page.getByRole('button', { name: /导出 JSON|Export JSON/ }).click();
  const download = await downloadPromise;
  const path = await download.path();
  expect(path).toBeTruthy();
  const artifact = JSON.parse(await readFile(path!, 'utf8'));
  expect(artifact.dataSets.analysis).toEqual([{ cnt: 1, e2et_order_title: title }]);
  expect(artifact.reportDsl.dataSources.analysis.aggregateQuery).toEqual(persistedQuery);
  await page.screenshot({ path: `${process.env.AURA_EVIDENCE_DIR}/reopened.png`, fullPage: true });
});
