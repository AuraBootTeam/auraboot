/** Deterministic tool execution over a real model. This is not a real-LLM reasoning test. */
import { test, expect } from '../../fixtures';
import { Client } from 'pg';
import { PG_CONN } from '../../helpers/environments';

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
  const analysisId = await card.getAttribute('data-analysis-id');
  expect(analysisId).toMatch(/^[0-9a-f-]{36}$/);
  const db = new Client(PG_CONN);
  await db.connect();
  try {
    await expect
      .poll(async () => {
        const rows = await db.query(
          'SELECT event_name, props, user_id::text, tenant_id::text FROM ab_behavior_event WHERE interaction_id = $1 ORDER BY occurred_at, id',
          [analysisId],
        );
        return rows.rows.map((row) => ({ name: row.event_name, props: row.props }));
      })
      .toEqual([
        { name: 'analytics_requested', props: null },
        {
          name: 'analytics_query_succeeded',
          props: { rowCount: 1, queryHash: expect.stringMatching(/^[0-9a-f]{64}$/) },
        },
      ]);
  } finally {
    await db.end();
  }

  const createdResponse = page.waitForResponse(
    (r) => r.request().method() === 'POST' && new URL(r.url()).pathname === '/api/dashboards',
  );
  await card.getByTestId('chatbi-save-dashboard').click();
  const created = await createdResponse;
  expect(created.status()).toBe(200);
  const source = created.request().postDataJSON().widgets[0].config.dataSource;
  expect(source).toMatchObject({ type: 'aggregate', ...query });
  const saved = (await created.json()).data;
  expect(saved.extension.analyticsOrigin.analysisId).toBe(analysisId);
  const persisted = await page.request.get(`/api/dashboards/${saved.pid}`);
  expect(persisted.status()).toBe(200);
  expect((await persisted.json()).data.extension.analyticsOrigin).toEqual(
    saved.extension.analyticsOrigin,
  );
  const tampered = created.request().postDataJSON();
  tampered.widgets[0].config.dataSource.limit = 4;
  const rejected = await page.request.post('/api/dashboards', { data: tampered });
  expect(rejected.status()).toBe(400);
  const rewrittenOrigin = await page.request.put(`/api/dashboards/${saved.pid}`, {
    data: {
      extension: { analyticsOrigin: { analysisId: 'forged-analysis', queryHash: 'forged' } },
    },
  });
  expect(rewrittenOrigin.status()).toBe(422);
  const unchanged = await page.request.get(`/api/dashboards/${saved.pid}`);
  expect(unchanged.status()).toBe(200);
  expect((await unchanged.json()).data.extension.analyticsOrigin).toEqual(
    saved.extension.analyticsOrigin,
  );

  const proof = new Client(PG_CONN);
  await proof.connect();
  try {
    await expect
      .poll(
        async () => {
          const result = await proof.query(
            "SELECT status, target_key FROM ab_behavior_outcome_outbox WHERE interaction_id = $1 AND event_name = 'analytics_dashboard_saved'",
            [analysisId],
          );
          return result.rows;
        },
        { timeout: 15000 },
      )
      .toEqual([{ status: 'published', target_key: saved.pid }]);
    await expect
      .poll(async () => {
        const result = await proof.query(
          "SELECT props FROM ab_behavior_event WHERE interaction_id = $1 AND event_name = 'analytics_dashboard_saved'",
          [analysisId],
        );
        return result.rows;
      })
      .toEqual([
        {
          props: {
            targetType: 'dashboard',
            targetKey: saved.pid,
            queryHash: saved.extension.analyticsOrigin.queryHash,
          },
        },
      ]);
  } finally {
    await proof.end();
  }

  await expect(card.getByTestId('chatbi-saved-dashboard')).toBeVisible();
  await page.screenshot({
    path: `${process.env.AURA_EVIDENCE_DIR}/dashboard-saved.png`,
    fullPage: true,
  });
  const requery = page.waitForResponse((response) => {
    if (
      new URL(response.url()).pathname !== '/api/meta/chart-data' ||
      response.request().method() !== 'POST'
    )
      return false;
    return response
      .request()
      .postDataJSON()
      ?.filters?.some((filter: { value?: string }) => filter.value === title);
  });
  await card.getByTestId('chatbi-saved-dashboard').click();
  await expect(page).toHaveURL(new RegExp(`/dashboards/view/${saved.code}$`));
  const refreshed = await requery;
  expect(refreshed.status()).toBe(200);
  expect(refreshed.request().postDataJSON()).toMatchObject({ type: 'aggregate', ...query });
  expect((await refreshed.json()).data.rows).toEqual([{ cnt: 1, e2et_order_title: title }]);
  await expect(page.locator('main').getByText(title, { exact: true })).toBeVisible();
  await page.screenshot({
    path: `${process.env.AURA_EVIDENCE_DIR}/dashboard-reopened.png`,
    fullPage: true,
  });
});
