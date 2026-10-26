import { test, expect, type Page } from '@playwright/test';
import { Client } from 'pg';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { BASE_URL, BACKEND_URL } from '../../helpers/environments';

type Account = { email: string; password: string };
type Fixture = {
  baseURL: string; backendURL: string; tenantId: string;
  admin: Account; reader: Account; modelCode: string; modelName: string;
  adminConversationId: number; privateHistoryValue: string;
  productIdentity: string; bundleSha256: string;
  database: { host: string; port: number; database: string; user: string; password: string };
};
const fixture: Fixture = JSON.parse(readFileSync(process.env.AGENT_PERMISSION_FIXTURE!, 'utf8'));
const evidence = process.env.AGENT_PERMISSION_EVIDENCE!;
const facts: unknown[] = [];
let database: Client;

test.beforeAll(async () => {
  mkdirSync(evidence, { recursive: true });
  expect(fixture.baseURL).toBe(BASE_URL);
  expect(fixture.backendURL).toBe(BACKEND_URL);
  const identity = JSON.parse(readFileSync(fixture.productIdentity, 'utf8'));
  expect(identity.bundleSha256).toBe(fixture.bundleSha256);
  expect(identity.checks.database).toBe(fixture.database.database);
  expect(identity.checks.capabilities.agent.state).toBe('installed');
  database = new Client(fixture.database);
  await database.connect();
  expect((await database.query('SELECT current_database() AS name')).rows[0].name)
    .toBe(fixture.database.database);
});

test.afterAll(async () => {
  await database?.end();
  writeFileSync(join(evidence, 'proof.json'), JSON.stringify(facts, null, 2), { mode: 0o600 });
});

async function login(page: Page, account: Account) {
  await page.goto('/login');
  await page.locator('input#identifier, input#email').first().fill(account.email);
  await page.locator('input#password').fill(account.password);
  await page.locator('form button[type=submit]').click();
  await expect(page.getByTestId('ai-panel-toggle')).toBeVisible({ timeout: 45_000 });
}

async function openPanel(page: Page, fresh = false) {
  const input = page.getByTestId('aurabot-input');
  if (!await input.isVisible()) await page.getByTestId('ai-panel-toggle').click();
  await expect(input).toBeVisible();
  if (fresh) {
    await page.getByTestId('aurabot-history-trigger').click();
    await page.getByTestId('aurabot-new-session').click();
  }
}

function events(raw: string) {
  return raw.split(/\r?\n\r?\n/).flatMap(frame => {
    const name = frame.match(/^event:\s*(.+)$/m)?.[1]?.trim();
    const data = frame.split(/\r?\n/).filter(line => line.startsWith('data:'))
      .map(line => line.slice(5).trimStart()).join('\n');
    if (!name || !data) return [];
    try { return [{ name, data: JSON.parse(data) }]; }
    catch { return [{ name, data }]; }
  });
}

async function send(page: Page, message: string, name: string) {
  const response = page.waitForResponse(candidate =>
    new URL(candidate.url()).pathname === '/api/ai/aurabot/chat/stream'
      && candidate.request().method() === 'POST', { timeout: 120_000 });
  await page.getByTestId('aurabot-input').fill(message);
  await page.getByTestId('aurabot-send').click();
  const actual = await response;
  expect(actual.status()).toBe(200);
  const raw = (await actual.body()).toString('utf8');
  writeFileSync(join(evidence, name + '.private.sse.txt'), raw, { mode: 0o600 });
  await expect(page.getByTestId('aurabot-input')).toBeEnabled({ timeout: 120_000 });
  return events(raw);
}

