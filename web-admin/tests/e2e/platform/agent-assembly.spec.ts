import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { mkdirSync } from 'node:fs';
import { test, expect, type Page, type TestInfo, type Route } from '@playwright/test';
import { LoginPage } from '../../pages/LoginPage';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';

const expected = process.env.AURA_TEST_AGENT_STATE ?? 'installed';
if (!['installed', 'not_installed'].includes(expected)) throw new Error('Invalid AURA_TEST_AGENT_STATE');
const mode = expected === 'installed' ? 'FULL' : 'BASE';
const providerMode = process.env.AURA_TEST_AGENT_PROVIDER_MODE;
if (providerMode && providerMode !== 'stub') throw new Error('Unsupported AURA_TEST_AGENT_PROVIDER_MODE');
test.use({ storageState: { cookies: [], origins: [] }, locale: 'zh-CN', viewport: { width: 1440, height: 1000 } });

async function login(page: Page, email = DEFAULT_TEST_ACCOUNT.email, password = DEFAULT_TEST_ACCOUNT.password) {
  const form = new LoginPage(page);
  await form.goto();
  await form.waitForFormReady();
  await form.emailInput.fill(email);
  await form.passwordInput.fill(password);
  await form.submitButton.click();
  await expect(page.getByTestId('ai-panel-toggle')).toBeVisible();
  const response = await page.request.get('/api/application/capabilities');
  expect(response.status()).toBe(200);
  expect(await response.json()).toEqual({ schemaVersion: 1, agent: { state: expected } });
}
async function capture(page: Page, info: TestInfo, id: string) {
  const directory = process.env.AURA_AGENT_ACCEPTANCE_DIR;
  const filename = directory ? path.join(directory, `${id}.png`) : info.outputPath(`${id}.png`);
  mkdirSync(path.dirname(filename), { recursive: true });
  await page.screenshot({ path: filename, fullPage: true });
  await info.attach(id, { path: filename, contentType: 'image/png' });
}
async function expectAssemblyToggle(page: Page) {
  if (expected === 'installed') await expect(page.getByTestId('ai-panel-toggle')).toBeEnabled();
  else await expect(page.getByTestId('ai-panel-toggle')).toHaveAttribute('title', '此应用未安装 AI 模块');
}

async function holdRediscovery(page: Page) {
  let release!: () => void;
  let intercepted!: () => void;
  let completed!: () => void;
  const pending = new Promise<void>(resolve => { release = resolve; });
  const received = new Promise<void>(resolve => { intercepted = resolve; });
  const handled = new Promise<void>(resolve => { completed = resolve; });
  let failure: unknown;
  const handler = async (route: Route) => {
    intercepted();
    try { await pending; await route.continue(); }
    catch (error) { failure = error; }
    finally { completed(); }
  };
  await page.route('**/api/application/capabilities', handler, { times: 1 });
  await page.evaluate(() => window.dispatchEvent(new Event('online')));
  await received;
  return { release: async () => {
    release();
    await handled;
    await page.unroute('**/api/application/capabilities', handler);
    if (failure) throw failure;
  } };
}

