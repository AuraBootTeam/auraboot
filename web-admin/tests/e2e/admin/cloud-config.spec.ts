/** Real menu-driven cloud configuration actions; retain isolated run evidence. */
import { test, expect } from '../../fixtures';
import type { Page, Locator, Response, APIResponse } from '@playwright/test';

const PAGE_URL = '/admin/cloud-config';
const API_BASE = '/api/admin/cloud-config';
const SERVICE_TABS = ['sms', 'email', 'oauth', 'storage', 'cdn'] as const;
const TEST_PROVIDER = 'tencent_sms';
const TEST_PROVIDER_LABEL = '腾讯云短信';
const RUN_ID = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
const TEST_CONFIG = {
  secretId: 'e2e-test-secret-id-001',
  secretKey: 'e2e-test-secret-key-001',
  appId: `e2e-app-${RUN_ID}`,
  signName: `E2ESign-${RUN_ID}`,
};
const EDITED_SIGN_NAME = `Edited-${RUN_ID}`;
type Config = {
  pid: string;
  config: string;
  enabled: boolean;
  configLevel: string;
  providerCode: string;
};

async function gotoCloudConfig(page: Page) {
  await page.goto('/dashboards');
  await expect(page.locator('header[data-hydrated]')).toHaveAttribute('data-hydrated', 'true');
  const nav = page.locator('nav').first();
  const link = nav.locator(`a[href="${PAGE_URL}"]`);
  if (!(await link.isVisible()))
    await nav.getByRole('button', { name: /系统管理|System Administration/ }).click();
  await expect(link).toBeVisible();
  await link.click();
  await expect(page).toHaveURL(new RegExp(`${PAGE_URL}$`));
  await expect(page.locator('h1').filter({ hasText: '云服务配置' })).toBeVisible();
}
async function openCloudConfigEditor(page: Page) {
  await page.getByTestId('cloud-config-create-btn').click();
  const modal = page
    .locator('div.fixed.inset-0')
    .filter({ has: page.getByTestId('cloud-config-save-btn') });
  await expect(modal).toBeVisible();
  return modal;
}
async function fillField(modal: Locator, labelText: string, value: string) {
  await modal
    .locator('label')
    .filter({ hasText: labelText })
    .locator('xpath=..')
    .locator('input')
    .fill(value);
}
function saveResponse(page: Page) {
  return page.waitForResponse(
    (r) => r.request().method() === 'POST' && new URL(r.url()).pathname === API_BASE,
  );
}
async function assertSuccessful(response: Response | APIResponse) {
  expect(response.status()).toBe(200);
  expect(String((await response.json()).code)).toBe('0');
}
async function platformConfigs(page: Page): Promise<Config[]> {
  const response = await page.request.get(`${API_BASE}?level=platform`);
  await assertSuccessful(response);
  const body = await response.json();
  expect(Array.isArray(body.data)).toBe(true);
  return body.data;
}
async function readConfig(page: Page, pid: string): Promise<Config> {
  const response = await page.request.get(`${API_BASE}/${pid}`);
  await assertSuccessful(response);
  const body = await response.json();
  expect(body.data.pid).toBe(pid);
  return body.data;
}
function cardFor(page: Page, pid: string | undefined) {
  expect(pid, "the UI create action must persist this run's configuration").toBeTruthy();
  return page.locator(`[data-config-pid="${pid}"]`);
}

