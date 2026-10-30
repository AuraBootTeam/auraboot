import { test, expect, request, type APIRequestContext } from '@playwright/test';
import { Client } from 'pg';
import { readFileSync, writeFileSync, appendFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { execFileSync, spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { assertRssBudget, assertSteadyTotals, canonicalJson, sseEvents, steadyContract } from './steady-contract';

type Fixture = {
  baseURL: string; backendURL: string; tenantId: string; bundleSha256: string; productIdentity: string;
  owner: { email: string; password: string; userId: string };
  database: { host: string; port: number; database: string; user: string; password: string };
  modelCode: string; businessPid: string; expectedBusiness: Record<string, string | number>;
  memory: { pid: string; value: string; foreignValue: string; expectedSpace: string; question: string };
  runtime: { name: string; workspace: string; processDirectory: string };
};
const fixture: Fixture = JSON.parse(readFileSync(process.env.AGENT_PERMISSION_FIXTURE!, 'utf8'));
const directory = process.env.AGENT_PERMISSION_EVIDENCE!;
const minuteMs = 60_000;
const processKeys = ['backend', 'bff', 'web', 'redis-owned', 'embedding'];
const delay = (ms: number) => new Promise(resolve => setTimeout(resolve, Math.max(0, ms)));

function ownedPid(key: string) {
  execFileSync(join(fixture.runtime.workspace, 'aura'), ['runtime', 'process', 'check', fixture.runtime.name, key], { stdio: 'pipe' });
  const parts = readFileSync(join(fixture.runtime.processDirectory, key + '.tsv'), 'utf8').trimEnd().split('\t');
  const pid = Number(parts[1]);
  if (parts[0] !== key || !Number.isSafeInteger(pid) || pid < 1) throw new Error('Invalid owned process descriptor');
  return pid;
}

function resources() {
  const processes = execFileSync('ps', ['-axo', 'pid=,ppid=,rss='], { encoding: 'utf8' }).trim().split('\n')
    .map(line => line.trim().split(/\s+/).map(Number));
  return Object.fromEntries(processKeys.map(key => {
    const pid = ownedPid(key), descendants = new Set([pid]);
    for (let changed = true; changed;) {
      changed = false;
      for (const [child, parent] of processes) if (descendants.has(parent) && !descendants.has(child)) {
        descendants.add(child); changed = true;
      }
    }
    const own = processes.find(row => row[0] === pid);
    if (!own || own[2] <= 0) throw new Error('Owned process RSS missing');
    return [key, { pid, rssKiB: processes.filter(row => descendants.has(row[0])).reduce((sum, row) => sum + row[2], 0) }];
  })) as Record<string, { pid: number; rssKiB: number }>;
}

test('two-hour product workload preserves truth, private recall, resource bounds and transport recovery', async ({ page }) => {
  test.setTimeout(steadyContract.minutes * minuteMs + 10 * minuteMs);
  mkdirSync(directory, { recursive: true });
  const identity = JSON.parse(readFileSync(fixture.productIdentity, 'utf8'));
  expect(identity.bundleSha256).toBe(fixture.bundleSha256);
  expect(identity.checks.database).toBe(fixture.database.database);
  expect(identity.checks.capabilities.agent.state).toBe('installed');
  expect(fixture.modelCode).toMatch(/^[a-z][a-z0-9_]{0,59}$/);
  expect(fixture.memory.question).not.toContain(fixture.memory.value);
  const database = new Client({ ...fixture.database, application_name: 'agent-steady-observer' });
  await database.connect();
  const clients: APIRequestContext[] = [];
  const sessions = Array.from({ length: 4 }, () => randomUUID());
  const journal = join(directory, 'observations.jsonl');
  const record = (value: unknown) => appendFileSync(journal, JSON.stringify(value) + '\n', { mode: 0o600 });
  const timings: number[] = [], turns: Array<{ traceId: string; sessionId: string; minute: number }> = [];
  const samples: Array<Record<string, { pid: number; rssKiB: number }>> = [];
  let reads = 0, faults = 0, stoppedBff: number | undefined;
  try {
    expect((await database.query('SELECT current_database() AS name')).rows[0].name).toBe(fixture.database.database);
    const before = (await database.query(`SELECT * FROM mt_${fixture.modelCode} WHERE pid=$1`, [fixture.businessPid])).rows;
    expect(before).toHaveLength(1);
    const changeCount = async () => Number((await database.query('SELECT count(*) AS n FROM ab_data_change_log WHERE tenant_id=$1 AND model_code=$2', [fixture.tenantId, fixture.modelCode])).rows[0].n);
    const changesBefore = await changeCount();
    for (let i = 0; i < 4; i++) {
      const client = await request.newContext({ baseURL: fixture.backendURL, timeout: 30_000, ignoreHTTPSErrors: false });
      clients.push(client);
      const login = await client.post('/api/auth/login', { data: { email: fixture.owner.email, password: fixture.owner.password }, maxRetries: 0 });
      expect(login.status()).toBe(200);
      const loginData = (await login.json()).data;
      const selected = await client.post('/api/tenant-selection/process', {
        headers: { Authorization: `Bearer ${loginData.jwt}` }, data: { action: 'select', tenantId: fixture.tenantId }, maxRetries: 0,
      });
      expect(selected.status()).toBe(200);
      const jwt = (await selected.json()).data.jwt;
      expect(jwt).toBeTruthy();
      await client.dispose();
      clients[i] = await request.newContext({ baseURL: fixture.backendURL, timeout: 30_000,
        extraHTTPHeaders: { Authorization: `Bearer ${jwt}` } });
    }
    // The UI context uses its own normal BFF session for the real transport fault.
    await page.goto('/login');
    await page.locator('input#identifier, input#email').first().fill(fixture.owner.email);
    await page.locator('input#password').fill(fixture.owner.password);
    await page.locator('form button[type=submit]').click();
    await expect(page.getByTestId('ai-panel-toggle')).toBeVisible({ timeout: 45_000 });
    const businessPath = `/api/dynamic/${fixture.modelCode}/${fixture.businessPid}`;
    const checkBusiness = async (client: APIRequestContext) => {
      const at = performance.now();
      const response = await client.get(businessPath, { timeout: 30_000, maxRetries: 0 });
      expect(response.status()).toBe(200);
      expect((await response.json()).data).toMatchObject(fixture.expectedBusiness);
      return performance.now() - at;
    };
    await checkBusiness(page.request);
    for (const client of clients) await checkBusiness(client);
    const started = performance.now();
    record({ type: 'start', at: new Date().toISOString(), contract: steadyContract, sessions, actorUserIds: Array(4).fill(fixture.owner.userId), note: 'Four independent request/conversation contexts of one real account; not a four-role qualification', resources: resources() });
    for (let minute = 0; minute < 120; minute++) {
      await delay(started + minute * minuteMs - performance.now());
      const durations = await Promise.all(clients.map(async client => {
        const businessMs = await checkBusiness(client); reads++;
        const at = performance.now();
        const memory = await client.get(`/api/admin/memory/embeddings/${fixture.memory.pid}?agentCode=aurabot`, { timeout: 30_000, maxRetries: 0 });
        expect(memory.status()).toBe(200);
        expect((await memory.json()).data).toMatchObject({ pid: fixture.memory.pid, status: 'current', expectedSpace: fixture.memory.expectedSpace });
        reads++;
        return { businessMs, memoryMs: performance.now() - at };
      }));
      timings.push(...durations.flatMap(row => [row.businessMs, row.memoryMs]));
      const sample = resources(); samples.push(sample);
      record({ type: 'minute', minute, elapsedMs: performance.now() - started, durations, resources: sample });
      if (minute === 60) {
        const pid = ownedPid('bff'); stoppedBff = pid;
        // Independent failsafe resumes only this exact process even if the test worker exits.
        const failsafe = spawn('python3', ['-c', 'import os,signal,time; time.sleep(10); os.kill(int(__import__("sys").argv[1]),signal.SIGCONT)', String(pid)], { detached: true, stdio: 'ignore' });
        failsafe.unref(); process.kill(pid, 'SIGSTOP');
        let failedAsExpected = false;
        try { await page.request.get(businessPath, { timeout: 2000, maxRetries: 0 }); }
        catch { failedAsExpected = true; }
        expect(failedAsExpected).toBe(true);
        await delay(11_000); process.kill(pid, 'SIGCONT'); stoppedBff = undefined;
        expect(ownedPid('bff')).toBe(pid);
        const recoveryMs = await checkBusiness(page.request); faults++;
        record({ type: 'fault', minute, service: 'bff', pid, controlledTransportTimeout: true, recoveryMs, businessRetries: 0 });
      }
      if (minute % 5 === 0) {
        const actor = Math.floor(minute / 5) % 4;
        const message = `[STEADY-${sessions[actor]}-${minute}] ${fixture.memory.question}`;
        const at = performance.now();
        const reply = await clients[actor].post('/api/ai/aurabot/chat/stream', { timeout: 120_000, maxRetries: 0,
          data: { agentCode: 'aurabot', sessionId: sessions[actor], message,
            pageContext: { modelCode: fixture.modelCode }, options: { provider: 'deepseek', model: 'deepseek-flash', maxTokens: 1536 } } });
        expect(reply.status()).toBe(200);
        const raw = await reply.text();
        writeFileSync(join(directory, `turn-${minute}.sse.txt`), raw, { mode: 0o600 });
        const events = sseEvents(raw);
        expect(events.some(e => ['error', 'confirm_required'].includes(e.name))).toBe(false);
        const done = events.filter(e => e.name === 'done'); expect(done).toHaveLength(1);
        const answer = String(done[0].data.content), traceId = String(done[0].data.traceId || '');
        expect(traceId).toBeTruthy(); expect(answer).toContain(fixture.memory.value);
        expect(raw).not.toContain(fixture.memory.foreignValue);
        expect(answer.replace(/[`*]/g, '')).toMatch(/status\s*=\s*OPEN\s*;\s*days_late\s*=\s*12/);
        const starts = events.filter(e => e.name === 'tool_start');
        const results = events.filter(e => e.name === 'tool_result');
        expect(starts.length).toBeGreaterThan(0); expect(results).toHaveLength(starts.length);
        const signatures = starts.map(e => canonicalJson([e.data.toolName, e.data.input]));
        expect(new Set(signatures).size).toBe(signatures.length);
        expect(new Set(starts.map(e => e.data.toolId)).size).toBe(starts.length);
        for (const result of results) expect(result.data.success).toBe(true);
        expect(starts.some(e => String(e.data.toolName).includes(fixture.modelCode)
          || JSON.stringify(e.data.input).includes('mt_' + fixture.modelCode))).toBe(true);
        turns.push({ traceId, sessionId: sessions[actor], minute });
        record({ type: 'model-turn', minute, traceId, sessionId: sessions[actor], elapsedMs: performance.now() - at, toolCalls: starts.length });
      }
    }
    await delay(started + 120 * minuteMs - performance.now());
    const elapsedMs = performance.now() - started;
    assertSteadyTotals(elapsedMs, reads, turns.length, faults);
    expect(new Set(turns.map(row => row.traceId)).size).toBe(24);
    expect(await changeCount()).toBe(changesBefore);
    expect((await database.query(`SELECT * FROM mt_${fixture.modelCode} WHERE pid=$1`, [fixture.businessPid])).rows).toEqual(before);
    const rss = Object.fromEntries(processKeys.map(key => [key, assertRssBudget(samples.slice(0, 15).map(row => row[key].rssKiB), samples.slice(-15).map(row => row[key].rssKiB))]));
    for (const key of processKeys) expect(new Set(samples.map(row => row[key].pid)).size).toBe(1);
    for (const turn of turns) {
      const traces = (await database.query("SELECT user_id,metadata::jsonb->>'turn_id' AS turn_id,total_input_tokens,total_output_tokens FROM ab_ai_trace WHERE tenant_id=$1 AND trace_id=$2", [fixture.tenantId, turn.traceId])).rows;
      expect(traces).toHaveLength(1); expect(String(traces[0].user_id)).toBe(fixture.owner.userId);
      expect(Number(traces[0].total_input_tokens)).toBeGreaterThan(0); expect(Number(traces[0].total_output_tokens)).toBeGreaterThan(0);
      expect(Number((await database.query('SELECT count(*) AS n FROM ab_agent_memory WHERE tenant_id=$1 AND metadata LIKE $2', [fixture.tenantId, `%source_turn_id=${traces[0].turn_id};%`])).rows[0].n)).toBe(1);
    }
    const usage = (await database.query('SELECT DISTINCT u.id,u.input_tokens,u.output_tokens,u.provider FROM ab_gen_ai_usage u JOIN ab_ai_trace t ON (u.trace_id=t.trace_id OR u.trace_id=t.otel_trace_id) WHERE t.tenant_id=$1 AND t.trace_id=ANY($2::text[])', [fixture.tenantId, turns.map(row => row.traceId)])).rows;
    expect(usage.length).toBeGreaterThanOrEqual(24);
    const sorted = [...timings].sort((a, b) => a - b);
    writeFileSync(join(directory, 'proof.json'), JSON.stringify({ status: 'pass', elapsedMs, businessAndMemoryReads: reads, actualModelTurns: 24, actualProviderCalls: usage.length, faults, businessRetries: 0, rss, readLatencyMs: { p50: sorted[Math.floor(sorted.length * .5)], p95: sorted[Math.floor(sorted.length * .95)], max: sorted.at(-1) }, sessions, turns }, null, 2), { mode: 0o600 });
  } finally {
    if (stoppedBff !== undefined) process.kill(stoppedBff, 'SIGCONT');
    await Promise.allSettled(clients.map(client => client.dispose())); await database.end();
  }
});
