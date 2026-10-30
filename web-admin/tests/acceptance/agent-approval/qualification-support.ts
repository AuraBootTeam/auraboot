import { expect, type Page } from '@playwright/test';
import { Client } from 'pg';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';

export const fixture = JSON.parse(readFileSync(process.env.AGENT_PERMISSION_FIXTURE!, 'utf8'));
export const evidence = process.env.AGENT_PERMISSION_EVIDENCE!;
export function record(name: string, value: unknown) {
  mkdirSync(evidence, { recursive: true });
  writeFileSync(join(evidence, name + '.json'), JSON.stringify(value, null, 2), { mode: 0o600 });
}
export async function storage() {
  const identity = JSON.parse(readFileSync(fixture.productIdentity, 'utf8'));
  expect(identity.verificationStatus).toBe('passed');
  expect(identity.runtime).toBe(fixture.runtime.name);
  expect(identity.bundleSha256).toBe(fixture.bundleSha256);
  expect(identity.checks.database).toBe(fixture.database.database);
  expect(identity.checks.capabilities.agent.state).toBe('installed');
  expect(identity.checks.semanticRecallDefault).toBe(false);
  const database = new Client(fixture.database);
  await database.connect();
  expect((await database.query('SELECT current_database() AS name')).rows[0].name).toBe(
    fixture.database.database,
  );
  return database;
}
export async function login(page: Page, account = fixture.admin) {
  await page.goto('/login');
  await page.locator('input#identifier, input#email').first().fill(account.email);
  await page.locator('input#password').fill(account.password);
  await page.locator('form button[type=submit]').click();
  await expect(page.getByTestId('ai-panel-toggle')).toBeVisible({ timeout: 45_000 });
  const target = String(account.tenantId ?? fixture.tenantId);
  let me = await (await page.request.get('/api/auth/me')).json();
  if (String(me.data.user.tenantId) !== target) {
    await page.getByTestId('user-menu').getByRole('button').first().click();
    const switched = page.waitForResponse(
      (r) =>
        new URL(r.url()).pathname === '/_action/switch-space' && r.request().method() === 'POST',
    );
    await page.getByTestId('tenant-switch-' + target).click();
    expect((await switched).status()).toBe(302);
    await page.waitForLoadState('load');
    await expect(page.getByTestId('ai-panel-toggle')).toBeVisible();
    me = await (await page.request.get('/api/auth/me')).json();
  }
  expect(String(me.data.user.tenantId)).toBe(target);
  expect(me.data.user.email).toBe(account.email);
}
export async function panel(page: Page) {
  if (!(await page.getByTestId('aurabot-input').isVisible()))
    await page.getByTestId('ai-panel-toggle').click();
  await page.getByTestId('aurabot-history-trigger').click();
  await page.getByTestId('aurabot-new-session').click();
}
export function events(raw: string): Array<{ name: string; data: any }> {
  return raw.split(/\r?\n\r?\n/).flatMap((frame) => {
    const name = frame.match(/^event:\s*(.+)$/m)?.[1]?.trim();
    const data = frame
      .split(/\r?\n/)
      .filter((line) => line.startsWith('data:'))
      .map((line) => line.slice(5).trimStart())
      .join('\n');
    if (!name || !data) return [];
    return [{ name, data: JSON.parse(data) }];
  });
}
export async function send(page: Page, message: string, name: string) {
  const response = page.waitForResponse(
    (r) =>
      new URL(r.url()).pathname === '/api/ai/aurabot/chat/stream' &&
      r.request().method() === 'POST',
    { timeout: 150_000 },
  );
  await page.getByTestId('aurabot-input').fill(message);
  await page.getByTestId('aurabot-send').click();
  const actual = await response;
  expect(actual.status()).toBe(200);
  const raw = (await actual.body()).toString('utf8');
  writeFileSync(join(evidence, name + '.private.sse.txt'), raw, { mode: 0o600 });
  await expect(page.getByTestId('aurabot-input')).toBeEnabled({ timeout: 150_000 });
  return events(raw);
}
export async function propose(page: Page, code: string, name: string) {
  await panel(page);
  const result = await send(
    page,
    `请实际调用技能 model:create（工具 aurabot_model_create），只创建编码为 ${code}、displayName 为进程恢复验收台账的模型。不调用 platform_create_model，不建议其他模型，不做额外修改。请提交审批。`,
    name,
  );
  const pending = result.find((item) => item.name === 'confirm_required');
  expect(pending, 'The real model must request a governed write').toBeTruthy();
  expect(pending!.data.toolName).toBe('agent_approval_gate');
  expect(pending!.data.extension).toMatchObject({
    _aurabot_skill: true,
    skillName: 'model:create',
  });
  expect(pending!.data.input.code).toBe(code);
  await expect(page.getByTestId('skill-preview-card').getByTestId('confirm-btn')).toBeVisible();
  return pending!.data;
}
export async function modelEffect(database: Client, code: string) {
  expect(code).toMatch(/^[a-z][a-z0-9_]+$/);
  const rows = (
    await database.query(
      'SELECT pid,status FROM ab_meta_model WHERE tenant_id=$1 AND code=$2 AND deleted_flag=FALSE',
      [fixture.tenantId, code],
    )
  ).rows;
  const physical = (
    await database.query('SELECT to_regclass($1) AS relation', ['public.mt_' + code])
  ).rows[0].relation;
  return { rows, physical };
}
export async function approvalWitness(database: Client, pending: { pendingTurnId: string }) {
  const approvals = (
    await database.query(
      'SELECT pid,approval_status,expires_at,run_id,task_id,request_data FROM ab_agent_approval WHERE tenant_id=$1 AND pid=$2',
      [fixture.tenantId, pending.pendingTurnId],
    )
  ).rows;
  expect(approvals).toHaveLength(1);
  const approval = approvals[0];
  const runs = (
    await database.query(
      'SELECT pid,run_status,resumed_from,execution_plan,error_message,timeout_at FROM ab_agent_run WHERE tenant_id=$1 AND task_id=$2 ORDER BY created_at,pid',
      [fixture.tenantId, approval.task_id],
    )
  ).rows;
  expect(runs.some((row) => row.pid === approval.run_id)).toBe(true);
  const checkpoints = (
    await database.query(
      'SELECT run_pid,reason,step_index,plan_snapshot FROM ab_agent_run_checkpoint WHERE tenant_id=$1 AND run_pid=ANY($2::varchar[]) ORDER BY created_at,pid',
      [fixture.tenantId, runs.map((row) => row.pid)],
    )
  ).rows;
  expect(checkpoints.some((row) => row.reason === 'approval_pending')).toBe(true);
  return { approval, runs, checkpoints };
}
