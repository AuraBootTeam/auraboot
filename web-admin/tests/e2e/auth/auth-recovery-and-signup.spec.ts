/**
 * Auth Recovery & Signup Deep E2E
 *
 * Covers:
 * - Signup page rendering and validation
 * - Forgot password flow validation and success feedback
 * - Reset password error handling (missing token / weak password / mismatch)
 *
 * This suite runs in unauthenticated context and uses real UI interactions.
 */

import { test, expect } from '../../fixtures';

async function waitForAuthHydration(page: import('@playwright/test').Page): Promise<void> {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('form').first()).toBeVisible();
}

test.describe('Auth Recovery & Signup Deep', () => {
  test.use({ storageState: { cookies: [], origins: [] } });

  test('ARS-001: signup page renders with required inputs', async ({ page }) => {
    await page.goto('/signup', { waitUntil: 'domcontentloaded' });

    await expect(page.locator('input#email')).toBeVisible();
    await expect(page.locator('input#password')).toBeVisible();
    await expect(page.locator('button[type="submit"]')).toBeVisible();

    // Link back to login should be visible for navigation sanity
    await expect(page.locator('a[href*="/login"]').first()).toBeVisible();
  });

  test('ARS-002: signup rejects invalid email format', async ({ page }) => {
    await page.goto('/signup', { waitUntil: 'domcontentloaded' });

    await page.locator('input#email').click();
    await page.locator('input#email').fill('invalid-email');
    await page.locator('input#password').click();
    await page.locator('input#password').fill('Test2026x');
    await page.locator('button[type="submit"]').click();

    // Should stay on signup and show validation message
    await expect(page).toHaveURL(/\/signup/);
    await expect(page.locator('#email-error, [aria-describedby="email-error"]').first()).toBeVisible();
  });

  test('ARS-003: forgot-password rejects malformed email', async ({ page }) => {
    await page.goto('/forgot-password', { waitUntil: 'domcontentloaded' });
    await waitForAuthHydration(page);

    const emailInput = page.locator('input[type="email"]').first();
    await expect(emailInput).toBeVisible();
    await emailInput.fill('bad-email');
    await page.getByRole('button', { name: /send reset link/i }).click();

    // Browser-native email validation is used in this page.
    const isValid = await emailInput.evaluate((el: HTMLInputElement) => el.checkValidity());
    expect(isValid).toBe(false);
    await expect(page).toHaveURL(/\/forgot-password/);
  });

  test('ARS-004: forgot-password shows confirmation with valid email', async ({ page }) => {
    await page.goto('/forgot-password', { waitUntil: 'domcontentloaded' });
    await waitForAuthHydration(page);

    const submitResp = page.waitForResponse(
      (r) => r.url().includes('/api/auth/forgot-password') && r.request().method().toLowerCase() === 'post',
      { timeout: 30000 },
    );

    await page.locator('input[type="email"]').first().fill('admin@auraboot.test');
    await page.getByRole('button', { name: /send reset link/i }).click();
    const resp = await submitResp;
    expect(resp.ok()).toBe(true);

    // App should render success confirmation page
    await expect(page.getByText(/check your email/i)).toBeVisible();
    await expect(page.getByText(/back to login/i)).toBeVisible();
  });

  test('ARS-005: reset-password without token shows invalid-link error', async ({ page }) => {
    await page.goto('/reset-password', { waitUntil: 'domcontentloaded' });
    await waitForAuthHydration(page);

    const newPwd = page.locator('input[type="password"]').first();
    const confirmPwd = page.locator('input[type="password"]').nth(1);
    await newPwd.fill('Test2026x');
    await confirmPwd.fill('Test2026x');
    await page.getByRole('button', { name: /reset password/i }).click();

    await expect(page.getByText(/invalid reset link/i)).toBeVisible();
    await expect(page).toHaveURL(/\/reset-password/);
  });

  test('ARS-006: reset-password enforces strength and confirm match', async ({ page }) => {
    await page.goto('/reset-password?token=e2e-dummy-token', { waitUntil: 'domcontentloaded' });
    await waitForAuthHydration(page);
    const newPwd = page.locator('input[type="password"]').first();
    const confirmPwd = page.locator('input[type="password"]').nth(1);

    // Weak password branch
    await newPwd.fill('123');
    await confirmPwd.fill('123');
    await page.getByRole('button', { name: /reset password/i }).click();
    await expect(page.getByText(/at least 8 characters/i)).toBeVisible();

    // Mismatch branch
    await newPwd.fill('Test2026x');
    await confirmPwd.fill('Test2026y');
    await page.getByRole('button', { name: /reset password/i }).click();
    await expect(page.getByText(/passwords do not match/i)).toBeVisible();
  });
});