async function readModel(page: Page, name: string) {
  const result = await send(page, '请实际调用只读工具 aurabot_model_query（技能 model:query），'
    + '参数 modelCode 为 ' + fixture.modelCode + '，告诉我业务模型名称和编码，不读取业务记录，不执行任何修改。', name);
  const starts = result.filter(item => item.name === 'tool_start');
  const invocation = starts.find(item => /(?:aurabot[:_])?model[:_]query/.test(item.data.toolName || ''));
  expect(invocation, 'The real model-query tool must execute; model text alone is insufficient').toBeTruthy();
  expect(invocation!.data.input.modelCode).toBe(fixture.modelCode);
  const completed = result.find(item => item.name === 'tool_result'
    && item.data.toolId === invocation!.data.toolId);
  expect(completed?.data.success).toBe(true);
  // Chromium's DevTools body decoding can use a legacy charset for SSE;
  // bind the ASCII model key here and verify the Chinese name in the real DOM.
  expect(completed?.data.result?.data?.model?.code).toBe(fixture.modelCode);
  await expect(page.locator('[data-testid=aurabot-panel]')).toContainText(fixture.modelName);
  return { toolId: invocation!.data.toolId, tool: invocation!.data.toolName };
}

async function modelInventory() {
  return (await database.query(`SELECT
    (SELECT count(*)::int FROM ab_meta_model WHERE tenant_id=$1 AND deleted_flag=FALSE) AS models,
    (SELECT count(*)::int FROM information_schema.tables WHERE table_schema='public' AND table_name LIKE 'mt_%') AS tables`,
  [fixture.tenantId])).rows[0];
}

async function forbiddenEffect(code: string) {
  const model = await database.query(
    'SELECT count(*)::int AS n FROM ab_meta_model WHERE tenant_id=$1 AND code=$2 AND deleted_flag=FALSE',
    [fixture.tenantId, code]);
  const physical = await database.query('SELECT to_regclass($1) AS relation', ['public.mt_' + code]);
  return { models: model.rows[0].n, physical: physical.rows[0].relation };
}

test('ordinary member reads model metadata and restores the visible result after reload', async ({ page }) => {
  await login(page, fixture.reader);
  await openPanel(page, true);
  const tool = await readModel(page, 'reader-read');
  await page.getByTestId('rc-queried-model').scrollIntoViewIfNeeded();
  await expect(page.getByTestId('rc-queried-model')).toContainText(fixture.modelCode);
  await expect(page.getByTestId('rc-card')).not.toBeVisible();
  await page.screenshot({ path: join(evidence, 'reader-model-read.png'), fullPage: true });
  const usageBefore = (await database.query(
    'SELECT count(*)::int AS n FROM ab_gen_ai_usage WHERE tenant_id=$1', [fixture.tenantId])).rows[0].n;
  await page.reload();
  await expect(page.getByTestId('ai-panel-toggle')).toBeVisible();
  await openPanel(page);
  await expect(page.locator('[data-testid=aurabot-panel]')).toContainText(fixture.modelName);
  const usageAfter = (await database.query(
    'SELECT count(*)::int AS n FROM ab_gen_ai_usage WHERE tenant_id=$1', [fixture.tenantId])).rows[0].n;
  expect(usageAfter).toBe(usageBefore);
  await page.screenshot({ path: join(evidence, 'reader-model-history.png'), fullPage: true });
  facts.push({ id: 'U1', ...tool, historyModelRequests: usageAfter - usageBefore });
});

