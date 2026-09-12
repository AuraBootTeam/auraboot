/** Real query provenance, published commands, immutable dynamic records and outbox facts. */
import { test, expect } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { resolve } from 'node:path';
import { Client } from 'pg';
import { PG_CONN } from '../helpers/environments';

test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

test('AuraBot tool confirmation persists an AI suggestion without adopting it', async ({
  request,
}) => {
  test.setTimeout(120000);
  const imported = await request.post('/api/plugins/import/import-directory-sync', {
    data: {
      path: resolve(process.cwd(), '../plugins/core-dashboard'),
      conflictStrategy: 'OVERWRITE',
      validateReferences: true,
      autoPublishPages: true,
    },
  });
  expect(imported.status(), await imported.text()).toBe(200);
  const importResult = await imported.json();
  expect(importResult.success, JSON.stringify(importResult)).toBe(true);
  const marker = `suggestion-${randomUUID()}`;
  const fixture = await request.post('/api/dynamic/e2et_order/create', {
    data: {
      e2et_order_title: marker,
      e2et_order_type: 'normal',
      e2et_order_urgent: false,
      e2et_order_status: 'draft',
    },
  });
  expect(fixture.status()).toBe(200);
  const chat = await request.post('/api/ai/aurabot/chat/stream', {
    data: {
      sessionId: marker,
      clientMsgId: randomUUID(),
      message:
        '@@AURABOOT_STUB_TOOL_USE@@ ' +
        JSON.stringify({
          name: 'aurabot_chat-bi',
          input: {
            modelCode: 'e2et_order',
            chartType: 'table',
            dimensions: ['e2et_order_title'],
            metrics: [{ field: 'pid', aggregation: 'count', alias: 'cnt' }],
            filters: [{ field: 'e2et_order_title', operator: 'eq', value: marker }],
            limit: 5,
          },
        }),
    },
  });
  expect(chat.status()).toBe(200);
  const contracts = (await chat.text())
    .split(/\r?\n\r?\n/)
    .filter((block) => /^event:\s*result_contract/.test(block))
    .map((block) => {
      const decoded = JSON.parse(
        block
          .split(/\r?\n/)
          .filter((line) => line.startsWith('data:'))
          .map((line) => line.slice(5).trim())
          .join('\n'),
      );
      return typeof decoded === 'string' ? JSON.parse(decoded) : decoded;
    });
  const analysis = contracts.find((contract) => contract?.data?.data?.analysisId)?.data.data;
  expect(analysis, JSON.stringify(contracts)).toBeDefined();
  const db = new Client(PG_CONN);
  await db.connect();
  try {
    await expect
      .poll(async () =>
        Number(
          (
            await db.query(
              'SELECT count(*) FROM ab_behavior_event WHERE interaction_id=$1 AND event_name=$2',
              [analysis.analysisId, 'analytics_query_succeeded'],
            )
          ).rows[0].count,
        ),
      )
      .toBe(1);
    const parseEvents = (raw: string) =>
      raw
        .split(/\r?\n\r?\n/)
        .filter((block) => block.startsWith('event:'))
        .map((block) => {
          const lines = block.split(/\r?\n/);
          const event = lines[0].slice(6).trim();
          const rawData = lines
            .filter((line) => line.startsWith('data:'))
            .map((line) => line.slice(5).trim())
            .join('\n');
          const value = JSON.parse(rawData);
          return { event, data: typeof value === 'string' ? JSON.parse(value) : value };
        });
    const toolId = randomUUID();
    const proposal = {
      analysisId: analysis.analysisId,
      query: analysis.dataSource,
      title: 'AI analysis recommendation',
      content: 'Review the filtered orders before making a business decision.',
      executionIntent: {
        type: 'agent_task',
        goal: 'Review the selected order and report findings.',
      },
      requestId: randomUUID(),
    };
    const offered = await request.post('/api/ai/aurabot/chat/stream', {
      data: {
        sessionId: marker,
        clientMsgId: randomUUID(),
        context: { modelCode: 'core_dashboard_suggestion', pageType: 'custom' },
        message:
          '创建分析建议版本。\n@@AURABOOT_STUB_TOOL_USE@@ ' +
          JSON.stringify({
            id: toolId,
            name: 'cmd_core_dashboard_propose_suggestion',
            input: proposal,
          }),
      },
    });
    expect(offered.status()).toBe(200);
    const start = parseEvents(await offered.text());
    const confirm = start.find((event) => event.event === 'confirm_required')?.data;
    expect(confirm, JSON.stringify(start)).toBeTruthy();
    expect(confirm.toolName).toBe('cmd_core_dashboard_propose_suggestion');
    expect(confirm.pendingTurnId).toBeTruthy();
    const before = await request.get('/api/analytics/suggestions', {
      params: { analysisId: analysis.analysisId },
    });
    expect((await before.json()).data.records).toHaveLength(0);
    const resumed = await request.post('/api/ai/aurabot/execute', {
      data: {
        pendingTurnId: confirm.pendingTurnId,
        toolId: confirm.toolId,
        confirmed: true,
      },
    });
    expect(resumed.status()).toBe(200);
    const resume = parseEvents(await resumed.text());
    expect(
      resume.find((event) => event.event === 'tool_result')?.data.success,
      JSON.stringify(resume),
    ).toBe(true);
    const listed = await request.get('/api/analytics/suggestions', {
      params: { analysisId: analysis.analysisId },
    });
    expect(listed.status()).toBe(200);
    const rows = (await listed.json()).data.records;
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({
      origin: 'agent_generated',
      title: proposal.title,
      adoptionPid: null,
    });
    const facts = await db.query(
      'SELECT event_name, payload FROM ab_behavior_outcome_outbox WHERE interaction_id=$1 ORDER BY id',
      [analysis.analysisId],
    );
    expect(facts.rows).toHaveLength(1);
    expect(facts.rows[0]).toMatchObject({
      event_name: 'analytics_suggestion_proposed',
      payload: { proposalOrigin: 'agent_generated' },
    });
    const adopted = await request.post(
      '/api/meta/commands/execute/core_dashboard:adopt_suggestion',
      {
        data: { payload: { versionPid: rows[0].pid, requestId: randomUUID() } },
      },
    );
    expect(adopted.status()).toBe(200);
    const adoption = (await adopted.json()).data.data.record;
    expect(adoption.core_dashboard_decision_mode).toBe('ai_assisted');
    const after = await request.get('/api/analytics/suggestions', {
      params: { analysisId: analysis.analysisId },
    });
    expect((await after.json()).data.records[0]).toMatchObject({
      origin: 'agent_generated',
      adoptionPid: adoption.pid,
      decisionMode: 'ai_assisted',
    });
    const adoptedFacts = await db.query(
      'SELECT payload, event_id FROM ab_behavior_outcome_outbox WHERE interaction_id=$1 AND event_name=$2',
      [analysis.analysisId, 'analytics_suggestion_adopted'],
    );
    expect(adoptedFacts.rows).toHaveLength(1);
    expect(adoptedFacts.rows[0].payload).toMatchObject({
      decisionMode: 'ai_assisted',
      suggestionVersionPid: rows[0].pid,
    });
    const execution = { adoptionPid: adoption.pid, requestId: randomUUID() };
    const launch = async () =>
      request.post('/api/ai/aurabot/chat/stream', {
        data: {
          sessionId: marker,
          clientMsgId: randomUUID(),
          message: 'Client text must not replace the frozen goal',
          analyticsExecution: execution,
        },
      });
    const started = await launch();
    expect(started.status()).toBe(200);
    const startedEvents = parseEvents(await started.text());
    expect(
      startedEvents.some((event) => event.event === 'error'),
      JSON.stringify(startedEvents),
    ).toBe(false);
    const tasks = await db.query(
      "SELECT pid, description, input_data::jsonb AS input_data FROM ab_agent_task WHERE input_data::jsonb->'analyticsExecution'->>'adoptionPid'=$1",
      [adoption.pid],
    );
    expect(tasks.rows).toHaveLength(1);
    const task = tasks.rows[0];
    expect(task.description).toBe(proposal.executionIntent.goal);
    expect(task.input_data.analyticsExecution).toMatchObject({
      adoptionPid: adoption.pid,
      versionPid: rows[0].pid,
      analysisId: analysis.analysisId,
      decisionMode: 'ai_assisted',
    });
    const bindings = await db.query(
      'SELECT task_pid, goal, binding FROM ab_analytics_task_execution WHERE adoption_pid=$1',
      [adoption.pid],
    );
    expect(bindings.rows).toHaveLength(1);
    expect(bindings.rows[0].task_pid).toBe(task.pid);
    expect(bindings.rows[0].goal).toBe(proposal.executionIntent.goal);
    expect(bindings.rows[0].binding).toEqual(task.input_data.analyticsExecution);
    const runs = await db.query('SELECT pid, run_status FROM ab_agent_run WHERE task_id=$1', [
      task.pid,
    ]);
    expect(runs.rows).toHaveLength(1);
    expect(runs.rows[0].run_status).toBe('success');
    const startFacts = await db.query(
      'SELECT payload, caused_by_event_id FROM ab_behavior_outcome_outbox WHERE run_id=$1 AND event_name=$2',
      [runs.rows[0].pid, 'agent_execution_started'],
    );
    expect(startFacts.rows).toHaveLength(1);
    expect(startFacts.rows[0].payload.analyticsExecution).toMatchObject({
      adoptionPid: adoption.pid,
      versionPid: rows[0].pid,
    });
    expect(startFacts.rows[0].caused_by_event_id).toBe(adoptedFacts.rows[0].event_id);
    const repeated = await launch();
    expect(repeated.status()).toBe(200);
    expect(await repeated.text()).toContain('already has a task');
    execution.requestId = randomUUID();
    const freshIdentity = await launch();
    expect(freshIdentity.status()).toBe(200);
    expect(await freshIdentity.text()).toContain('already has a task');
    expect(
      (
        await db.query('SELECT count(*) FROM ab_analytics_task_execution WHERE adoption_pid=$1', [
          adoption.pid,
        ])
      ).rows[0].count,
    ).toBe('1');
    expect(
      (await db.query('SELECT count(*) FROM ab_agent_run WHERE task_id=$1', [task.pid])).rows[0]
        .count,
    ).toBe('1');
  } finally {
    await db.end();
  }
});
