/**
 * SavedViewPage - Page Object for SavedView management testing.
 * Covers view switching (TABLE/KANBAN/CALENDAR/GALLERY/GANTT),
 * view CRUD, filter configuration, and column settings.
 *
 * @since 7.0.0
 */

import { type Page, type Locator, expect } from '@playwright/test';
import { BasePage } from './BasePage';

export type ViewType = 'table' | 'kanban' | 'calendar' | 'gallery' | 'gantt';

export class SavedViewPage extends BasePage {
  private path: string;

  constructor(page: Page, path: string) {
    super(page);
    this.path = path;
  }

  async goto(): Promise<void> {
    const listResponsePromise = this.page.waitForResponse(
      (resp) => resp.url().includes('/list') && resp.status() === 200,
      { timeout: 10000 },
    ).catch(() => null);

    await this.page.goto(this.path);
    await this.waitForLoad();
    await listResponsePromise;
  }

  // --- View Selector ---

  /** The main view selector dropdown trigger */
  get viewSelectorTrigger(): Locator {
    return this.page.locator('[aria-haspopup="listbox"]');
  }

  /** Open the view selector dropdown */
  async openViewSelector(): Promise<void> {
    await this.viewSelectorTrigger.click();
    await this.page.locator('[role="listbox"]').waitFor({ state: 'visible', timeout: 3000 });
  }

  /** Select a view by name from the dropdown */
  async selectView(name: string): Promise<void> {
    await this.openViewSelector();
    await this.page.locator(`[role="option"]`).filter({ hasText: name }).click();
  }

  /** Get all view options in the dropdown */
  get viewOptions(): Locator {
    return this.page.locator('[role="option"]');
  }

  // --- View Type Switching ---

  /** Get view type switcher buttons */
  get viewTypeSwitcher(): Locator {
    return this.page.locator('button').filter({ has: this.page.locator('svg') });
  }

  /** Switch to a specific view type by clicking its button */
  async switchViewType(type: ViewType): Promise<void> {
    // View type buttons are in a button group, each has a title or aria-label
    const typeMap: Record<ViewType, string> = {
      table: 'table',
      kanban: 'kanban',
      calendar: 'calendar',
      gallery: 'gallery',
      gantt: 'gantt',
    };
    const btn = this.page.locator(`button`).filter({ hasText: new RegExp(typeMap[type], 'i') }).first();
    if (await btn.isVisible({ timeout: 2000 }).catch(() => false)) {
      await btn.click();
      return;
    }
    // Fallback: try by position in the type button group
    const typeButtons = this.page.locator('.flex.rounded-lg > button, .inline-flex > button');
    const typeIndex = ['table', 'kanban', 'calendar', 'gallery', 'gantt'].indexOf(type);
    if (typeIndex >= 0 && await typeButtons.nth(typeIndex).isVisible({ timeout: 2000 }).catch(() => false)) {
      await typeButtons.nth(typeIndex).click();
    }
  }

  // --- View Management Panel ---

  /** Open the view management panel */
  async openManagePanel(): Promise<void> {
    await this.openViewSelector();
    const manageBtn = this.page.locator('button').filter({ hasText: /manage/i }).first();
    await manageBtn.click();
    await this.page.locator('[role="dialog"], [aria-modal="true"]').waitFor({ state: 'visible', timeout: 5000 });
  }

  /** The manage panel dialog */
  get managePanel(): Locator {
    return this.page.locator('[role="dialog"], [aria-modal="true"]');
  }

  /** Click "New View" button in manage panel */
  async clickNewView(): Promise<void> {
    await this.managePanel.locator('button').filter({ hasText: /new view/i }).click();
  }

  /** Fill the new view name */
  async fillViewName(name: string): Promise<void> {
    await this.page.locator('#view-name').fill(name);
  }

  /** Select view scope */
  async selectScope(scope: 'personal' | 'team' | 'global'): Promise<void> {
    const scopeSelect = this.page.locator('#view-scope');
    await scopeSelect.selectOption(scope);
  }

  /** Select view type in create form */
  async selectViewType(type: ViewType): Promise<void> {
    const btn = this.managePanel.locator('button').filter({ hasText: type }).first();
    await btn.click();
  }

  /** Submit the create view form */
  async submitCreateView(): Promise<void> {
    const createBtn = this.managePanel.locator('button').filter({ hasText: /create/i }).first();
    await createBtn.click();
  }

  /** Create a new view with all steps */
  async createView(name: string, type: ViewType = 'table', scope: 'personal' | 'team' | 'global' = 'personal'): Promise<void> {
    await this.openManagePanel();
    await this.clickNewView();
    await this.fillViewName(name);
    await this.selectScope(scope);
    if (type !== 'table') {
      await this.selectViewType(type);
    }
    await this.submitCreateView();
  }

