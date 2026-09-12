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
      content: 'Review this analysis before deciding on a business action.',
      executionIntent: {
        type: 'agent_task',
        goal: 'Review the selected orders and report findings.',
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
    expect(original.status()).toBe(200);
    expect((await original.json()).data.core_dashboard_content).toBe(proposal.content);
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
    expect(genericCreate.status(), await genericCreate.text()).toBeGreaterThanOrEqual(400);
    expect(await genericCreate.text()).toContain(
      'can only be created through an authorized command',
    );
    const changed = await request.put(`/api/dynamic/core_dashboard_suggestion/${first.pid}`, {
      data: { core_dashboard_content: 'Forged replacement' },
    });
    expect(changed.status(), await changed.text()).toBeGreaterThanOrEqual(400);
    expect(await changed.text()).toContain('immutable');
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
      Number(
        (
          await db.query(
            'SELECT count(*) FROM ab_behavior_outcome_outbox WHERE interaction_id=$1',
            [analysis.analysisId],
          )
        ).rows[0].count,
      ),
    ).toBe(3);
  } finally {
    await db.end();
  }
});
