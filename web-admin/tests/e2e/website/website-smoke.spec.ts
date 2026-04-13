import { test, expect } from '@playwright/test';

// Website plugin routes are not registered in routes.ts (commented out).
// Anonymous users at `/` are redirected to `/login`, not the marketing page.
// Re-enable when the website plugin routes are restored in app/routes.ts.
test.describe('Website Platform Plugin - Smoke Tests', () => {
  // eslint-disable-next-line playwright/no-skipped-test
  test.skip(() => true, 'Website plugin routes not active — marketing pages not routed');

  test('WS-01: homepage loads for anonymous visitor', async ({ browser }) => {
    const context = await browser.newContext({ viewport: { width: 1280, height: 720 } });
    const page = await context.newPage();
    await page.goto('http://localhost:5173/', { waitUntil: 'load' });

    // Should see hero section, not login page
    await expect(page.locator('h1')).toContainText('Build Enterprise Apps');
    await expect(page.locator('text=Get Started').first()).toBeVisible();

    // Should NOT be redirected to login
    expect(page.url()).not.toContain('/login');

    await context.close();
  });

  test('WS-02: pricing page loads for anonymous visitor', async ({ browser }) => {
    const context = await browser.newContext({ viewport: { width: 1280, height: 720 } });
    const page = await context.newPage();
    await page.goto('http://localhost:5173/pricing', { waitUntil: 'load' });

    await expect(page.locator('h1')).toBeVisible();
    // Should see pricing tiers
    await expect(page.locator('text=Community').first()).toBeVisible();
    await expect(page.locator('text=Professional').first()).toBeVisible();

    expect(page.url()).not.toContain('/login');
    await context.close();
  });

  test('WS-03: docs page loads for anonymous visitor', async ({ browser }) => {
    const context = await browser.newContext({ viewport: { width: 1280, height: 720 } });
    const page = await context.newPage();
    await page.goto('http://localhost:5173/docs', { waitUntil: 'load' });

    await expect(page.locator('h1')).toBeVisible();
    expect(page.url()).not.toContain('/login');
    await context.close();
  });

  test('WS-04: blog page loads for anonymous visitor', async ({ browser }) => {
    const context = await browser.newContext({ viewport: { width: 1280, height: 720 } });
    const page = await context.newPage();
    await page.goto('http://localhost:5173/blog', { waitUntil: 'load' });

    await expect(page.locator('h1')).toBeVisible();
    expect(page.url()).not.toContain('/login');
    await context.close();
  });

  test('WS-05: plugins page loads for anonymous visitor', async ({ browser }) => {
    const context = await browser.newContext({ viewport: { width: 1280, height: 720 } });
    const page = await context.newPage();
    await page.goto('http://localhost:5173/plugins', { waitUntil: 'load' });

    await expect(page.locator('h1')).toBeVisible();
    expect(page.url()).not.toContain('/login');
    await context.close();
  });

  test('WS-06: about page loads for anonymous visitor', async ({ browser }) => {
    const context = await browser.newContext({ viewport: { width: 1280, height: 720 } });
    const page = await context.newPage();
    await page.goto('http://localhost:5173/about', { waitUntil: 'load' });

    await expect(page.locator('h1')).toBeVisible();
    expect(page.url()).not.toContain('/login');
    await context.close();
  });

  test('WS-07: community page loads for anonymous visitor', async ({ browser }) => {
    const context = await browser.newContext({ viewport: { width: 1280, height: 720 } });
    const page = await context.newPage();
    await page.goto('http://localhost:5173/community', { waitUntil: 'load' });

    await expect(page.locator('h1')).toBeVisible();
    expect(page.url()).not.toContain('/login');
    await context.close();
  });

  test('WS-08: demo page loads for anonymous visitor', async ({ browser }) => {
    const context = await browser.newContext({ viewport: { width: 1280, height: 720 } });
    const page = await context.newPage();
    await page.goto('http://localhost:5173/demo', { waitUntil: 'load' });

    await expect(page.locator('h1')).toBeVisible();
    expect(page.url()).not.toContain('/login');
    await context.close();
  });

  test('WS-09: marketing routes NOT caught by DSL catch-all', async ({ browser }) => {
    const context = await browser.newContext({ viewport: { width: 1280, height: 720 } });
    const page = await context.newPage();
    await page.goto('http://localhost:5173/pricing', { waitUntil: 'load' });

    // These texts come from $.tsx catch-all when unauthenticated
    await expect(page.locator('text=Please login first')).not.toBeVisible();
    await expect(page.locator('text=Page Unavailable')).not.toBeVisible();
    await expect(page.locator('text=Menu configuration not found')).not.toBeVisible();

    await context.close();
  });

  test('WS-10: header shows navigation for visitor', async ({ browser }) => {
    const context = await browser.newContext({ viewport: { width: 1280, height: 720 } });
    const page = await context.newPage();
    await page.goto('http://localhost:5173/', { waitUntil: 'load' });

    // Header should have nav links (Login/Get Started for anon, or Go to App for logged-in)
    const header = page.locator('header');
    await expect(header).toBeVisible();
    // At least one CTA should be visible (Login or Go to App)
    const hasLogin = await header
      .locator('text=Login')
      .isVisible()
      .catch(() => false);
    const hasGoToApp = await header
      .locator('text=Go to App')
      .isVisible()
      .catch(() => false);
    expect(hasLogin || hasGoToApp).toBeTruthy();

    await context.close();
  });

  test('WS-11: footer links are present', async ({ browser }) => {
    const context = await browser.newContext({ viewport: { width: 1280, height: 720 } });
    const page = await context.newPage();
    await page.goto('http://localhost:5173/', { waitUntil: 'load' });

    const footer = page.locator('footer');
    await expect(footer).toBeVisible();
    await expect(footer.locator('text=Documentation')).toBeVisible();
    await expect(footer.locator('text=AuraBoot')).toBeVisible();

    await context.close();
  });

  test('WS-12: app routes still work for logged-in users', async ({ page }) => {
    // Uses default authenticated context (admin user via storageState)
    await page.goto('http://localhost:5173/meta/models', { waitUntil: 'load' });
    // Should NOT be redirected to marketing homepage
    expect(page.url()).toContain('/meta/models');
  });
});
