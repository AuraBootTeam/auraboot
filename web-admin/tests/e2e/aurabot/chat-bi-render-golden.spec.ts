/** Deterministic tool execution over a real model. This is not a real-LLM reasoning test. */
import { test, expect } from '../../fixtures';

test.use({
  storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
  locale: 'zh-CN',
});

test('AuraBot filtered analysis saves its complete query to a dashboard', async ({ page }) => {
  test.setTimeout(120000);
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
  await page.getByTestId('ai-panel-toggle').click();
  const panel = page.getByTestId('aurabot-panel');
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
  const createdResponse = page.waitForResponse(
    (r) => r.request().method() === 'POST' && new URL(r.url()).pathname === '/api/dashboards',
  );
  await card.getByTestId('chatbi-save-dashboard').click();
  const created = await createdResponse;
  expect(created.status()).toBe(200);
  const source = created.request().postDataJSON().widgets[0].config.dataSource;
  expect(source).toMatchObject({ type: 'aggregate', ...query });
  await expect(card.getByTestId('chatbi-saved-dashboard')).toBeVisible();
  await page.screenshot({
    path: `${process.env.AURA_EVIDENCE_DIR}/dashboard-saved.png`,
    fullPage: true,
  });
});
