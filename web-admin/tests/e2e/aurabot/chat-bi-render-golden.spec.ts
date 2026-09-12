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
  page.on('pageerror', (error) => console.log('[analytics-page-error]', error.message));
  page.on('console', (message) => {
    if (message.type() === 'error') console.log('[analytics-console-error]', message.text());
  });
  const funnelFrom = new Date().toISOString();
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
        {
          name: 'analytics_result_viewed',
          props: {
            queryHash: expect.stringMatching(/^[0-9a-f]{64}$/),
            signalSource: 'client_visible',
          },
        },
      ]);
  } finally {
    await db.end();
  }

  const replayView = await page.request.post(`/api/analytics/results/${analysisId}/view`, {
    data: {},
  });
  expect(replayView.status()).toBe(200);
  const inventedView = await page.request.post(
    `/api/analytics/results/00000000-0000-0000-0000-000000000000/view`,
    { data: {} },
  );
  expect(inventedView.status()).toBe(400);

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
  const queryPath = `/api/dashboards/${saved.pid}/widgets/${saved.widgets[0].id}/data`;
  const requery = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === queryPath && response.request().method() === 'POST',
  );
  await card.getByTestId('chatbi-saved-dashboard').click();
  await expect(page).toHaveURL(new RegExp(`/dashboards/view/${saved.code}$`));
  const refreshed = await requery;
  expect(refreshed.status()).toBe(200);
  const usageRequest = refreshed.request().postDataJSON();
  expect(usageRequest).toEqual({ usageId: expect.stringMatching(/^[0-9a-f-]{36}$/) });
  expect((await refreshed.json()).data.rows).toEqual([{ cnt: 1, e2et_order_title: title }]);
  await expect(page.locator('main').getByText(title, { exact: true })).toBeVisible();
  const duplicateUse = await page.request.post(queryPath, { data: usageRequest });
  expect(duplicateUse.status()).toBe(200);
  expect((await duplicateUse.json()).data.rows).toEqual([{ cnt: 1, e2et_order_title: title }]);
  const missingWidget = await page.request.post(
    `/api/dashboards/${saved.pid}/widgets/missing/data`,
    { data: usageRequest },
  );
  expect(missingWidget.status()).toBe(404);
  const usedProof = new Client(PG_CONN);
  await usedProof.connect();
  try {
    await expect
      .poll(
        async () => {
          const used = await usedProof.query(
            "SELECT props FROM ab_behavior_event WHERE interaction_id = $1 AND event_name = 'analytics_dashboard_used'",
            [analysisId],
          );
          return used.rows;
        },
        { timeout: 15000 },
      )
      .toEqual([
        {
          props: {
            targetType: 'dashboard',
            targetKey: saved.pid,
            widgetId: saved.widgets[0].id,
            queryHash: saved.extension.analyticsOrigin.queryHash,
            originalQuery: true,
          },
        },
      ]);
    const viewed = await usedProof.query(
      "SELECT count(*)::int AS count FROM ab_behavior_event WHERE interaction_id = $1 AND event_name = 'analytics_result_viewed'",
      [analysisId],
    );
    expect(viewed.rows).toEqual([{ count: 1 }]);
  } finally {
    await usedProof.end();
  }
  const funnel = await page.request.get('/api/analytics/behavior/analysis-funnel', {
    params: { from: funnelFrom, to: new Date().toISOString() },
  });
  expect(funnel.status()).toBe(200);
  const funnelData = (await funnel.json()).data;
  expect(funnelData.definitionVersion).toBe('analysis-task-funnel-v1');
  expect(funnelData.records.map((stage: { tasks: number }) => stage.tasks)).toEqual([
    1, 1, 1, 1, 1,
  ]);
  expect(funnelData.records.map((stage: { overallRate: number }) => stage.overallRate)).toEqual([
    1, 1, 1, 1, 1,
  ]);
  expect(funnelData.quality).toMatchObject({
    missingCorrelationEvents: 0,
    withoutWindowEntryTasks: 0,
    unmatchedStageEvents: 0,
    sampledEvents: 0,
  });
  await page.screenshot({
    path: `${process.env.AURA_EVIDENCE_DIR}/dashboard-reopened.png`,
    fullPage: true,
  });
});
