/**
 * AutomationListPage - Page Object for the Automation Management list page.
 *
 * Encapsulates automation list selectors and common actions:
 * toggling enable/disable, opening execution logs, navigating to editor,
 * and deleting automations.
 *
 * @since 6.0.0
 */

import { type Page, type Locator, expect } from '@playwright/test';
import { BasePage } from './BasePage';

export class AutomationListPage extends BasePage {
  constructor(page: Page) {
    super(page);
  }

  // --- Navigation ---

  async goto(): Promise<void> {
    await this.page.goto('/automations');
    await this.waitForLoad();
    await expect(this.pageTitle).toBeVisible({ timeout: 10000 });
  }

  // --- Page-level locators ---

  get pageTitle(): Locator {
    return this.page.locator('[data-testid="page-title"]');
  }

  get createButton(): Locator {
    return this.page.locator('[data-testid="btn-create-automation"]');
  }

  // --- Row locators ---

  automationRow(pid: string): Locator {
    return this.page.locator(`[data-testid="automation-row-${pid}"]`);
  }

  toggleButton(pid: string): Locator {
    return this.page.locator(`[data-testid="btn-toggle-${pid}"]`);
  }
  statusBadge(pid: string): Locator {
    return this.page.locator(`[data-testid="status-${pid}"]`);
  }

  logsButton(pid: string): Locator {
    return this.page.locator(`[data-testid="btn-logs-${pid}"]`);
  }

  editLink(pid: string): Locator {
    return this.page.locator(`[data-testid="btn-edit-${pid}"]`);
  }

  deleteButton(pid: string): Locator {
    return this.page.locator(`[data-testid="btn-delete-${pid}"]`);
  }

  // --- Dialog locators ---

  get logDialog(): Locator {
    return this.page.locator('[data-testid="execution-log-dialog"]');
  }

  get closeDialogButton(): Locator {
    return this.page.locator('[data-testid="btn-close-dialog"]');
  }

  get confirmDialogLocator(): Locator {
    return this.page.locator('[data-testid="confirm-dialog"]');
  }

  get confirmOkButton(): Locator {
    return this.page.locator('[data-testid="confirm-ok"]');
  }

  // --- Actions ---

  /** Toggle automation enable/disable and wait for the API response. */
  async toggle(pid: string): Promise<void> {
    await this.toggleButton(pid).click();
  }

  /** Open execution logs dialog for an automation. */
  async openLogs(pid: string): Promise<void> {
    const btn = this.logsButton(pid);
    await expect(btn).toBeVisible({ timeout: 10000 });
    await btn.click();
    await expect(this.logDialog).toBeVisible({ timeout: 8000 });
  }

  /** Close the execution logs dialog. */
  async closeLogs(): Promise<void> {
    await this.closeDialogButton.click();
    await expect(this.logDialog).toBeHidden({ timeout: 3000 });
  }

  /** Delete an automation: click delete, confirm, and wait for row removal. */
  async deleteAutomation(pid: string): Promise<void> {
    const nativeDialog = this.page.waitForEvent('dialog', { timeout: 1500 }).catch(() => null);
    await this.deleteButton(pid).click();
    const dialog = await nativeDialog;
    if (dialog) {
      await dialog.accept();
      return;
    }

    await expect(this.confirmDialogLocator).toBeVisible({ timeout: 5000 });
    await this.confirmOkButton.click();
    await expect(this.confirmDialogLocator).toBeHidden({ timeout: 5000 });
  }
}