  /** Delete a view by name from the manage panel */
  async deleteView(name: string): Promise<void> {
    await this.openManagePanel();
    const viewItem = this.managePanel.locator('button').filter({ hasText: name }).first();
    // Hover to show action buttons
    await viewItem.hover();
    // Find the delete (trash) button near this item
    const deleteBtn = viewItem.locator('..').locator('button').filter({ has: this.page.locator('svg') }).last();
    await deleteBtn.click();
    // Confirm deletion
    const confirmBtn = this.page.locator('button').filter({ hasText: /ok|confirm|yes|确定|确认/i }).first();
    if (await confirmBtn.isVisible({ timeout: 2000 }).catch(() => false)) {
      await confirmBtn.click();
    }
  }

  /** Set a view as default */
  async setDefaultView(name: string): Promise<void> {
    await this.openManagePanel();
    const viewItem = this.managePanel.locator('button').filter({ hasText: name }).first();
    await viewItem.hover();
    // Star icon button
    const starBtn = viewItem.locator('..').locator('button[title*="default" i], button[title*="Default" i]').first();
    await starBtn.click();
  }

  /** Duplicate a view by name */
  async duplicateView(name: string, newName: string): Promise<void> {
    await this.openManagePanel();
    const viewItem = this.managePanel.locator('button').filter({ hasText: name }).first();
    await viewItem.hover();
    // Copy icon button
    const copyBtn = viewItem.locator('..').locator('button').filter({ has: this.page.locator('svg') }).nth(1);
    await copyBtn.click();
    // Handle the prompt dialog
    this.page.once('dialog', async (dialog) => {
      await dialog.accept(newName);
    });
  }

  /** Close the manage panel */
  async closeManagePanel(): Promise<void> {
    const closeBtn = this.managePanel.locator('button[aria-label="Close panel"], button[aria-label="Close"]').first();
    if (await closeBtn.isVisible({ timeout: 1000 }).catch(() => false)) {
      await closeBtn.click();
    }
  }

  // --- Filter Configuration ---

  /** Open filter save panel */
  async openFilterSave(): Promise<void> {
    const btn = this.page.locator('[data-testid="filter-save"]');
    if (await btn.isVisible({ timeout: 2000 }).catch(() => false)) {
      await btn.click();
    }
  }

  /** Add a filter in the ViewFilterPanel */
  async addFilter(field: string, operator: string, value?: string): Promise<void> {
    const addBtn = this.page.locator('button').filter({ hasText: /add filter/i }).first();
    await addBtn.click();
    // Select field
    const fieldSelects = this.page.locator('select').filter({ hasText: /select field/i });
    const lastSelect = fieldSelects.last();
    await lastSelect.selectOption(field);
    // Select operator
    const opSelects = this.page.locator('select');
    // The operator select is the next select after the field select
    await opSelects.last().selectOption(operator);
    // Fill value if needed
    if (value && !['isNull', 'isNotNull'].includes(operator)) {
      const valueInput = this.page.locator('input[placeholder*="Filter value"]').last();
      await valueInput.fill(value);
    }
  }

  // --- Column Settings ---

  /** Get column settings button */
  get columnSettingsBtn(): Locator {
    return this.page.locator('button').filter({ hasText: /column|列/i }).first();
  }

  // --- View-Specific Elements ---

  /** Kanban board container */
  get kanbanBoard(): Locator {
    return this.page.locator('[data-testid="kanban-board"], .kanban-board, [class*="kanban"]').first();
  }

  /** Calendar grid container */
  get calendarGrid(): Locator {
    return this.page.locator('.fc, .fc-view, [class*="calendar"]').first();
  }

  /** Gallery grid container */
  get galleryGrid(): Locator {
    return this.page.locator('.grid, [class*="gallery"]').first();
  }

  /** Gantt timeline container */
  get ganttTimeline(): Locator {
    return this.page.locator('[class*="gantt"], .gantt-container, svg').first();
  }

  /** Table container */
  get tableView(): Locator {
    return this.page.locator('table, [role="table"]').first();
  }

  // --- Sort ---

  /** Click a sortable column header to toggle sort */
  async clickColumnSort(columnText: string): Promise<void> {
    const header = this.page.locator('thead th, [role="columnheader"]').filter({ hasText: columnText }).first();
    await header.click();
  }

  // --- Pagination ---

  get paginationInfo(): Locator {
    return this.page.locator('[data-testid="pagination-info"], .pagination-info');
  }
}
