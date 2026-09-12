/** ACP approval configuration and dispatch use public APIs; SQL is read-only. */
import { test, expect, request as requestFactory } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { Client } from 'pg';
import { PG_CONN } from '../../helpers/environments';
test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
for (const mode of ['approve', 'reject', 'race']) {
  test(`ACP authorized decision after foreign-user denial: ${mode}`, async ({ request }) => {
    let approved = mode !== 'reject';
    test.setTimeout(90000);
    const tag = randomUUID().replaceAll('-', '').slice(0, 16);
    const code = `approved_${tag}`,
      tool = `custom:${code}`,
      agent = `agent_${tag}`;
    const create = async (model: string, data: Record<string, unknown>) => {
      const response = await request.post(`/api/dynamic/${model}/create`, { data });
      expect(response.status(), await response.text()).toBe(200);
      const body = await response.json();
      expect(String(body.code), JSON.stringify(body)).toBe('0');
      return body.data;
    };
    const createdTool = await create('agent-tool', {
      tool_code: code,
      tool_name: `Approved health ${tag}`,
      tool_description: 'Read owned runtime health after approval',
      tool_type: 'api_call',
      source_code: `GET ${process.env.BACKEND_URL}/actuator/health`,
      input_schema: JSON.stringify({
        type: 'object',
        properties: { marker: { type: 'string' } },
        required: ['marker'],
      }),
      requires_approval: true,
      risk_level: 'L3',
      tool_status: 'active',
    });
    await create('agent-definition', {
      agent_code: agent,
      name: agent,
      agent_type: 'reactive',
      model: 'stub-model',
      system_prompt: 'Execute the requested tool.',
      tools: JSON.stringify([tool]),
      guardrails: JSON.stringify({ provider: 'stub' }),
      status: 'active',
      visibility: 'tenant',
    });
    const db = new Client(PG_CONN);
    await db.connect();
    try {
      const owners = (
        await db.query(
          'SELECT tenant_id, created_by FROM ab_agent_definition WHERE agent_code=$1',
          [agent],
        )
      ).rows;
      expect(owners).toHaveLength(1);
      await create('approval-policy', {
        policy_name: `Approval ${tag}`,
        trigger_rules: JSON.stringify([{ type: 'tool_call', pattern: tool }]),
        approver_rules: JSON.stringify([{ type: 'USER', userId: owners[0].created_by }]),
        policy_status: 'active',
        auto_approve: false,
        timeout_hours: 24,
        timeout_action: 'reject',
      });
      const started = await request.post('/api/ai/aurabot/chat/stream', {
        headers: { Accept: 'text/event-stream' },
        data: {
          sessionId: randomUUID(),
          clientMsgId: randomUUID(),
          message:
            'Read the owned runtime health after explicit human approval and report the verified response for this task.\n@@AURABOOT_STUB_TOOL_USE@@ ' +
            JSON.stringify({ id: randomUUID(), name: tool, input: { marker: tag } }),
          options: {
            explicitDurableRequest: true,
            durableWorkflow: true,
            provider: 'stub',
            model: 'stub-model',
          },
        },
      });
      expect(started.status()).toBe(200);
      const firstText = await started.text();
      expect(firstText).toContain('event:confirm_required');
      const approvals = (
        await db.query(
          'SELECT pid, run_id, task_id, approval_status, consumed_at FROM ab_agent_approval WHERE tenant_id=$1 AND approval_description=$2',
          [owners[0].tenant_id, `Tool: ${tool}`],
        )
      ).rows;
      expect(approvals).toHaveLength(1);
      const approval = approvals[0];
      expect(approval.approval_status).toBe('pending');
      expect(approval.consumed_at).toBeNull();
      expect(approval.run_id).toBeTruthy();
      expect(approval.task_id).toBeTruthy();
      const before = (
        await db.query('SELECT run_status FROM ab_agent_run WHERE pid=$1', [approval.run_id])
      ).rows;
      expect(before).toHaveLength(1);
      expect(before[0].run_status).toBe('pending');
      const actions = async () =>
        (
          await db.query(
            'SELECT pid, run_id, action_status, command_result FROM ab_agent_action WHERE tenant_id=$1 AND command_code=$2',
            [owners[0].tenant_id, tool],
          )
        ).rows;
      expect(await actions()).toEqual([]);

      const roleCode = `approver_other_${tag}`;
      const role = await request.post('/api/roles', {
        data: {
          code: roleCode,
          name: roleCode,
          type: 'custom',
          status: 'active',
          scopeType: 'tenant',
          defaultDataScopeType: 'self',
        },
      });
      expect(role.status()).toBe(200);
      const email = `${roleCode}@e2e.local`,
        password = `Aa7!${randomUUID()}`;
      const user = await request.post('/api/admin/users', {
        data: {
          email,
          displayName: 'Other approver',
          initialPassword: password,
          roleCodes: [roleCode],
          sendInviteEmail: false,
        },
      });
      expect(user.status()).toBe(200);
      const login = await request.post('/api/auth/login', { data: { email, password } });
      expect(login.status()).toBe(200);
      const jwt = (await login.json()).data.jwt;
      const raw = Buffer.from(jwt.split('.')[1], 'base64url').toString('utf8');
      expect(raw.match(/"tenantId"\s*:\s*"?(\d+)/)?.[1]).toBe(String(owners[0].tenant_id));
      const users = (await db.query('SELECT id, pid FROM ab_user WHERE email=$1', [email])).rows;
      expect(users).toHaveLength(1);
      expect(JSON.parse(raw).sub).toBe(users[0].pid);
      expect(String(users[0].id)).not.toBe(String(owners[0].created_by));
      const foreign = await requestFactory.newContext({
        baseURL: process.env.BACKEND_URL,
        extraHTTPHeaders: { Authorization: `Bearer ${jwt}` },
      });
      try {
        for (const decision of [true, false]) {
          const denied = await foreign.post('/api/ai/aurabot/execute', {
            headers: { Accept: 'text/event-stream' },
            data: { pendingTurnId: approval.pid, confirmed: decision },
          });
          expect(denied.status()).toBe(200);
          expect(await denied.text()).toContain('Insufficient permission');
          const unchanged = (
            await db.query(
              'SELECT approval_status, consumed_at FROM ab_agent_approval WHERE pid=$1',
              [approval.pid],
            )
          ).rows;
          expect(unchanged[0]).toMatchObject({ approval_status: 'pending', consumed_at: null });
          expect(await actions()).toEqual([]);
          expect(
            (await db.query('SELECT pid FROM ab_agent_run WHERE task_id=$1', [approval.task_id]))
              .rows,
          ).toHaveLength(1);
        }
      } finally {
        await foreign.dispose();
      }

      const decide = async (decision: boolean) => {
        const response = await request.post('/api/ai/aurabot/execute', {
          headers: { Accept: 'text/event-stream' },
          data: { pendingTurnId: approval.pid, confirmed: decision },
        });
        expect(response.status()).toBe(200);
        return response.text();
      };
      let text: string;
      if (mode === 'race') {
        const results = await Promise.all([decide(true), decide(false)]);
        expect(results.filter((result) => result.includes('event:error'))).toHaveLength(1);
        expect(results.filter((result) => result.includes('event:done'))).toHaveLength(1);
        approved = results[0].includes('event:done');
        text = results[approved ? 0 : 1];
      } else {
        text = await decide(approved);
      }
      expect(text).not.toContain('event:error');
      expect(text).toContain('event:done');
      const after = (
        await db.query('SELECT approval_status, consumed_at FROM ab_agent_approval WHERE pid=$1', [
          approval.pid,
        ])
      ).rows;
      expect(after[0].approval_status).toBe(approved ? 'approved' : 'rejected');
      if (approved) expect(after[0].consumed_at).not.toBeNull();
      else expect(after[0].consumed_at).toBeNull();
      const runs = (
        await db.query(
          'SELECT pid, run_status FROM ab_agent_run WHERE task_id=$1 ORDER BY created_at',
          [approval.task_id],
        )
      ).rows;
      const executed = await actions();
      if (approved) {
        expect(runs).toHaveLength(2);
        expect(runs[1].pid).not.toBe(approval.run_id);
        expect(runs[1].run_status).toBe('success');
        expect(executed).toHaveLength(1);
        expect(executed[0]).toMatchObject({
          run_id: runs[1].pid,
          action_status: 'success',
          command_result: 'success',
        });
      } else {
        expect(runs).toHaveLength(1);
        expect(runs[0].run_status).toBe('failed');
        expect(executed).toEqual([]);
      }
      const replay = await request.post('/api/ai/aurabot/execute', {
        headers: { Accept: 'text/event-stream' },
        data: { pendingTurnId: approval.pid, confirmed: true },
      });
      expect(replay.status()).toBe(200);
      expect(await replay.text()).toContain('event:error');
      expect(await actions()).toEqual(executed);
      expect(
        (await db.query('SELECT pid FROM ab_agent_run WHERE task_id=$1', [approval.task_id])).rows,
      ).toHaveLength(approved ? 2 : 1);
    } finally {
      const retired = await request.put(`/api/dynamic/agent-tool/${createdTool.pid}`, {
        data: { tool_status: 'inactive' },
      });
      expect(retired.status(), await retired.text()).toBe(200);
      expect(String((await retired.json()).code)).toBe('0');
      await db.end();
    }
  });
}
