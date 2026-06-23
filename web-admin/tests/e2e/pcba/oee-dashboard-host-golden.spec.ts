/**
 * PCBA OEE dashboard host golden.
 *
 * This spec is intentionally self-authenticated and `--no-deps` friendly: the
 * companion host script prepares/imports/seeds the running stack, then this
 * spec validates the real dashboard route through Vite -> BFF -> backend.
 */

import { test, expect } from '@playwright/test';
import { createCookieSessionStorage } from 'react-router';

const ADMIN = {
  email: process.env.ADMIN_EMAIL ?? 'admin@auraboot.com',
  password: process.env.ADMIN_PASSWORD ?? 'Test2026x',
};

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

test.use({
  storageState: { cookies: [], origins: [] },
  viewport: { width: 1600, height: 900 },
});

async function persistSessionCookie(
  context: import('@playwright/test').BrowserContext,
  baseURL: string,
  jwt: string,
): Promise<void> {
  const session = await sessionStorage.getSession();
  session.set('jwtToken', jwt);
  const setCookie = await sessionStorage.commitSession(session, { maxAge: 60 * 60 * 24 });
  const value = setCookie.match(/__session=([^;]+)/)?.[1];
  expect(value, 'failed to build __session cookie').toBeTruthy();

  await context.addCookies([
    { name: '__session', value: value!, url: baseURL, httpOnly: true, secure: false, sameSite: 'Lax' },
    { name: '__session', value: value!, domain: 'localhost', path: '/', httpOnly: true, secure: false, sameSite: 'Lax' },
    { name: '__session', value: value!, domain: '127.0.0.1', path: '/', httpOnly: true, secure: false, sameSite: 'Lax' },
  ]);
}

async function loginAndSelectBusinessTenant(
  context: import('@playwright/test').BrowserContext,
  request: import('@playwright/test').APIRequestContext,
  baseURL: string,
): Promise<void> {
  const login = await request.post(`${baseURL}/api/auth/login`, {
    data: ADMIN,
    headers: { 'Content-Type': 'application/json' },
  });
  const loginBody = await login.json().catch(() => ({}));
  expect(login.ok(), `login failed: ${login.status()} ${JSON.stringify(loginBody)}`).toBeTruthy();

  let jwt = loginBody?.data?.jwt;
  expect(jwt, `login response has no jwt: ${JSON.stringify(loginBody)}`).toBeTruthy();

  if (!loginBody?.data?.tenantId) {
    const spaces = await request.get(`${baseURL}/api/tenant-selection/my-spaces`, {
      headers: { Authorization: `Bearer ${jwt}` },
    });
    const spacesBody = await spaces.json().catch(() => ({}));
    expect(spaces.ok(), `my-spaces failed: ${spaces.status()} ${JSON.stringify(spacesBody)}`).toBeTruthy();
    const businessTenant = spacesBody?.data?.find(
      (space: { spaceType?: string; tenantId?: string | number }) =>
        space.spaceType === 'business' && space.tenantId,
    );
    expect(businessTenant, `no business tenant in ${JSON.stringify(spacesBody)}`).toBeTruthy();

    const select = await request.post(`${baseURL}/api/tenant-selection/process`, {
      headers: { Authorization: `Bearer ${jwt}`, 'Content-Type': 'application/json' },
      data: { action: 'select', tenantId: businessTenant.tenantId },
    });
    const selectBody = await select.json().catch(() => ({}));
    expect(select.ok(), `tenant selection failed: ${select.status()} ${JSON.stringify(selectBody)}`).toBeTruthy();
    jwt = selectBody?.data?.jwt;
    expect(jwt, `tenant selection response has no jwt: ${JSON.stringify(selectBody)}`).toBeTruthy();
  }

  await persistSessionCookie(context, baseURL, jwt);
}

test.beforeEach(async ({ context, request, baseURL }) => {
  expect(baseURL, 'PLAYWRIGHT_BASE_URL/baseURL is required').toBeTruthy();
  await loginAndSelectBusinessTenant(context, request, baseURL!);
});

