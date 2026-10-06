/**
 * Existing invitation acceptance cases, driven through the member menu and
 * native invitation dialog. Requests stay on the configured BFF runtime.
 */
import type { Page, TestInfo } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { BASE_URL } from '../../helpers/environments';

test.use({ locale: 'zh-CN' });
test.describe.configure({ mode: 'serial' });

const endpoint = '/api/tenant/invite-code/';
const matches =
  (path: string, method: string) => (response: import('@playwright/test').Response) => {
    const url = new URL(response.url());
    return (
      url.origin === new URL(BASE_URL).origin &&
      url.pathname === `${endpoint}${path}` &&
      response.request().method() === method
    );
  };

async function openMembers(page: Page) {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  await page.evaluate(() => localStorage.removeItem('sidebar-collapsed'));
  await page.reload();
  const link = page.locator('nav a[href="/p/tenant_member"]');
  if (!(await link.isVisible())) {
    await page.locator('nav button').filter({ hasText: '组织管理' }).first().click();
  }
  await expect(link).toBeVisible();
  await link.click();
  await expect(page).toHaveURL(/\/p\/tenant_member/);
  await expect(page.getByTestId('invite-section')).toBeVisible();
}

async function openInvite(page: Page) {
  const response = page.waitForResponse(matches('current', 'GET'));
  await page.getByTestId('invite-section').click();
  const current = await response;
  expect(current.status()).toBe(200);
  const body = await current.json();
  expect(body.code).toBe('0');
  const dialog = page.getByTestId('invite-dialog');
  await expect(dialog.getByRole('heading')).toHaveText('成员邀请');
  await expect(page.getByTestId('invite-loading')).toHaveCount(0);
  await expect(page.getByTestId('invite-read-error')).toHaveCount(0);
  await assertCurrent(page, body.data);
  return dialog;
}

async function assertCurrent(page: Page, current: { code: string; expiredAt: string } | null) {
  if (current) {
    await expect(page.getByTestId('invite-code-value')).toHaveText(current.code);
    await expect(page.getByTestId('invite-dialog')).toContainText('有效期至');
    expect(Number.isFinite(Date.parse(current.expiredAt))).toBe(true);
  } else {
    await expect(page.getByTestId('invite-code-value')).toHaveCount(0);
    await expect(page.getByTestId('invite-dialog')).toContainText('当前没有有效邀请码');
  }
}

async function generateThroughDialog(page: Page) {
  const generated = page.waitForResponse(matches('generate', 'POST'));
  const loaded = page.waitForResponse(matches('current', 'GET'));
  const startedAt = Date.now();
  await page
    .getByTestId('invite-dialog')
    .getByRole('button', { name: /^生成(新)?邀请码$/ })
    .click();
  const response = await generated;
  expect(response.status()).toBe(200);
  expect(new URL(response.url()).searchParams.get('expiryDays')).toBe('7');
  const body = await response.json();
  expect(body.code).toBe('0');
  expect(body.data).toMatch(/^[a-z0-9]{8}$/);
  const current = await loaded;
  expect(current.status()).toBe(200);
  const readback = await current.json();
  expect(readback.code).toBe('0');
  expect(readback.data.code).toBe(body.data);
  const expiresAt = Date.parse(readback.data.expiredAt);
  expect(expiresAt).toBeGreaterThanOrEqual(startedAt + 7 * 86400000 - 1000);
  expect(expiresAt).toBeLessThanOrEqual(Date.now() + 7 * 86400000 + 1000);
  await assertCurrent(page, readback.data);
  await expect(page.getByTestId('invite-read-error')).toHaveCount(0);
  await expect(page.getByTestId('invite-dialog')).toContainText(
    '生成新邀请码不会自动撤销已有邀请码',
  );
  return body.data as string;
}

async function validate(page: Page, code: string, expected: boolean) {
  const response = await page.request.get(`${endpoint}validate?code=${encodeURIComponent(code)}`);
  expect(new URL(response.url()).origin).toBe(new URL(BASE_URL).origin);
  expect(response.status()).toBe(200);
  const body = await response.json();
  expect(body.code).toBe('0');
  expect(body.data).toBe(expected);
}

async function capture(page: Page, info: TestInfo, name: string) {
  const path = info.outputPath(`${name}.png`);
  await page.screenshot({ path, fullPage: true });
  await info.attach(name, { path, contentType: 'image/png' });
}

