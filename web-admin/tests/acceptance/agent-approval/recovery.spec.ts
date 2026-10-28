import { test, expect, type Page } from '@playwright/test';
import { Client } from 'pg';
import { readFileSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { execFileSync, execFile } from 'node:child_process';
import { promisify } from 'node:util';
import {
  fixture,
  evidence,
  record,
  storage,
  login,
  panel,
  send,
  propose,
  events,
  modelEffect,
  approvalWitness,
} from './qualification-support';

const execute = promisify(execFile);
let database: Client;
test.describe.configure({ mode: 'serial' });
test.beforeAll(async () => {
  database = await storage();
  expect(fixture.runtime.name).toMatch(/^agent-[a-z0-9-]+$/);
  expect(fixture.runtime.processDirectory).toBe(
    resolve(fixture.runtime.workspace, '.workspace/runtimes', fixture.runtime.name, 'processes'),
  );
  expect(fixture.runtime.bundle).toBe(
    resolve(
      fixture.runtime.workspace,
      '.workspace/runtime-bundles',
      fixture.runtime.name,
      'full-ai-candidate',
    ),
  );
});
test.afterAll(async () => {
  await database?.end();
});

async function workspace(args: string[], name: string) {
  const result = await execute(join(fixture.runtime.workspace, 'aura'), args, {
    cwd: fixture.runtime.workspace,
    timeout: 300_000,
    maxBuffer: 8 * 1024 * 1024,
  });
  writeFileSync(join(evidence, name + '.log'), result.stdout + result.stderr, { mode: 0o600 });
  return result.stdout;
}
async function interruptBackend(name: string) {
  await workspace(
    ['runtime', 'process', 'check', fixture.runtime.name, 'backend'],
    name + '-ownership',
  );
  const row = readFileSync(join(fixture.runtime.processDirectory, 'backend.tsv'), 'utf8')
    .trimEnd()
    .split('\t');
  expect(row).toHaveLength(7);
  expect(row[0]).toBe('backend');
  const pid = Number(row[1]);
  expect(Number.isSafeInteger(pid) && pid > 1).toBe(true);
  const command = execFileSync('ps', ['-ww', '-p', String(pid), '-o', 'command='], {
    encoding: 'utf8',
  }).trim();
  expect(command).toContain(fixture.runtime.bundle + '/backend/app.jar');
  expect(command).toContain(' -jar ');
  const cwd = execFileSync('lsof', ['-a', '-p', String(pid), '-d', 'cwd', '-Fn'], {
    encoding: 'utf8',
  });
  expect(cwd).toContain(fixture.runtime.name + '/work/agent-product/backend');
  record(name + '-before-sigkill', {
    pid,
    command,
    cwd,
    signal: 'SIGKILL',
    bundleSha256: fixture.bundleSha256,
  });
  // This is the owned product JVM, not a provider exception or graceful shutdown.
  process.kill(pid, 'SIGKILL');
  await expect
    .poll(() => {
      try {
        return execFileSync('ps', ['-p', String(pid), '-o', 'stat='], { encoding: 'utf8' })
          .trim()
          .startsWith('Z');
      } catch {
        return true;
      }
    })
    .toBe(true);
  return pid;
}
async function restore(name: string, previous: number) {
  await workspace(['runtime', 'suspend', fixture.runtime.name], name + '-retaining-suspend');
  await workspace(['runtime', 'resume', fixture.runtime.name], name + '-resume');
  const row = readFileSync(join(fixture.runtime.processDirectory, 'backend.tsv'), 'utf8').split(
    '\t',
  );
  expect(Number(row[1])).not.toBe(previous);
  await workspace(['runtime', 'verify', fixture.runtime.name, '--json'], name + '-verified');
  return Number(row[1]);
}
async function decide(page: Page, pending: any) {
  const response = await page.request.post('/api/ai/aurabot/execute', {
    data: { pendingTurnId: pending.pendingTurnId, toolId: pending.toolId, confirmed: true },
    timeout: 150_000,
  });
  expect(response.status()).toBe(200);
  return events((await response.body()).toString('utf8'));
}
async function assertCreatedOnce(code: string) {
  const actual = await modelEffect(database, code);
  expect(actual.rows).toHaveLength(1);
  expect(actual.physical).toBe('mt_' + code);
  return actual;
}

test('FT1: pending approval survives actual JVM death and requires an explicit owner decision', async ({
  page,
}) => {
  const code = fixture.codes.pendingCrash;
  await login(page);
  const pending = await propose(page, code, 'FT1-pending');
  const before = await approvalWitness(database, pending);
  expect(before.approval.approval_status).toBe('pending');
  expect(await modelEffect(database, code)).toEqual({ rows: [], physical: null });
  const oldPid = await interruptBackend('FT1');
  const newPid = await restore('FT1', oldPid);
  expect(await modelEffect(database, code)).toEqual({ rows: [], physical: null });
  const decision = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/ai/aurabot/execute' &&
      response.request().method() === 'POST',
    { timeout: 150_000 },
  );
  await page.getByTestId('skill-preview-card').getByTestId('confirm-btn').click();
  const outcome = events((await (await decision).body()).toString('utf8'));
  expect(outcome.some((item) => item.name === 'done')).toBe(true);
  expect(outcome.some((item) => item.name === 'error')).toBe(false);
  const actual = await assertCreatedOnce(code);
  const after = await approvalWitness(database, pending);
  expect(after.approval.approval_status).toBe('approved');
  expect(
    after.runs.some(
      (row) => row.resumed_from === before.approval.run_id && row.run_status === 'success',
    ),
  ).toBe(true);
  const repeated = await decide(page, pending);
  expect(repeated.some((item) => item.name === 'error')).toBe(true);
  expect(await assertCreatedOnce(code)).toEqual(actual);
  const usageBefore = (
    await database.query('SELECT count(*)::int AS n FROM ab_gen_ai_usage WHERE tenant_id=$1', [
      fixture.tenantId,
    ])
  ).rows[0].n;
  await page.reload();
  await expect(page.getByTestId('ai-panel-toggle')).toBeVisible();
  if (!(await page.getByTestId('aurabot-input').isVisible()))
    await page.getByTestId('ai-panel-toggle').click();
  await expect(page.getByTestId('skill-preview-card')).toHaveCount(0);
  expect(
    (
      await database.query('SELECT count(*)::int AS n FROM ab_gen_ai_usage WHERE tenant_id=$1', [
        fixture.tenantId,
      ])
    ).rows[0].n,
  ).toBe(usageBefore);
  expect(await assertCreatedOnce(code)).toEqual(actual);
  await page.screenshot({ path: join(evidence, 'FT1-restored.png'), fullPage: true });
  record('FT1-proof', { oldPid, newPid, pending, before, after, outcome, actual, repeated });
});

