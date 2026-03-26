/**
 * DynamicListPage - generic page object for schema-driven list pages.
 * Works with any model by accepting the page path and model code.
 *
 * Uses data-testid selectors for stability (Phase 3 upgrade).
 *
 * @since 4.0.0
 */

import { type Page, type Locator, expect } from '@playwright/test';
import { BasePage } from './BasePage';

export class DynamicListPage extends BasePage {
  private path: string;

  constructor(page: Page, path: string) {
    super(page);
    this.path = path;
  }

  async goto(): Promise<void> {
    // Set up list API listener BEFORE navigation so we catch the response
    const listResponsePromise = this.page.waitForResponse(
      (resp) => resp.url().includes('/list') && resp.status() === 200,
      { timeout: 10000 }
    ).catch(() => null);

    await this.page.goto(this.path);
    await this.waitForLoad();

    // Wait for spinner to disappear
    const spinner = this.page.locator('.animate-spin, [data-testid="loading"]');
    try {
      await spinner.waitFor({ state: 'visible', timeout: 2000 });
    } catch {
      // Spinner might already be gone
    }
    await expect(spinner).not.toBeVisible({ timeout: 15000 });

    // Wait for table content to appear
    const content = this.page.locator('table, [role="table"]');
    await content.first().waitFor({ state: 'visible', timeout: 15000 }).catch(() => {});

    // Wait for list data API to return
    await listResponsePromise;
  }

  // --- Filter section ---

  /** Get a filter input by field name or placeholder */
  filterInput(fieldOrPlaceholder: string): Locator {
    return this.page.locator(
      `input[name="${fieldOrPlaceholder}"], input[placeholder*="${fieldOrPlaceholder}"]`
    );
  }

  /** Get a filter select by field name */
  filterSelect(field: string): Locator {
    return this.page.locator(`select[name="${field}"], [data-field="${field}"] select`);
  }

  /** Fill a filter field */
  async fillFilter(field: string, value: string): Promise<void> {
    const input = this.filterInput(field);
    if (await input.isVisible()) {
      await input.fill(value);
    }
  }

  /** Select a filter option */
  async selectFilter(field: string, value: string): Promise<void> {
    const select = this.filterSelect(field);
    if (await select.isVisible()) {
      await select.selectOption(value);
    }
  }

  /** Click search button */
  get searchButton(): Locator {
    return this.page.locator('[data-testid="filter-search"]');
  }

  /** Click reset button */
  get resetButton(): Locator {
    return this.page.locator('[data-testid="filter-reset"]');
  }

  async search(): Promise<void> {
    await this.searchButton.click();
    await this.waitForLoad();
  }

  async resetFilters(): Promise<void> {
    await this.resetButton.click();
    await this.waitForLoad();
  }

  // --- Toolbar section ---

  /** Get the add/create button (first toolbar button) */
  get addButton(): Locator {
    return this.page.locator('[data-testid^="toolbar-btn-"]').first();
  }

  /** Get a toolbar button by code */
  toolbarButton(code: string): Locator {
    return this.page.locator(`[data-testid="toolbar-btn-${code}"]`);
  }

  /** Get a toolbar button by text (fallback) */
  toolbarButtonByText(text: string): Locator {
    return this.page.locator(`button:has-text("${text}"), a:has-text("${text}")`);
  }

  async clickAdd(): Promise<void> {
    await this.addButton.click();
  }

  // --- Tab section ---

  /** Click a tab by its key */
  async clickTab(key: string): Promise<void> {
    const tab = this.page.locator(`[data-testid="tab-${key}"]`);
    const listResponse = this.page.waitForResponse(
      (r) => r.url().includes('/list') && r.status() === 200,
      { timeout: 5000 }
    ).catch(() => null);
    await tab.click();
    await listResponse;
  }

  /** Click a tab by text (fallback for tabs without data-testid) */
  async clickTabByText(text: string | RegExp): Promise<void> {
    const tab = this.page.locator('nav[aria-label="Tabs"] button').filter({ hasText: text }).first();
    const listResponse = this.page.waitForResponse(
      (r) => r.url().includes('/list') && r.status() === 200,
      { timeout: 5000 }
    ).catch(() => null);
    await tab.click();
    await listResponse;
  }

  /** Get all tabs */
  get tabs(): Locator {
    return this.page.locator('nav[aria-label="Tabs"] button');
  }

  // --- Table section ---

  /** Get all table rows */
  get tableRows(): Locator {
    return this.page.locator('tbody tr');
  }

  /** Get table row by index (0-based) */
  row(index: number): Locator {
    return this.page.locator('tbody tr').nth(index);
  }

  /** Get cell value by row index and column index */
  async getCellText(rowIndex: number, colIndex: number): Promise<string> {
    return await this.row(rowIndex).locator('td').nth(colIndex).innerText();
  }

  /** Get total row count */
  async getRowCount(): Promise<number> {
    await this.waitForLoadingComplete();
    return await this.tableRows.count();
  }

  /** Assert row count */
  async expectRowCount(count: number): Promise<void> {
    await expect(this.tableRows).toHaveCount(count);
  }

  /** Get the empty state element */
  get emptyState(): Locator {
    return this.page.locator('[data-testid="empty"], .empty-state, text="暂无数据"');
  }

  /** Assert empty state is visible */
  async expectEmpty(): Promise<void> {
    await expect(this.emptyState).toBeVisible();
  }

  // --- Row actions ---

  /** Click edit on a row by index */
  async editRow(index: number): Promise<void> {
    const row = this.row(index);
    await row.locator('[data-testid="row-action-edit"]').click();
  }

  /** Click view on a row by index */
  async viewRow(index: number): Promise<void> {
    const row = this.row(index);
    await row.locator('[data-testid="row-action-view"]').click();
  }

  /** Click delete on a row by index */
  async deleteRow(index: number): Promise<void> {
    const row = this.row(index);
    await row.locator('[data-testid="row-action-delete"]').click();
  }

  /** Click a row action by its command code */
  async clickRowActionByCode(index: number, code: string): Promise<void> {
    const row = this.row(index);
    await row.locator(`[data-testid="row-action-${code}"]`).click();
  }

  /** Click a custom action button on a row by text (fallback) */
  async clickRowAction(index: number, actionText: string): Promise<void> {
    const row = this.row(index);
    await row.locator(`button:has-text("${actionText}")`).click();
  }

  // --- Selection ---

  /** Select all rows via header checkbox */
  async selectAll(): Promise<void> {
    await this.page.locator('thead input[type="checkbox"]').check();
  }

  /** Select a specific row by index */
  async selectRow(index: number): Promise<void> {
    await this.row(index).locator('input[type="checkbox"]').check();
  }

  // --- Pagination ---

  /** Get the pagination info text */
  get paginationInfo(): Locator {
    return this.page.locator('[data-testid="pagination-info"], .pagination-info');
  }

  /** Go to next page */
  async nextPage(): Promise<void> {
    await this.page.locator('[data-testid="pagination-next"]').click();
    await this.waitForLoad();
  }

  /** Go to previous page */
  async prevPage(): Promise<void> {
    await this.page.locator('[data-testid="pagination-prev"]').click();
    await this.waitForLoad();
  }

  /** Go to first page */
  async firstPage(): Promise<void> {
    await this.page.locator('[data-testid="pagination-first"]').click();
    await this.waitForLoad();
  }

  /** Go to last page */
  async lastPage(): Promise<void> {
    await this.page.locator('[data-testid="pagination-last"]').click();
    await this.waitForLoad();
  }
}
