import { test, expect, type Page } from '@playwright/test';
import { Client } from 'pg';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';

const fixture = JSON.parse(readFileSync(process.env.AGENT_PERMISSION_FIXTURE!, 'utf8'));
const directory = process.env.AGENT_PERMISSION_EVIDENCE!;
const facts: unknown[] = [];
let database: Client;
test.beforeAll(async () => {
  mkdirSync(directory, { recursive: true });
  const identity = JSON.parse(readFileSync(fixture.productIdentity, 'utf8'));
  expect(identity.bundleSha256).toBe(fixture.bundleSha256);
  expect(identity.checks.database).toBe(fixture.database.database);
  expect(identity.checks.capabilities.agent.state).toBe('installed');
  database = new Client(fixture.database);
  await database.connect();
  const source = (await database.query('SELECT memory_title,memory_content,scope,scope_key FROM ab_agent_memory WHERE pid=$1 AND tenant_id=$2 AND deleted_flag=FALSE', [fixture.memory.pid, fixture.tenantId])).rows;
  expect(source).toHaveLength(1);
  expect(source[0].memory_title).toBe(fixture.memory.actualTitle);
  expect(source[0].memory_content).toContain(fixture.memory.value);
  expect(source[0].scope).toBe('user');
  expect(source[0].scope_key).toBe(fixture.owner.userId);
  expect(fixture.memory.question).not.toContain(fixture.memory.value);
});
test.afterAll(async () => {
  await database?.end();
  writeFileSync(join(directory, 'proof.json'), JSON.stringify(facts, null, 2));
});
async function login(page: Page, account: { email: string; password: string }) {
  await page.goto('/login');
  await page.locator('input#identifier, input#email').first().fill(account.email);
  await page.locator('input#password').fill(account.password);
  await page.locator('form button[type=submit]').click();
  await expect(page.getByTestId('ai-panel-toggle')).toBeVisible({ timeout: 45_000 });
  await page.getByTestId('ai-panel-toggle').click();
  await expect(page.getByTestId('aurabot-input')).toBeVisible();
  await fresh(page);
}
async function fresh(page: Page) {
  await page.getByTestId('aurabot-history-trigger').click();
  await page.getByTestId('aurabot-new-session').click();
}
async function send(page: Page, message: string, name: string, loading = false) {
  const response = page.waitForResponse(r => new URL(r.url()).pathname === '/api/ai/aurabot/chat/stream' && r.request().method() === 'POST', { timeout: 150_000 });
  await page.getByTestId('aurabot-input').fill(message);
  await page.getByTestId('aurabot-send').click();
  if (loading) {
    await expect(page.getByTestId('aurabot-input')).toBeDisabled();
    await page.screenshot({ path: join(directory, 'web-memory-loading.png'), fullPage: true });
  }
  const actual = await response;
  expect(actual.status()).toBe(200);
  const raw = (await actual.body()).toString();
  writeFileSync(join(directory, name + '.sse.txt'), raw);
  expect(raw).not.toMatch(/^event:\s*(?:error|confirm_required|tool_start)\s*$/m);
  expect(raw).toMatch(/^event:\s*done\s*$/m);
  await expect(page.getByTestId('aurabot-input')).toBeEnabled({ timeout: 150_000 });
  return page.getByTestId('chat-msg-agent').last();
}
test('private recall, missing facts, persisted history and manual recovery', async ({ page }) => {
  await login(page, fixture.owner);
  const answer = await send(page, fixture.memory.question, 'owner-recall', true);
  await expect(answer).toContainText(fixture.memory.value);
  await expect(answer).not.toContainText(/scope\s*=|Historical model response|memory_agent_id/);
  await expect(answer).toContainText(fixture.memory.actualTitle);
  await page.screenshot({ path: join(directory, 'web-memory-supported.png'), fullPage: true });
  const before = (await database.query('SELECT count(*)::int AS n FROM ab_gen_ai_usage WHERE tenant_id=$1', [fixture.tenantId])).rows[0].n;
  await page.reload();
  await expect(page.getByTestId('ai-panel-toggle')).toBeVisible();
  if (!await page.getByTestId('aurabot-input').isVisible()) await page.getByTestId('ai-panel-toggle').click();
  await expect(page.getByTestId('chat-msg-agent').last()).toContainText(fixture.memory.value);
  const after = (await database.query('SELECT count(*)::int AS n FROM ab_gen_ai_usage WHERE tenant_id=$1', [fixture.tenantId])).rows[0].n;
  expect(after).toBe(before);
  await page.screenshot({ path: join(directory, 'web-memory-history.png'), fullPage: true });
  await fresh(page);
  const missing = await send(page, fixture.memory.missingQuestion, 'owner-missing');
  await expect(missing).toContainText(fixture.memory.missingMarker);
  await expect(missing).not.toContainText(/scope\s*=|Historical model response|memory_agent_id/);
  await page.screenshot({ path: join(directory, 'web-memory-missing.png'), fullPage: true });
  // Only the negative phase substitutes one transport failure; all answers use real upstreams.
  await page.route('**/api/ai/aurabot/chat/stream', route => route.fulfill({ status: 503, contentType: 'application/json', body: '{"error":"Controlled acceptance transport unavailable"}' }));
  await page.getByTestId('aurabot-input').fill(fixture.memory.question);
  await page.getByTestId('aurabot-send').click();
  await expect(page.getByTestId('aurabot-input')).toBeEnabled();
  const error = page.getByTestId('aurabot-panel').getByText('无法连接 AI 助手，请检查网络后重新发送。', { exact: true });
  await expect(error).toBeVisible();
  await error.scrollIntoViewIfNeeded();
  await page.screenshot({ path: join(directory, 'web-memory-error.png'), fullPage: true });
  await page.unroute('**/api/ai/aurabot/chat/stream');
  const recovered = await send(page, fixture.memory.question, 'owner-recovered');
  await expect(recovered).toContainText(fixture.memory.value);
  await expect(recovered).not.toContainText(/scope\s*=|Historical model response|memory_agent_id/);
  await recovered.scrollIntoViewIfNeeded();
  await page.screenshot({ path: join(directory, 'web-memory-recovery.png'), fullPage: true });
  facts.push({ id: 'R5.web.owner', historyModelRequests: after - before, transportFaultOnly: true });
});
test('a different selected user cannot retrieve the owner private source', async ({ page }) => {
  await login(page, fixture.other);
  const answer = await send(page, fixture.memory.isolationQuestion, 'other-isolation');
  await expect(answer).toContainText(fixture.memory.isolationMarker);
  await expect(answer).not.toContainText(fixture.memory.value);
  await expect(page.getByTestId('aurabot-panel')).not.toContainText(fixture.memory.actualTitle);
  await page.screenshot({ path: join(directory, 'web-memory-isolation.png'), fullPage: true });
  facts.push({ id: 'R5.web.isolation', owner: fixture.owner.userId, other: fixture.other.userId });
});
