import { test, expect, type Page, type TestInfo } from '@playwright/test';
import fs from 'node:fs/promises';
import { provisionInternalApiUser } from '../../helpers/internal-api-owned-user';

const catalog = JSON.parse(await fs.readFile(new URL('../../../../scripts/internal-api/catalog.json', import.meta.url), 'utf8'));
test.use({ storageState: { cookies: [], origins: [] } });

async function capture(id: string, page: Page, info: TestInfo) {
  const scene = catalog.scenarios.find((row: { id: string }) => row.id === id);
  expect(scene, `Unregistered scene: ${id}`).toBeDefined();
  expect(scene.test).toBe(info.title);
  const path = info.outputPath(scene.screenshot);
  await page.screenshot({ path, fullPage: true, animations: 'disabled' });
  await info.attach(id, { path, contentType: 'image/png' });
  info.annotations.push({ type: 'internal-api-scenario', description: id });
}

test('dashboard usage and account logout survive missing randomUUID', async ({ browser, baseURL }, info) => {
  test.setTimeout(90_000);
  if (!baseURL) throw new Error('A managed runtime base URL is required');
  const actor = await provisionInternalApiUser(browser, baseURL);
  const { context, page, origin } = actor;
  const pageErrors: string[] = [], cleanupErrors: unknown[] = [], checks: object[] = [];
  const widgetResults: Array<{ path: string; status: number; code: unknown; error?: string }> = [];
  const widgetPending: Promise<void>[] = [];
  const reloadedRequests = new WeakSet<object>();
  let reloaded = false;
  let journeyError: unknown;
  page.on('pageerror', error => pageErrors.push(error.message));
  page.on('request', request => { if (reloaded) reloadedRequests.add(request); });
  page.on('response', response => {
    if (!reloadedRequests.has(response.request())) return;
    const path = new URL(response.url()).pathname;
    if (!['/api/inbox', '/api/workbench/stats'].includes(path)) return;
    widgetPending.push(response.json().then(payload => {
      widgetResults.push({ path, status: response.status(), code: payload.code });
    }).catch(error => {
      widgetResults.push({ path, status: response.status(), code: null, error: String(error) });
    }));
  });
  try {
    await expect(page).toHaveURL(/\/home(?:[?#].*)?$/);
    await context.addCookies([{ name: 'locale', value: 'zh-CN', url: origin }]);
    await context.addInitScript(() => {
      localStorage.setItem('locale', 'zh-CN');
      // Reproduce the precise capability absence seen on CI's HTTP origin.
      Object.defineProperty(globalThis.crypto, 'randomUUID', { value: undefined, configurable: true });
    });
    await page.reload({ waitUntil: 'commit' });
    reloaded = true;
    await page.waitForLoadState('load');
    expect(await page.evaluate(() => typeof crypto.randomUUID)).toBe('undefined');
    expect(await page.evaluate(() => typeof crypto.getRandomValues)).toBe('function');
    await expect(page.getByTestId('ab:dashboard:workbench:container')).toBeVisible();
    await expect(page.locator('header[data-hydrated="true"]')).toBeAttached();
    await expect(page.getByText('暂无待办事项', { exact: true })).toBeVisible();
    await expect(page.getByTestId('shortcuts-customize-button')).toBeVisible();
    await expect(page.getByTestId('stat-card-inbox_pending').getByText('0', { exact: true })).toBeVisible();
    await Promise.all(widgetPending);
    expect([...new Set(widgetResults.map(row => row.path))].sort()).toEqual(['/api/inbox', '/api/workbench/stats']);
    expect(widgetResults.every(row => row.status === 200 && row.code === '0' && !row.error)).toBe(true);
    await expect(page.getByRole('heading', { name: '出错了！', exact: true })).toHaveCount(0);
    await capture('SESSION-01', page, info);

    await page.getByTestId('user-menu').locator('button').first().click();
    await expect(page.getByTestId('user-dropdown')).toBeVisible();
    const logoutLink = page.getByTestId('user-dropdown').locator('a[href="/logout"]');
    await expect(logoutLink).toBeVisible();
    await capture('SESSION-02', page, info);
    await logoutLink.click();
    await expect(page).toHaveURL(/\/logout$/);
    const confirm = page.getByRole('button', { name: '确认退出', exact: true });
    await expect(confirm).toBeVisible();
    await capture('SESSION-03', page, info);
    await confirm.click();
    await expect(page).toHaveURL(/\/login(?:[?#].*)?$/);
    await expect(page.locator('input#identifier')).toBeVisible();
    const identity = await context.request.get('/api/auth/me');
    checks.push({ method: 'get', path: '/api/auth/me', status: identity.status() });
    expect(identity.status()).toBe(401);
    expect(pageErrors).toEqual([]);
    await capture('SESSION-04', page, info);
  } catch (error) { journeyError = error; }
  finally {
    try {
      // Dispose the exact actor session even when the browser journey fails early.
      const logout = await context.request.post('/logout', { form: {}, headers: { Origin: origin }, maxRedirects: 0 });
      expect(logout.status()).toBe(302);
      expect((await context.request.get('/api/auth/me')).status()).toBe(401);
    } catch (error) { cleanupErrors.push(error); }
    try { await actor.dispose(); } catch (error) { cleanupErrors.push(error); }
    await info.attach('session-compatibility-evidence', { body: JSON.stringify({
      claim: 'owned-browser-capability-and-session-journey; no response-schema acceptance',
      userPid: actor.userPid, checks, widgetResults, pageErrors,
      journeyError: journeyError ? String(journeyError) : null, sessionCleanup: actor.sessionCleanup, cleanupErrors: cleanupErrors.map(String),
    }), contentType: 'application/json' });
  }
  if (journeyError || cleanupErrors.length) throw new AggregateError(
    [...(journeyError ? [journeyError] : []), ...cleanupErrors], 'HTTP capability/session journey or cleanup failed',
  );
});
