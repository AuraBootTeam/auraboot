/** Execution cohort UI on the existing DSL page, using real conversation setup and statistics. */
import { test, expect } from '../../fixtures';
import { randomUUID } from 'node:crypto';
import { resolve } from 'node:path';

test.use({
  storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
  locale: 'zh-CN',
});

test('execution tables follow the applied window and explain empty rates', async ({
  page,
}, testInfo) => {
  test.setTimeout(120000);
  const imported = await page.request.post('/api/plugins/import/import-directory-sync', {
    data: {
      path: resolve(process.cwd(), '../plugins/core-dashboard'),
      conflictStrategy: 'OVERWRITE',
      validateReferences: true,
      autoPublishPages: true,
    },
  });
  expect(imported.status()).toBe(200);
  const importBody = await imported.json();
  expect(importBody.data?.success ?? importBody.success, JSON.stringify(importBody)).toBe(true);
  const start = new Date();
  start.setSeconds(0, 0);
  const end = new Date(start.getTime() + 120000);
  const marker = randomUUID();
  const execution = await page.request.post('/api/ai/aurabot/chat/stream', {
    data: {
      sessionId: marker,
      clientMsgId: marker,
      message: `Execution page fixture ${marker}`,
      options: { explicitDurableRequest: true },
    },
  });
  expect(execution.status()).toBe(200);
  expect(await execution.text()).not.toContain('event:error');
  const params = { from: start.toISOString(), to: end.toISOString() };
  await expect
    .poll(
      async () => {
        const r = await page.request.get('/api/analytics/behavior/executions', { params });
        expect(r.status()).toBe(200);
        return (await r.json()).data.counts.succeeded;
      },
      { timeout: 15000 },
    )
    .toBeGreaterThanOrEqual(1);
  await page.goto('/home', { waitUntil: 'domcontentloaded' });
  const sidebar = page.getByTestId('sidebar');
  const entry = sidebar.locator('a[href="/p/c/behavior_analytics"]');
  if (!(await entry.isVisible())) await sidebar.getByText('数据分析', { exact: true }).click();
  await entry.click();
  const input = page.getByTestId('query-time-window');
  const local = (d: Date) =>
    new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
  await input.getByLabel('开始时间（含）').fill(local(start));
  await input.getByLabel('结束时间（不含）').fill(local(end));
  const next = page.waitForResponse(
    (r) =>
      r.url().includes('/api/analytics/behavior/executions') &&
      new URL(r.url()).searchParams.get('from') === params.from,
  );
  await input.getByRole('button', { name: '应用时间范围' }).click();
  const response = await next;
  expect(response.status()).toBe(200);
  const data = (await response.json()).data;
  const counts = page.getByRole('heading', { name: '执行状态', exact: true }).locator('../..');
  const rates = page
    .getByRole('heading', { name: '成功率与统计范围', exact: true })
    .locator('../..');
  const expected = ['started', 'succeeded', 'failed', 'cancelled', 'unresolved'].map((key) =>
    new Intl.NumberFormat('zh-CN').format(data.counts[key]),
  );
  await expect(counts.locator('tbody tr').first().locator('td')).toHaveText(expected);
  await expect(rates).toContainText('总体成功率');
  await expect(
    page.getByText('执行成功不代表建议已采纳或产生业务收益。', { exact: false }),
  ).toBeAttached();
  await rates.scrollIntoViewIfNeeded();
  await page.screenshot({
    path: resolve(process.env.AURA_EVIDENCE_DIR || testInfo.outputDir, 'execution-loaded.png'),
  });
  await testInfo.attach('execution-consumed-response', {
    body: JSON.stringify(data),
    contentType: 'application/json',
  });
  await input.getByLabel('开始时间（含）').fill('2027-01-01T00:00');
  await input.getByLabel('结束时间（不含）').fill('2027-01-02T00:00');
  const emptyFrom = await page.evaluate(() => new Date('2027-01-01T00:00').toISOString());
  const empty = page.waitForResponse(
    (r) =>
      r.url().includes('/api/analytics/behavior/executions') &&
      new URL(r.url()).searchParams.get('from') === emptyFrom,
  );
  await input.getByRole('button', { name: '应用时间范围' }).click();
  expect((await empty).status()).toBe(200);
  await expect(counts.locator('tbody tr').first().locator('td')).toHaveText([
    '0',
    '0',
    '0',
    '0',
    '0',
  ]);
  await expect(rates.getByText('无样本', { exact: true })).toHaveCount(2);
  await rates.scrollIntoViewIfNeeded();
  await page.screenshot({
    path: resolve(process.env.AURA_EVIDENCE_DIR || testInfo.outputDir, 'execution-empty.png'),
  });
  // Inject only the transport failure; recovery consumes the real backend response.
  const executionRoute = '**/api/analytics/behavior/executions*';
  await page.route(executionRoute, (route) =>
    route.fulfill({
      status: 503,
      contentType: 'application/json',
      body: '{"message":"Service unavailable"}',
    }),
  );
  await input.getByLabel('结束时间（不含）').fill('2027-01-03T00:00');
  await input.getByRole('button', { name: '应用时间范围' }).click();
  const errors = page.getByRole('alert').filter({ hasText: '数据加载失败' });
  await expect(errors).toHaveCount(2);
  await errors.first().scrollIntoViewIfNeeded();
  await page.screenshot({
    path: resolve(process.env.AURA_EVIDENCE_DIR || testInfo.outputDir, 'execution-error.png'),
  });
  await page.unroute(executionRoute);
  const recovered = page.waitForResponse(
    (r) => r.url().includes('/api/analytics/behavior/executions') && r.status() === 200,
  );
  await input.getByRole('button', { name: '最近 30 天' }).click();
  const recoveredData = (await (await recovered).json()).data;
  await expect(errors).toHaveCount(0);
  await expect(counts.locator('tbody tr').first().locator('td')).toHaveText(
    ['started', 'succeeded', 'failed', 'cancelled', 'unresolved'].map((key) =>
      new Intl.NumberFormat('zh-CN').format(recoveredData.counts[key]),
    ),
  );
  await rates.scrollIntoViewIfNeeded();
  await page.screenshot({
    path: resolve(process.env.AURA_EVIDENCE_DIR || testInfo.outputDir, 'execution-window.png'),
  });
});
