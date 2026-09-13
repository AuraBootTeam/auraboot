/** Real query provenance, published commands, immutable dynamic records and outbox facts. */
import { test, expect, request as requestFactory } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { resolve } from 'node:path';
import { Client } from 'pg';
import { PG_CONN } from '../helpers/environments';

test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

test('source-bound suggestion versions and explicit adoption remain immutable and idempotent', async ({
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
  const outcomeTitle = `${marker}-followup`;
  for (const title of [marker, marker, `${marker}-excluded`]) {
    const fixture = await request.post('/api/dynamic/e2et_order/create', {
      data: {
        e2et_order_title: title,
        e2et_order_type: 'normal',
        e2et_order_urgent: false,
        e2et_order_status: 'draft',
      },
    });
    expect(fixture.status(), await fixture.text()).toBe(200);
  }
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
  expect(analysis.records).toHaveLength(1);
  expect(analysis.records[0].e2et_order_title).toBe(marker);
  expect(Number(analysis.records[0].cnt)).toBe(2);
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
    const execute = async (
      command: string,
      payload: Record<string, unknown>,
      targetRecordId?: string,
    ) => {
      const response = await request.post(`/api/meta/commands/execute/core_dashboard:${command}`, {
        data: { payload, targetRecordId },
      });
      const body = await response.json();
      expect(response.status(), JSON.stringify(body)).toBe(200);
      expect(String(body.code), JSON.stringify(body)).toBe('0');
      return body.data.data.record;
    };
    const proposal = {
      analysisId: analysis.analysisId,
      query: analysis.dataSource,
      title: marker,
      content: 'Create one follow-up test order after reviewing the selected orders.',
      executionIntent: {
        type: 'agent_task',
        goal:
          'Create one follow-up test order using the existing order command.\n@@AURABOOT_STUB_TOOL_USE@@ ' +
          JSON.stringify({
            name: 'cmd:e2et:create_order',
            input: {
              e2et_order_title: outcomeTitle,
              e2et_order_type: 'normal',
              e2et_order_customer: 'Analysis follow-up fixture',
              e2et_order_urgent: false,
            },
          }),
      },
      requestId: randomUUID(),
    };
    const first = await execute('propose_suggestion', proposal);
    expect(first.core_dashboard_version, JSON.stringify(first)).toBe(1);
    expect(first.core_dashboard_origin).toBe('human_authored');
    expect(first.core_dashboard_analysis_id).toBe(analysis.analysisId);
    const storedIntent = (value: unknown) =>
      typeof value === 'string' ? JSON.parse(value) : value;
    expect(storedIntent(first.core_dashboard_execution_intent)).toEqual(proposal.executionIntent);
    const intentConflict = await request.post(
      '/api/meta/commands/execute/core_dashboard:propose_suggestion',
      {
        data: {
          payload: {
            ...proposal,
            executionIntent: { ...proposal.executionIntent, goal: 'Changed task' },
          },
        },
      },
    );
    expect(intentConflict.status()).toBe(400);
    expect(await intentConflict.text()).toContain('Request identity was already used');
    for (const executionIntent of [
      { type: 'raw_sql', goal: 'Execute arbitrary SQL' },
      { type: 'agent_task', goal: ' ' },
      { type: 'agent_task', goal: 'Review', runPid: first.pid },
    ]) {
      const invalidIntent = await request.post(
        '/api/meta/commands/execute/core_dashboard:propose_suggestion',
        {
          data: { payload: { ...proposal, requestId: randomUUID(), executionIntent } },
        },
      );
      expect(invalidIntent.status(), await invalidIntent.text()).toBe(400);
    }
    const replay = await execute('propose_suggestion', proposal);
    expect(replay.pid).toBe(first.pid);
    const revised = await execute(
      'propose_suggestion',
      {
        ...proposal,
        executionIntent: undefined,
        content: 'Revised recommendation after review.',
        previousPid: first.pid,
        requestId: randomUUID(),
      },
      first.pid,
    );
    expect(revised.core_dashboard_version).toBe(2);
    expect(storedIntent(revised.core_dashboard_execution_intent)).toEqual(proposal.executionIntent);
    expect(revised.core_dashboard_group_key).toBe(first.core_dashboard_group_key);
    expect(revised.core_dashboard_previous_pid).toBe(first.pid);
    const original = await request.get(`/api/dynamic/core_dashboard_suggestion/${first.pid}`);
    expect(original.status()).toBe(403);
    const decision = { versionPid: first.pid, requestId: randomUUID() };
    const adopted = await execute('adopt_suggestion', decision);
    expect(adopted.core_dashboard_version_pid).toBe(first.pid);
    expect(adopted.core_dashboard_version).toBe(1);
    expect(adopted.core_dashboard_decision_mode).toBe('human');
    expect((await execute('adopt_suggestion', decision)).pid).toBe(adopted.pid);
    const firstPage = await request.get('/api/analytics/suggestions', {
      params: { analysisId: analysis.analysisId, page: 1, pageSize: 1 },
    });
    expect(firstPage.status()).toBe(200);
    expect((await firstPage.json()).data).toMatchObject({
      total: 2,
      page: 1,
      pageSize: 1,
      records: [{ pid: revised.pid, version: 2, adoptionPid: null }],
    });
    const secondPage = await request.get('/api/analytics/suggestions', {
      params: { analysisId: analysis.analysisId, page: 2, pageSize: 1 },
    });
    expect(secondPage.status()).toBe(200);
    const pageData = (await secondPage.json()).data;
    expect(pageData).toMatchObject({
      total: 2,
      page: 2,
      pageSize: 1,
      records: [{ pid: first.pid, version: 1, adoptionPid: adopted.pid, decisionMode: 'human' }],
    });
    expect(pageData.records[0].content).toBe(proposal.content);
    expect(Object.keys(pageData.records[0]).sort()).toEqual(
      [
        'pid',
        'title',
        'content',
        'version',
        'groupKey',
        'origin',
        'adoptionPid',
        'decisionMode',
        'executionGoal',
        'execution',
      ].sort(),
    );
    for (const params of [
      { page: 0, pageSize: 1 },
      { page: 1, pageSize: 51 },
    ]) {
      const invalid = await request.get('/api/analytics/suggestions', {
        params: { analysisId: analysis.analysisId, ...params },
      });
      expect(invalid.status()).toBe(400);
    }
    const beyond = await request.get('/api/analytics/suggestions', {
      params: { analysisId: analysis.analysisId, page: 3, pageSize: 1 },
    });
    expect(beyond.status()).toBe(200);
    expect((await beyond.json()).data).toMatchObject({ total: 2, records: [] });
    const conflict = await request.post(
      '/api/meta/commands/execute/core_dashboard:adopt_suggestion',
      { data: { payload: { ...decision, versionPid: revised.pid } } },
    );
    expect(conflict.status(), await conflict.text()).toBe(400);
    const forged = await request.post(
      '/api/meta/commands/execute/core_dashboard:propose_suggestion',
      { data: { payload: { ...proposal, analysisId: randomUUID(), requestId: randomUUID() } } },
    );
    expect(forged.status(), await forged.text()).toBe(400);
    const genericCreate = await request.post('/api/dynamic/core_dashboard_suggestion/create', {
      data: { ...first, pid: undefined, id: undefined },
    });
    expect(genericCreate.status(), await genericCreate.text()).toBe(403);
    expect(await genericCreate.text()).toContain(
      'Raw analytics records require the authorized analytics service',
    );
    const changed = await request.put(`/api/dynamic/core_dashboard_suggestion/${first.pid}`, {
      data: { core_dashboard_content: 'Forged replacement' },
    });
    expect(changed.status(), await changed.text()).toBe(403);
    expect(await changed.text()).toContain(
      'Raw analytics records require the authorized analytics service',
    );
    const preserved = await request.get('/api/analytics/suggestions', {
      params: { analysisId: analysis.analysisId, page: 2, pageSize: 1 },
    });
    expect(preserved.status()).toBe(200);
    expect((await preserved.json()).data.records[0].content).toBe(proposal.content);
    const facts = await db.query(
      'SELECT event_id, event_name, caused_by_event_id, target_key, payload FROM ab_behavior_outcome_outbox WHERE interaction_id=$1 ORDER BY id',
      [analysis.analysisId],
    );
    expect(facts.rows.map((row) => row.event_name)).toEqual([
      'analytics_suggestion_proposed',
      'analytics_suggestion_proposed',
      'analytics_suggestion_adopted',
    ]);
    expect(facts.rows[2].payload.suggestionVersionPid).toBe(first.pid);
    expect(facts.rows[2].target_key).toBe(adopted.pid);
    expect(facts.rows[2].caused_by_event_id).toBe(facts.rows[0].event_id);
    await expect
      .poll(
        async () => {
          const stored = await db.query(
            "SELECT event_id FROM ab_behavior_event WHERE interaction_id=$1 AND producer_name='server-outcome-outbox' AND event_name IN ('analytics_suggestion_proposed', 'analytics_suggestion_adopted')",
            [analysis.analysisId],
          );
          return stored.rows.map((row) => row.event_id).sort();
        },
        { timeout: 15000 },
      )
      .toEqual(facts.rows.map((row) => row.event_id).sort());
    const executionFrom = new Date().toISOString();
    const dispatch = async () => {
      const response = await request.post('/api/ai/aurabot/chat/stream', {
        headers: { Accept: 'text/event-stream' },
        data: {
          sessionId: marker,
          clientMsgId: randomUUID(),
          message: proposal.executionIntent.goal,
          context: { modelCode: 'e2et_order', pageType: 'custom' },
          analyticsExecution: { adoptionPid: adopted.pid, requestId: randomUUID() },
          options: { provider: 'stub', model: 'stub-model' },
        },
      });
      expect(response.status(), await response.text()).toBe(200);
      return response.text();
    };
    const businessRecords = async () =>
      (
        await db.query(
          'SELECT pid, e2et_order_title, e2et_order_status FROM mt_e2et_order WHERE e2et_order_title=$1',
          [outcomeTitle],
        )
      ).rows;
    expect(await businessRecords()).toEqual([]);
    const completed = await dispatch();
    expect(completed).toContain('event:done');
    expect(completed).not.toContain('event:error');
    const executionRows = async () =>
      (
        await db.query(
          `SELECT a.task_pid, a.binding, a.goal, r.pid AS run_pid, r.run_status
       FROM ab_analytics_task_execution a JOIN ab_agent_run r
         ON r.tenant_id=a.tenant_id AND r.task_id=a.task_pid
       WHERE a.adoption_pid=$1`,
          [adopted.pid],
        )
      ).rows;
    const linked = await executionRows();
    expect(linked).toHaveLength(1);
    expect(linked[0].run_status).toBe('success');
    await expect
      .poll(
        async () =>
          (
            await db.query(
              "SELECT detail::jsonb->>'status' AS status FROM ab_agent_observation WHERE source_id=$1 AND obs_title=$2",
              [linked[0].run_pid, `run_completed: ${linked[0].run_pid}`],
            )
          ).rows,
      )
      .toEqual([{ status: 'success' }]);
    if (process.env.AURA_ANALYTICS_EXPECT_COMMAND_ROLLBACK === 'true') {
      test.info().annotations.push({
        type: 'fault-injection',
        description: 'Actual outbox post-insert failure',
      });
      expect(await businessRecords()).toEqual([]);
      const committed = await db.query(
        'SELECT event_id FROM ab_behavior_outcome_outbox WHERE run_id=$1 AND event_name=$2',
        [linked[0].run_pid, 'analytics_business_command_committed'],
      );
      expect(committed.rows).toEqual([]);
      const failed = await db.query(
        'SELECT action_status, error_message FROM ab_agent_action WHERE run_id=$1 AND command_code=$2',
        [linked[0].run_pid, 'e2et:create_order'],
      );
      expect(failed.rows).toHaveLength(1);
      expect(failed.rows[0].action_status).toBe('failed');
      expect(failed.rows[0].error_message).toContain(
        'injected after analytics business outcome insert',
      );
      return;
    }
    const createdOrders = await businessRecords();
    expect(createdOrders).toHaveLength(1);
    expect(createdOrders[0]).toMatchObject({
      e2et_order_title: outcomeTitle,
      e2et_order_status: 'draft',
    });
    const actions = async () =>
      (
        await db.query(
          'SELECT pid, target_record_pid, target_model, action_status, after_snapshot FROM ab_agent_action WHERE run_id=$1 AND command_code=$2 ORDER BY pid',
          [linked[0].run_pid, 'e2et:create_order'],
        )
      ).rows;
    const recordedActions = await actions();
    expect(recordedActions).toHaveLength(1);
    expect(recordedActions[0]).toMatchObject({
      target_record_pid: createdOrders[0].pid,
      target_model: 'e2et_order',
      action_status: 'success',
      after_snapshot: {
        pid: createdOrders[0].pid,
        e2et_order_title: outcomeTitle,
        e2et_order_status: 'draft',
      },
    });
    expect(linked[0].goal).toBe(proposal.executionIntent.goal);
    expect(linked[0].binding).toMatchObject({
      adoptionPid: adopted.pid,
      analysisId: analysis.analysisId,
    });
    const executionFacts = (
      await db.query(
        `SELECT event_id, event_name, caused_by_event_id, interaction_id, target_type, target_key, payload FROM ab_behavior_outcome_outbox
       WHERE run_id=$1 ORDER BY id`,
        [linked[0].run_pid],
      )
    ).rows;
    expect(executionFacts.map((row) => row.event_name)).toEqual([
      'agent_execution_started',
      'analytics_business_command_committed',
      'agent_execution_completed',
    ]);
    expect(executionFacts[0].caused_by_event_id).toBe(facts.rows[2].event_id);
    expect(executionFacts[0].payload.analyticsExecution).toMatchObject({
      adoptionPid: adopted.pid,
      analysisId: analysis.analysisId,
    });
    expect(executionFacts[1].caused_by_event_id).toBe(executionFacts[0].event_id);
    expect(executionFacts[1]).toMatchObject({
      target_type: 'e2et_order',
      target_key: createdOrders[0].pid,
      payload: {
        commandCode: 'e2et:create_order',
        modelCode: 'e2et_order',
        recordPid: createdOrders[0].pid,
        operation: 'create',
        analyticsExecution: { adoptionPid: adopted.pid, analysisId: analysis.analysisId },
      },
    });
    expect(Object.keys(executionFacts[1].payload).sort()).toEqual(
      [
        'commandCode',
        'modelCode',
        'recordPid',
        'operation',
        'analyticsExecution',
        'principalType',
      ].sort(),
    );
    expect(executionFacts[2].caused_by_event_id).toBe(executionFacts[0].event_id);
    expect(executionFacts[2].payload.status).toBe('success');
    const businessResults = await request.get(
      `/api/analytics/suggestions/${adopted.pid}/business-results`,
    );
    expect(businessResults.status(), await businessResults.text()).toBe(200);
    const resultPage = (await businessResults.json()).data;
    expect(resultPage).toMatchObject({ hasMore: false, page: 1, pageSize: 10 });
    expect(resultPage.records).toHaveLength(1);
    expect(resultPage.records[0]).toMatchObject({
      eventId: executionFacts[1].event_id,
      operation: 'create',
    });
    expect(Object.keys(resultPage.records[0]).sort()).toEqual(
      ['eventId', 'modelLabel', 'operation', 'recordedAt'].sort(),
    );
    const nextResults = await request.get(
      `/api/analytics/suggestions/${adopted.pid}/business-results`,
      { params: { page: 2 } },
    );
    expect(nextResults.status()).toBe(200);
    expect((await nextResults.json()).data.records).toEqual([]);

    for (const fact of executionFacts) expect(fact.interaction_id).toBe(analysis.analysisId);
    await expect
      .poll(
        async () =>
          (
            await db.query(
              'SELECT event_id FROM ab_behavior_event WHERE run_id=$1 AND interaction_id=$2 ORDER BY event_id',
              [linked[0].run_pid, analysis.analysisId],
            )
          ).rows.map((row) => row.event_id),
        { timeout: 15000 },
      )
      .toEqual(executionFacts.map((row) => row.event_id).sort());
    const projected = await request.get('/api/analytics/suggestions', {
      params: { analysisId: analysis.analysisId, page: 2, pageSize: 1 },
    });
    expect(projected.status()).toBe(200);
    expect((await projected.json()).data.records[0]).toMatchObject({
      pid: first.pid,
      adoptionPid: adopted.pid,
      execution: { state: 'success', attempts: 1 },
    });
    const statistics = await request.get('/api/analytics/behavior/executions', {
      params: { from: executionFrom, to: new Date().toISOString() },
    });
    expect(statistics.status()).toBe(200);
    expect((await statistics.json()).data.counts).toMatchObject({
      started: 1,
      succeeded: 1,
      failed: 0,
      unresolved: 0,
    });
    await dispatch();
    expect(await executionRows()).toEqual(linked);
    expect(await businessRecords()).toEqual(createdOrders);
    expect(await actions()).toEqual(recordedActions);
    expect(
      (
        await db.query(
          'SELECT event_id FROM ab_behavior_outcome_outbox WHERE run_id=$1 ORDER BY id',
          [linked[0].run_pid],
        )
      ).rows.map((row) => row.event_id),
    ).toEqual(executionFacts.map((row) => row.event_id));

    for (const role of ['tenant_member', 'tenant_admin']) {
      const email = `suggestion-${randomUUID()}@e2e.local`;
      const password = `Aa7!${randomUUID()}`;
      const user = await request.post('/api/admin/users', {
        data: {
          email,
          displayName: 'Suggestion permission fixture',
          initialPassword: password,
          roleCodes: [role],
          sendInviteEmail: false,
        },
      });
      expect(user.status()).toBe(200);
      const login = await request.post('/api/auth/login', { data: { email, password } });
      expect(login.status()).toBe(200);
      const jwt = (await login.json()).data.jwt;
      expect(jwt).toBeTruthy();
      const other = await requestFactory.newContext({
        baseURL: process.env.BACKEND_URL,
        extraHTTPHeaders: { Authorization: `Bearer ${jwt}` },
      });
      try {
        const foreignResults = await other.get(
          `/api/analytics/suggestions/${adopted.pid}/business-results`,
        );
        expect([400, 403]).toContain(foreignResults.status());
        expect(await foreignResults.text()).not.toContain(executionFacts[1].event_id);
        const foreignRead = await other.get('/api/analytics/suggestions', {
          params: { analysisId: analysis.analysisId },
        });
        expect(foreignRead.status()).toBe(role === 'tenant_member' ? 403 : 200);
        if (role === 'tenant_admin')
          expect((await foreignRead.json()).data).toMatchObject({ records: [], total: 0 });
        const denied = await other.post(
          '/api/meta/commands/execute/core_dashboard:adopt_suggestion',
          {
            data: { payload: { versionPid: first.pid, requestId: randomUUID() } },
          },
        );
        expect(denied.status(), await denied.text()).toBe(role === 'tenant_member' ? 403 : 400);
        if (role === 'tenant_admin') {
          expect(await denied.text()).toContain('Suggestion version is unavailable to this user');
          const foreignSource = await other.post(
            '/api/meta/commands/execute/core_dashboard:propose_suggestion',
            {
              data: { payload: { ...proposal, requestId: randomUUID() } },
            },
          );
          expect(foreignSource.status()).toBe(400);
          expect(await foreignSource.text()).toContain('Analysis source is unavailable');
        }
      } finally {
        await other.dispose();
      }
    }
    expect(
      (
        await db.query(
          'SELECT event_id FROM ab_behavior_outcome_outbox WHERE interaction_id=$1 ORDER BY event_id',
          [analysis.analysisId],
        )
      ).rows.map((row) => row.event_id),
    ).toEqual(
      [
        ...facts.rows.map((row) => row.event_id),
        ...executionFacts.map((row) => row.event_id),
      ].sort(),
    );
  } finally {
    await db.end();
  }
});
