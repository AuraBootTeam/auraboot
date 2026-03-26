/**
 * LoginPage - Page Object for the Login page.
 *
 * Covers login form display, credential entry, form validation,
 * and successful/failed authentication flows at /login.
 *
 * @since 4.0.0
 */

import { type Page, type Locator, expect } from '@playwright/test';
import { BasePage } from './BasePage';

export class LoginPage extends BasePage {
  constructor(page: Page) {
    super(page);
  }

  // --- Navigation ---

  async goto(): Promise<void> {
    await this.page.goto('/login');
    await this.waitForLoad();
  }

  /** Navigate to root (unauthenticated users will be redirected to login) */
  async gotoRoot(): Promise<void> {
    await this.page.goto('/');
    await this.waitForLoad();
  }

  // --- Form Fields ---

  /** Email input field */
  get emailInput(): Locator {
    return this.page.locator('input#email');
  }

  /** Password input field */
  get passwordInput(): Locator {
    return this.page.locator('input#password');
  }

  /** Submit button */
  get submitButton(): Locator {
    return this.page.locator(
      'form button[type="submit"], ' +
      'form button:has-text("立即登录"), ' +
      'form button:has-text("Login"), ' +
      '[data-testid="login-form"] button[type="submit"]'
    ).first();
  }

  /** Remember-me checkbox */
  get rememberCheckbox(): Locator {
    return this.page.locator('input#remember');
  }

  /** Sign-up link */
  get signUpLink(): Locator {
    return this.page.locator('a:has-text("立即注册")');
  }

  // --- Error Messages ---

  /** Email validation error */
  get emailError(): Locator {
    return this.page.locator('#email-error');
  }

  /** Password validation error */
  get passwordError(): Locator {
    return this.page.locator('#password-error');
  }

  // --- Actions ---

  /**
   * Fill and submit the login form.
   * Uses click-before-fill to ensure React hydration is complete.
   */
  async login(email: string, password: string): Promise<void> {
    await this.emailInput.click();
    await this.emailInput.fill(email);
    await this.passwordInput.click();
    await this.passwordInput.fill(password);
    await this.submitButton.click();
  }

  /** Wait for email input to become visible (form ready) */
  async waitForFormReady(): Promise<void> {
    await this.emailInput.waitFor({ state: 'visible', timeout: 5000 });
  }

  // --- Assertions ---

  /** Assert the login form is fully visible */
  async expectFormVisible(): Promise<void> {
    await expect(this.emailInput).toBeVisible();
    await expect(this.passwordInput).toBeVisible();
    await expect(this.submitButton).toBeVisible();
  }

  /** Assert login succeeded (URL no longer contains /login) */
  async expectLoggedIn(options?: { timeout?: number }): Promise<void> {
    const timeout = options?.timeout ?? 15000;
    await this.page.waitForURL(
      (url) => {
        const href = url.toString();
        return !href.includes('/login') || href.includes('tenant-selection');
      },
      { timeout, waitUntil: 'domcontentloaded' }
    );
    expect(this.page.url()).not.toContain('/login');
  }

  /** Assert still on login page (form is visible) */
  async expectStillOnLoginPage(): Promise<void> {
    const visible = await this.emailInput.isVisible();
    expect(visible).toBe(true);
  }

  /** Assert email error message is displayed */
  async expectEmailError(text?: string | RegExp): Promise<void> {
    await expect(this.emailError).toBeVisible();
    if (text) {
      if (typeof text === 'string') {
        await expect(this.emailError).toContainText(text);
      } else {
        await expect(this.emailError).toHaveText(text);
      }
    }
  }

  /** Assert password error message is displayed */
  async expectPasswordError(text?: string | RegExp): Promise<void> {
    await expect(this.passwordError).toBeVisible();
    if (text) {
      if (typeof text === 'string') {
        await expect(this.passwordError).toContainText(text);
      } else {
        await expect(this.passwordError).toHaveText(text);
      }
    }
  }

  /** Check if login form is visible (non-throwing) */
  async isFormVisible(options?: { timeout?: number }): Promise<boolean> {
    const timeout = options?.timeout ?? 3000;
    return this.emailInput.isVisible({ timeout }).catch(() => false);
  }
}
