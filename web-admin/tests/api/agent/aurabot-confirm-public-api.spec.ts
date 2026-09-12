/** Public API setup and L2 confirmation; PostgreSQL is read-only evidence. */
import { test, expect, request as requestFactory } from '@playwright/test';
import { randomUUID } from 'node:crypto';
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
for (const mode of ['approve', 'deny', 'concurrent']) {
  const approved = mode !== 'deny';
  test(`public confirmation preserves owner and frozen input: ${mode}`, async ({ request }) => {
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
      const roleCode = `confirm_reader_${suffix}`;
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
      expect(role.status(), await role.text()).toBe(200);
      const email = `${roleCode}@e2e.local`,
        password = `Aa7!${randomUUID()}`;
      const stranger = await request.post('/api/admin/users', {
        data: {
          email,
          displayName: 'Other confirmation user',
          initialPassword: password,
          roleCodes: [roleCode],
          sendInviteEmail: false,
        },
      });
      expect(stranger.status(), await stranger.text()).toBe(200);
      const login = await request.post('/api/auth/login', { data: { email, password } });
      expect(login.status()).toBe(200);
      const jwt = (await login.json()).data.jwt;
      expect(jwt).toBeTruthy();
      const rawClaims = Buffer.from(jwt.split('.')[1], 'base64url').toString('utf8');
      const tenantClaim = rawClaims.match(/"tenantId"\s*:\s*"?(\d+)/)?.[1];
      const claims = JSON.parse(rawClaims);
      const agentRows = (
        await db.query(
          'SELECT tenant_id, created_by FROM ab_agent_definition WHERE agent_code=$1',
          [agentCode],
        )
      ).rows;
      const users = (await db.query('SELECT id, pid FROM ab_user WHERE email=$1', [email])).rows;
      expect(agentRows).toHaveLength(1);
      expect(users).toHaveLength(1);
      expect(tenantClaim).toBe(String(agentRows[0].tenant_id));
      expect(claims.sub).toBe(users[0].pid);
      expect(String(users[0].id)).not.toBe(String(agentRows[0].created_by));
      const foreign = await requestFactory.newContext({
        baseURL: process.env.BACKEND_URL,
        extraHTTPHeaders: { Authorization: `Bearer ${jwt}` },
      });
      try {
        const denied = await foreign.post('/api/ai/aurabot/execute', {
          headers: { Accept: 'text/event-stream' },
          data: { pendingTurnId: confirm.pendingTurnId, toolId, confirmed: true },
        });
        expect(denied.status()).toBe(200);
        const deniedEvents = events(await denied.text());
        expect(deniedEvents.some((e) => e.event === 'error')).toBe(true);
        expect(deniedEvents.some((e) => e.event === 'tool_result')).toBe(false);
        expect(await read()).toEqual([]);
      } finally {
        await foreign.dispose();
      }
      const forgedCode = `forged_${suffix}`;
      const payload = {
        pendingTurnId: confirm.pendingTurnId,
        toolId: 'untrusted-tool-id',
        confirmed: approved,
        toolName: 'untrusted_tool',
        input: { code: forgedCode },
      };
      const execute = async () => {
        const response = await request.post('/api/ai/aurabot/execute', {
          headers: { Accept: 'text/event-stream' },
          data: payload,
        });
        expect(response.status()).toBe(200);
        return events(await response.text());
      };
      let results: ReturnType<typeof events>;
      if (mode === 'concurrent') {
        const attempts = await Promise.all([execute(), execute()]);
        const successes = attempts.filter((attempt) =>
          attempt.some((e) => e.event === 'tool_result' && e.data.success === true),
        );
        const rejected = attempts.filter((attempt) => attempt.some((e) => e.event === 'error'));
        expect(successes).toHaveLength(1);
        expect(rejected).toHaveLength(1);
        expect(rejected[0].some((e) => e.event === 'tool_result')).toBe(false);
        results = successes[0];
      } else {
        results = await execute();
      }
      expect(results.some((e) => e.event === 'done')).toBe(true);
      expect(results.some((e) => e.event === 'error')).toBe(false);
      const saved = await read();
      if (approved) {
        const result = results.find((e) => e.event === 'tool_result')?.data;
        expect(result, JSON.stringify(results)).toBeTruthy();
        expect(result.toolId).toBe(toolId);
        expect(result.success).toBe(true);
        expect(result.result.success).toBe(true);
        expect(result.result.data.modelCode).toBe(modelCode);
        expect(saved).toHaveLength(1);
        expect(saved[0].status).toBe('published');
      } else {
        expect(results.some((e) => e.event === 'tool_result')).toBe(false);
        expect(saved).toEqual([]);
      }
      expect(
        (await db.query('SELECT pid FROM ab_meta_model WHERE code=$1', [forgedCode])).rows,
      ).toEqual([]);
      const replay = await request.post('/api/ai/aurabot/execute', {
        headers: { Accept: 'text/event-stream' },
        data: { ...payload, confirmed: true },
      });
      const replayText = await replay.text();
      expect(replay.status() >= 400 || replayText.includes('event:error'), replayText).toBe(true);
      expect(await read()).toEqual(saved);
    } finally {
      await db.end();
    }
  });
}
