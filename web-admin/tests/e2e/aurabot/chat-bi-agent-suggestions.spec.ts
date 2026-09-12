/** Browser-driven commands and real persistence; deterministic LLM tool selection. */
import { test, expect } from '../../fixtures';
import { randomUUID } from 'node:crypto';
import { resolve } from 'node:path';
import { Client } from 'pg';
import { PG_CONN } from '../../helpers/environments';

test.use({
  storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
  locale: 'zh-CN',
});

test('AI suggestion confirmation and manual adoption use the visible AuraBot controls', async ({
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
    const originalFetch = window.fetch.bind(window);
    window.fetch = async (...args) => {
      const response = await originalFetch(...args);
      if (response.url.endsWith('/api/ai/aurabot/chat/stream')) {
        void response
          .clone()
          .text()
          .then((text) => {
            (window as any).__analyticsStream = text;
          });
      }
      return response;
    };

    if (!sessionStorage.getItem('suggestion-fixture-initialized')) {
      localStorage.removeItem('aurabot.lastConversationId');
      sessionStorage.setItem('suggestion-fixture-initialized', '1');
    }
  });
  await page.goto('/home', { waitUntil: 'domcontentloaded' });
  await expect(page.locator('header[data-hydrated="true"]')).toBeVisible();
  const panel = page.getByTestId('aurabot-panel');
  const toggle = page.getByTestId('ai-panel-toggle');
  await expect(toggle).toHaveClass(/text-gray-500/);
  await toggle.click();
  await expect(panel.getByTestId('aurabot-input')).toBeVisible();
  await panel.getByTestId('aurabot-history-trigger').click();
  await panel.getByTestId('aurabot-new-session').click();
  await expect(panel.getByTestId('aurabot-history-dropdown')).toHaveCount(0);
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
  const newConversationResponse = page.waitForResponse(
    (r) => r.request().method() === 'POST' && r.url().endsWith('/api/ai/aurabot/conversations'),
  );
  const queryResponse = page.waitForResponse(
    (r) => r.request().method() === 'POST' && r.url().endsWith('/api/ai/aurabot/chat/stream'),
  );
  await input.press('Enter');
  const createdConversation = await newConversationResponse;
  expect(createdConversation.request().postDataJSON().newConversation).toBe(true);
  expect(createdConversation.status()).toBe(200);
  const firstConversationId = (await createdConversation.json()).data.conversationId;
  await (await queryResponse).finished();
  await page.waitForFunction(() => typeof (window as any).__analyticsStream === 'string');
  const queryStream: string = await page.evaluate(() => (window as any).__analyticsStream);
  const contracts = queryStream
    .split(/\r?\n\r?\n/)
    .filter(
      (block) =>
        block.startsWith('event:result_contract') || block.startsWith('event: result_contract'),
    )
    .map((block) => {
      const value = JSON.parse(
        block
          .split(/\r?\n/)
          .filter((line) => line.startsWith('data:'))
          .map((line) => line.slice(5).trim())
          .join('\n'),
      );
      return typeof value === 'string' ? JSON.parse(value) : value;
    });
  const result = contracts.find((contract) => contract?.data?.data?.analysisId)?.data.data;
  expect(result).toBeTruthy();
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
  await expect(card.locator('thead')).not.toContainText('e2et_order_title');
  await expect(card.locator('th').first()).toHaveText('数量');
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
    const proposal = {
      analysisId,
      query: result.dataSource,
      title: marker,
      content: 'Check the order before deciding.',
      executionIntent: {
        type: 'agent_task',
        goal: 'Review the selected order and report findings.',
      },
      requestId: randomUUID(),
    };
    await input.fill(
      '创建分析建议版本。\n@@AURABOOT_STUB_TOOL_USE@@ ' +
        JSON.stringify({
          id: randomUUID(),
          name: 'cmd_core_dashboard_propose_suggestion',
          input: proposal,
        }),
    );
    await input.press('Enter');
    const confirmation = panel.getByTestId('aurabot-confirm-card');
    await expect(confirmation).toBeVisible({ timeout: 45000 });
    await expect(confirmation).toContainText(proposal.executionIntent.goal);
    await confirmation.scrollIntoViewIfNeeded();
    await shot('ai-confirm');
    const before = await page.request.get('/api/analytics/suggestions', {
      params: { analysisId: analysisId! },
    });
    expect((await before.json()).data.records).toHaveLength(0);
    const resumedResponse = page.waitForResponse(
      (r) => r.request().method() === 'POST' && r.url().endsWith('/api/ai/aurabot/execute'),
    );
    await confirmation.getByTestId('aurabot-confirm-approve').click();
    const resumed = await resumedResponse;
    expect(resumed.status()).toBe(200);
    expect(resumed.request().postDataJSON()).toMatchObject({ confirmed: true });
    expect(resumed.request().postDataJSON().pendingTurnId).toBeTruthy();
    await resumed.finished();
    await suggestions.getByRole('button', { name: '刷新建议', exact: true }).click();
    const version = suggestions.getByTestId('analytics-suggestion');
    await expect(version).toContainText(marker);
    await version.scrollIntoViewIfNeeded();
    await shot('ai-saved');
    const saved = await page.request.get('/api/analytics/suggestions', {
      params: { analysisId: analysisId! },
    });
    const row = (await saved.json()).data.records[0];
    expect(row).toMatchObject({ origin: 'agent_generated', adoptionPid: null });
    await version.getByRole('button', { name: '采纳此版本', exact: true }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toContainText('执行需另行发起');
    await expect(dialog).toContainText(proposal.executionIntent.goal);
    const adoptionResponse = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        r.url().includes('/execute/core_dashboard:adopt_suggestion'),
    );
    await dialog.getByRole('button', { name: '确认采纳', exact: true }).click();
    const adoption = await adoptionResponse;
    expect(adoption.status()).toBe(200);
    expect(adoption.request().postDataJSON().payload.versionPid).toBe(row.pid);
    expect((await adoption.json()).data.data.record.core_dashboard_decision_mode).toBe(
      'ai_assisted',
    );
    await expect(version).toContainText('已采纳');
    await version.scrollIntoViewIfNeeded();
    await shot('ai-adopted');
    const facts = await db.query(
      'SELECT event_name, payload FROM ab_behavior_outcome_outbox WHERE interaction_id=$1 ORDER BY id',
      [analysisId],
    );
    expect(facts.rows).toHaveLength(2);
    expect(facts.rows[0]).toMatchObject({
      event_name: 'analytics_suggestion_proposed',
      payload: { proposalOrigin: 'agent_generated' },
    });
    expect(facts.rows[1]).toMatchObject({
      event_name: 'analytics_suggestion_adopted',
      payload: { decisionMode: 'ai_assisted', suggestionVersionPid: row.pid },
    });
    const launch = version.getByRole('button', { name: '发起执行', exact: true });
    await expect(launch).toBeVisible();
    let executionRequests = 0;
    page.on('request', (request) => {
      if (
        request.method() === 'POST' &&
        request.url().endsWith('/api/ai/aurabot/chat/stream') &&
        request.postDataJSON()?.analyticsExecution
      )
        executionRequests++;
    });
    await launch.click();
    const executionDialog = page.getByRole('dialog');
    await expect(executionDialog).toContainText(proposal.executionIntent.goal);
    await shot('ai-execute-confirm');
    await executionDialog.getByRole('button', { name: '取消', exact: true }).click();
    await expect(launch).toBeFocused();
    expect(executionRequests).toBe(0);
    await page.setViewportSize({ width: 390, height: 844 });
    await launch.click();
    await expect(
      executionDialog.getByRole('button', { name: '确认执行', exact: true }),
    ).toBeVisible();
    await shot('ai-execute-narrow');
    await executionDialog.getByRole('button', { name: '取消', exact: true }).click();
    await page.setViewportSize({ width: 1440, height: 1000 });
    await launch.click();
    const executionResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        response.url().endsWith('/api/ai/aurabot/chat/stream') &&
        !!response.request().postDataJSON()?.analyticsExecution,
    );
    await executionDialog.getByRole('button', { name: '确认执行', exact: true }).click();
    const executed = await executionResponse;
    expect(executed.status()).toBe(200);
    const adoptionPid = (await adoption.json()).data.data.record.pid;
    expect(executed.request().postDataJSON().analyticsExecution).toEqual({
      adoptionPid,
      requestId: expect.any(String),
    });
    await executed.finished();
    expect(await executed.text()).not.toContain('event:error');
    expect(executionRequests).toBe(1);
    const linked = await db.query(
      `SELECT t.description, r.pid, r.run_status, a.binding
      FROM ab_analytics_task_execution a JOIN ab_agent_task t ON t.pid=a.task_pid AND t.tenant_id=a.tenant_id
      JOIN ab_agent_run r ON r.task_id=t.pid AND r.tenant_id=t.tenant_id WHERE a.adoption_pid=$1`,
      [adoptionPid],
    );
    expect(linked.rows).toHaveLength(1);
    expect(linked.rows[0]).toMatchObject({
      description: proposal.executionIntent.goal,
      run_status: 'success',
      binding: { adoptionPid, versionPid: row.pid },
    });
    const started = await db.query(
      "SELECT caused_by_event_id FROM ab_behavior_outcome_outbox WHERE run_id=$1 AND event_name='agent_execution_started'",
      [linked.rows[0].pid],
    );
    expect(started.rows).toHaveLength(1);
    const adoptedEvent = await db.query(
      "SELECT event_id FROM ab_behavior_outcome_outbox WHERE interaction_id=$1 AND event_name='analytics_suggestion_adopted'",
      [analysisId],
    );
    expect(started.rows[0].caused_by_event_id).toBe(adoptedEvent.rows[0].event_id);
    const terminal = await db.query(
      "SELECT interaction_id, payload->>'status' AS status FROM ab_behavior_outcome_outbox WHERE run_id=$1 AND event_name='agent_execution_completed'",
      [linked.rows[0].pid],
    );
    expect(terminal.rows).toEqual([{ interaction_id: analysisId, status: 'success' }]);
    await expect(input).toBeEnabled();
    const executionMessage = panel
      .getByTestId('chat-msg-user')
      .filter({ hasText: '执行已采纳建议：' + marker });
    await expect(executionMessage).toContainText(marker);
    await executionMessage.scrollIntoViewIfNeeded();
    await expect(executionMessage).toBeInViewport();
    await expect(
      panel.getByTestId('chat-msg-agent').filter({ hasText: 'tool-output' }),
    ).toHaveCount(0);
    await shot('ai-executed');
    await suggestions.getByRole('button', { name: '刷新建议', exact: true }).click();
    await expect(version.getByTestId('analytics-execution-status')).toContainText('执行成功');
    await expect(version.getByTestId('analytics-execution-status')).toContainText('运行次数: 1');
    await expect(version.getByRole('button', { name: '发起执行', exact: true })).toHaveCount(0);
    const persistedStatus = await page.request.get('/api/analytics/suggestions', {
      params: { analysisId: analysisId! },
    });
    expect((await persistedStatus.json()).data.records[0].execution).toEqual({
      state: 'success',
      attempts: 1,
    });
    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.locator('header[data-hydrated="true"]')).toBeVisible();
    await page.getByTestId('ai-panel-toggle').click();
    const restored = page
      .locator(`[data-testid="chatbi-result-card"][data-analysis-id="${analysisId}"]`)
      .getByTestId('analytics-execution-status');
    await expect(restored).toContainText('执行成功', { timeout: 15000 });
    await restored.scrollIntoViewIfNeeded();
    await expect(
      panel.getByTestId('chat-msg-agent').filter({ hasText: 'tool-output' }),
    ).toHaveCount(0);
    await shot('ai-status');
    await panel.getByTestId('aurabot-history-trigger').click();
    await panel.getByTestId('aurabot-new-session').click();
    const secondConversationResponse = page.waitForResponse(
      (r) => r.request().method() === 'POST' && r.url().endsWith('/api/ai/aurabot/conversations'),
    );
    await input.fill('Start an independent analysis conversation.');
    const secondTurn = page.waitForResponse(
      (r) => r.request().method() === 'POST' && r.url().endsWith('/api/ai/aurabot/chat/stream'),
    );
    await input.press('Enter');
    const secondCreated = await secondConversationResponse;
    expect(secondCreated.status()).toBe(200);
    expect(secondCreated.request().postDataJSON().newConversation).toBe(true);
    const secondConversationId = (await secondCreated.json()).data.conversationId;
    expect(secondConversationId).not.toBe(firstConversationId);
    await (await secondTurn).finished();
    await expect(panel.getByTestId('chatbi-result-card')).toHaveCount(0);
    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.locator('header[data-hydrated="true"]')).toBeVisible();
    await page.getByTestId('ai-panel-toggle').click();
    await expect(panel.getByTestId('chat-msg-user')).toHaveText(
      'Start an independent analysis conversation.',
    );
    await expect(panel.getByTestId('chatbi-result-card')).toHaveCount(0);
    await shot('new-isolated');
    await panel.getByTestId('aurabot-history-trigger').click();
    await panel.getByTestId(`aurabot-session-${firstConversationId}`).click();
    await expect(restored).toContainText('执行成功');
    await restored.scrollIntoViewIfNeeded();
    await shot('history-restored');
  } finally {
    await db.end();
  }
});
