/**
 * User Account Deactivation E2E Tests
 *
 * Tests DA-001 ~ DA-006: Deactivation page navigation, 3-step flow,
 * validation, submit + cancel flow.
 *
 * Route: /personal/deactivation
 * API: GET  /api/auth/deactivation/status
 *      POST /api/auth/deactivation/request
 *      POST /api/auth/deactivation/cancel
 *
 * Uses serial execution because DA-004 creates a deactivation that DA-005 cancels.
 *
 * @since 4.0.0
 */

import { test, expect } from '../../fixtures';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';
import { execSync } from 'child_process';

const PAGE_URL = '/personal/deactivation';

/**
 * Ensure the test user account is enabled and has no pending deactivation.
 *
 * Known issue: User registration does not set is_enabled = true (Java boolean
 * primitive defaults to false), so we fix it here. Also clears any leftover
 * deactivation records and resets deactivation_status.
 */
function ensureUserEnabled(): void {
  const email = DEFAULT_TEST_ACCOUNT.email;
  const db = 'aura_boot';
  const psql = (sql: string) =>
    execSync(
      `psql -h localhost -U ghj -d ${db} -P pager=off -t -c "${sql}"`,
      { encoding: 'utf-8', timeout: 5000 },
    ).trim();

  // Enable user and clear deactivation status
  psql(
    `UPDATE ab_user SET is_enabled = true, deactivation_status = NULL WHERE email = '${email}'`,
  );
  // Remove any lingering deactivation records
  psql(
    `DELETE FROM ab_user_deactivation WHERE user_email = '${email}'`,
  );
}

