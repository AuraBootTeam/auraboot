import { expect, test } from '@playwright/test';
import { createCookieSessionStorage } from 'react-router';
import path from 'node:path';
import { BACKEND_URL, BASE_URL as WEB_BASE_URL } from '../../helpers/environments';

const EVIDENCE_DIR = path.join(process.env.AURA_EVIDENCE_ROOT || '/tmp', 'ui-golden-20260914');
const sessionStorage = createCookieSessionStorage({
  cookie: {
    name: '__session',
    httpOnly: true,
    path: '/',
    sameSite: 'lax',
    secrets: [process.env.SESSION_SECRET || 'dev-only-secret-do-not-use-in-production'],
    secure: false,
  },
});

async function authenticate(page: import('@playwright/test').Page) {
  const seedResponse = await page.request.post(
    `${BACKEND_URL}/api/test/seed?testRunId=open-platform-ui-20260914`,
    { timeout: 30_000 },
  );
  expect(seedResponse.ok()).toBeTruthy();
  const seed = await seedResponse.json();
  expect(seed.jwt).toEqual(expect.any(String));
  const session = await sessionStorage.getSession();
  session.set('jwtToken', seed.jwt);
  const setCookie = await sessionStorage.commitSession(session, { maxAge: 604800 });
  const value = setCookie.match(/__session=([^;]+)/)?.[1];
  expect(value).toBeTruthy();
  await page.context().addCookies([
    { name: '__session', value: value!, url: WEB_BASE_URL, httpOnly: true, sameSite: 'Lax' },
    { name: 'locale', value: 'zh-CN', url: WEB_BASE_URL, sameSite: 'Lax' },
  ]);
  await page.addInitScript(() => localStorage.setItem('locale', 'zh-CN'));
}

async function capture(page: import('@playwright/test').Page, id: string, fullPage = true) {
  await page.screenshot({
    path: path.join(EVIDENCE_DIR, `${id}.png`),
    fullPage,
    timeout: 30_000,
  });
}

async function dismissToasts(page: import('@playwright/test').Page) {
  const closeButtons = page.getByRole('button', { name: 'Close notification' });
  while ((await closeButtons.count()) > 0) {
    const previousCount = await closeButtons.count();
    await closeButtons.first().click();
    await expect(closeButtons).toHaveCount(previousCount - 1);
  }
  await expect(page.getByRole('alert')).toHaveCount(0);
}

