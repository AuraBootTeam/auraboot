/**
 * BasePage - base page object for all E2E page objects.
 * Provides common navigation, waiting, and assertion utilities.
 *
 * @since 4.0.0
 */

import { type Page, type Locator, expect } from '@playwright/test';

export abstract class BasePage {
  constructor(public readonly page: Page) {}

  /** Navigate to the page's URL */
  abstract goto(): Promise<void>;

  /** Wait for page to finish loading */
  async waitForLoad(): Promise<void> {
    await this.page.waitForLoadState('domcontentloaded');
  }

  /** Get a toast/notification message */
  get toast(): Locator {
    return this.page.locator('[role="alert"], [data-testid="toast"], .toast-message');
  }

  /** Get the loading indicator */
  get loadingIndicator(): Locator {
    return this.page.locator('.animate-spin, [data-testid="loading"]');
  }

  /** Wait for loading to complete */
  async waitForLoadingComplete(): Promise<void> {
    await expect(this.loadingIndicator).not.toBeVisible({ timeout: 10000 });
  }

  /** Assert toast message appears */
  async expectToast(message: string | RegExp): Promise<void> {
    if (typeof message === 'string') {
      await expect(this.toast).toContainText(message);
    } else {
      await expect(this.toast).toHaveText(message);
    }
  }

  /** Assert the page URL */
  async expectUrl(pattern: string | RegExp): Promise<void> {
    if (typeof pattern === 'string') {
      await expect(this.page).toHaveURL(new RegExp(pattern));
    } else {
      await expect(this.page).toHaveURL(pattern);
    }
  }

  /** Click a button by its text */
  async clickButton(text: string): Promise<void> {
    await this.page.locator(`button:has-text("${text}")`).click();
  }

  /** Confirm a dialog (if present) */
  async confirmDialog(): Promise<void> {
    const dialog = this.page.locator('[role="dialog"], [role="alertdialog"]');
    if (await dialog.isVisible()) {
      await dialog.locator('button:has-text("确定"), button:has-text("确认")').click();
    }
  }

  /** Cancel a dialog (if present) */
  async cancelDialog(): Promise<void> {
    const dialog = this.page.locator('[role="dialog"], [role="alertdialog"]');
    if (await dialog.isVisible()) {
      await dialog.locator('[data-testid="dialog-cancel"], button:has-text("取消")').click();
    }
  }
}
