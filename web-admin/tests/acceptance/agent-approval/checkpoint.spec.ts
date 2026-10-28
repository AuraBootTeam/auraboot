import { test, expect } from '@playwright/test';
import { Client } from 'pg';
import { join } from 'node:path';
import {
  fixture,
  evidence,
  record,
  storage,
  login,
  propose,
  modelEffect,
  approvalWitness,
  events,
  panel,
  send,
} from './qualification-support';
import { interruptBackend, restore } from './process-support';

test('A1: committed model survives JVM death before its step checkpoint without replay', async ({
  page,
}) => {
  const database = await storage();
  const blocker = new Client(fixture.database);
  await blocker.connect();
  let locked = false;
  try {
    const code = fixture.codes.checkpointCrash;
    expect(code).toMatch(/^[a-z][a-z0-9_]+$/);
    expect(await modelEffect(database, code)).toEqual({ rows: [], physical: null });
    await login(page);
    const pending = await propose(page, code, 'A1-pending');
    const before = await approvalWitness(database, pending);
    await blocker.query('BEGIN');
    // The existing approval checkpoint is committed already. SHARE permits reads
    // and blocks only the later INSERT into the real checkpoint store.
    await blocker.query('LOCK TABLE ab_agent_run_checkpoint IN SHARE MODE');
    locked = true;
    const delivery = page
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
              `SELECT pid,wait_event_type,wait_event,query,xact_start
        FROM pg_stat_activity WHERE datname=$1 AND wait_event_type='Lock'
        AND query ~* 'INSERT[[:space:]]+INTO[[:space:]]+ab_agent_run_checkpoint'`,
              [fixture.database.database],
            )
          ).rows;
          return waiting.length;
        },
        { timeout: 150_000 },
      )
      .toBe(1);
    const committed = await modelEffect(database, code);
    expect(committed.rows).toHaveLength(1);
    expect(committed.rows[0].status).toBe('published');
    expect(committed.physical).toBe('mt_' + code);
    const window = await approvalWitness(database, pending);
    expect(window.approval.approval_status).toBe('approved');
    const children = window.runs.filter((row) => row.resumed_from === before.approval.run_id);
    expect(children).toHaveLength(1);
    expect(children[0].run_status).toBe('running');
    expect(
      window.checkpoints.filter(
        (row) =>
          row.run_pid === children[0].pid &&
          ['step_completed', 'approval_tool_resumed'].includes(row.reason),
      ),
    ).toHaveLength(0);
    record('A1-observed-commit-checkpoint-window', { before, window, waiting, committed });
    await page.screenshot({ path: join(evidence, 'A1-awaiting-checkpoint.png'), fullPage: true });
    const oldPid = await interruptBackend('A1');
    await blocker.query('ROLLBACK');
    locked = false;
    await delivery;
    const newPid = await restore('A1', oldPid);
    expect(await modelEffect(database, code)).toEqual(committed);
    const after = await approvalWitness(database, pending);
    expect(after.runs.map((row) => row.pid)).toEqual(window.runs.map((row) => row.pid));
    const repeated = await page.request.post(fixture.backendURL + '/api/ai/aurabot/execute', {
      headers: { Authorization: 'Bearer ' + fixture.adminBearer },
      data: { pendingTurnId: pending.pendingTurnId, toolId: pending.toolId, confirmed: true },
    });
    expect(repeated.status()).toBe(200);
    const replay = events((await repeated.body()).toString('utf8'));
    expect(replay.some((item) => item.name === 'error')).toBe(true);
    expect(await modelEffect(database, code)).toEqual(committed);
    const final = await approvalWitness(database, pending);
    expect(final.runs.map((row) => row.pid)).toEqual(after.runs.map((row) => row.pid));
    await page.reload();
    await expect(page.getByTestId('ai-panel-toggle')).toBeVisible();
    await panel(page);
    const recoveredRead = await send(
      page,
      `请实际调用 list_e2et_customer，只读查询并核对客户编号 ${fixture.form.code}。不创建或修改任何数据。`,
      'A1-new-read',
    );
    expect(recoveredRead.some((item) => item.name === 'error')).toBe(false);
    expect(recoveredRead.some((item) => item.name === 'done')).toBe(true);
    expect(
      recoveredRead.some(
        (item) => item.name === 'tool_start' && item.data.toolName === 'list_e2et_customer',
      ),
    ).toBe(true);
    expect(
      recoveredRead.some(
        (item) =>
          item.name === 'tool_result' &&
          item.data.success === true &&
          JSON.stringify(item.data.result).includes(fixture.form.code),
      ),
    ).toBe(true);
    expect(await modelEffect(database, code)).toEqual(committed);
    await page.screenshot({ path: join(evidence, 'A1-recovered-read.png'), fullPage: true });
    record('A1-proof', {
      oldPid,
      newPid,
      before,
      window,
      after,
      final,
      committed,
      replay,
      recoveredRead,
      automaticReplay: false,
    });
  } finally {
    if (locked) await blocker.query('ROLLBACK');
    await blocker.end();
    await database.end();
  }
});
