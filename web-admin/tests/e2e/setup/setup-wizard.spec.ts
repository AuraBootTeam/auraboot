/**
 * Setup Wizard E2E Test
 *
 * Tests the system bootstrap wizard at /setup.
 *
 * Coverage dimensions:
 *   D1  Page Load          — /setup loads with correct form fields
 *   D5  Form Field Types   — email input, password inputs, checkbox, radio
 *   D12 Form Validation    — empty submit, invalid email, short password, mismatch
 *   D14 Toast / Feedback   — error messages appear inline
 *
 * NOTE: Tests requiring an uninitialized database are marked test.skip.
 * After reset-and-init.sh the system IS initialized, so /setup renders an
 * 'already initialized' page (no redirect). The post-init tests cover the
 * already-done page, status API shape, and absence of the banner on root.
 *
 * @since 11.0.0
 */

import { test, expect } from '@playwright/test';

test.describe('Setup Wizard', () => {
  test('shows already-initialized page when /setup accessed after init', async ({ page, request }) => {
    const status = await (await request.get('/api/bootstrap/status')).json();
    test.skip(status.data?.initialized !== true,
        'System is uninitialized — run with default (no --no-bootstrap) to exercise this path');

    await page.goto('/setup', { waitUntil: 'domcontentloaded' });

    // No redirect — page stays on /setup and renders the already-done card
    await expect(page).toHaveURL(/\/setup/);
    await expect(page.getByTestId('bootstrap-already-done')).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText('System already initialized')).toBeVisible();
    await expect(page.getByRole('link', { name: 'Back to home' })).toBeVisible();

    // Wizard form must NOT render
    await expect(page.getByText('Welcome to AuraBoot')).not.toBeVisible({ timeout: 1_000 });
  });

  test('bootstrap status API returns initialized=true with empty missingParts', async ({ page }) => {
    const res = await page.request.get('/api/bootstrap/status');
    expect(res.ok()).toBeTruthy();

    const body = await res.json();
    test.skip(body.data?.initialized !== true,
        'System is uninitialized — run with default (no --no-bootstrap) to exercise this path');

    expect(body.code).toBe('0');
    expect(body.data.initialized).toBe(true);
    expect(Array.isArray(body.data.missingParts)).toBe(true);
    expect(body.data.missingParts.length).toBe(0);
  });

  test('setup API rejects when already initialized', async ({ page, request }) => {
    // Guard: never POST /setup in uninitialized env — it would silently bootstrap
    // the system and pollute subsequent tests.
    const status = await (await request.get('/api/bootstrap/status')).json();
    test.skip(status.data?.initialized !== true,
        'System is uninitialized — calling /setup here would bootstrap; run with default mode');

    const res = await page.request.post('/api/bootstrap/setup', {
      data: {
        companyName: 'Test Company',
        adminEmail: 'test@example.com',
        adminPassword: 'Test12345678',
        systemMode: 'single',
        seedDemoData: false,
      },
    });

    const body = await res.json();
    // Backend returns error "System is already initialized"
    expect(body.code).not.toBe('0');
    expect(body.message).toContain('already initialized');
  });

  // -------------------------------------------------------------------------
  // Tests 1-5: Form validation on uninitialized system
  // These require an empty database (no bootstrap has run).
  // Skipped in normal test runs since reset-and-init.sh initializes the system.
  // -------------------------------------------------------------------------

  test.skip('setup page loads with all form fields (requires empty database)', async ({ page }) => {
    // This test only works on a truly uninitialized system
    await page.goto('/setup', { waitUntil: 'domcontentloaded' });

    // Verify page title / heading
    await expect(page.getByText('Welcome to AuraBoot')).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText('Set up your platform in a few seconds')).toBeVisible();

    // Verify form fields exist
    // Company Name
    const companyInput = page.locator('input[type="text"]').first();
    await expect(companyInput).toBeVisible();
    await expect(companyInput).toHaveAttribute('placeholder', 'My Company');

    // Admin Email
    const emailInput = page.locator('input[type="email"]');
    await expect(emailInput).toBeVisible();
    await expect(emailInput).toHaveAttribute('placeholder', 'admin@company.com');

    // Password fields
    const passwordInputs = page.locator('input[type="password"]');
    await expect(passwordInputs).toHaveCount(2);
    await expect(passwordInputs.nth(0)).toHaveAttribute('placeholder', 'At least 8 characters');
    await expect(passwordInputs.nth(1)).toHaveAttribute('placeholder', 'Re-enter your password');

    // Demo data checkbox
    await expect(page.getByText('Load demo data')).toBeVisible();
    const checkbox = page.locator('input[type="checkbox"]');
    await expect(checkbox).toBeChecked(); // default is true

    // Advanced Settings toggle
    await expect(page.getByText('Advanced Settings')).toBeVisible();

    // Submit button
    await expect(page.getByRole('button', { name: 'Launch AuraBoot' })).toBeVisible();
  });

  test.skip('shows error when submitting with empty email (requires empty database)', async ({
    page,
  }) => {
    await page.goto('/setup', { waitUntil: 'domcontentloaded' });
    await expect(page.getByText('Welcome to AuraBoot')).toBeVisible({ timeout: 10_000 });

    // Click submit without filling anything
    await page.getByRole('button', { name: 'Launch AuraBoot' }).click();

    // Should show "Email is required" error (first validation check in handleSubmit)
    await expect(page.getByText('Email is required')).toBeVisible({ timeout: 5_000 });
  });

  test.skip('shows error for short password (requires empty database)', async ({ page }) => {
    await page.goto('/setup', { waitUntil: 'domcontentloaded' });
    await expect(page.getByText('Welcome to AuraBoot')).toBeVisible({ timeout: 10_000 });

    // Fill email but use short password
    await page.locator('input[type="email"]').fill('test@example.com');
    await page.locator('input[type="password"]').nth(0).fill('short');
    await page.locator('input[type="password"]').nth(1).fill('short');

    await page.getByRole('button', { name: 'Launch AuraBoot' }).click();

    // Should show password length error (validation: < 8 chars)
    await expect(page.getByText('Password must be at least 8 characters')).toBeVisible({
      timeout: 5_000,
    });
  });

  test.skip('shows error for password mismatch (requires empty database)', async ({ page }) => {
    await page.goto('/setup', { waitUntil: 'domcontentloaded' });
    await expect(page.getByText('Welcome to AuraBoot')).toBeVisible({ timeout: 10_000 });

    // Fill email and mismatched passwords
    await page.locator('input[type="email"]').fill('test@example.com');
    await page.locator('input[type="password"]').nth(0).fill('ValidPassword123');
    await page.locator('input[type="password"]').nth(1).fill('DifferentPassword456');

    await page.getByRole('button', { name: 'Launch AuraBoot' }).click();

    // Should show mismatch error
    await expect(page.getByText('Passwords do not match')).toBeVisible({ timeout: 5_000 });
  });

  test.skip('shows error when password is empty (requires empty database)', async ({ page }) => {
    await page.goto('/setup', { waitUntil: 'domcontentloaded' });
    await expect(page.getByText('Welcome to AuraBoot')).toBeVisible({ timeout: 10_000 });

    // Fill email but leave password empty
    await page.locator('input[type="email"]').fill('test@example.com');

    await page.getByRole('button', { name: 'Launch AuraBoot' }).click();

    // Should show "Password is required"
    await expect(page.getByText('Password is required')).toBeVisible({ timeout: 5_000 });
  });

  test('banner is NOT visible on root after initialization', async ({ page, request }) => {
    const status = await (await request.get('/api/bootstrap/status')).json();
    test.skip(status.data?.initialized !== true,
        'System is uninitialized — banner is expected to be visible in that mode');

    await page.goto('/', { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('bootstrap-banner')).toBeHidden({ timeout: 3_000 });
  });

  // -------------------------------------------------------------------------
  // Bootstrap UX — uninitialized scenarios
  //
  // Precondition: system is NOT initialized. Run `./scripts/oss-reset-and-init.sh
  // --no-bootstrap` before this suite, or these tests self-skip when they detect
  // an already-initialized backend.
  // -------------------------------------------------------------------------

  test('shows banner (no silent redirect to /setup) on uninitialized', async ({ page, request }) => {
    const res = await request.get('/api/bootstrap/status');
    const body = await res.json();
    test.skip(body.data?.initialized === true,
        'System is initialized — run with --no-bootstrap to exercise this path');

    await page.goto('/', { waitUntil: 'domcontentloaded' });
    // Unauthenticated + uninitialized → auth middleware redirects to /login;
    // the banner renders on /login too because it lives in root layout.
    // The important contract: no redirect to /setup (the old behavior).
    await expect(page).not.toHaveURL(/\/setup/);
    const banner = page.getByTestId('bootstrap-banner');
    await expect(banner).toBeVisible({ timeout: 10_000 });
    await expect(banner).toContainText('System not initialized');
  });

  test('banner CTA navigates to /setup on uninitialized root', async ({ page, request }) => {
    const res = await request.get('/api/bootstrap/status');
    const body = await res.json();
    test.skip(body.data?.initialized === true,
        'System is initialized — run with --no-bootstrap to exercise this path');

    await page.goto('/', { waitUntil: 'domcontentloaded' });
    await page.getByTestId('bootstrap-banner-cta').click();
    await expect(page).toHaveURL(/\/setup$/);
  });

  test('not-ready card appears when business route hit uninitialized', async ({ page, request }) => {
    const res = await request.get('/api/bootstrap/status');
    const body = await res.json();
    test.skip(body.data?.initialized === true,
        'System is initialized — run with --no-bootstrap to exercise this path');

    // Any business-pageKey URL; ErrorBoundary should fall into BootstrapNotReady
    await page.goto('/p/iam_user', { waitUntil: 'domcontentloaded' });
    const notReady = page.getByTestId('bootstrap-not-ready');
    // May appear, or banner may be the only indicator if loader succeeded.
    // Either of them visible counts — assert at least one bootstrap-UX testid is visible.
    const banner = page.getByTestId('bootstrap-banner');
    await expect(banner.or(notReady).first()).toBeVisible({ timeout: 10_000 });
  });

  test('status API reports missing system_config when uninitialized', async ({ request }) => {
    const res = await request.get('/api/bootstrap/status');
    const body = await res.json();
    test.skip(body.data?.initialized === true,
        'System is initialized — run with --no-bootstrap to exercise this path');

    expect(body.code).toBe('0');
    expect(body.data.initialized).toBe(false);
    expect(body.data.missingParts).toEqual(['system_config']);
    expect(body.data.reason).toBe('Bootstrap not completed');
  });
});
