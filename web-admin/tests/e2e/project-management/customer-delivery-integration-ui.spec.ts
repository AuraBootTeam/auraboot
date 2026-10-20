import { test, expect, type Page, type APIResponse, type Response } from '@playwright/test';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { BACKEND_URL } from '../../helpers/environments';
import { loginDeliveryMember } from '../../helpers/delivery-session';
import { navigateToMenuByClick } from '../helpers';

const evidence = process.env.AURA_EVIDENCE_DIR!;
const fixturePath = process.env.CUSTOMER_INTEGRATION_FIXTURE;
if (!evidence || !fixturePath) throw Error('Bind the real protocol result and owned evidence directory');
// One-time credentials must never enter automatic trace, video or failure screenshots.
test.use({ locale: 'zh-CN', storageState: { cookies: [], origins: [] }, trace: 'off', video: 'off', screenshot: 'off', actionTimeout: 15_000 });
test.setTimeout(240_000);
async function data(response: APIResponse | Response) {
  const body = await response.json(); expect(response.status(), JSON.stringify(body)).toBe(200); expect(String(body.code)).toBe('0'); return body.data;
}
async function shot(page: Page, id: string) {
  await mkdir(join(evidence, 'screenshots'), { recursive: true });
  await page.screenshot({ path: join(evidence, 'screenshots', `${id}.png`) });
}
async function platform(page: Page, name: string) {
  await page.getByTestId('user-menu').getByRole('button').first().click();
  await page.getByTestId('open-platform-link').click();
  const card = page.getByTestId('open-platform-application-list').locator('article').filter({ hasText: name });
  await expect(card).toHaveCount(1); return card;
}
async function closeSecret(page: Page) {
  const close = page.getByRole('button', { name: '已保存，关闭原文', exact: true });
  if (await close.isVisible()) {
    try { await close.click(); }
    catch { await page.goto('/home'); throw Error('The normal secret close action failed; the private page was unloaded'); }
  }
}