test('OEE dashboard renders seeded host data and calls real APIs', async ({ page }, testInfo) => {
  test.setTimeout(90_000);

  const consoleErrors: string[] = [];
  const pageErrors: string[] = [];
  const failedRequests: string[] = [];
  let summaryApiResponses = 0;

  page.on('console', (message) => {
    if (message.type() === 'error') consoleErrors.push(message.text());
  });
  page.on('pageerror', (error) => pageErrors.push(String(error)));
  page.on('requestfailed', (request) => {
    const url = request.url();
    const failure = request.failure()?.errorText ?? 'unknown';
    if (failure === 'net::ERR_ABORTED') return;
    if (!url.includes('/api/notifications/stream') && !url.includes('/api/collect')) {
      failedRequests.push(`${url}: ${failure}`);
    }
  });
  page.on('response', (response) => {
    if (
      response.url().includes('/api/manufacturing/oee/fleet/summary') &&
      response.status() === 200
    ) {
      summaryApiResponses += 1;
    }
  });

  const fleetResponse = page.waitForResponse(
    (response) =>
      response.url().includes('/api/manufacturing/oee/fleet?') && response.status() === 200,
    { timeout: 30_000 },
  );
  const summaryResponse = page.waitForResponse(
    (response) =>
      response.url().includes('/api/manufacturing/oee/fleet/summary') && response.status() === 200,
    { timeout: 30_000 },
  );

  await page.goto('/dashboards/view/pe_oee_dashboard', { waitUntil: 'domcontentloaded' });

  const [fleet, summary] = await Promise.all([fleetResponse, summaryResponse]);
  const fleetJson = await fleet.json();
  const summaryJson = await summary.json();

  const records = fleetJson?.data?.records ?? [];
  const summaryRow = summaryJson?.data?.records?.[0] ?? {};
  expect(records).toHaveLength(2);
  expect(records.find((row: { code?: string }) => row.code === 'SMT-01')).toMatchObject({
    oeePct: 57,
    availabilityPct: 80,
    performancePct: 75,
    qualityPct: 95,
  });
  expect(records.find((row: { code?: string }) => row.code === 'TEST-01')).toMatchObject({
    oeePct: 42.2,
    availabilityPct: 62.5,
    performancePct: 75,
    qualityPct: 90,
  });
  expect(summaryRow).toMatchObject({
    oeePct: 49.6,
    teepPct: 47.8,
    equipmentWithDataCount: 2,
  });

  const main = page.locator('main, [role="main"], .ant-layout-content').first();
  await expect(main.getByText('OEE 设备效率大屏')).toBeVisible({ timeout: 30_000 });
  await expect(main).toContainText('TEEP');
  await expect(main.getByText('SMT-01')).toBeVisible();
  await expect(main.getByText('TEST-01')).toBeVisible();
  await expect(main.getByText('50%', { exact: true })).toBeVisible();
  await expect(main.getByText('71%', { exact: true })).toBeVisible();
  await expect(main.getByText('75%', { exact: true })).toBeVisible();
  await expect(main.getByText('93%', { exact: true })).toBeVisible();
  await expect(main.getByText('48%', { exact: true })).toBeVisible();
  await expect(main).toContainText('设备数2', { timeout: 30_000 });
  await expect(main.getByText('57', { exact: true }).first()).toBeVisible();
  await expect(main.getByText('42.20', { exact: true }).first()).toBeVisible();
  await expect(main.getByText('53.40', { exact: true }).first()).toBeVisible();
  expect(summaryApiResponses, 'six KPI cards should share one summary API response').toBe(1);

  await page.screenshot({
    path: testInfo.outputPath('oee-host-dashboard.png'),
    fullPage: true,
  });

  expect(pageErrors, `page errors: ${pageErrors.join('\n')}`).toEqual([]);
  expect(consoleErrors, `console errors: ${consoleErrors.join('\n')}`).toEqual([]);
  expect(failedRequests, `failed requests: ${failedRequests.join('\n')}`).toEqual([]);
});
