import { randomUUID } from 'node:crypto';
import { expect, test, type APIRequestContext } from '@playwright/test';
import { Client } from 'pg';
import { BACKEND_URL, PG_CONN } from '../../helpers/environments';

const TEST_ACCOUNT = { email: 'admin@auraboot.com', password: 'Test2026x' };
const STUB_TOOL_USE_MARKER = '@@AURABOOT_STUB_TOOL_USE@@';

type AuthContext = {
  jwt: string;
  tenantId: string;
  userId: string;
};

type SseEvent = {
  event: string;
  data: unknown;
};

type SseResponse = {
  ok: boolean;
  status: number;
  text: string;
};

function uniqueModelCode(): string {
  return `it_e2e_${Date.now().toString(36)}_${randomUUID().replace(/-/g, '').slice(-8)}`;
}

function uniquePid(prefix: string): string {
  return `${prefix}${randomUUID().replace(/-/g, '').slice(0, 26 - prefix.length)}`;
}

function apiHeaders(jwt: string): Record<string, string> {
  return {
    Authorization: `Bearer ${jwt}`,
    'Content-Type': 'application/json',
  };
}

function decodeJwtPayload(jwt: string): Record<string, unknown> {
  const payload = jwt.split('.')[1];
  const pad = '='.repeat((4 - (payload.length % 4)) % 4);
  const json = Buffer.from(payload.replace(/-/g, '+').replace(/_/g, '/') + pad, 'base64')
    .toString('utf8');
  return JSON.parse(json);
}

function stringClaim(value: unknown): string | undefined {
  return value === undefined || value === null ? undefined : String(value);
}

async function postSse(jwt: string, path: string, data: unknown): Promise<SseResponse> {
  const response = await fetch(`${BACKEND_URL}${path}`, {
    method: 'POST',
    headers: {
      ...apiHeaders(jwt),
      Accept: 'text/event-stream',
    },
    body: JSON.stringify(data),
    signal: AbortSignal.timeout(60_000),
  });
  return {
    ok: response.ok,
    status: response.status,
    text: await response.text(),
  };
}

async function login(request: APIRequestContext): Promise<AuthContext> {
  const login = await request.post(`${BACKEND_URL}/api/auth/login`, {
    data: TEST_ACCOUNT,
  });
  expect(login.ok(), 'admin login should succeed').toBe(true);
  const body = await login.json();
  expect(String(body.code), JSON.stringify(body)).toBe('0');

  const data = body.data ?? {};
  let jwt = data.jwt as string;
  let payload = decodeJwtPayload(jwt);
  let tenantId = stringClaim(data.tenantId) ?? stringClaim(payload.tenantId);

  if (!tenantId) {
    const spaces = await request.get(`${BACKEND_URL}/api/tenant-selection/my-spaces`, {
      headers: apiHeaders(jwt),
    });
    expect(spaces.ok(), 'tenant spaces should be available').toBe(true);
    const spacesBody = await spaces.json();
    const businessSpace = (spacesBody.data ?? []).find(
      (space: any) => space.spaceType === 'business' && space.tenantId,
    );
    expect(businessSpace, JSON.stringify(spacesBody)).toBeTruthy();

    const selected = await request.post(`${BACKEND_URL}/api/tenant-selection/process`, {
      headers: apiHeaders(jwt),
      data: { action: 'select', tenantId: businessSpace.tenantId },
    });
    expect(selected.ok(), 'business tenant selection should succeed').toBe(true);
    const selectedBody = await selected.json();
    jwt = selectedBody.data.jwt;
    payload = decodeJwtPayload(jwt);
    tenantId = stringClaim(selectedBody.data.tenantId) ?? stringClaim(payload.tenantId);
  }

  const userId = stringClaim(data.userId);
  expect(jwt).toBeTruthy();
  expect(tenantId).toBeTruthy();
  expect(userId).toBeTruthy();
  return { jwt, tenantId: tenantId!, userId: userId! };
}

function parseSse(text: string): SseEvent[] {
  const events: SseEvent[] = [];
  let currentEvent = 'message';

  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trimEnd();
    if (line.startsWith('event:')) {
      currentEvent = line.slice('event:'.length).trim();
      continue;
    }
    if (!line.startsWith('data:')) continue;

    const rawData = line.slice('data:'.length).trim();
    let data: unknown = rawData;
    try {
      data = JSON.parse(rawData);
    } catch {
      // Spring may serialize Map data as a non-JSON string in some SSE adapters.
      data = rawData;
    }
    events.push({ event: currentEvent, data });
  }

  return events;
}

async function psql(sql: string): Promise<string> {
  const client = new Client(PG_CONN);
  await client.connect();
  try {
    const result = await client.query(sql);
    const rows = Array.isArray(result)
      ? result.flatMap((item) => item.rows ?? [])
      : result.rows ?? [];
    return rows
      .map((row) => Object.values(row).map((value) => String(value)).join('|'))
      .join('\n')
      .trim();
  } finally {
    await client.end();
  }
}

function sqlLiteral(value: string): string {
  return `'${value.replace(/'/g, "''")}'`;
}

async function cleanupModel(modelCode: string): Promise<void> {
  const tableName = `mt_${modelCode}`;
  await psql(`
DO $$
DECLARE
  target_model_id BIGINT;
  field_ids BIGINT[];
BEGIN
  EXECUTE 'DROP TABLE IF EXISTS ${tableName}';

  SELECT id INTO target_model_id
    FROM ab_meta_model
   WHERE code = ${sqlLiteral(modelCode)}
   LIMIT 1;

  IF target_model_id IS NOT NULL THEN
    SELECT ARRAY_AGG(field_id) INTO field_ids
      FROM ab_meta_model_field_binding
     WHERE ab_meta_model_field_binding.model_id = target_model_id
       AND (is_system_binding IS NULL OR is_system_binding = FALSE);

    DELETE FROM ab_meta_model_field_binding
     WHERE ab_meta_model_field_binding.model_id = target_model_id;

    IF field_ids IS NOT NULL THEN
      DELETE FROM ab_meta_field WHERE id = ANY(field_ids);
    END IF;

    DELETE FROM ab_meta_model WHERE id = target_model_id;
  END IF;
END $$;
`);
}

