/**
 * ReportsOverviewPage - Page Object for the Reports Overview dashboard page.
 *
 * Encapsulates the read-only dashboard viewer, refresh/edit buttons,
 * loading and empty states for /reports/overview.
 *
 * @since 4.0.0
 */

import { type Page, type Locator, expect } from '@playwright/test';
import { BasePage } from './BasePage';

export class ReportsOverviewPage extends BasePage {
  constructor(page: Page) {
    super(page);
  }

  // --- Navigation ---

  /**
   * Navigate to /reports/overview and wait for the page title to appear.
   * Throws if redirected to login or if the page fails to load.
   */
  async goto(): Promise<void> {
    await this.page.goto('/reports/overview', { waitUntil: 'domcontentloaded' });

    const headingLocator = this.pageTitle;
    const loginLocator = this.page.locator('text=请先登录, text=欢迎登录');

    const result = await Promise.race([
      headingLocator.first().waitFor({ timeout: 8000 }).then(() => 'content' as const),
      loginLocator.first().waitFor({ timeout: 8000 }).then(() => 'login' as const),
    ]).catch(() => 'timeout' as const);

    if (result !== 'content') {
      throw new Error(`Reports overview page did not load (result: ${result})`);
    }
  }

  /**
   * Check whether the page loaded successfully (vs login redirect).
   * Returns true if the page title is visible, false otherwise.
   */
  async isLoaded(): Promise<boolean> {
    return this.pageTitle.isVisible({ timeout: 2000 }).catch(() => false);
  }

  // --- Locators ---

  get pageTitle(): Locator {
    return this.page.locator('[data-testid="page-title"]');
  }

  get refreshButton(): Locator {
    return this.page.locator('[data-testid="btn-refresh"]');
  }

  get editDashboardButton(): Locator {
    return this.page.locator('[data-testid="btn-edit-dashboard"]');
  }

  get createDashboardButton(): Locator {
    return this.page.locator('[data-testid="btn-create-dashboard"]');
  }

  /** Either edit or create dashboard button (whichever is visible) */
  get editOrCreateButton(): Locator {
    return this.editDashboardButton.or(this.createDashboardButton);
  }

  override get loadingIndicator(): Locator {
    return this.page.locator('[data-testid="loading-indicator"]');
  }

  get dashboardViewer(): Locator {
    return this.page.locator('[data-testid="dashboard-viewer"]');
  }

  get emptyState(): Locator {
    return this.page.locator('[data-testid="empty-state"]');
  }

  get gridLayout(): Locator {
    return this.page.locator('.react-grid-layout');
  }

  get errorOverlay(): Locator {
    return this.page.locator(
      '[data-testid="error-overlay"], .error-overlay, #webpack-dev-server-client-overlay'
    );
  }

  // --- Actions ---

  /** Click the refresh button */
  async refresh(): Promise<void> {
    await this.refreshButton.click();
  }

  /** Wait for loading indicator to disappear and content to settle */
  async waitForContentLoad(): Promise<void> {
    await this.page.waitForLoadState('domcontentloaded');
    const spinner = this.page.locator('.animate-spin');
    await spinner.first().waitFor({ state: 'hidden', timeout: 8000 }).catch(() => {});
  }
}