test.describe('Member Invite Flow', () => {
  test('MI-01: should generate invite code through the management UI @smoke', async ({
    page,
  }, info) => {
    await openMembers(page);
    const dialog = await openInvite(page);
    for (let i = 0; i < 5; i += 1) {
      await page.keyboard.press('Tab');
      expect(
        await dialog.evaluate((node) => node.contains(document.activeElement)),
        'Tab focus stays within invitation dialog',
      ).toBe(true);
    }
    await page.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0);
    await expect(page.getByTestId('invite-section')).toBeFocused();
    await openInvite(page);
    if ((await page.getByTestId('invite-code-value').count()) === 0) {
      await capture(page, info, 'invite-empty');
    }
    const code = await generateThroughDialog(page);
    await validate(page, code, true);
    await capture(page, info, 'invite-generated');
  });

  test('MI-02: should persist a new invite code and recover a failed read without another write', async ({
    page,
  }, info) => {
    let initialWrites = 0;
    page.on('request', (request) => {
      if (new URL(request.url()).pathname === `${endpoint}generate` && request.method() === 'POST')
        initialWrites += 1;
    });
    await openMembers(page);
    let releaseRead!: () => void;
    const readGate = new Promise<void>((resolve) => {
      releaseRead = resolve;
    });
    await page.route(`**${endpoint}current`, async (route) => {
      await readGate;
      await route.fulfill({
        status: 503,
        contentType: 'application/json',
        body: JSON.stringify({
          code: '503',
          message: 'Controlled initial read outage',
          data: null,
        }),
      });
    });
    const initialRead = page.waitForResponse(matches('current', 'GET'));
    await page.getByTestId('invite-section').click();
    try {
      await expect(page.getByTestId('invite-loading')).toHaveText('正在读取邀请码…');
      await expect(page.getByTestId('invite-code-value')).toHaveCount(0);
      await expect(
        page.getByTestId('invite-dialog').getByRole('button', { name: /^生成(新)?邀请码$/ }),
      ).toHaveCount(0);
      await capture(page, info, 'invite-loading');
    } finally {
      releaseRead();
    }
    expect((await initialRead).status()).toBe(503);
    await expect(page.getByTestId('invite-read-error')).toHaveText(
      '邀请码读取失败，请重新读取后再操作。',
    );
    expect(initialWrites).toBe(0);
    await capture(page, info, 'invite-initial-read-failed');
    await page.unroute(`**${endpoint}current`);
    const initialRecovery = page.waitForResponse(matches('current', 'GET'));
    await page.getByRole('button', { name: '重新读取', exact: true }).click();
    const recoveredInitial = await initialRecovery;
    expect(recoveredInitial.status()).toBe(200);
    const recoveredInitialBody = await recoveredInitial.json();
    expect(recoveredInitialBody.code).toBe('0');
    await assertCurrent(page, recoveredInitialBody.data);
    expect(initialWrites).toBe(0);
    const original = await generateThroughDialog(page);
    await page
      .getByTestId('invite-dialog')
      .getByRole('button', { name: '关闭', exact: true })
      .click();
    await page.reload();
    await openInvite(page);
    await expect(page.getByTestId('invite-code-value')).toHaveText(original);

    let writes = 0;
    page.on('request', (request) => {
      if (new URL(request.url()).pathname === `${endpoint}generate` && request.method() === 'POST')
        writes += 1;
    });
    await page.route(`**${endpoint}current`, (route) =>
      route.fulfill({
        status: 503,
        contentType: 'application/json',
        body: JSON.stringify({ code: '503', message: 'Controlled read outage', data: null }),
      }),
    );
    const generated = page.waitForResponse(matches('generate', 'POST'));
    const failedRead = page.waitForResponse(matches('current', 'GET'));
    await page
      .getByTestId('invite-dialog')
      .getByRole('button', { name: '生成新邀请码', exact: true })
      .click();
    const created = await generated;
    expect(created.status()).toBe(200);
    const body = await created.json();
    expect(body.code).toBe('0');
    expect(body.data).toMatch(/^[a-z0-9]{8}$/);
    expect(body.data).not.toBe(original);
    expect((await failedRead).status()).toBe(503);
    await expect(page.getByTestId('invite-read-error')).toHaveText(
      '邀请码已生成，但读取当前状态失败。请重新读取，避免重复生成。',
    );
    await expect(
      page.getByTestId('invite-dialog').getByRole('button', { name: /^生成(新)?邀请码$/ }),
    ).toHaveCount(0);
    await expect(page.getByTestId('invite-code-value')).toHaveCount(0);
    expect(writes).toBe(1);
    await capture(page, info, 'invite-read-failed');
    await page.unroute(`**${endpoint}current`);
    const recovered = page.waitForResponse(matches('current', 'GET'));
    await page.getByRole('button', { name: '重新读取', exact: true }).click();
    const readback = await recovered;
    expect(readback.status()).toBe(200);
    const current = await readback.json();
    expect(current.code).toBe('0');
    expect(current.data.code).toBe(body.data);
    await assertCurrent(page, current.data);
    expect(writes, 'read retry must not repeat generation').toBe(1);
    await validate(page, original, true);
    await validate(page, body.data, true);
    await capture(page, info, 'invite-read-recovered');
  });

  // The UI fixes expiry at seven days. This existing API case checks the explicit
  // one-day endpoint contract, then observes that same persisted code in the UI.
  test('MI-03: should create invite code with the requested expiry', async ({ page }, info) => {
    const startedAt = Date.now();
    const response = await page.request.post(`${endpoint}generate?expiryDays=1`);
    expect(new URL(response.url()).origin).toBe(new URL(BASE_URL).origin);
    expect(response.status()).toBe(200);
    const body = await response.json();
    expect(body.code).toBe('0');
    expect(body.data).toMatch(/^[a-z0-9]{8}$/);
    const current = await page.request.get(`${endpoint}current`);
    expect(current.status()).toBe(200);
    const readback = await current.json();
    expect(readback.code).toBe('0');
    expect(readback.data.code).toBe(body.data);
    const expiry = Date.parse(readback.data.expiredAt);
    expect(expiry).toBeGreaterThanOrEqual(startedAt + 86400000 - 1000);
    expect(expiry).toBeLessThanOrEqual(Date.now() + 86400000 + 1000);
    await openMembers(page);
    await openInvite(page);
    await expect(page.getByTestId('invite-code-value')).toHaveText(body.data);
    await capture(page, info, 'invite-one-day');
  });

  test('MI-04: should revoke the displayed code and show actual remaining state', async ({
    page,
  }, info) => {
    await openMembers(page);
    await openInvite(page);
    const code = await generateThroughDialog(page);
    let revokeWrites = 0;
    page.on('request', (request) => {
      if (new URL(request.url()).pathname === `${endpoint}revoke` && request.method() === 'POST')
        revokeWrites += 1;
    });
    await page.route(`**${endpoint}revoke?**`, (route) =>
      route.fulfill({
        status: 503,
        contentType: 'application/json',
        body: JSON.stringify({ code: '503', message: 'Controlled revoke outage', data: false }),
      }),
    );
    const failedWrite = page.waitForResponse(matches('revoke', 'POST'));
    await page.getByRole('button', { name: '撤销当前邀请码', exact: true }).click();
    expect((await failedWrite).status()).toBe(503);
    await expect(page.getByText('邀请码撤销失败，请稍后重试。', { exact: true })).toBeVisible();
    await expect(page.getByTestId('invite-code-value')).toHaveText(code);
    await validate(page, code, true);
    await capture(page, info, 'invite-revoke-failed');
    await page.unroute(`**${endpoint}revoke?**`);
    await page.route(`**${endpoint}current`, (route) =>
      route.fulfill({
        status: 503,
        contentType: 'application/json',
        body: JSON.stringify({
          code: '503',
          message: 'Controlled post-revoke read outage',
          data: null,
        }),
      }),
    );
    const revoked = page.waitForResponse(matches('revoke', 'POST'));
    const failedRead = page.waitForResponse(matches('current', 'GET'));
    await page.getByRole('button', { name: '撤销当前邀请码', exact: true }).click();
    const response = await revoked;
    expect(response.status()).toBe(200);
    expect(new URL(response.url()).searchParams.get('code')).toBe(code);
    const body = await response.json();
    expect(body.code).toBe('0');
    expect(body.data).toBe(true);
    expect((await failedRead).status()).toBe(503);
    await expect(page.getByTestId('invite-read-error')).toHaveText(
      '当前邀请码已撤销，但读取剩余邀请码失败。请重新读取。',
    );
    await expect(page.getByTestId('invite-code-value')).toHaveCount(0);
    await expect(page.getByRole('button', { name: '撤销当前邀请码', exact: true })).toHaveCount(0);
    await validate(page, code, false);
    expect(revokeWrites).toBe(2);
    await capture(page, info, 'invite-revoked-read-failed');
    await page.unroute(`**${endpoint}current`);
    const loaded = page.waitForResponse(matches('current', 'GET'));
    await page.getByRole('button', { name: '重新读取', exact: true }).click();
    const current = await loaded;
    expect(current.status()).toBe(200);
    const readback = await current.json();
    expect(readback.code).toBe('0');
    expect(readback.data?.code).not.toBe(code);
    await assertCurrent(page, readback.data);
    expect(revokeWrites, 'read retry must not repeat revocation').toBe(2);
    await validate(page, code, false);
    await capture(page, info, 'invite-revoked');
    await page
      .getByTestId('invite-dialog')
      .getByRole('button', { name: '关闭', exact: true })
      .click();
    await page.reload();
    await openInvite(page);
    await assertCurrent(page, readback.data);
    await validate(page, code, false);
  });
});
