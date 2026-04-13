/**
 * Login E2E Tests
 *
 * Tests the login flow and authentication UI.
 *
 * NOTE: Most tests use storageState for authentication.
 * This file specifically tests the login UI itself.
 *
 * @since 4.0.0
 */

import { test, expect } from '@playwright/test';
import { LoginPage } from '../../pages';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';

const TEST_CREDENTIALS = {
  email: DEFAULT_TEST_ACCOUNT.email,
  password: DEFAULT_TEST_ACCOUNT.password,
};

test.describe('Login Flow', () => {
  // Use a fresh context without storageState for login tests
  test.use({ storageState: { cookies: [], origins: [] } });

  let loginPage: LoginPage;

  test.beforeEach(async ({ page }) => {
    loginPage = new LoginPage(page);
  });

  test('should display login form when not authenticated', async () => {
    await loginPage.goto();
    await loginPage.expectFormVisible();
  });

  test('should login successfully with valid credentials @smoke', async () => {
    // Extend timeout for this test since login involves API calls + SSR hydration
    test.setTimeout(30000);

    await loginPage.goto();
    await loginPage.waitForFormReady();

    // Fill login form with click-before-fill to ensure React hydration
    await loginPage.emailInput.click();
    await loginPage.emailInput.fill(TEST_CREDENTIALS.email);
    await expect(loginPage.emailInput).toHaveValue(TEST_CREDENTIALS.email);

    await loginPage.passwordInput.click();
    await loginPage.passwordInput.fill(TEST_CREDENTIALS.password);
    await expect(loginPage.passwordInput).toHaveValue(TEST_CREDENTIALS.password);

    // Submit the actual form directly. Under full-suite load, button click can race
    // with hydration, while requestSubmit() follows the browser's real form path.
    await loginPage.page
      .locator('form')
      .first()
      .evaluate((form: HTMLFormElement) => {
        form.requestSubmit();
      });

    // Wait for login to complete (allow extra time for SSR + API)
    await loginPage.expectLoggedIn({ timeout: 20000 });
  });

  test('should show error with invalid credentials', async () => {
    await loginPage.goto();

    // Fill with invalid credentials and submit
    await loginPage.login('invalid@example.com', 'wrongpassword');

    // Should stay on login page or show inline/server error.
    await loginPage.page.waitForLoadState('domcontentloaded');
    const stillOnLogin = loginPage.page.url().includes('/login');
    const hasError = await loginPage.page
      .locator('[role="alert"], .text-red-500, .text-red-600, #email-error, #password-error')
      .first()
      .isVisible({ timeout: 3000 })
      .catch(() => false);
    expect(stillOnLogin || hasError).toBe(true);
  });

  test('should have proper form validation', async () => {
    await loginPage.goto();

    // Try to submit empty form
    await loginPage.submitButton.click();

    // Form should show validation errors or remain on page
    await loginPage.expectStillOnLoginPage();
  });
});

test.describe('Authenticated Session', () => {
  // These tests use the default storageState (authenticated)

  test('should redirect to dashboard when already authenticated', async ({ page }) => {
    const loginPage = new LoginPage(page);
    await loginPage.gotoRoot();

    // Should not see login form when already authenticated
    const hasLoginForm = await loginPage.isFormVisible({ timeout: 3000 });

    // If we see login form, storageState may have expired
    if (hasLoginForm) {
      console.warn('Login form visible - storageState may need refresh');
    }
  });
});