test.describe.serial('Cloud Config Management', () => {
  let createdPid: string | undefined;
  let incompletePid: string | undefined;

  test('CC-001: should load page with correct structure @smoke', async ({ page }, info) => {
    await gotoCloudConfig(page);
    await expect(page.getByTestId('cloud-config-create-btn')).toBeVisible();
    for (const tab of SERVICE_TABS)
      await expect(page.getByTestId(`cloud-config-tab-${tab}`)).toBeVisible();
    await expect(page.getByTestId('cloud-config-level-platform')).toBeVisible();
    await expect(page.getByTestId('cloud-config-level-tenant')).toBeVisible();
    await page.screenshot({ path: info.outputPath('CC-001-page.png'), fullPage: true });
  });
  test('CC-002: should switch service type tabs', async ({ page }) => {
    await gotoCloudConfig(page);
    for (const tab of SERVICE_TABS) {
      const button = page.getByTestId(`cloud-config-tab-${tab}`);
      await button.click();
      await expect(button).toHaveClass(/text-blue-600/);
    }
  });
  test('CC-003: should switch between PLATFORM and TENANT levels', async ({ page }) => {
    await gotoCloudConfig(page);
    for (const level of ['tenant', 'platform']) {
      const response = page.waitForResponse(
        (r) =>
          new URL(r.url()).pathname === API_BASE &&
          new URL(r.url()).searchParams.get('level') === level,
      );
      const button = page.getByTestId(`cloud-config-level-${level}`);
      await button.click();
      await assertSuccessful(await response);
      await expect(button).toHaveClass(/bg-white/);
    }
  });
  test('CC-004: should create SMS configuration @smoke', async ({ page }, info) => {
    await gotoCloudConfig(page);
    await page.getByTestId('cloud-config-tab-sms').click();
    const modal = await openCloudConfigEditor(page);
    await modal.locator('select').nth(1).selectOption(TEST_PROVIDER);
    for (const [label, value] of [
      ['Secret ID', TEST_CONFIG.secretId],
      ['Secret Key', TEST_CONFIG.secretKey],
      ['App ID', TEST_CONFIG.appId],
      ['签名名称', TEST_CONFIG.signName],
    ])
      await fillField(modal, label, value);
    const response = saveResponse(page);
    await page.getByTestId('cloud-config-save-btn').click();
    const saved = await response;
    expect(saved.request().postDataJSON()).toMatchObject({
      configLevel: 'platform',
      serviceType: 'sms',
      providerCode: TEST_PROVIDER,
    });
    expect(JSON.parse(saved.request().postDataJSON().config)).toEqual(TEST_CONFIG);
    await assertSuccessful(saved);
    await expect(modal).toBeHidden();
    const configs = (await platformConfigs(page)).filter(
      (c) => JSON.parse(c.config).appId === TEST_CONFIG.appId,
    );
    expect(configs).toHaveLength(1);
    createdPid = configs[0].pid;
    const config = await readConfig(page, createdPid);
    expect(config.configLevel).toBe('platform');
    expect(JSON.parse(config.config)).toMatchObject({
      appId: TEST_CONFIG.appId,
      signName: TEST_CONFIG.signName,
    });
    const card = cardFor(page, createdPid);
    await expect(card.getByText(TEST_PROVIDER_LABEL, { exact: true })).toBeVisible();
    await card.getByTitle('展开详情').click();
    await expect(card).toContainText(TEST_CONFIG.appId);
    await expect(card).toContainText(TEST_CONFIG.signName);
    await page.screenshot({ path: info.outputPath('CC-004-created.png'), fullPage: true });
  });
  test('CC-005: should edit an existing configuration', async ({ page }, info) => {
    await gotoCloudConfig(page);
    const original = JSON.parse((await readConfig(page, createdPid!)).config);
    await cardFor(page, createdPid).getByTestId(`cloud-config-edit-${TEST_PROVIDER}`).click();
    const modal = page
      .locator('div.fixed.inset-0')
      .filter({ has: page.getByTestId('cloud-config-save-btn') });
    await expect(modal.getByText('编辑配置', { exact: true })).toBeVisible();
    await fillField(modal, '签名名称', EDITED_SIGN_NAME);
    const response = saveResponse(page);
    await page.getByTestId('cloud-config-save-btn').click();
    await assertSuccessful(await response);
    await expect(modal).toBeHidden();
    expect(JSON.parse((await readConfig(page, createdPid!)).config)).toEqual({
      ...original,
      signName: EDITED_SIGN_NAME,
    });
    await page.reload();
    const card = cardFor(page, createdPid);
    await card.getByTitle('展开详情').click();
    await expect(card).toContainText(EDITED_SIGN_NAME);
    await page.screenshot({ path: info.outputPath('CC-005-edited.png'), fullPage: true });
  });
  test('CC-006: should toggle enable/disable on a configuration', async ({ page }, info) => {
    await gotoCloudConfig(page);
    const original = await readConfig(page, createdPid!);
    const toggle = cardFor(page, createdPid).getByTestId(`cloud-config-toggle-${TEST_PROVIDER}`);
    for (const enabled of [false, true]) {
      const response = saveResponse(page);
      await toggle.click();
      await assertSuccessful(await response);
      await expect(toggle).toHaveAttribute('aria-checked', String(enabled));
      const changed = await readConfig(page, createdPid!);
      expect(changed.enabled).toBe(enabled);
      expect(JSON.parse(changed.config)).toEqual(JSON.parse(original.config));
      await page.screenshot({
        path: info.outputPath(`CC-006-${enabled ? 'enabled' : 'disabled'}.png`),
        fullPage: true,
      });
    }
  });
  test('CC-008: should trigger test connection', async ({ page }, info) => {
    await gotoCloudConfig(page);
    // Isolated negative setup: missing secrets force rejection before external HTTP.
    const invalidAppId = `incomplete-${RUN_ID}`;
    const setup = await page.request.post(API_BASE, {
      data: {
        configLevel: 'platform',
        serviceType: 'sms',
        providerCode: TEST_PROVIDER,
        config: JSON.stringify({ appId: invalidAppId }),
        enabled: true,
        priority: 0,
      },
    });
    await assertSuccessful(setup);
    const incomplete = (await platformConfigs(page)).filter(
      (c) => JSON.parse(c.config).appId === invalidAppId,
    );
    expect(incomplete).toHaveLength(1);
    const pid = incomplete[0].pid;
    incompletePid = pid;
    await page.reload();
    const response = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' && new URL(r.url()).pathname === `${API_BASE}/${pid}/test`,
    );
    await cardFor(page, pid).getByTestId(`cloud-config-test-${TEST_PROVIDER}`).click();
    const result = await response;
    await assertSuccessful(result);
    expect((await result.json()).data).toMatchObject({
      status: 'error',
      message: 'Missing required fields: secretId, secretKey, appId',
    });
    const errorToast = page.getByRole('alert').filter({ hasText: '连接测试失败' });
    await expect(errorToast).toBeVisible();
    await expect(errorToast).toHaveCSS('opacity', '1');
    await page.screenshot({ path: info.outputPath('CC-008-connection-error.png'), fullPage: true });
  });
  test('CC-007: should delete a configuration', async ({ page }, info) => {
    await gotoCloudConfig(page);
    const pid = createdPid!;
    const card = cardFor(page, pid);
    await expect(card).toBeVisible();
    page.once('dialog', (dialog) => dialog.accept());
    const response = page.waitForResponse(
      (r) =>
        r.request().method() === 'DELETE' && new URL(r.url()).pathname === `${API_BASE}/${pid}`,
    );
    await card.getByTestId(`cloud-config-delete-${TEST_PROVIDER}`).click();
    await assertSuccessful(await response);
    await expect(card).toHaveCount(0);
    expect((await platformConfigs(page)).some((c) => c.pid === pid)).toBe(false);
    await page.reload();
    await expect(cardFor(page, incompletePid)).toBeVisible();
    await expect(card).toHaveCount(0);
    await page.screenshot({ path: info.outputPath('CC-007-deleted.png'), fullPage: true });
  });
});
