/** Public API setup and L2 confirmation; PostgreSQL is read-only evidence. */
import { test, expect } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { Client } from 'pg';
import { PG_CONN } from '../../helpers/environments';
test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
function events(text: string): Array<{ event: string; data: any }> {
  let event = '';
  return text.split(/\r?\n/).flatMap((line) => {
    if (line.startsWith('event:')) event = line.slice(6).trim();
    return line.startsWith('data:') ? [{ event, data: JSON.parse(line.slice(5).trim()) }] : [];
  });
}
test('pending confirmation survives an externally verified backend restart', async ({
  request,
}) => {
  test.setTimeout(180000);
  const readyPath = process.env.CONFIRM_RESTART_READY;
  const acknowledgementPath = process.env.CONFIRM_RESTART_ACK;
  expect(readyPath, 'A unique supervisor readiness path is required').toBeTruthy();
  expect(acknowledgementPath, 'A unique supervisor acknowledgement path is required').toBeTruthy();
  expect(existsSync(readyPath!)).toBe(false);
  expect(existsSync(acknowledgementPath!)).toBe(false);
  const suffix = randomUUID().replaceAll('-', '').slice(0, 16);
  const agentCode = `confirm_${suffix}`,
    modelCode = `confirm_model_${suffix}`,
    toolId = randomUUID();
  const created = await request.post('/api/dynamic/agent-definition/create', {
    data: {
      agent_code: agentCode,
      name: `Confirmation ${suffix}`,
      agent_type: 'reactive',
      model: 'stub-model',
      system_prompt: 'Use the requested model creation tool.',
      guardrails: JSON.stringify({ provider: 'stub' }),
      status: 'active',
      visibility: 'tenant',
    },
  });
  expect(created.ok(), await created.text()).toBe(true);
  const body = await created.json();
  expect(String(body.code), JSON.stringify(body)).toBe('0');
  const input = {
    code: modelCode,
    displayName: `Confirmation ${suffix}`,
    description: 'Public API confirmation fixture',
    modelCategory: 'ENTITY',
    domainCategory: 'test',
    dataSensitivity: 'INTERNAL',
  };
  const db = new Client(PG_CONN);
  await db.connect();
  try {
    const read = async () =>
      (await db.query('SELECT pid, code, status FROM ab_meta_model WHERE code=$1', [modelCode]))
        .rows;
    expect(await read()).toEqual([]);
    const started = await request.post('/api/ai/aurabot/chat/stream', {
      headers: { Accept: 'text/event-stream' },
      data: {
        sessionId: randomUUID(),
        clientMsgId: randomUUID(),
        agentCode,
        message:
          '@@AURABOOT_STUB_TOOL_USE@@ ' +
          JSON.stringify({ id: toolId, name: 'aurabot_model_create', input }),
        options: { provider: 'stub', model: 'stub-model', maxTokens: 512 },
      },
    });
    expect(started.status()).toBe(200);
    const first = events(await started.text());
    const confirm = first.find((e) => e.event === 'confirm_required')?.data;
    expect(confirm, JSON.stringify(first)).toBeTruthy();
    expect(confirm.toolId).toBe(toolId);
    expect(confirm.input.code).toBe(modelCode);
    expect(confirm.pendingTurnId).toBeTruthy();
    expect(await read()).toEqual([]);
    // The supervisor restarts only this owned backend after preview exists.
    // No pending token, JWT, or tool input is written to the handshake files.
    writeFileSync(readyPath!, JSON.stringify({ ready: true }), { flag: 'wx' });
    await expect.poll(() => existsSync(acknowledgementPath!), { timeout: 120000 }).toBe(true);
    const acknowledgement = JSON.parse(readFileSync(acknowledgementPath!, 'utf8'));
    expect(acknowledgement.oldPid).toBeGreaterThan(0);
    expect(acknowledgement.newPid).toBeGreaterThan(0);
    expect(acknowledgement.newPid).not.toBe(acknowledgement.oldPid);
    expect(acknowledgement.health).toBe('UP');
    expect(await read()).toEqual([]);
    const payload = { pendingTurnId: confirm.pendingTurnId, toolId, confirmed: true };
    const resumed = await request.post('/api/ai/aurabot/execute', {
      headers: { Accept: 'text/event-stream' },
      data: payload,
    });
    expect(resumed.status()).toBe(200);
    const results = events(await resumed.text());
    const result = results.find((e) => e.event === 'tool_result')?.data;
    expect(result, JSON.stringify(results)).toBeTruthy();
    expect(result.success).toBe(true);
    expect(result.result.success).toBe(true);
    expect(result.result.data.modelCode).toBe(modelCode);
    expect(results.some((e) => e.event === 'done')).toBe(true);
    const saved = await read();
    expect(saved).toHaveLength(1);
    expect(saved[0].status).toBe('published');
    const replay = await request.post('/api/ai/aurabot/execute', {
      headers: { Accept: 'text/event-stream' },
      data: payload,
    });
    const replayText = await replay.text();
    expect(replay.status() >= 400 || replayText.includes('event:error'), replayText).toBe(true);
    expect(await read()).toEqual(saved);
  } finally {
    await db.end();
  }
});
