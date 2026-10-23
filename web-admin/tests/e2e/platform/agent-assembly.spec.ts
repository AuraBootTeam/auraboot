import path from 'node:path';
import { mkdirSync } from 'node:fs';
import { test, expect, type Page, type TestInfo } from '@playwright/test';
import { LoginPage } from '../../pages/LoginPage';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';

const expected = process.env.AURA_TEST_AGENT_STATE ?? 'installed';
if (!['installed', 'not_installed'].includes(expected)) throw new Error('Invalid AURA_TEST_AGENT_STATE');
const mode = expected === 'installed' ? 'FULL' : 'BASE';
test.use({ storageState: { cookies: [], origins: [] }, locale: 'zh-CN', viewport: { width: 1440, height: 1000 } });

async function login(page: Page) {
  const form = new LoginPage(page);
  await form.goto();
  await form.waitForFormReady();
  await form.emailInput.fill(DEFAULT_TEST_ACCOUNT.email);
  await form.passwordInput.fill(DEFAULT_TEST_ACCOUNT.password);
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

test.describe('optional application assembly', () => {
  test('ordinary models remain usable and AI navigation follows the verified assembly', async ({ page }, info) => {
    await login(page);
    await expectAssemblyToggle(page);
    await page.getByRole('link', { name: '模型管理', exact: true }).click();
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
      await expect(page.getByRole('button', { name: '添加提供商', exact: true })).toHaveCount(0);
    }
    await capture(page, info, expected === 'installed' ? 'WEB-FULL-AI' : 'WEB-BASE-DEEP');
  });

  test('pending discovery preserves ordinary navigation and refuses AI activation', async ({ page }, info) => {
    let release!: () => void;
    const pending = new Promise<void>(resolve => { release = resolve; });
    await page.route('**/api/application/capabilities', async route => { await pending; await route.continue(); });
    try {
      await login(page);
      await expect(page.getByTestId('ai-panel-toggle')).toBeDisabled();
      await expect(page.getByTestId('ai-panel-toggle')).toHaveAttribute('title', '正在检查 AI 可用性…');
      await page.getByRole('link', { name: '模型管理', exact: true }).click();
      await expect(page.getByRole('link', { name: 'org_department', exact: true })).toBeVisible();
      await capture(page, info, `WEB-${mode}-LOADING`);
      release();
      await expectAssemblyToggle(page);
    } finally { release(); await page.unroute('**/api/application/capabilities'); }
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
    if (expected === 'installed') await expect(page.getByRole('heading', { name: '模型服务', exact: true })).toBeVisible();
    else await expect(page.getByTestId('agent-availability')).toHaveAttribute('data-state', 'not_installed');
    await capture(page, info, `WEB-${mode}-RECOVERED`);
  });
});
