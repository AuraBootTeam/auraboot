import type { Page } from '@playwright/test';
import { BASE_URL } from './environments';

/**
 * Login through the application session flow, falling back to the real login form.
 * This helper is platform-owned and intentionally contains no product fixtures.
 */
export async function loginViaUI(page: Page, email: string, password: string): Promise<void> {
  try {
    const loginResp = await page.request.post(`${BASE_URL}/login`, {
      form: {
        identifier: email,
        password,
        remember: 'on',
        redirectTo: '/',
      },
      maxRedirects: 0,
    });
    const setCookie = loginResp.headers()['set-cookie'];
    const match = setCookie?.match(/__session=([^;]+)/);
    if (!match?.[1]) {
      throw new Error('login action did not return __session cookie');
    }
    const cookieBase = {
      name: '__session',
      value: match[1],
      path: '/',
      httpOnly: true,
      sameSite: 'Lax' as const,
      expires: Math.floor(Date.now() / 1000) + 604800,
    };
    await page.context().addCookies([
      { ...cookieBase, domain: 'localhost' },
      { ...cookieBase, domain: '127.0.0.1' },
    ]);

    await page.goto('/home', { waitUntil: 'domcontentloaded' });
    if (/\/tenant-selection(?:$|\?)/.test(page.url())) {
      const spacesResp = await page.request.get(`${BASE_URL}/api/tenant-selection/my-spaces`);
      if (spacesResp.ok()) {
        const spacesBody = await spacesResp.json();
        const spaces = Array.isArray(spacesBody?.data) ? spacesBody.data : [];
        const bizSpace = spaces.find((space: any) => space?.spaceType === 'business');
        if (bizSpace?.tenantId) {
          const selectResp = await page.request.post(`${BASE_URL}/api/tenant-selection/process`, {
            headers: { 'Content-Type': 'application/json' },
            data: { action: 'select', tenantId: bizSpace.tenantId },
          });
          if (selectResp.ok()) {
            const selectBody = await selectResp.json();
            const tenantJwt = String(selectBody?.data?.jwt ?? '');
            if (tenantJwt) {
              const tenantCookieBase = { ...cookieBase, value: tenantJwt };
              await page.context().addCookies([
                { ...tenantCookieBase, domain: 'localhost' },
                { ...tenantCookieBase, domain: '127.0.0.1' },
              ]);
              await page.goto('/home', { waitUntil: 'domcontentloaded' });
            }
          }
        }
      }
    }

    if (!/\/login(?:$|\?)/.test(page.url())) {
      return;
    }
  } catch {
    // Continue with the real UI form when direct session bootstrap is unavailable.
  }

  await page.goto('/login', { waitUntil: 'domcontentloaded' });
  await page
    .locator('[data-testid="login-page-root"][data-hydrated="true"]')
    .waitFor({ state: 'attached', timeout: 10_000 });
  const emailInput = page.locator('input#identifier, input#email').first();
  await emailInput.waitFor({ state: 'visible', timeout: 10_000 });
  await emailInput.click();
  await emailInput.fill(email);

  const passwordInput = page.locator('input#password').first();
  await passwordInput.click();
  await passwordInput.fill(password);

  await page.getByRole('button', { name: /立即登录|login|登录|sign in/i }).click();
  await page.waitForFunction(() => !window.location.pathname.endsWith('/login'), undefined, {
    timeout: 15_000,
  });
}


/**
 * A fresh UI login can land the session in the System space (platform_admin
 * only), which lacks page-management permissions like page.page.manage —
 * page-seeding then 403s. Explicitly select the account's business space via
 * the BFF switch-space route so the session cookie is re-minted with the
 * business-scoped token (a direct backend tenant-selection call rotates the
 * credentials out from under the browser session).
 */
export async function ensureBusinessSpace(page: Page): Promise<void> {
  const spaces = await page.request.get('/api/tenant-selection/my-spaces');
  const body = (await spaces.json().catch(() => ({}))) as {
    data?: Array<Record<string, unknown>>;
  };
  const business = (body?.data ?? []).find((sp) => sp.spaceType === 'business');
  if (!business || typeof business.tenantId !== 'string') {
    throw new Error(`ensureBusinessSpace: no business space in my-spaces (${await spaces.text()})`);
  }
  // Already inside a business space? A redundant switch-space 500s on the
  // current stack — probe a business-scoped endpoint first and skip.
  const alreadyBusiness = await page.request.get('/api/meta/models?page=1&pageSize=1');
  if (alreadyBusiness.ok()) return;
  let switched: Awaited<ReturnType<typeof page.request.post>> | null = null;
  let lastDetail = '';
  for (let attempt = 0; attempt < 3; attempt += 1) {
    // The BFF registers this handler at /_action/switch-space (app/routes.ts);
    // /api/switch-space is NOT a BFF route — it catch-all-proxies to Spring and
    // 404s ("No static resource"), which silently left sessions in the System
    // space (page.page.manage missing downstream).
    switched = await page.request.post('/_action/switch-space', {
      form: { tenantId: business.tenantId, redirectTo: '/' },
      maxRedirects: 0,
    });
    if (switched.ok() || switched.status() === 302) break;
    lastDetail = `${switched.status()} ${await switched.text()}`;
    await new Promise((r) => setTimeout(r, 1000));
  }
  if (!switched || !(switched.ok() || switched.status() === 302)) {
    throw new Error(`ensureBusinessSpace: switch-space failed after retries: ${lastDetail}`);
  }
}