test.describe('optional application assembly', () => {
  test('ordinary models remain usable and AI navigation follows the verified assembly', async ({ page }, info) => {
    await login(page);
    await expectAssemblyToggle(page);
    await page.getByRole('link', { name: '模型管理', exact: true }).click();
    await page.getByRole('textbox', { name: '搜索...', exact: true }).fill('org_department');
    await expect(page.getByRole('link', { name: 'org_department', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: '新建模型', exact: true })).toBeEnabled();
    const aiLink = page.getByRole('link', { name: 'LLM 供应商', exact: true });
    if (expected === 'installed') await expect(aiLink).toBeVisible();
    else await expect(aiLink).toHaveCount(0);
    await capture(page, info, `WEB-${mode}-NAV`);
  });

  test('AI menu or retained deep link shows an explicit assembly outcome', async ({ page }, info) => {
    await login(page);
    await expectAssemblyToggle(page);
    if (expected === 'installed') {
      await page.getByRole('link', { name: 'LLM 供应商', exact: true }).click();
      await expect(page.getByRole('heading', { name: '模型服务', exact: true })).toBeVisible();
      await expect(page.getByRole('button', { name: '添加提供商', exact: true })).toBeEnabled();
    } else {
      // Direct navigation is intentional here: verify a historical AI deep link.
      await page.goto('/aurabot/providers');
      await expect(page.getByTestId('agent-availability')).toHaveAttribute('data-state', 'not_installed');
      await expect(page.getByRole('heading', { name: '此应用未安装 AI 模块' })).toBeVisible();
      await expect(page.getByTestId('sidebar').getByRole('link', { name: '模型管理', exact: true })).toBeVisible();
      await expect(page.getByTestId('sidebar').locator('xpath=ancestor-or-self::*[@inert]')).toHaveCount(0);
      await expect(page.getByRole('button', { name: '添加提供商', exact: true })).toHaveCount(0);
    }
    await capture(page, info, expected === 'installed' ? 'WEB-FULL-AI' : 'WEB-BASE-DEEP');
  });

  test('pending discovery preserves ordinary navigation and refuses AI activation', async ({ page }, info) => {
    await login(page);
    await expectAssemblyToggle(page);
    await page.getByRole('link', { name: '模型管理', exact: true }).click();
    await page.getByRole('textbox', { name: '搜索...', exact: true }).fill('org_department');
    await expect(page.getByRole('link', { name: 'org_department', exact: true })).toBeVisible();
    const discovery = await holdRediscovery(page);
    try {
      await expect(page.getByTestId('ai-panel-toggle')).toBeDisabled();
      await expect(page.getByTestId('ai-panel-toggle')).toHaveAttribute('title', '正在检查 AI 可用性…');
      await expect(page.getByRole('button', { name: '新建模型', exact: true })).toBeEnabled();
      await capture(page, info, `WEB-${mode}-LOADING`);
    } finally { await discovery.release(); }
    await expectAssemblyToggle(page);
  });

  test('same-session rediscovery preserves an unsaved provider draft', async ({ page }, info) => {
    test.skip(expected !== 'installed', 'Provider drafts require the installed AI module');
    await login(page);
    await expectAssemblyToggle(page);
    await page.getByRole('link', { name: 'LLM 供应商', exact: true }).click();
    await page.getByTestId('add-provider-btn').click();
    await page.getByTestId('picker-preset-custom').click();
    const draft = page.getByTestId('custom-display-name');
    await draft.fill('Unsaved assembly verification draft');
    const discovery = await holdRediscovery(page);
    try {
      await expect(page.getByTestId('agent-rediscovery')).toBeVisible();
      await expect(draft).toHaveValue('Unsaved assembly verification draft');
      await expect(draft.locator('xpath=ancestor::*[@inert]')).toHaveCount(1);
      await expect(page.getByTestId('sidebar').getByRole('link', { name: '模型管理', exact: true })).toBeVisible();
      await expect(page.getByTestId('sidebar').locator('xpath=ancestor-or-self::*[@inert]')).toHaveCount(0);
      await capture(page, info, 'WEB-FULL-REDISCOVERY');
    } finally { await discovery.release(); }
    await expect(page.getByTestId('agent-rediscovery')).toHaveCount(0);
    await expect(draft).toHaveValue('Unsaved assembly verification draft');
    await expect(draft).toBeEditable();
    await capture(page, info, 'WEB-FULL-DRAFT-RESTORED');
  });

  test('ordinary designer and its AI action follow the same verified assembly', async ({ page }, info) => {
    await login(page);
    await expectAssemblyToggle(page);
    await page.getByTestId('sidebar').getByRole('link', { name: '页面配置', exact: true }).click();
    await page.getByTestId('list-search-input').fill('auth_appearance');
    await page.getByTestId('list-search-input').press('Enter');
    const row = page.getByRole('row').filter({ hasText: 'auth_appearance' });
    await expect(row).toHaveCount(1);
    await row.getByRole('button', { name: '更多操作', exact: true }).click();
    await page.getByTestId('row-action-dropdown').getByText('旧页面设计器', { exact: true }).click();
    await expect(page.getByTestId('toolbar-save')).toBeVisible();
    const ai = page.getByTestId('toolbar-ai-generate');
    if (expected === 'installed') {
      await expect(ai).toBeEnabled();
      await ai.click();
      await expect(page.getByTestId('ai-page-panel')).toBeVisible();
      await expect(page.getByTestId('ai-panel-input')).toBeEditable();
      if (providerMode === 'stub') {
        // Explicit provider fixture: real UI/backend transport, deterministic local LLM.
        // Disabled provider presets are retained; no real provider is enabled.
        for (const level of ['tenant', 'platform']) {
          const configs = await page.request.get(`/api/llm-config?level=${level}`);
          expect(configs.status()).toBe(200);
          const body = await configs.json();
          expect(String(body.code)).toBe('0');
          expect(Array.isArray(body.data)).toBe(true);
          expect(body.data.filter((config: { enabled: boolean }) => config.enabled).length).toBe(0);
        }
        const instruction = 'Add a summary card without saving the current page';
        const generated = page.waitForResponse(response => new URL(response.url()).pathname === '/api/agent/nl-modeling/generate-page' && response.request().method() === 'POST');
        await page.getByTestId('ai-panel-input').fill(instruction);
        await page.getByTestId('ai-panel-send').click();
        const response = await generated;
        expect(response.request().postDataJSON().message).toBe(instruction);
        expect(response.request().postDataJSON().systemPrompt.length).toBeGreaterThan(0);
        expect(response.status()).toBe(200);
        const result = await response.json();
        expect(result.content).toBe('[stub response]');
        await expect(page.getByTestId('ai-msg-user')).toHaveText(instruction);
        await expect(page.getByTestId('ai-msg-assistant')).toHaveText(result.content);
        await expect(page.getByTestId('ai-panel-input')).toBeEditable();
        await expect(page.getByTestId('toolbar-save')).toBeDisabled();
        await capture(page, info, 'WEB-FULL-DESIGNER-REQUEST');
      }

    } else {
      await expect(ai).toBeDisabled();
      await expect(page.getByTestId('ai-page-panel')).toHaveCount(0);
    }
    await capture(page, info, `WEB-DESIGNER-${mode}`);
  });

  test('installation discovery does not grant a restricted member AI permission', async ({ page, browser, baseURL }, info) => {
    await login(page);
    const email = `agent-boundary-${randomUUID()}@e2e.local`;
    const created = await page.request.post('/api/admin/users', {
      headers: { Origin: baseURL! },
      data: { email, displayName: 'Assembly restricted member', initialPassword: DEFAULT_TEST_ACCOUNT.password,
        roleCodes: [], roleAssignmentMode: 'NONE', sendInviteEmail: false },
    });
    expect(created.status()).toBe(200);
    const account = await created.json();
    expect(String(account.code)).toBe('0');
    expect(account.data.assignedRoles).toEqual([]);
    const context = await browser.newContext({ baseURL, storageState: { cookies: [], origins: [] }, locale: 'zh-CN', viewport: { width: 1440, height: 1000 } });
    try {
      const member = await context.newPage();
      await login(member, email);
      // An intentionally retained deep link must enforce permission after installation discovery.
      const protectedReads: string[] = [];
      member.on('request', request => { if (new URL(request.url()).pathname === '/api/llm-config') protectedReads.push(request.url()); });
      await member.goto('/aurabot/providers');
      await expectAssemblyToggle(member);
      await expect(member.getByTestId('add-provider-btn')).toHaveCount(0);
      if (expected === 'installed') {
        await expect(member.getByText('你没有管理模型服务的权限，请联系管理员。', { exact: true })).toBeVisible();
        expect(protectedReads).toEqual([]);
        await expect(member.getByText('Access forbidden', { exact: true })).toHaveCount(0);
        const response = await member.request.get('/api/llm-config?level=tenant');
        expect(response.status()).toBe(403);
      } else await expect(member.getByTestId('agent-availability')).toHaveAttribute('data-state', 'not_installed');
      await capture(member, info, `WEB-PERMISSION-${mode}`);
    } finally { await context.close(); }
  });

  test('failed discovery stays unknown and retries against the real backend', async ({ page }, info) => {
    // Fault injection targets discovery only; auth, menus, models and the recovery response stay real.
    await page.route('**/api/application/capabilities', route => route.fulfill({ status: 503, body: '' }));
    await login(page);
    await expect(page.getByTestId('ai-panel-toggle')).toHaveAttribute('title', '暂时无法确认 AI 可用性');
    await page.goto('/aurabot/providers');
    await expect(page.getByTestId('agent-availability')).toHaveAttribute('data-state', 'unknown');
    await expect(page.getByRole('button', { name: '重试', exact: true })).toBeVisible();
    await capture(page, info, `WEB-${mode}-ERROR`);
    await page.unroute('**/api/application/capabilities');
    await page.getByRole('button', { name: '重试', exact: true }).click();
    if (expected === 'installed') {
      await expect(page.getByRole('heading', { name: '模型服务', exact: true })).toBeVisible();
      await expect(page.getByText('暂无已配置的模型提供商', { exact: true })).toBeVisible();
    }
    else await expect(page.getByTestId('agent-availability')).toHaveAttribute('data-state', 'not_installed');
    await capture(page, info, `WEB-${mode}-RECOVERED`);
  });
});


