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
 * NOTE: Tests 1-5 (form interaction on uninitialized system) are skipped because
 * the test environment runs after reset-and-init.sh, meaning the system IS
 * initialized. The /setup loader redirects to /login when initialized.
 * Test 6 (redirect) is the primary positive test for this environment.
 *
 * @since 11.0.0
 */

import { test, expect } from '@playwright/test';

test.describe('Setup Wizard', () => {
  // -------------------------------------------------------------------------
  // Test 6: Already initialized system redirects to /login
  // This is the main positive test — after reset-and-init.sh the system
  // is initialized, so /setup should redirect.
  // -------------------------------------------------------------------------
  test('redirects away from /setup when system is already initialized', async ({ page }) => {
    // Navigate to /setup — the loader checks /api/bootstrap/status
    // and redirects away if initialized === true
    await page.goto('/setup', { waitUntil: 'domcontentloaded' });

    // Wait for the redirect — should end up NOT on /setup
    await page.waitForURL((url) => !url.pathname.includes('/setup'), { timeout: 15_000 });

    const url = page.url();
    expect(
      !url.includes('/setup'),
      `Expected redirect away from /setup, but still at: ${url}`
    ).toBeTruthy();

    // Verify setup wizard content is NOT visible
    const setupHeading = page.locator('text=System Setup');
    await expect(setupHeading).not.toBeVisible({ timeout: 3_000 });
  });

  test('bootstrap status API returns initialized=true', async ({ page }) => {
    // Verify the API that drives the redirect
    const res = await page.request.get('/api/bootstrap/status');
    expect(res.ok()).toBeTruthy();

    const body = await res.json();
    expect(body.code).toBe('0');
    expect(body.data.initialized).toBe(true);
  });

  test('setup API rejects when already initialized', async ({ page }) => {
    // POST to /setup should fail with error when system is already bootstrapped
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

  test.skip('shows error when submitting with empty email (requires empty database)', async ({ page }) => {
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
    await expect(page.getByText('Password must be at least 8 characters')).toBeVisible({ timeout: 5_000 });
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
});
