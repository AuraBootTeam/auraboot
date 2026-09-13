/** Public API fixtures for AgentApprovalRollbackIT; this is a provisioning helper, not an acceptance test. */
import { request as requestFactory, expect } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { writeFile } from 'node:fs/promises';
import { Client } from 'pg';
import { PG_CONN } from './environments';

async function main() {
  const path = process.env.AURA_APPROVAL_ROLLBACK_FIXTURES;
  if (!path) throw new Error('AURA_APPROVAL_ROLLBACK_FIXTURES is required');
  const request = await requestFactory.newContext({
    baseURL: process.env.PLAYWRIGHT_BASE_URL,
    storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
  });
  const db = new Client(PG_CONN);
  await db.connect();
  const fixtures = [];
  const create = async (model: string, data: Record<string, unknown>) => {
    const response = await request.post(`/api/dynamic/${model}/create`, { data });
    expect(response.status(), await response.text()).toBe(200);
    const body = await response.json();
    expect(String(body.code)).toBe('0');
    return body.data;
  };
  try {
    for (const mode of ['reject', 'expired']) {
      const tag = randomUUID().replaceAll('-', '').slice(0, 16);
      const code = `rollback_${tag}`,
        tool = `custom:${code}`,
        agent = `agent_${tag}`;
      const createdTool = await create('agent-tool', {
        tool_code: code,
        tool_name: code,
        tool_description: 'Approval transaction fixture',
        tool_type: 'api_call',
        source_code: `GET ${process.env.BACKEND_URL}/actuator/health`,
        input_schema: JSON.stringify({
          type: 'object',
          properties: { marker: { type: 'string' } },
        }),
        requires_approval: true,
        risk_level: 'L3',
        tool_status: 'active',
      });
      try {
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
        const rows = (
          await db.query(
            'SELECT tenant_id, created_by FROM ab_agent_definition WHERE agent_code=$1',
            [agent],
          )
        ).rows;
        expect(rows).toHaveLength(1);
        const owner = rows[0];
        await create('approval-policy', {
          policy_name: code,
          trigger_rules: JSON.stringify([{ type: 'tool_call', pattern: tool }]),
          approver_rules: JSON.stringify([{ type: 'USER', userId: owner.created_by }]),
          policy_status: 'active',
          auto_approve: false,
          timeout_hours: mode === 'expired' ? 0 : 24,
          timeout_action: 'reject',
        });
        const response = await request.post('/api/ai/aurabot/chat/stream', {
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
        expect(response.status()).toBe(200);
        expect(await response.text()).toContain('event:confirm_required');
        const approvals = (
          await db.query(
            'SELECT pid, task_id, run_id, approval_status FROM ab_agent_approval WHERE tenant_id=$1 AND approval_description=$2',
            [owner.tenant_id, `Tool: ${tool}`],
          )
        ).rows;
        expect(approvals).toHaveLength(1);
        expect(approvals[0].approval_status).toBe('pending');
        fixtures.push({
          mode,
          tenantId: String(owner.tenant_id),
          actorId: String(owner.created_by),
          ...approvals[0],
        });
      } finally {
        const retired = await request.put(`/api/dynamic/agent-tool/${createdTool.pid}`, {
          data: { tool_status: 'inactive' },
        });
        expect(retired.status()).toBe(200);
        expect(String((await retired.json()).code)).toBe('0');
      }
    }
    await writeFile(path, JSON.stringify(fixtures, null, 2));
    console.log('Prepared two pending approval fixtures through public APIs.');
  } finally {
    await request.dispose();
    await db.end();
  }
}
void main();