test.describe.serial('Account Deactivation', () => {
  // Pre-cleanup: ensure user is enabled and cancel any active deactivation
  test.beforeAll(async ({ browser }) => {
    // Fix user state directly in DB (works around registration bug where
    // is_enabled defaults to false)
    ensureUserEnabled();

    const ctx = await browser.newContext({
      storageState: './tests/storage/admin.json',
    });
    const cleanupPage = await ctx.newPage();
    try {
      await cleanupPage.request
        .post('/api/auth/deactivation/cancel')
        .catch(() => {});
    } finally {
      await cleanupPage.close();
      await ctx.close();
    }
  });

  // Cleanup: cancel any deactivation created during tests and re-enable user
  test.afterAll(async ({ browser }) => {
    const ctx = await browser.newContext({
      storageState: './tests/storage/admin.json',
    });
    const cleanupPage = await ctx.newPage();
    try {
      await cleanupPage.request
        .post('/api/auth/deactivation/cancel')
        .catch(() => {});
    } finally {
      await cleanupPage.close();
      await ctx.close();
    }
    // Always restore user state so other tests are not affected
    ensureUserEnabled();
  });

  /**
   * DA-001: Page load shows step 1
   * Verify page title, step indicator, reason options, and Continue button.
   */
  test('DA-001: should display step 1 with warnings @smoke', async ({
    page,
  }) => {
    await page.goto(PAGE_URL);
    await page.waitForLoadState('domcontentloaded');

    // Page title
    await expect(
      page.locator('h1').filter({ hasText: 'Account Deactivation' }),
    ).toBeVisible();

    // Step 1 content
    const step1 = page.locator('[data-testid="deactivation-step-1"]');
    await expect(step1).toBeVisible();

    // Warning box
    await expect(page.getByText('What happens when you deactivate')).toBeVisible();

    // Reason options
    await expect(page.getByText('I no longer need this service')).toBeVisible();

    // Continue button (should be disabled — no reason selected)
    const nextBtn = page.locator('[data-testid="deactivation-next-step2"]');
    await expect(nextBtn).toBeVisible();
    await expect(nextBtn).toBeDisabled();
  });

  /**
   * DA-002: Step 1 validation — Continue enabled after selecting reason
   */
  test('DA-002: should enable Continue after selecting reason', async ({
    page,
  }) => {
    await page.goto(PAGE_URL);
    await page.waitForLoadState('domcontentloaded');

    const nextBtn = page.locator('[data-testid="deactivation-next-step2"]');
    await expect(nextBtn).toBeDisabled();

    // Select a reason
    await page.getByText('Privacy concerns').click();

    // Button should now be enabled
    await expect(nextBtn).toBeEnabled();
  });

  /**
   * DA-003: Step 2 shows password input
   */
  test('DA-003: should navigate to step 2 with password input', async ({
    page,
  }) => {
    await page.goto(PAGE_URL);
    await page.waitForLoadState('domcontentloaded');

    // Select reason and advance
    await page.getByText('Privacy concerns').click();
    await page.locator('[data-testid="deactivation-next-step2"]').click();

    // Step 2 should be visible
    const step2 = page.locator('[data-testid="deactivation-step-2"]');
    await expect(step2).toBeVisible();

    // Password input
    const passwordInput = page.locator(
      '[data-testid="deactivation-password-input"]',
    );
    await expect(passwordInput).toBeVisible();

    // Continue button disabled until password entered
    const nextBtn = page.locator('[data-testid="deactivation-next-step3"]');
    await expect(nextBtn).toBeDisabled();

    // Enter password
    await passwordInput.fill(DEFAULT_TEST_ACCOUNT.password);
    await expect(nextBtn).toBeEnabled();
  });

  /**
   * DA-004: Full flow — submit deactivation request
   */
  test('DA-004: should submit deactivation request', async ({ page }) => {
    await page.goto(PAGE_URL);
    await page.waitForLoadState('domcontentloaded');

    // Step 1: Select reason
    await page.getByText('Too many notifications').click();
    await page.locator('[data-testid="deactivation-next-step2"]').click();

    // Step 2: Enter password
    await page
      .locator('[data-testid="deactivation-password-input"]')
      .fill(DEFAULT_TEST_ACCOUNT.password);
    await page.locator('[data-testid="deactivation-next-step3"]').click();

    // Step 3: Consent and submit
    const step3 = page.locator('[data-testid="deactivation-step-3"]');
    await expect(step3).toBeVisible();

    // Submit button disabled without consent
    const submitBtn = page.locator('[data-testid="deactivation-submit-btn"]');
    await expect(submitBtn).toBeDisabled();

    // Check consent
    await page
      .locator('[data-testid="deactivation-consent-checkbox"]')
      .check();
    await expect(submitBtn).toBeEnabled();

    // Submit and wait for API
    const submitResponse = page.waitForResponse(
      (resp) =>
        resp.url().includes('/api/auth/deactivation/request') &&
        resp.request().method().toLowerCase() === 'post',
      { timeout: 10000 },
    );
    await submitBtn.click();
    const resp = await submitResponse;
    const body = await resp.json().catch(() => null);
    expect(resp.status(), `Deactivation request failed: ${JSON.stringify(body)}`).toBe(200);

    // Should show cooling-off status — page re-renders after setExistingStatus
    const statusCard = page.locator('[data-testid="deactivation-status"]');
    await expect(statusCard).toBeVisible({ timeout: 15000 });
    // The heading text may be "Cooling-Off Period" or localized variant
    const coolingHeading = statusCard.locator('h2').filter({ hasText: /Cooling.Off/i });
    await expect(coolingHeading).toBeVisible({ timeout: 10000 });
  });

  /**
   * DA-005: Cancel deactivation from status view
   */
  test('DA-005: should cancel active deactivation', async ({ page }) => {
    await page.goto(PAGE_URL);
    await page.waitForLoadState('domcontentloaded');

    // Should show the status from DA-004
    const statusCard = page.locator('[data-testid="deactivation-status"]');
    await expect(statusCard).toBeVisible({ timeout: 10000 });

    // Cancel button
    const cancelBtn = page.locator('[data-testid="deactivation-cancel-btn"]');
    await expect(cancelBtn).toBeVisible();

    // Cancel and wait for API
    const cancelResponse = page.waitForResponse(
      (resp) =>
        resp.url().includes('/api/auth/deactivation/cancel') &&
        resp.request().method().toLowerCase() === 'post',
      { timeout: 10000 },
    );
    await cancelBtn.click();
    await cancelResponse;

    // Should return to step 1
    const step1 = page.locator('[data-testid="deactivation-step-1"]');
    await expect(step1).toBeVisible({ timeout: 10000 });
  });

  /**
   * DA-006: Back button from step 1 navigates to profile
   */
  test('DA-006: should navigate back to profile', async ({ page }) => {
    await page.goto(PAGE_URL);
    await page.waitForLoadState('domcontentloaded');

    const backBtn = page.locator('[data-testid="deactivation-back-btn"]');
    await expect(backBtn).toBeVisible();

    await backBtn.click();
    await expect(page).toHaveURL(/\/personal\/profile/, { timeout: 10000 });
  });
});
