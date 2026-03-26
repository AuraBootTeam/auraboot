/**
 * UserProfilePage - Page Object for the Personal Profile page.
 *
 * Covers profile display, editing, and avatar management at /personal/profile.
 *
 * @since 4.0.0
 */

import { type Page, type Locator, expect } from '@playwright/test';
import { BasePage } from './BasePage';

export class UserProfilePage extends BasePage {
  constructor(page: Page) {
    super(page);
  }

  // --- Navigation ---

  async goto(): Promise<void> {
    await this.page.goto('/personal/profile', { waitUntil: 'domcontentloaded' });
    await this.waitForLoad();
  }

  // --- Page State ---

  get title(): Locator {
    return this.page.locator('h1:has-text("个人资料")');
  }

  get avatar(): Locator {
    return this.page.locator('img[alt="用户头像"]');
  }

  get retryButton(): Locator {
    return this.page.locator('[data-testid="profile-retry-btn"]');
  }

  /** Check if profile page loaded successfully */
  async isLoaded(): Promise<boolean> {
    return this.title.isVisible({ timeout: 12000 }).catch(() => false);
  }

  /** Check if page is in error state */
  async isError(): Promise<boolean> {
    return this.retryButton.isVisible({ timeout: 1000 }).catch(() => false);
  }

  // --- Edit Mode ---

  get editButton(): Locator {
    return this.page.locator('[data-testid="profile-edit-btn"]');
  }

  get saveButton(): Locator {
    return this.page.locator('[data-testid="profile-save-btn"]');
  }

  get cancelButton(): Locator {
    return this.page.locator('[data-testid="profile-cancel-btn"]');
  }

  /** All visible form inputs (excludes hidden and file inputs) */
  get formInputs(): Locator {
    return this.page.locator('form:has(input[name="intent"][value="update-profile"]) input:not([type="hidden"]):not([type="file"])');
  }

  /** Enter edit mode */
  async startEditing(): Promise<void> {
    for (let i = 0; i < 3; i++) {
      await this.editButton.click();
      const entered = await this.cancelButton.isVisible({ timeout: 1200 }).catch(() => false);
      if (entered) {
        break;
      }
      await this.cancelButton.waitFor({ state: 'visible', timeout: 1500 }).catch(() => {});
    }
    await expect(this.cancelButton).toBeVisible({ timeout: 5000 });
    await expect(this.formInputs.first()).toBeVisible({ timeout: 5000 });
  }

  /** Cancel editing */
  async cancelEditing(): Promise<void> {
    await this.cancelButton.click();
  }
}
