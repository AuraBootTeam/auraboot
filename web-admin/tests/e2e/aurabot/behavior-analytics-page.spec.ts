/** Real menu and API rendering. Data values are compared with the response the page consumes. */
import { test, expect } from '../../fixtures';
import { resolve } from 'node:path';

test.use({
  storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
  locale: 'zh-CN',
});

test('behavior menu renders ordered funnel and both retention units', async ({
  page,
}, testInfo) => {
  test.setTimeout(60000);
  const queryUrls: string[] = [];
  page.on('request', (request) => {
    if (request.url().includes('/api/analytics/behavior/')) queryUrls.push(request.url());
  });
  await page.goto('/home', { waitUntil: 'domcontentloaded' });
  const sidebar = page.getByTestId('sidebar');
  const entry = sidebar.locator('a[href="/p/c/behavior_analytics"]');
  if (!(await entry.isVisible())) await sidebar.getByText('数据分析', { exact: true }).click();
  await entry.evaluate((element) => element.scrollIntoView({ block: 'center' }));
  await expect(entry).toBeVisible();
  const funnelResponse = page.waitForResponse(
    (r) =>
      r.url().includes('/api/analytics/behavior/analysis-funnel') && r.request().method() === 'GET',
  );
  const retentionResponse = (unit: string) =>
    page.waitForResponse(
      (r) =>
        r.url().includes('/api/analytics/behavior/retention') &&
        new URL(r.url()).searchParams.get('unit') === unit,
    );
  const userPromise = retentionResponse('user');
  const artifactPromise = retentionResponse('artifact');
  await entry.click();
  await expect(page).toHaveURL(/\/p\/c\/behavior_analytics/);
  const responses = await Promise.all([funnelResponse, userPromise, artifactPromise]);
  for (const response of responses) expect(response.status()).toBe(200);
  const [funnel, users, artifacts] = await Promise.all(
    responses.map(async (r) => (await r.json()).data),
  );
  await testInfo.attach('consumed-analytics-responses', {
    body: JSON.stringify({ funnel, users, artifacts }, null, 2),
    contentType: 'application/json',
  });
  const funnelCard = page
    .getByRole('heading', { name: '分析任务转化', exact: true })
    .locator('../..');
  await expect(funnelCard).toBeVisible();
  expect(funnel.records).toHaveLength(5);
  for (const [index, stage] of funnel.records.entries()) {
    const row = funnelCard.locator('tbody tr').nth(index);
    await expect(row.locator('td').nth(1)).toHaveText(
      new Intl.NumberFormat('zh-CN').format(stage.tasks),
    );
  }
  await expect(funnelCard).toContainText('结果已呈现');
  await expect(funnelCard).not.toContainText('query_succeeded');
  await funnelCard.screenshot({
    path: resolve(process.env.AURA_EVIDENCE_DIR || testInfo.outputDir, 'behavior-funnel.png'),
  });
  for (const [heading, body] of [
    ['用户再次使用', users],
    ['看板再次使用', artifacts],
  ] as const) {
    const card = page.getByRole('heading', { name: heading, exact: true }).locator('../..');
    await expect(card).toBeVisible();
    if (!body.records.length) {
      await expect(card).toContainText('当前范围内尚无首次使用记录');
    } else {
      const record = body.records[0];
      const row = card.locator('tbody tr').first();
      await expect(row.locator('td').nth(0)).toHaveText(record.cohortDay);
      await expect(row.locator('td').nth(1)).toHaveText(`D${record.dayOffset}`);
      await expect(row.locator('td').nth(2)).toHaveText(String(record.cohortSize));
      if (record.status === 'immature') {
        await expect(row.locator('td').nth(3)).toHaveText('尚未计算');
        await expect(row.locator('td').nth(4)).toHaveText('尚未计算');
        await expect(row.locator('td').nth(5)).toHaveText('观察中');
      }
    }
    await card.evaluate((element) => element.scrollIntoView({ block: 'center' }));
    await expect(card.locator('tbody tr').first()).toBeVisible();
    await page.screenshot({
      path: resolve(
        process.env.AURA_EVIDENCE_DIR || testInfo.outputDir,
        heading === '用户再次使用'
          ? 'behavior-user-retention.png'
          : 'behavior-artifact-retention.png',
      ),
    });
  }
  const windows = () =>
    queryUrls.map((url) => {
      const query = new URL(url).searchParams;
      return { from: query.get('from'), to: query.get('to') };
    });
  expect(queryUrls.length).toBeGreaterThanOrEqual(5);
  expect(windows()[0].from).toBeTruthy();
  expect(windows().every((window) => JSON.stringify(window) === JSON.stringify(windows()[0]))).toBe(
    true,
  );
  const sources = new Set(
    queryUrls.map((url) => {
      const value = new URL(url);
      return (
        value.pathname +
        (value.searchParams.get('unit') ? '?unit=' + value.searchParams.get('unit') : '')
      );
    }),
  );
  expect([...sources].sort()).toEqual(
    ['analysis-funnel', 'overview', 'retention?unit=artifact', 'retention?unit=user', 'top-events']
      .map((path) => '/api/analytics/behavior/' + path)
      .sort(),
  );
  const input = page.getByTestId('query-time-window');
  const beforeDraft = queryUrls.length;
  await input.getByLabel('开始时间（含）').fill('2027-01-01T00:00');
  await input.getByLabel('结束时间（不含）').fill('2027-01-02T00:00');
  expect(queryUrls).toHaveLength(beforeDraft);
  const previous = queryUrls.length;
  const expected = await page.evaluate(() => ({
    from: new Date('2027-01-01T00:00').toISOString(),
    to: new Date('2027-01-02T00:00').toISOString(),
  }));
  const next = page.waitForResponse(
    (r) =>
      r.url().includes('/api/analytics/behavior/retention') &&
      new URL(r.url()).searchParams.get('from') === expected.from,
  );
  await input.getByRole('button', { name: '应用时间范围' }).click();
  const emptyResponse = await next;
  expect(emptyResponse.status()).toBe(200);
  expect((await emptyResponse.json()).data.records).toEqual([]);
  await expect(page.getByText('当前范围内尚无首次使用记录', { exact: false })).toHaveCount(2);
  expect(queryUrls.slice(previous).length).toBeGreaterThanOrEqual(5);
  expect(
    windows()
      .slice(previous)
      .every((window) => JSON.stringify(window) === JSON.stringify(expected)),
  ).toBe(true);
  const emptyCard = page
    .getByRole('heading', { name: '看板再次使用', exact: true })
    .locator('../..');
  await emptyCard.evaluate((element) => element.scrollIntoView({ block: 'center' }));
  await page.screenshot({
    path: resolve(process.env.AURA_EVIDENCE_DIR || testInfo.outputDir, 'behavior-window-empty.png'),
  });
  await input.getByLabel('开始时间（含）').fill('2027-02-01T00:00');
  const beforeInvalid = queryUrls.length;
  await input.getByRole('button', { name: '应用时间范围' }).click();
  await expect(input.getByRole('alert')).toContainText('开始时间须早于结束时间');
  expect(queryUrls).toHaveLength(beforeInvalid);
  await input.screenshot({
    path: resolve(process.env.AURA_EVIDENCE_DIR || testInfo.outputDir, 'behavior-window-error.png'),
  });
  const reset = page.waitForResponse(
    (r) =>
      r.url().includes('/api/analytics/behavior/analysis-funnel') &&
      new URL(r.url()).searchParams.get('from') !== expected.from,
  );
  await input.getByRole('button', { name: '最近 30 天' }).click();
  expect((await reset).status()).toBe(200);
  await expect(input.getByRole('alert')).toHaveCount(0);
  await page.getByRole('tab', { name: '使用与转化', exact: true }).scrollIntoViewIfNeeded();
  await expect(page.getByText('这里只反映已收到的事件', { exact: false })).toBeVisible();
  await page.screenshot({
    path: resolve(
      process.env.AURA_EVIDENCE_DIR || testInfo.outputDir,
      'behavior-analytics-page.png',
    ),
    fullPage: true,
  });
});
