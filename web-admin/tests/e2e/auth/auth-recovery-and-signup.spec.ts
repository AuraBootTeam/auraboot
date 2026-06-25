/**
 * Auth Recovery & Signup Deep E2E
 *
 * Covers:
 * - Signup disabled by default for admin-managed SaaS tenants
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

  test('ARS-001: signup redirects to login when public registration is disabled', async ({ page }) => {
    await page.goto('/signup', { waitUntil: 'domcontentloaded' });

    await expect(page).toHaveURL(/\/login/, { timeout: 10000 });
    await expect(page.locator('input#identifier, input#email').first()).toBeVisible();
  });

  test('ARS-002: login page does not expose public signup link by default', async ({ page }) => {
    await page.goto('/login', { waitUntil: 'domcontentloaded' });

    await expect(page.locator('a[href*="signup"]').first()).toHaveCount(0);
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