test('FT2: killing the JVM during a blocked real INSERT rolls back and never replays an unproved effect', async ({
  page,
}) => {
  const code = fixture.codes.inflightCrash;
  await login(page);
  const pending = await propose(page, code, 'FT2-pending');
  const before = await approvalWitness(database, pending);
  const blocker = new Client(fixture.database);
  await blocker.connect();
  let released = false;
  try {
    await blocker.query('BEGIN');
    // SHARE permits the real precondition SELECT, but blocks its INSERT's ROW EXCLUSIVE lock.
    await blocker.query('LOCK TABLE ab_meta_model IN SHARE MODE');
    const response = page
      .waitForResponse(
        (r) =>
          new URL(r.url()).pathname === '/api/ai/aurabot/execute' &&
          r.request().method() === 'POST',
      )
      .catch(() => null);
    await page.getByTestId('skill-preview-card').getByTestId('confirm-btn').click();
    let waiting: unknown[] = [];
    await expect
      .poll(
        async () => {
          waiting = (
            await database.query(
              "SELECT pid,wait_event_type,wait_event,query,xact_start FROM pg_stat_activity WHERE datname=$1 AND wait_event_type='Lock' AND query ~* 'INSERT[[:space:]]+INTO[[:space:]]+ab_meta_model'",
              [fixture.database.database],
            )
          ).rows;
          return waiting.length;
        },
        { timeout: 45_000 },
      )
      .toBe(1);
    record('FT2-observed-real-transaction', waiting);
    const inflight = await approvalWitness(database, pending);
    expect(inflight.approval.approval_status).toBe('approved');
    const child = inflight.runs.filter((row) => row.resumed_from === before.approval.run_id);
    expect(child).toHaveLength(1);
    expect(child[0].run_status).toBe('running');
    const oldPid = await interruptBackend('FT2');
    await blocker.query('ROLLBACK');
    released = true;
    await response;
    const newPid = await restore('FT2', oldPid);
    expect(await modelEffect(database, code)).toEqual({ rows: [], physical: null });
    const after = await approvalWitness(database, pending);
    const interrupted = after.runs.filter((row) => row.resumed_from === before.approval.run_id);
    expect(interrupted).toHaveLength(1);
    expect(['running', 'failed']).toContain(interrupted[0].run_status);
    if (interrupted[0].run_status === 'failed')
      expect(interrupted[0].error_message).toMatch(/stale|timeout|timed out|interrupt/i);
    const repeated = await decide(page, pending);
    expect(repeated.some((item) => item.name === 'error')).toBe(true);
    expect(await modelEffect(database, code)).toEqual({ rows: [], physical: null });
    const final = await approvalWitness(database, pending);
    expect(final.runs.map((row) => row.pid)).toEqual(after.runs.map((row) => row.pid));
    record('FT2-proof', {
      oldPid,
      newPid,
      pending,
      waiting,
      before,
      inflight,
      after,
      repeated,
      actual: await modelEffect(database, code),
      unsafeAutomaticReplay: false,
    });
  } finally {
    if (!released) await blocker.query('ROLLBACK');
    await blocker.end();
  }
});