test.describe('Open Platform golden journey', () => {
  test.setTimeout(120_000);
  test.use({
    storageState: { cookies: [], origins: [] },
    locale: 'zh-CN',
    viewport: { width: 1440, height: 1000 },
  });

  test('covers application, installation, credential and revocation states', async ({
    page,
    context,
  }) => {
    await authenticate(page);
    await page.goto('/');
    await expect(page).not.toHaveURL(/\/login|\/setup/);
    await page.getByRole('button', { name: /User avatar/ }).click();
    const menuEntry = page.getByTestId('open-platform-link');
    await expect(menuEntry).toBeVisible();
    await capture(page, 'OP-OPS-01');
    await menuEntry.click();
    await expect(page.getByTestId('open-platform-page')).toBeVisible();
    await expect(page.getByTestId('open-platform-page')).not.toContainText(
      /Page Unavailable|加载失败|Page not found/,
    );
    await expect(page.getByTestId('open-platform-empty')).toBeVisible();
    await expect(page.getByRole('heading', { name: '暂无外部应用' })).toBeVisible();
    await capture(page, 'OP-UI-03');

    await page.route('**/api/open-platform/applications', async (route) => {
      await route.fulfill({
        status: 503,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'service_unavailable', message: '受控加载失败' }),
      });
    });
    await page.goto('/');
    await page.getByRole('button', { name: /User avatar/ }).click();
    await page.getByTestId('open-platform-link').click();
    await expect(page.getByTestId('open-platform-page')).toContainText('受控加载失败');
    await expect(page.getByRole('button', { name: '重试' })).toBeVisible();
    await expect(page.getByTestId('sidebar')).toBeVisible();
    await capture(page, 'OP-UI-04');
    await page.unroute('**/api/open-platform/applications');
    await page.getByRole('button', { name: '重试' }).click();
    await expect(page.getByTestId('open-platform-empty')).toBeVisible();

    await page.getByTestId('open-platform-create-app').click();
    const createDialog = page.getByRole('dialog', { name: '创建外部应用' });
    await expect(createDialog).toBeVisible();
    await expect(createDialog.getByRole('button', { name: '新建' })).toBeDisabled();
    await capture(page, 'OP-UI-05');
    await page.getByTestId('open-platform-app-name').fill('金蝶 ERP 连接器');
    await createDialog.getByRole('textbox', { name: '描述' }).fill('生产订单与库存事件统一接入');
    await createDialog.getByRole('button', { name: '新建' }).click();
    await expect(page.getByText('金蝶 ERP 连接器')).toBeVisible();
    await expect(page.getByText('生产订单与库存事件统一接入')).toBeVisible();
    await expect(page.getByTestId('open-platform-page')).not.toContainText(
      /01[A-Z0-9]{20,}|[0-9a-f]{8}-[0-9a-f-]{27,}/i,
    );
    await capture(page, 'OP-UI-06');
    await dismissToasts(page);

    await page.getByRole('button', { name: '添加安装' }).click();
    const installDialog = page.getByRole('dialog', { name: '安装应用' });
    await installDialog.getByLabel('环境').selectOption('production');
    await installDialog.getByTestId('open-platform-rate-limit').fill('1200');
    const scopeChecks = installDialog.getByRole('checkbox');
    await expect(scopeChecks.first()).toBeChecked();
    await expect(scopeChecks).toHaveCount(4);
    await installDialog.getByLabel('assets.read').check();
    await installDialog.getByLabel('automation.events.write').check();
    await installDialog.getByLabel('openapi.profile.read').check();
    await capture(page, 'OP-UI-07');
    await installDialog.getByRole('button', { name: '安装' }).click();
    await expect(page.getByText('生产环境')).toBeVisible();
    await expect(page.getByText('限流: 1200/min')).toBeVisible();

    await page.getByRole('button', { name: '新建凭据' }).click();
    const secretDialog = page.getByRole('dialog', { name: '立即保存此凭据' });
    await expect(secretDialog).toContainText('Client Secret 仅显示一次');
    await expect(secretDialog.getByText('client_id')).toBeVisible();
    await expect(secretDialog.getByText('client_secret')).toBeVisible();
    const clientId = (await secretDialog.locator('code').nth(0).textContent())?.trim();
    const clientSecret = (await secretDialog.locator('code').nth(1).textContent())?.trim();
    expect(clientId).toMatch(/^ab_client_/);
    expect(clientSecret).toEqual(expect.any(String));
    await dismissToasts(page);
    await secretDialog
      .locator('code')
      .nth(1)
      .evaluate((element) => {
        element.textContent = '[redacted for evidence]';
      });
    await capture(page, 'OP-UI-08');
    await secretDialog.getByRole('button', { name: '我已保存' }).click();

    const tokenResponse = await page.request.post(`${BACKEND_URL}/oauth2/token`, {
      form: {
        grant_type: 'client_credentials',
        client_id: clientId!,
        client_secret: clientSecret!,
        scope: 'assets.manage assets.read automation.events.write openapi.profile.read',
      },
    });
    expect(tokenResponse.status()).toBe(200);
    const token = await tokenResponse.json();
    expect(token.token_type).toBe('Bearer');
    expect(String(token.scope).split(' ').sort()).toEqual([
      'assets.manage',
      'assets.read',
      'automation.events.write',
      'openapi.profile.read',
    ]);
    expect(token.access_token).toEqual(expect.any(String));
    expect(token.expires_in).toBeGreaterThan(0);

    const whoAmIResponse = await page.request.get(`${BACKEND_URL}/api/open/v1/whoami`, {
      headers: {
        Authorization: `Bearer ${token.access_token}`,
        'X-Request-Id': 'open-platform-golden-whoami',
        'X-Tenant-Id': 'spoofed-tenant-must-be-ignored',
      },
    });
    expect(whoAmIResponse.status()).toBe(200);
    const principal = await whoAmIResponse.json();
    expect(principal.environment).toBe('production');
    expect(principal.scopes).toEqual(
      expect.arrayContaining(['automation.events.write', 'openapi.profile.read']),
    );
    expect(principal.applicationPid).toEqual(expect.any(String));
    expect(principal.installationPid).toEqual(expect.any(String));

    const externalEvent = {
      id: 'order-10001-created',
      type: 'order.created',
      schemaVersion: 1,
      occurredAt: '2026-09-14T08:00:00Z',
      subject: { type: 'order', pid: 'order-10001' },
      sequence: 1,
      data: { orderNo: 'SO-10001', source: 'kingdee' },
    };
    const eventResponse = await page.request.post(
      `${BACKEND_URL}/api/open/v1/event-sources/kingdee/events`,
      {
        headers: {
          Authorization: `Bearer ${token.access_token}`,
          'Idempotency-Key': 'open-platform-golden-order-10001',
        },
        data: externalEvent,
      },
    );
    expect(eventResponse.status()).toBe(202);
    expect(await eventResponse.json()).toMatchObject({
      eventId: externalEvent.id,
      duplicate: false,
    });

    const assetCreateResponse = await page.request.post('/api/dynamic/tasset_asset/create', {
      data: {
        tasset_as_code: 'AST-OPEN-API-001',
        tasset_as_name: 'Open API 验证笔记本',
        tasset_as_serial: 'OPEN-API-GOLDEN-001',
        tasset_as_status: 'available',
        tasset_as_location: 'Shanghai Lab',
      },
    });
    expect(assetCreateResponse.status()).toBe(200);
    const assetCreateBody = await assetCreateResponse.json();
    expect(assetCreateBody.code).toBe('0');
    const assetPid = assetCreateBody.data.pid as string;

    const assetReadResponse = await page.request.get(
      `${BACKEND_URL}/api/open/v1/resources/assets/${assetPid}`,
      { headers: { Authorization: `Bearer ${token.access_token}` } },
    );
    expect(assetReadResponse.status()).toBe(200);
    const publishedAsset = await assetReadResponse.json();
    expect(publishedAsset).toMatchObject({
      pid: assetPid,
      name: 'Open API 验证笔记本',
      serialNumber: 'OPEN-API-GOLDEN-001',
      status: 'available',
    });
    expect(Object.keys(publishedAsset)).not.toContain('tasset_as_name');

    const idempotencyKey = 'open-platform-asset-assign-0001';
    const assignAsset = () =>
      page.request.post(`${BACKEND_URL}/api/open/v1/commands/assets.assign:execute`, {
        headers: {
          Authorization: `Bearer ${token.access_token}`,
          'Idempotency-Key': idempotencyKey,
        },
        data: { targetPid: assetPid, input: { assignee: 'alice' } },
      });
    const assignmentResponse = await assignAsset();
    expect(assignmentResponse.status()).toBe(200);
    expect(await assignmentResponse.json()).toMatchObject({
      command: 'assets.assign',
      idempotentReplay: false,
      resource: { pid: assetPid, assignedTo: 'alice', status: 'in_use' },
    });
    const replayResponse = await assignAsset();
    expect(replayResponse.status()).toBe(200);
    expect(await replayResponse.json()).toMatchObject({
      command: 'assets.assign',
      idempotentReplay: true,
      resource: { pid: assetPid, assignedTo: 'alice', status: 'in_use' },
    });

    const internalResourceProbe = await page.request.get(
      `${BACKEND_URL}/api/open/v1/resources/tasset_asset/${assetPid}`,
      { headers: { Authorization: `Bearer ${token.access_token}` } },
    );
    expect(internalResourceProbe.status()).toBe(404);

    await page.getByRole('button', { name: '运维' }).click();
    const operations = page.getByTestId('open-platform-operations-panel');
    await expect(operations).toBeVisible();
    await expect(operations).toContainText('调用审计');
    await expect(operations).toContainText('open-platform-golden-whoami');
    await capture(page, 'OP-OPS-02');
    await capture(page, 'OP-OPS-03');

    await operations.getByLabel('request_id').fill('open-platform-golden-whoami');
    await expect(operations).toContainText('GET /api/open/v1/whoami');
    await expect(operations).not.toContainText('assets.assign');
    await capture(page, 'OP-OPS-04');
    await operations.getByLabel('request_id').fill('request-id-with-no-match');
    await expect(operations).toContainText(/没有.*筛选条件的调用/);
    await capture(page, 'OP-OPS-04-empty');
    await operations.getByLabel('request_id').fill('');

    await page.route('**/api/open-platform/installations/*/audits*', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: '0',
          data: [
            {
              requestId: 'req-forbidden',
              method: 'GET',
              path: '/api/open/v1/resources/assets/a1',
              status: 403,
              durationMs: 12,
              occurredAt: '2026-09-14T08:00:00Z',
            },
            {
              requestId: 'req-throttled',
              method: 'GET',
              path: '/api/open/v1/whoami',
              status: 429,
              durationMs: 3,
              occurredAt: '2026-09-14T08:01:00Z',
            },
            {
              requestId: 'req-failed',
              method: 'POST',
              path: '/api/open/v1/event-sources/erp/events',
              status: 500,
              durationMs: 81,
              occurredAt: '2026-09-14T08:02:00Z',
            },
          ],
        }),
      });
    });
    await operations.getByTestId('open-platform-operations-refresh').click();
    await expect(operations).toContainText('req-throttled');
    await capture(page, 'OP-OPS-05');
    await page.unroute('**/api/open-platform/installations/*/audits*');

    let replayed = false;
    await page.route(
      '**/api/open-platform/installations/*/webhook-deliveries/*/replay',
      async (route) => {
        replayed = true;
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ code: '0', data: null }),
        });
      },
    );
    await page.route('**/api/open-platform/installations/*/webhook-deliveries*', async (route) => {
      const deliveries = [
        {
          pid: 'delivery-success',
          subscriptionName: '金蝶订单事件',
          eventId: 'evt-success',
          status: 'success',
          retryCount: 0,
          maxRetries: 5,
          responseStatus: 204,
          createdAt: '2026-09-14T07:58:00Z',
          replayable: false,
        },
        {
          pid: 'delivery-pending',
          subscriptionName: '金蝶库存事件',
          eventId: 'evt-pending',
          status: 'pending',
          retryCount: 1,
          maxRetries: 5,
          createdAt: '2026-09-14T07:59:00Z',
          replayable: false,
        },
        {
          pid: 'delivery-golden',
          subscriptionName: '金蝶库存事件',
          eventId: 'evt-golden',
          status: replayed ? 'pending' : 'dead_letter',
          retryCount: replayed ? 0 : 5,
          maxRetries: 5,
          responseStatus: replayed ? undefined : 503,
          failureReason: replayed ? undefined : 'HTTP 503',
          replayCount: replayed ? 1 : 0,
          createdAt: '2026-09-14T08:00:00Z',
          replayable: !replayed,
        },
      ];
      const status = new URL(route.request().url()).searchParams.get('status');
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: '0',
          data: status ? deliveries.filter((item) => item.status === status) : deliveries,
        }),
      });
    });
    await operations.getByTestId('open-platform-operations-refresh').click();
    await expect(operations).toContainText('金蝶库存事件');
    await capture(page, 'OP-OPS-06');
    await operations.getByLabel('投递状态').selectOption('dead_letter');
    await expect(operations).toContainText('HTTP 503');
    await expect(operations).not.toContainText('evt-success');
    await expect(operations).not.toContainText('evt-pending');
    await capture(page, 'OP-OPS-07');
    await operations.getByRole('button', { name: '重放' }).click();
    const replayConfirmation = operations.getByRole('alert');
    await expect(replayConfirmation).toContainText('重放此投递？');
    await replayConfirmation.scrollIntoViewIfNeeded();
    await capture(page, 'OP-OPS-08', false);
    await operations.getByRole('button', { name: '确认重放' }).click();
    await expect(operations).toContainText(/没有.*筛选条件的投递/);
    await operations.getByLabel('投递状态').selectOption('');
    await expect(operations).toContainText('evt-golden');
    await capture(page, 'OP-OPS-09');
    await dismissToasts(page);
    await expect(operations.getByRole('button', { name: '重放' })).toHaveCount(0);
    await capture(page, 'OP-OPS-10');
    await page.unroute('**/api/open-platform/installations/*/webhook-deliveries*');
    await page.unroute('**/api/open-platform/installations/*/webhook-deliveries/*/replay');

    await page.route('**/api/open-platform/installations/*/overview*', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: '0',
          data: {
            windowHours: 24,
            totalCalls: 0,
            errorCalls: 0,
            throttledCalls: 0,
            errorRate: 0,
            p95DurationMs: 0,
            deadLetterCount: 0,
          },
        }),
      });
    });
    await page.route('**/api/open-platform/installations/*/audits*', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: '0', data: [] }),
      });
    });
    await page.route('**/api/open-platform/installations/*/webhook-deliveries*', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: '0', data: [] }),
      });
    });
    await operations.getByTestId('open-platform-operations-refresh').click();
    await expect(operations).toContainText(/没有.*筛选条件的调用/);
    await capture(page, 'OP-OPS-13');
    await page.unroute('**/api/open-platform/installations/*/overview*');
    await page.unroute('**/api/open-platform/installations/*/audits*');
    await page.unroute('**/api/open-platform/installations/*/webhook-deliveries*');

    await page.route('**/api/open-platform/installations/*/overview*', async (route) => {
      await route.fulfill({
        status: 503,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'service_unavailable', message: '受控运维加载失败' }),
      });
    });
    await operations.getByTestId('open-platform-operations-refresh').click();
    await expect(operations).toContainText('受控运维加载失败');
    await capture(page, 'OP-OPS-14');
    await page.unroute('**/api/open-platform/installations/*/overview*');
    await operations.getByRole('button', { name: /重试|Retry/ }).click();
    await expect(operations).toContainText('调用审计');

    await page.getByRole('button', { name: '查看凭据' }).click();
    const credentialList = page.locator('[data-testid^="open-platform-credentials-"]');
    await expect(credentialList).toContainText('ab_client_');
    await credentialList.getByRole('button', { name: '轮换' }).click();
    const rotateDialog = page.getByRole('dialog', { name: '轮换凭据' });
    await expect(rotateDialog).toContainText('宽限期');
    await expect(rotateDialog).toContainText('已签发的 Token');
    await rotateDialog.getByRole('spinbutton').fill('1');
    await expect(rotateDialog.getByRole('button', { name: '轮换' })).toBeDisabled();
    await capture(page, 'OP-OPS-12');
    await rotateDialog.getByRole('button', { name: '取消' }).click();
    await credentialList.getByRole('button', { name: '轮换' }).click();
    await page.getByRole('dialog', { name: '轮换凭据' }).getByRole('spinbutton').fill('1440');
    await rotateDialog.getByRole('button', { name: '轮换' }).click();
    const rotatedSecretDialog = page.getByRole('dialog', { name: '立即保存此凭据' });
    await expect(rotatedSecretDialog).toBeVisible();
    const rotatedClientId = (
      await rotatedSecretDialog.locator('code').nth(0).textContent()
    )?.trim();
    const rotatedClientSecret = (
      await rotatedSecretDialog.locator('code').nth(1).textContent()
    )?.trim();
    await rotatedSecretDialog
      .locator('code')
      .nth(1)
      .evaluate((element) => {
        element.textContent = '[redacted for evidence]';
      });
    await capture(page, 'OP-OPS-11');
    await rotatedSecretDialog.getByRole('button', { name: '我已保存' }).click();

    const rotatedTokenResponse = await page.request.post(`${BACKEND_URL}/oauth2/token`, {
      form: {
        grant_type: 'client_credentials',
        client_id: rotatedClientId!,
        client_secret: rotatedClientSecret!,
        scope: 'openapi.profile.read',
      },
    });
    expect(rotatedTokenResponse.status()).toBe(200);
    const oldCredentialDuringGrace = await page.request.post(`${BACKEND_URL}/oauth2/token`, {
      form: {
        grant_type: 'client_credentials',
        client_id: clientId!,
        client_secret: clientSecret!,
        scope: 'openapi.profile.read',
      },
    });
    expect(oldCredentialDuringGrace.status()).toBe(200);

    await credentialList.getByRole('button', { name: '吊销' }).first().click();
    const revokeDialog = page.getByRole('dialog', { name: '确认吊销凭据？' });
    await expect(revokeDialog).toContainText('Access Token 将立即失效');
    await capture(page, 'OP-UI-09');
    await revokeDialog.getByRole('button', { name: '取消' }).click();

    await page.getByRole('button', { name: '编辑 Scope' }).click();
    const scopeDialog = page.getByRole('dialog', { name: '编辑 Scope' });
    await expect(scopeDialog).toContainText('立即吊销此安装的现有 Access Token');
    await capture(page, 'OP-UI-10');
    await scopeDialog.getByRole('button', { name: '保存' }).click();
    await expect(scopeDialog).toBeHidden();
    await dismissToasts(page);
    const revokedTokenResponse = await page.request.get(`${BACKEND_URL}/api/open/v1/whoami`, {
      headers: { Authorization: `Bearer ${token.access_token}` },
    });
    expect(revokedTokenResponse.status()).toBe(401);

    await page.getByRole('button', { name: '停用安装' }).click();
    const disableInstallationDialog = page.getByRole('dialog', { name: '确认停用访问？' });
    await expect(disableInstallationDialog).toContainText('立即吊销有效 Token');
    await capture(page, 'OP-UI-11');
    await disableInstallationDialog.getByRole('button', { name: '停用' }).click();
    await expect(page.getByText('已停用').first()).toBeVisible();
    await dismissToasts(page);

    await page.getByRole('button', { name: '停用' }).first().click();
    const disableApplicationDialog = page.getByRole('dialog', { name: '确认停用访问？' });
    await expect(disableApplicationDialog).toContainText('无法在本页面恢复');
    await capture(page, 'OP-UI-12');
    await disableApplicationDialog.getByRole('button', { name: '停用' }).click();
    await expect(page.getByRole('button', { name: '添加安装' })).toBeDisabled();
    await dismissToasts(page);
    await capture(page, 'OP-UI-02');

    const popupPromise = context.waitForEvent('page');
    await page.getByRole('button', { name: /API 参考/ }).click();
    const apiReference = await popupPromise;
    await apiReference.waitForLoadState('domcontentloaded');
    await expect(apiReference).toHaveURL(
      /swagger-ui\/index\.html\?urls\.primaryName=open-platform/,
    );
    await apiReference.goto(`${BACKEND_URL}/swagger-ui/index.html?urls.primaryName=open-platform`);
    await expect(apiReference.locator('.opblock').first()).toBeVisible({ timeout: 30_000 });
    await expect(apiReference.locator('body')).not.toContainText(
      /Whitelabel Error Page|404 Not Found|Loading page configuration/,
    );
    await capture(apiReference, 'OP-UI-13', false);
    await apiReference.close();

    await page.setViewportSize({ width: 390, height: 844 });
    await expect(page.getByTestId('sidebar')).toHaveClass(/-translate-x-full/);
    await expect
      .poll(() =>
        page.getByTestId('sidebar').evaluate((element) => element.getBoundingClientRect().right),
      )
      .toBeLessThanOrEqual(1);
    await expect(page.getByTestId('open-platform-page')).toBeVisible();
    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    );
    expect(overflow).toBeLessThanOrEqual(1);
    await capture(page, 'OP-OPS-16-top', false);
    await expect(operations).toContainText('open-platform-golden-whoami');
    await operations.scrollIntoViewIfNeeded();
    await capture(page, 'OP-OPS-16', false);
  });
});
