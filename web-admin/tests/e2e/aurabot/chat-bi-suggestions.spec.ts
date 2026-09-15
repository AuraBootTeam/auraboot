/** Browser-driven commands and real persistence; deterministic LLM tool selection. */
import { test, expect } from '../../fixtures';
import { resolve } from 'node:path';
import { Client } from 'pg';
import { PG_CONN } from '../../helpers/environments';

test.use({
  storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
  locale: 'zh-CN',
});

test('analysis suggestions preserve versions and explicit adoption through reload', async ({
  page,
}) => {
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
  expect((await imported.json()).success).toBe(true);
  const marker = `建议验证 ${Date.now()}`;
  const fixture = await page.request.post('/api/dynamic/e2et_order/create', {
    data: {
      e2et_order_title: marker,
      e2et_order_type: 'normal',
      e2et_order_urgent: false,
      e2et_order_status: 'draft',
    },
  });
  expect(fixture.status()).toBe(200);
  await page.addInitScript(() => {
    if (!sessionStorage.getItem('suggestion-fixture-initialized')) {
      localStorage.removeItem('aurabot.lastConversationId');
      sessionStorage.setItem('suggestion-fixture-initialized', '1');
    }
  });
  await page.goto('/home', { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => {
    const toggle = document.querySelector('[data-testid="ai-panel-toggle"]');
    return toggle && Object.keys(toggle).some((key) => key.startsWith('__reactProps$'));
  });
  const panel = page.getByTestId('aurabot-panel');
  if (!(await panel.getByTestId('aurabot-input').isVisible()))
    await page.getByTestId('ai-panel-toggle').click();
  const query = {
    modelCode: 'e2et_order',
    dimensions: ['e2et_order_title'],
    metrics: [{ field: 'pid', aggregation: 'count', alias: 'cnt' }],
    filters: [{ field: 'e2et_order_title', operator: 'eq', value: marker }],
    limit: 5,
  };
  const input = panel.locator('textarea').first();
  await input.fill(
    '@@AURABOOT_STUB_TOOL_USE@@ ' +
      JSON.stringify({
        name: 'aurabot_chat-bi',
        input: {
          ...query,
          chartType: 'table',
          interpretation: '请检查本次订单分析，再记录处理建议。',
        },
      }),
  );
  await input.press('Enter');
  let card = page.getByTestId('chatbi-result-card');
  await expect(card).toHaveAttribute('data-row-count', '1', { timeout: 45000 });
  await expect(card).toContainText(marker);
  const analysisId = await card.getAttribute('data-analysis-id');
  card = page.locator(`[data-testid="chatbi-result-card"][data-analysis-id="${analysisId}"]`);
  const suggestions = card.getByTestId('analytics-suggestions');
  const shot = async (n: string) =>
    page.screenshot({
      path: `${process.env.AURA_EVIDENCE_DIR}/suggestion-${n}.png`,
      fullPage: true,
    });
  await expect(suggestions).toContainText('还没有已记录的建议');
  await suggestions.scrollIntoViewIfNeeded();
  await shot('01');
  const db = new Client(PG_CONN);
  await db.connect();
  try {
    await expect
      .poll(async () =>
        Number(
          (
            await db.query(
              'SELECT count(*) FROM ab_behavior_event WHERE interaction_id=$1 AND event_name=$2',
              [analysisId, 'analytics_query_succeeded'],
            )
          ).rows[0].count,
        ),
      )
      .toBe(1);
    let writes = 0;
    page.on('request', (request) => {
      if (
        request.method() === 'POST' &&
        request.url().includes('/api/meta/commands/execute/core_dashboard:')
      )
        writes++;
    });
    await suggestions.getByRole('button', { name: '记录建议', exact: true }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByRole('button', { name: '保存建议版本', exact: true }).click();
    await expect(dialog.getByRole('alert')).toContainText('请填写建议标题和内容');
    expect(writes).toBe(0);
    await shot('02');
    await dialog.getByLabel('建议标题', { exact: true }).fill(marker);
    await dialog
      .getByLabel('建议内容', { exact: true })
      .fill('核实订单处理条件，然后安排人工复核。');
    const proposalResponse = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        r.url().includes('/execute/core_dashboard:propose_suggestion'),
    );
    await dialog.getByRole('button', { name: '保存建议版本', exact: true }).click();
    const proposal = await proposalResponse;
    expect(proposal.status()).toBe(200);
    expect(proposal.request().postDataJSON().payload).toMatchObject({
      analysisId,
      query: { type: 'aggregate', ...query },
      title: marker,
    });
    const first = (await proposal.json()).data.data.record;
    await expect(dialog).toBeHidden();
    const original = suggestions.getByTestId('analytics-suggestion').filter({ hasText: '版本 1' });
    await expect(original).toContainText(marker);
    await original.scrollIntoViewIfNeeded();
    await shot('03');
    await original.getByRole('button', { name: '采纳此版本', exact: true }).click();
    await expect(dialog).toContainText('执行需另行发起');
    await expect(dialog).toContainText('版本 1');
    await shot('04');
    const adoptedResponse = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        r.url().includes('/execute/core_dashboard:adopt_suggestion'),
    );
    await dialog.getByRole('button', { name: '确认采纳', exact: true }).click();
    const adopted = await adoptedResponse;
    expect(adopted.status()).toBe(200);
    expect(adopted.request().postDataJSON().payload.versionPid).toBe(first.pid);
    await expect(original).toContainText('已采纳');
    await suggestions.getByRole('button', { name: '刷新建议', exact: true }).click();
    await expect(suggestions.getByRole('status')).toHaveCount(0);
    await expect(original).toContainText('已采纳');
    await shot('05');
    await original.getByRole('button', { name: '修订建议', exact: true }).click();
    await dialog.getByLabel('建议内容', { exact: true }).fill('增加库存确认后，再安排人工复核。');
    await dialog.getByRole('button', { name: '保存建议版本', exact: true }).click();
    const revised = suggestions.getByTestId('analytics-suggestion').filter({ hasText: '版本 2' });
    await expect(revised).toContainText('增加库存确认');
    await expect(original).toContainText('核实订单处理条件');
    await expect(original).toContainText('已采纳');
    await shot('06');
    await page.route('**/api/analytics/suggestions?*', (route) =>
      route.fulfill({
        status: 503,
        contentType: 'application/json',
        body: JSON.stringify({ code: 503, message: 'Injected read transport failure' }),
      }),
    );
    await suggestions.getByRole('button', { name: '刷新建议', exact: true }).click();
    await expect(suggestions.getByRole('alert')).toBeVisible();
    await expect(suggestions.getByTestId('analytics-suggestion')).toHaveCount(0);
    await shot('07');
    await page.unroute('**/api/analytics/suggestions?*');
    await suggestions.getByRole('button', { name: '重新加载', exact: true }).click();
    await expect(revised).toContainText('增加库存确认');
    await page.setViewportSize({ width: 390, height: 844 });
    await revised.getByRole('button', { name: '修订建议', exact: true }).click();
    await dialog.getByLabel('建议标题', { exact: true }).focus();
    await page.keyboard.press('Tab');
    await expect(dialog.getByLabel('建议内容', { exact: true })).toBeFocused();
    const bounds = await dialog.boundingBox();
    expect(bounds!.x).toBeGreaterThanOrEqual(0);
    expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(390);
    await shot('08');
    await dialog.getByRole('button', { name: '取消', exact: true }).click();
    await expect(revised.getByRole('button', { name: '修订建议', exact: true })).toBeFocused();
    await expect
      .poll(
        async () =>
          (
            await db.query(
              "SELECT event_name, count(*)::int AS count FROM ab_behavior_event WHERE interaction_id=$1 AND event_name IN ('analytics_suggestion_proposed','analytics_suggestion_adopted') GROUP BY event_name ORDER BY event_name",
              [analysisId],
            )
          ).rows,
      )
      .toEqual([
        { event_name: 'analytics_suggestion_adopted', count: 1 },
        { event_name: 'analytics_suggestion_proposed', count: 2 },
      ]);
    await page.setViewportSize({ width: 1280, height: 720 });
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.waitForFunction(() => {
      const toggle = document.querySelector('[data-testid="ai-panel-toggle"]');
      return toggle && Object.keys(toggle).some((key) => key.startsWith('__reactProps$'));
    });
    if (!(await panel.getByTestId('aurabot-input').isVisible()))
      await page.getByTestId('ai-panel-toggle').click();
    await expect(original).toContainText('已采纳', { timeout: 15000 });
    await expect(card).toContainText('已按当前权限重新查询');
    await expect(card).toHaveAttribute('data-row-count', '1');
    await original.scrollIntoViewIfNeeded();
    await shot('10');
    const persisted = await db.query('SELECT card_payload::text AS card_payload FROM ab_im_message WHERE card_payload::text LIKE $1', [`%${analysisId}%`]);
    expect(persisted.rows).toHaveLength(1);
    const metadata = JSON.parse(persisted.rows[0].card_payload);
    expect(metadata.analyticsReferences).toEqual([{ analysisId, chartType: 'table', dataSource: proposal.request().postDataJSON().payload.query }]);
    expect(metadata.analyticsReferences[0]).not.toHaveProperty('records');
    expect(metadata.analyticsReferences[0]).not.toHaveProperty('interpretation');
  } finally {
    await db.end();
  }
});