async function seedStubAgent(tenantId: string, agentCode: string): Promise<void> {
  const pid = uniquePid('agt');
  await psql(`
DELETE FROM ab_agent_definition
 WHERE tenant_id = ${sqlLiteral(tenantId)}
   AND agent_code = ${sqlLiteral(agentCode)};

INSERT INTO ab_agent_definition (
  pid, tenant_id, agent_code, name, description, agent_type,
  model, system_prompt, guardrails, status, visibility, deleted_flag
) VALUES (
  ${sqlLiteral(pid)},
  ${sqlLiteral(tenantId)},
  ${sqlLiteral(agentCode)},
  'E2E AuraBot Skill Resume Agent',
  'E2E agent that uses the stub LLM scripted tool_use path',
  'reactive',
  'stub-model',
  'Use the requested tool exactly when the user asks for model creation.',
  '{"provider":"stub"}',
  'active',
  'tenant',
  FALSE
);
`);
}

async function cleanupAgent(tenantId: string, agentCode: string): Promise<void> {
  await psql(`
DELETE FROM ab_agent_definition
 WHERE tenant_id = ${sqlLiteral(tenantId)}
   AND agent_code = ${sqlLiteral(agentCode)};
`);
}

test.describe('AuraBot skill resume runtime', () => {
  test.use({ storageState: { cookies: [], origins: [] } });

  test('chat/stream default provider emits done SSE event without error event', async ({ request }) => {
    const auth = await login(request);
    const streamed = await postSse(auth.jwt, '/api/ai/aurabot/chat/stream', {
      sessionId: `session-${randomUUID()}`,
      message: '统计客户信息',
      options: { stream: true },
    });

    expect(streamed.ok, streamed.text).toBe(true);
    const events = parseSse(streamed.text);
    const errorEvent = events.find((event) => event.event === 'error');
    expect(errorEvent, JSON.stringify(events)).toBeUndefined();
    expect(streamed.text).not.toContain('Invalid scheme');

    const done = events.find((event) => event.event === 'done')?.data as any;
    expect(done, JSON.stringify(events)).toBeTruthy();
    expect(String(done?.content ?? '')).not.toContain('Invalid scheme');
  });

  test('chat/stream creates pending skill preview and /execute confirms through canonical ToolLoopService', async ({
    request,
  }) => {
    const auth = await login(request);
    const modelCode = uniqueModelCode();
    const agentCode = `it_agent_${Date.now().toString(36)}_${randomUUID().replace(/-/g, '').slice(-6)}`;
    const sessionId = `session-${randomUUID()}`;
    const toolId = `tool-${randomUUID()}`;
    const params = {
      code: modelCode,
      displayName: `E2E Skill Resume ${modelCode}`,
      description: 'Created by AuraBot skill resume runtime E2E',
      modelCategory: 'ENTITY',
      domainCategory: 'test',
      dataSensitivity: 'INTERNAL',
    };

    try {
      await seedStubAgent(auth.tenantId, agentCode);

      const directive = `${STUB_TOOL_USE_MARKER} ${JSON.stringify({
        id: toolId,
        name: 'aurabot_model_create',
        input: params,
      })}`;
      const started = await postSse(auth.jwt, '/api/ai/aurabot/chat/stream', {
        sessionId,
        agentCode,
        message: `Create the requested test model through the scripted stub provider.\n${directive}`,
        options: { provider: 'stub', model: 'stub-model', maxTokens: 512 },
      });

      expect(started.ok, started.text).toBe(true);
      const startEvents = parseSse(started.text);
      const confirm = startEvents.find((event) => event.event === 'confirm_required')?.data as any;
      expect(confirm, JSON.stringify(startEvents)).toBeTruthy();
      expect(confirm?.toolId).toBe(toolId);
      expect(confirm?.toolName).toBe('aurabot_model_create');
      expect(confirm?.input?.code).toBe(modelCode);
      expect(confirm?.pendingTurnId, JSON.stringify(confirm)).toBeTruthy();

      const resumed = await postSse(auth.jwt, '/api/ai/aurabot/execute', {
        pendingTurnId: confirm.pendingTurnId,
        toolId,
        confirmed: true,
      });
      const resumedText = resumed.text;
      expect(resumed.ok, resumedText).toBe(true);
      const events = parseSse(resumedText);
      const toolResult = events.find((event) => event.event === 'tool_result')?.data as any;
      const done = events.find((event) => event.event === 'done')?.data as any;

      expect(toolResult, JSON.stringify(events)).toBeTruthy();
      expect(toolResult?.toolId).toBe(toolId);
      expect(toolResult?.success, JSON.stringify(events)).toBe(true);
      expect(toolResult?.result?.success, JSON.stringify(toolResult)).toBe(true);
      expect(toolResult?.result?.data?.modelCode).toBe(modelCode);
      expect(done?.content).toContain('[stub response]');

      const persisted = await psql(
        `SELECT code || '|' || status FROM ab_meta_model WHERE code = ${sqlLiteral(modelCode)} LIMIT 1;`,
      );
      expect(persisted).toContain(`${modelCode}|published`);
    } finally {
      await cleanupModel(modelCode);
      await cleanupAgent(auth.tenantId, agentCode);
    }
  });
});
