/**
 * Auth Recovery & Signup Deep E2E
 *
 * Covers:
 * - Signup page rendering and validation
 * - Forgot/reset password routes show admin-managed password policy
 *
 * This suite runs in unauthenticated context and uses real UI interactions.
 */

import { test, expect } from '../../fixtures';

async function waitForAuthHydration(page: import('@playwright/test').Page): Promise<void> {
  await page.waitForLoadState('networkidle');
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
    await expect(
      page.locator('#email-error, [aria-describedby="email-error"]').first(),
    ).toBeVisible();
  });

  test('ARS-003: forgot-password shows admin-managed password policy', async ({ page }) => {
    await page.goto('/forgot-password', { waitUntil: 'domcontentloaded' });
    await waitForAuthHydration(page);

    await expect(page.locator('[data-testid="forgot-password-disabled"]')).toBeVisible();
    await expect(page.getByText(/tenant administrator/i)).toBeVisible();
    await expect(page).toHaveURL(/\/forgot-password/);
  });

  test('ARS-004: reset-password shows admin-managed password policy', async ({ page }) => {
    await page.goto('/reset-password?token=e2e-dummy-token', { waitUntil: 'domcontentloaded' });
    await waitForAuthHydration(page);

    await expect(page.locator('[data-testid="reset-password-disabled"]')).toBeVisible();
    await expect(page.getByText(/tenant administrator/i)).toBeVisible();
  });
});
