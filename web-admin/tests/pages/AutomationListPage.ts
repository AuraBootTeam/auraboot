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
    return this.page.locator('[data-testid="confirm-dialog"], [role="alertdialog"], [role="dialog"]:has(button:has-text("OK"))').first();
  }

  get confirmOkButton(): Locator {
    return this.page.locator('[data-testid="confirm-ok"], [role="alertdialog"] button:has-text("OK"), [role="alertdialog"] button:has-text("确定"), [role="dialog"] button:has-text("OK"), [role="dialog"] button:has-text("确定")').first();
  }

  // --- Actions ---

  /** Toggle automation enable/disable and wait for the API response. */
  async toggle(pid: string): Promise<void> {
    // Wait for the POST /api/automations/{pid}/toggle round-trip so callers
    // don't have to poll on stale text. Under full-suite load the click → PATCH
    // → refetch chain can exceed 10s; explicitly anchoring on the response
    // avoids 20s flake bounds (see automation-enhanced AUTO-04).
    const togglePromise = this.page
      .waitForResponse(
        (r) => r.url().includes(`/api/automations/${pid}/toggle`) && r.status() < 500,
        { timeout: 20000 },
      )
      .catch(() => null);
    await this.toggleButton(pid).click();
    await togglePromise;
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

  /** Delete an automation: click delete, confirm, and wait for API response. */
  async deleteAutomation(pid: string): Promise<void> {
    await this.deleteButton(pid).click();

    // Wait for custom confirm dialog
    await expect(this.confirmDialogLocator).toBeVisible({ timeout: 5000 });

    // Click confirm and wait for DELETE API response
    const [deleteResp] = await Promise.all([
      this.page.waitForResponse(
        (r) => r.url().includes(`/api/automations/${pid}`) && r.request().method() === 'DELETE',
        { timeout: 10000 },
      ).catch(() => null),
      this.confirmOkButton.click(),
    ]);

    // Wait for dialog to close
    await expect(this.confirmDialogLocator).toBeHidden({ timeout: 5000 });

    // Wait for list to refresh
    if (deleteResp) {
      await this.page.waitForLoadState('networkidle', { timeout: 5000 }).catch(() => {});
    }
  }
}