test('provider names have one input and create/edit round trips preserve the latest display name', async ({ page }, info) => {
  test.skip(expected !== 'installed', 'Provider configuration requires the installed AI module');
  await login(page);
  await page.getByRole('link', { name: 'LLM 供应商', exact: true }).click();
  await page.getByTestId('add-provider-btn').click();
  await page.getByTestId('picker-preset-openai').click();
  await expect(page.getByTestId('field-displayName')).toHaveCount(1);
  await capture(page, info, 'PROVIDER-PRESET');
  await page.getByRole('button', { name: '取消', exact: true }).click();
  await page.getByTestId('add-provider-btn').click();
  await page.getByTestId('picker-preset-custom').click();
  const name = `Quality ${randomUUID().slice(0, 8)}`;
  const code = name.toLowerCase().replace(/[^a-z0-9]+/g, '-');
  await expect(page.getByTestId('custom-display-name')).toHaveCount(1);
  await expect(page.getByTestId('field-displayName')).toHaveCount(0);
  await page.getByTestId('custom-display-name').fill(name);
  await page.getByTestId('field-apiKey').fill('fixture-not-a-real-model-key');
  await capture(page, info, 'PROVIDER-CUSTOM-CREATE');
  const create = page.waitForResponse(response => new URL(response.url()).pathname === '/api/llm-config' && response.request().method() === 'POST');
  await page.getByTestId('panel-save-btn').click();
  const created = await create;
  expect(created.status()).toBe(200);
  expect(String((await created.json()).code)).toBe('0');
  expect(created.request().postDataJSON()).toMatchObject({ providerCode: code, enabled: false });
  const createdConfig = created.request().postDataJSON().config;
  expect(typeof createdConfig === 'string' ? JSON.parse(createdConfig) : createdConfig).toMatchObject({ displayName: name });
  await expect(page.getByTestId('provider-edit-panel')).toHaveCount(0);
  await page.reload();
  await page.getByTestId(`provider-edit-${code}`).click();
  await expect(page.getByTestId('field-displayName')).toHaveCount(1);
  const renamed = `${name} renamed`;
  await page.getByTestId('field-displayName').fill(renamed);
  const update = page.waitForResponse(response => new URL(response.url()).pathname === '/api/llm-config' && response.request().method() === 'POST');
  await page.getByTestId('panel-save-btn').click();
  const updated = await update;
  expect(updated.status()).toBe(200);
  expect(String((await updated.json()).code)).toBe('0');
  expect(updated.request().postDataJSON()).toMatchObject({ providerCode: code, enabled: false });
  await expect(page.getByTestId('provider-edit-panel')).toHaveCount(0);
  await page.reload();
  await expect(page.getByTestId(`provider-card-${code}`)).toContainText(renamed);
  await page.getByTestId(`provider-edit-${code}`).click();
  await expect(page.getByTestId('field-displayName')).toHaveValue(renamed);
  await capture(page, info, 'PROVIDER-CUSTOM');
});