test('FT3: a committed action whose browser receipt is withheld remains single-effect after JVM death', async ({
  page,
}) => {
  const code = fixture.codes.committedCrash;
  await login(page);
  const pending = await propose(page, code, 'FT3-pending');
  const before = await approvalWitness(database, pending);
  let release!: () => void;
  let fetched!: () => void;
  const hold = new Promise<void>((resolve) => {
    release = resolve;
  });
  const complete = new Promise<void>((resolve) => {
    fetched = resolve;
  });
  let received = '';
  await page.route('**/api/ai/aurabot/execute', async (route) => {
    const response = await route.fetch({ timeout: 150_000 });
    received = (await response.body()).toString('utf8');
    fetched();
    await hold;
    await route.fulfill({ response });
  });
  try {
    await page.getByTestId('skill-preview-card').getByTestId('confirm-btn').click();
    await complete;
    expect(events(received).some((item) => item.name === 'done')).toBe(true);
    expect(events(received).some((item) => item.name === 'error')).toBe(false);
    const actual = await assertCreatedOnce(code);
    const terminal = await approvalWitness(database, pending);
    expect(terminal.approval.approval_status).toBe('approved');
    const completed = terminal.runs.filter((row) => row.resumed_from === before.approval.run_id);
    expect(completed).toHaveLength(1);
    expect(completed[0].run_status).toBe('success');
    // The interception holds a real product response; no result/model/tool is substituted.
    await expect(page.getByTestId('aurabot-input')).toBeDisabled();
    await expect(
      page.getByTestId('aurabot-panel').getByText('正在提交审批决定…', { exact: true }),
    ).toBeVisible();
    const oldPid = await interruptBackend('FT3');
    const newPid = await restore('FT3', oldPid);
    release();
    await page.unroute('**/api/ai/aurabot/execute');
    const repeated = await decide(page, pending);
    expect(repeated.some((item) => item.name === 'error')).toBe(true);
    expect(await assertCreatedOnce(code)).toEqual(actual);
    expect((await approvalWitness(database, pending)).runs.map((row) => row.pid)).toEqual(
      terminal.runs.map((row) => row.pid),
    );
    await page.reload();
    await expect(page.getByTestId('ai-panel-toggle')).toBeVisible();
    await panel(page);
    const recovery = await send(
      page,
      `请实际调用只读工具 list_e2et_customer，pageNum=1、pageSize=20，不加关键词筛选。告诉我编码 ${fixture.form.code} 的客户名称和联系人，不进行修改。`,
      'FT4-new-read',
    );
    expect(recovery.some((item) => item.name === 'tool_result' && item.data.success === true)).toBe(
      true,
    );
    await expect(page.getByTestId('chat-msg-agent').last()).toContainText(fixture.form.manualName);
    await page.screenshot({
      path: join(evidence, 'FT3-single-effect-and-recovery.png'),
      fullPage: true,
    });
    record('FT3-proof', {
      oldPid,
      newPid,
      pending,
      actual,
      terminal,
      repeated,
      receiptWithheldBeforeDeath: true,
    });
  } finally {
    release();
    await page.unroute('**/api/ai/aurabot/execute');
  }
});