test('customer integration is inspectable, its external event survives designer save, and UI tokens rotate and revoke @critical', async ({ page }) => {
  const fixture = JSON.parse(await readFile(fixturePath!, 'utf8'));
  expect(fixture.profile).toBe('customer-delivery-integration-v1'); expect(fixture.status).toBe('passed');
  expect(fixture.finalReceiverInbox).toEqual({ count: 2, done: 2, projectionCount: 1 });
  expect(fixture.cleanupFailures).toEqual([]);
  await loginDeliveryMember(page);
  const base = `/api/open-platform/installations/${fixture.installationPid}`;
  const names: string[] = []; const stamp = Date.now().toString(36); const ledger: unknown[] = [];
  let permanent = ''; let fixed = ''; let result: Record<string, unknown> | undefined;
  const machine = async (token: string, status: number) => {
    const response = await page.request.get(`${BACKEND_URL}/api/open/v1/whoami`, { headers: { Authorization: `Bearer ${token}` } });
    expect(response.status()).toBe(status);
    if (status === 200) expect(await response.json()).toMatchObject({ applicationPid: fixture.applicationPid, installationPid: fixture.installationPid });
    ledger.push({ action: 'machine-identity', expectedStatus: status, actualStatus: response.status() });
  };
  const issue = async (mode: 'fixed' | 'never', name: string) => {
    const panel = page.getByTestId('integration-token-panel');
    names.push(name);
    await panel.getByRole('button', { name: '生成 Token', exact: true }).click();
    await panel.getByLabel('Token 名称', { exact: true }).fill(name);
    await panel.getByLabel('有效期模式', { exact: true }).selectOption(mode);
    await shot(page, mode === 'fixed' ? 'I02-fixed-token-form' : 'I03-permanent-token-form');
    let token = '';
    try {
      await panel.getByRole('button', { name: '确认', exact: true }).click();
      token = await panel.getByLabel('新 Token', { exact: true }).inputValue();
    } finally { await closeSecret(page); }
    expect(token.startsWith('ab_at_')).toBe(true);
    await expect(panel.getByLabel('新 Token', { exact: true })).toHaveCount(0);
    const rows = await data(await page.request.get(base + '/integration-tokens'));
    const row = rows.find((row: any) => row.name === name && row.status === 'active'); expect(row).toBeTruthy();
    if (mode === 'never') expect(row.expiresAt).toBeNull(); else expect(Date.parse(row.expiresAt)).toBeGreaterThan(Date.now());
    return token;
  };
  try {
    let card = await platform(page, fixture.applicationName);
    const installation = page.getByTestId(`open-platform-installation-${fixture.installationPid}`);
    await installation.scrollIntoViewIfNeeded(); await shot(page, 'I01-installation-and-authority');
    await card.getByTestId(`open-platform-tokens-${fixture.installationPid}`).click();
    fixed = await issue('fixed', `交付协同 · 定期接入 · ${stamp}`); await machine(fixed, 200);
    permanent = await issue('never', `交付协同 · 长期接入 · ${stamp}`); await machine(permanent, 200);
    await shot(page, 'I04-issued-token-metadata');
    await page.getByRole('button', { name: '关闭 Token 管理', exact: true }).click();

    await navigateToMenuByClick(page, ['系统管理', '自动化']);
    await page.getByRole('link', { name: fixture.automationName, exact: true }).click();
    await expect(page.getByTestId('flow-canvas-shell')).toBeVisible();
    await expect(page.getByTestId('flow-node-trigger_0')).toContainText('外部系统事件');
    await page.getByTestId('flow-node-trigger_0').click();
    await expect(page.getByTestId('prop-field-modelCode').getByRole('textbox')).toHaveValue(fixture.sourceCode);
    const eventTypes = await data(await page.request.get(`/api/automations/${fixture.automationPid}`));
    expect(eventTypes.triggerType).toBe('external_event');
    expect(eventTypes.triggerConfig.eventTypes).toEqual([`external.${fixture.sourceCode}.materials.received.v1`]);
    await shot(page, 'I05-external-event-designer');
    await page.getByTestId('automation-editor-name-input').fill(fixture.automationName + ' · 资料联动');
    const saving = page.waitForResponse(response => new URL(response.url()).pathname === `/api/automations/${fixture.automationPid}` && response.request().method() === 'PUT', { timeout: 15_000 });
    await page.getByTestId('designer-save').click();
    expect((await saving).status()).toBe(200);
    await page.reload();
    const saved = await data(await page.request.get(`/api/automations/${fixture.automationPid}`));
    expect(saved.triggerType).toBe('external_event'); expect(saved.modelCode).toBe(fixture.sourceCode);
    expect(saved.triggerConfig.eventTypes).toEqual(eventTypes.triggerConfig.eventTypes);
    await page.getByTestId('flow-node-action_0').click();
    await expect(page.getByTestId('model-map-field-0')).toHaveValue('tpm_pj_next_action');
    await expect(page.getByTestId('model-map-value-0')).toHaveValue('${trigger.data.nextAction}');
    await shot(page, 'I06-mapping-saved-and-reopened');

    const action = '客户材料已复核，林晓于11月12日前安排联合验收。';
    const event = { id: `materials-ui-${stamp}`, type: 'materials.received', schemaVersion: 1,
      occurredAt: new Date().toISOString(), subject: { type: 'project', pid: fixture.projectPid }, sequence: 3,
      data: { projectPid: fixture.projectPid, nextAction: action } };
    const accepted = await page.request.post(`${BACKEND_URL}/api/open/v1/event-sources/${fixture.sourceCode}/events`, {
      headers: { Authorization: `Bearer ${permanent}`, 'Idempotency-Key': `materials-ui-${stamp}` }, data: event });
    expect(accepted.status()).toBe(202); expect(await accepted.json()).toEqual({ eventId: event.id, duplicate: false });
    await expect.poll(async () => (await data(await page.request.get(`/api/dynamic/tpm_project/${fixture.projectPid}`))).tpm_pj_next_action, { timeout: 30_000 }).toBe(action);
    await navigateToMenuByClick(page, ['项目交付', '项目']);
    await page.getByTestId('list-search-input').fill(fixture.projectName); await page.getByTestId('list-search-input').press('Enter');
    const row = page.getByRole('row').filter({ has: page.getByText(fixture.projectName, { exact: true }) });
    await row.getByRole('button', { name: '查看', exact: true }).click();
    await expect(page.getByRole('heading', { name: fixture.projectName, exact: true })).toBeVisible();
    await expect(page.getByRole('main')).toContainText(action); await shot(page, 'I07-event-business-result');

    card = await platform(page, fixture.applicationName);
    await page.getByTestId(`open-platform-operations-${fixture.installationPid}`).click();
    const operations = page.getByTestId('open-platform-operations-panel');
    const replayed = operations.getByTestId(`webhook-delivery-${fixture.replayedDelivery.pid}`);
    await expect(replayed).toContainText('HTTP 204'); await replayed.scrollIntoViewIfNeeded(); await shot(page, 'I08-real-replay-delivery');
    await card.getByTestId(`open-platform-tokens-${fixture.installationPid}`).click();
    const panel = page.getByTestId('integration-token-panel');
    const active = panel.getByTestId('integration-token-row').filter({ hasText: `交付协同 · 长期接入 · ${stamp}` }).filter({ has: page.getByText('有效', { exact: true }) });
    await active.getByRole('button', { name: '轮换 Token', exact: true }).click();
    await panel.getByLabel('有效期模式', { exact: true }).selectOption('never');
    await panel.getByLabel('旧 Token 宽限分钟数', { exact: true }).fill('0'); await shot(page, 'I09-token-rotation-form');
    let replacement = '';
    try { await panel.getByRole('button', { name: '确认', exact: true }).click(); replacement = await panel.getByLabel('新 Token', { exact: true }).inputValue(); }
    finally { await closeSecret(page); }
    await machine(permanent, 401); await machine(replacement, 200); await machine(fixed, 200);
    await active.getByRole('button', { name: '撤销 Token', exact: true }).click(); await shot(page, 'I10-token-revoke-confirmation');
    await panel.getByRole('button', { name: '确认', exact: true }).click();
    await machine(replacement, 401); await machine(fixed, 200);
    await shot(page, 'I11-token-revoked-metadata');
    result = { profile: 'customer-integration-ui-v1', runtime: fixture.runtime,
      applicationPid: fixture.applicationPid, installationPid: fixture.installationPid, projectPid: fixture.projectPid,
      businessResult: action, savedTriggerType: saved.triggerType, eventId: event.id, machineChecks: ledger, status: 'passed' };
  } finally {
    await closeSecret(page);
    const rows = await data(await page.request.get(base + '/integration-tokens'));
    for (const token of rows.filter((row: any) => names.includes(row.name) && row.status !== 'revoked')) {
      await data(await page.request.delete(base + '/integration-tokens/' + token.pid));
    }
    const terminal = await data(await page.request.get(base + '/integration-tokens'));
    expect(terminal.filter((row: any) => names.includes(row.name)).every((row: any) => row.status === 'revoked')).toBe(true);
  }
  expect(result).toBeTruthy();
  await writeFile(join(evidence, 'integration-ui-result.json'), JSON.stringify(result, null, 2));
});
