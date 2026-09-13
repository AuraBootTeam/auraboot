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