test('FT4: a new approval with an actual zero-hour deadline stays non-executable across restart', async ({
  page,
}) => {
  await login(page);
  async function timeout(hours: number) {
    const response = await page.request.post(
      '/api/meta/commands/execute/acp:update_approval_policy',
      {
        data: {
          operationType: 'update',
          targetRecordPid: fixture.approvalPolicyPid,
          payload: { timeout_hours: hours },
        },
      },
    );
    expect(response.status()).toBe(200);
    expect((await response.json()).code).toBe('0');
    expect(
      (
        await database.query(
          'SELECT timeout_hours FROM ab_approval_policy WHERE tenant_id=$1 AND pid=$2',
          [fixture.tenantId, fixture.approvalPolicyPid],
        )
      ).rows[0].timeout_hours,
    ).toBe(hours);
  }
  expect(
    (
      await database.query(
        'SELECT timeout_hours FROM ab_approval_policy WHERE tenant_id=$1 AND pid=$2',
        [fixture.tenantId, fixture.approvalPolicyPid],
      )
    ).rows[0].timeout_hours,
  ).toBe(24);
  await timeout(0);
  try {
    const pending = await propose(page, fixture.codes.expired, 'FT4-actual-expired-proposal');
    const rows = (
      await database.query(
        'SELECT pid,expires_at,approval_status FROM ab_agent_approval WHERE tenant_id=$1 AND pid=$2',
        [fixture.tenantId, pending.pendingTurnId],
      )
    ).rows;
    expect(rows).toHaveLength(1);
    const expiredAt = new Date(rows[0].expires_at).getTime();
    expect(expiredAt).toBeLessThanOrEqual(Date.now());
    expect(await modelEffect(database, fixture.codes.expired)).toEqual({
      rows: [],
      physical: null,
    });
    const oldPid = await interruptBackend('FT4-expiry');
    const newPid = await restore('FT4-expiry', oldPid);
    const refused = await decide(page, pending);
    expect(refused.some((item) => item.name === 'error')).toBe(true);
    expect(refused.some((item) => item.name === 'tool_result' && item.data.success === true)).toBe(
      false,
    );
    expect(await modelEffect(database, fixture.codes.expired)).toEqual({
      rows: [],
      physical: null,
    });
    await page.reload();
    await expect(page.getByTestId('ai-panel-toggle')).toBeVisible();
    await panel(page);
    const recovery = await send(
      page,
      `请实际调用只读工具 list_e2et_customer，pageNum=1、pageSize=20，不加关键词筛选。告诉我编码 ${fixture.form.code} 的客户名称和联系人，不进行修改。`,
      'FT4-expired-then-new-read',
    );
    expect(recovery.some((item) => item.name === 'tool_result' && item.data.success === true)).toBe(
      true,
    );
    await expect(page.getByTestId('chat-msg-agent').last()).toContainText(fixture.form.manualName);
    await page.screenshot({
      path: join(evidence, 'FT4-expired-and-recovered.png'),
      fullPage: true,
    });
    record('FT4-proof', {
      pending,
      expiry: rows[0],
      observedAt: Date.now(),
      oldPid,
      newPid,
      refused,
      actual: await modelEffect(database, fixture.codes.expired),
      actualNewPolicyDeadline: true,
      existingApprovalRowsRedisTtlAndClockWereNotModified: true,
      longDeadlineEnduranceClaimed: false,
    });
  } finally {
    await timeout(24);
  }
});