test('ordinary member cannot create a model and can still perform an allowed read', async ({ page }) => {
  const code = 'reader_denied_' + randomUUID().replaceAll('-', '').slice(0, 12);
  const inventoryBefore = await modelInventory();
  expect(await forbiddenEffect(code)).toEqual({ models: 0, physical: null });
  await login(page, fixture.reader);
  await openPanel(page, true);
  const proposal = await send(page, '请实际调用 platform_create_model 创建一个业务模型，'
    + '编码为 ' + code + '，名称为权限拒绝验收台账。请提交创建请求，不要只返回示例。', 'reader-denied-proposal');
  expect(proposal.some(item => item.name === 'confirm_required'
    && item.data.toolName === 'platform_create_model')).toBe(true);
  await expect(page.getByTestId('aurabot-confirm-approve')).toBeVisible();
  expect(await modelInventory()).toEqual(inventoryBefore);
  const response = page.waitForResponse(candidate =>
    new URL(candidate.url()).pathname === '/api/ai/aurabot/execute'
      && candidate.request().method() === 'POST', { timeout: 120_000 });
  await page.getByTestId('aurabot-confirm-approve').click();
  const decision = await response;
  expect(decision.status()).toBe(200);
  const raw = (await decision.body()).toString('utf8');
  writeFileSync(join(evidence, 'reader-denied.private.sse.txt'), raw, { mode: 0o600 });
  const result = events(raw);
  const denial = result.find(item => item.name === 'tool_result');
  expect(denial?.data.success).toBe(false);
  expect(denial?.data.result.errorCode).toBe('TOOL_ACCESS_DENIED');
  expect(denial?.data.result.retryable).toBe(false);
  expect(denial?.data.result.errorFrame.errorClass).toBe('ToolAccessDenied');
  await expect(page.getByTestId('aurabot-input')).toBeEnabled({ timeout: 120_000 });
  expect(await forbiddenEffect(code)).toEqual({ models: 0, physical: null });
  expect(await modelInventory()).toEqual(inventoryBefore);
  await expect(page.getByTestId('skill-preview-card')).toHaveCount(0);
  await expect(page.getByTestId('aurabot-confirm-card')).toHaveCount(0);
  await expect(page.locator('[data-testid=aurabot-panel]')).toContainText(/权限|无权|授权|管理员/);
  expect(result.filter(item => item.name === 'tool_result' && item.data.success === true)
    .some(item => JSON.stringify(item.data.result).includes(code))).toBe(false);
  await page.screenshot({ path: join(evidence, 'reader-write-denied.png'), fullPage: true });
  await openPanel(page, true);
  const tool = await readModel(page, 'reader-after-denial');
  expect(await forbiddenEffect(code)).toEqual({ models: 0, physical: null });
  await page.screenshot({ path: join(evidence, 'reader-read-recovered.png'), fullPage: true });
  expect(await modelInventory()).toEqual(inventoryBefore);
  facts.push({ id: 'U2', forbiddenCode: code, effect: await forbiddenEffect(code), inventoryUnchanged: true, recovery: tool });
});

test('switching from admin to a nonmember cannot restore or list the admin conversation', async ({ page }) => {
  await login(page, fixture.admin);
  await openPanel(page);
  await page.getByTestId('aurabot-history-trigger').click();
  await page.getByTestId('aurabot-session-' + fixture.adminConversationId).click();
  await expect(page.locator('[data-testid=aurabot-panel]')).toContainText(fixture.privateHistoryValue);
  await page.screenshot({ path: join(evidence, 'admin-history-before-switch.png'), fullPage: true });
  await page.getByTestId('user-menu').getByRole('button').first().click();
  await page.getByTestId('user-dropdown').locator('a[href="/logout"]').click();
  await page.getByRole('button', { name: '确认退出' }).click();
  await page.waitForURL(/\/login/);
  await login(page, fixture.reader);
  await openPanel(page);
  await expect(page.locator('[data-testid=aurabot-panel]')).not.toContainText(fixture.privateHistoryValue);
  await page.getByTestId('aurabot-history-trigger').click();
  await expect(page.getByTestId('aurabot-session-' + fixture.adminConversationId)).toHaveCount(0);
  await page.screenshot({ path: join(evidence, 'reader-history-isolated.png'), fullPage: true });
  await page.reload();
  await expect(page.getByTestId('ai-panel-toggle')).toBeVisible();
  await openPanel(page);
  await expect(page.locator('[data-testid=aurabot-panel]')).not.toContainText(fixture.privateHistoryValue);
  facts.push({ id: 'U3', adminConversationAbsent: true, staleIdentityDidNotExposeHistory: true });
});
