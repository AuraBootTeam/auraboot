/**
 * PageDesignerPage - Page Object for the Page Designer.
 *
 * Encapsulates toolbar, tabs, component library, canvas, and properties panel
 * interactions behind data-testid selectors.
 *
 * @since 4.0.0
 */

import { type Page, type Locator, expect } from '@playwright/test';
import { BasePage } from './BasePage';

export class PageDesignerPage extends BasePage {
  constructor(page: Page) {
    super(page);
  }

  // --- Navigation ---

  /** Navigate directly to a page designer by page ID */
  async goto(pageId?: string): Promise<void> {
    if (pageId) {
      await this.page.goto(`/page-designer/${pageId}`);
    } else {
      await this.page.goto(`/page-designer`);
    }
    await this.waitForLoad();
  }

  /**
   * Open the designer via API + fallback to list navigation.
   * Returns true if successfully entered the designer canvas.
   */
  async openViaList(): Promise<boolean> {
    // Step 1: Prefer stable E2E fixture pages.
    // Prefer form/list fixtures first because they reliably contain selectable blocks.
    const stablePageKeys = [
      'e2e_test_form',
      'e2e_test_list',
      'e2e_test_dashboard',
      'bpm_process_management_list',
    ];
    for (const key of stablePageKeys) {
      try {
        const resp = await this.page.request.get(`/api/pages/key/${key}`);
        if (resp.ok()) {
          const body = await resp.json();
          const pageData = body.data;
          const pageId = pageData?.pid || pageData?.id;
          if (pageId) {
            await this.page.goto(`/page-designer/${pageId}`, { waitUntil: 'domcontentloaded' });
            await this.waitForLoad();
            await this.page
              .locator('text=Loading page...')
              .waitFor({ state: 'hidden', timeout: 30000 })
              .catch(() => null);
            if (await this.canvas.isVisible({ timeout: 15000 }).catch(() => false)) {
              return true;
            }
          }
        }
      } catch {
        // Continue with next key/fallback.
      }
    }

    // Step 2: Get first available page ID via API as a fallback.
    try {
      const resp = await this.page.request.get(
        `/api/pages?current=1&size=20&sortField=updatedAt&sortDirection=DESC`,
      );
      if (resp.ok()) {
        const body = await resp.json();
        const rawData = body.data;
        const pages = Array.isArray(rawData) ? rawData : rawData?.data || rawData?.records || [];
        const preferredPage =
          pages.find((p: any) => String(p?.pageKey ?? '').startsWith('e2e_')) ??
          pages.find((p: any) => p?.kind === 'composite' || p?.kind === 'list' || p?.kind === 'form') ??
          pages[0];
        if (preferredPage) {
          const pageId = preferredPage.pid || preferredPage.id;
          await this.page.goto(`/page-designer/${pageId}`, { waitUntil: 'domcontentloaded' });
          await this.waitForLoad();
          await this.page
            .locator('text=Loading page...')
            .waitFor({ state: 'hidden', timeout: 30000 })
            .catch(() => null);
          if (await this.canvas.isVisible({ timeout: 15000 }).catch(() => false)) {
            return true;
          }
        }
      }
    } catch {
      // Fall through to list navigation.
    }

    // Step 3: Fallback to list navigation
    await this.page.goto(`/page-designer`, { waitUntil: 'domcontentloaded' });
    await this.waitForLoad();

    const pageCount = this.page.locator('text=/\\d+ 个页面/');
    const hasPages = await pageCount.isVisible({ timeout: 10000 }).catch(() => false);

    const pageCards = this.page.locator('main h3');
    const hasCards = await pageCards
      .first()
      .isVisible({ timeout: 10000 })
      .catch(() => false);

    if (!hasPages && !hasCards) {
      return false;
    }

    const pageHeadings = this.page.locator('main h3');
    const count = await pageHeadings.count();
    if (count === 0) return false;

    await pageHeadings.first().dblclick();
    await this.waitForLoad();

    if (await this.saveButton.isVisible({ timeout: 15000 }).catch(() => false)) {
      return true;
    }

    const url = this.page.url();
    return url.includes('/page-designer/') && !url.endsWith('/page-designer');
  }

  /** Click back button to return to page list */
  async goBack(): Promise<void> {
    await this.backButton.click();
    await this.waitForLoad();
  }

  // --- Toolbar Locators ---

  get backButton(): Locator {
    return this.page.locator('[data-testid="toolbar-back"]');
  }

  get saveButton(): Locator {
    return this.page
      .locator('[data-testid="toolbar-save"], button:has-text("Save"), button:has-text("保存")')
      .first();
  }

  get publishButton(): Locator {
    return this.page
      .locator(
        '[data-testid="toolbar-publish"], button:has-text("Publish"), button:has-text("发布")',
      )
      .first();
  }

  get previewButton(): Locator {
    return this.page
      .locator(
        '[data-testid="toolbar-preview"], button:has-text("Preview"), button:has-text("预览")',
      )
      .first();
  }

  get undoButton(): Locator {
    return this.page
      .locator('[data-testid="toolbar-undo"], button[title*="Undo"], button:has-text("撤销")')
      .first();
  }

  get redoButton(): Locator {
    return this.page
      .locator('[data-testid="toolbar-redo"], button[title*="Redo"], button:has-text("重做")')
      .first();
  }

  get zoomInButton(): Locator {
    return this.page.locator('[data-testid="toolbar-zoom-in"], button[title*="Zoom in"]').first();
  }

  get zoomOutButton(): Locator {
    return this.page.locator('[data-testid="toolbar-zoom-out"], button[title*="Zoom out"]').first();
  }

  get zoomLevel(): Locator {
    return this.page.locator('[data-testid="toolbar-zoom-level"], button:has-text("%")').first();
  }

  // --- Toolbar Actions ---

  /** Click save and wait for response */
  async save(): Promise<void> {
    await this.saveButton.click();
    await this.page.waitForLoadState('domcontentloaded');
  }

  /** Click publish (may trigger a confirm dialog) */
  async publish(): Promise<void> {
    await this.publishButton.click();
  }

  /** Click preview */
  async preview(): Promise<void> {
    await this.previewButton.click();
  }

  // --- Left Panel Tabs ---

  get fieldsTab(): Locator {
    return this.page
      .locator('[data-testid="designer-tab-fields"], button:has-text("Fields"), button:has-text("字段")')
      .first();
  }

  get blocksTab(): Locator {
    return this.page
      .locator('[data-testid="designer-tab-blocks"], button:has-text("Blocks"), button:has-text("组件")')
      .first();
  }

  get outlineTab(): Locator {
    return this.page
      .locator('[data-testid="designer-tab-outline"], button:has-text("Outline"), button:has-text("大纲")')
      .first();
  }

  async clickFieldsTab(): Promise<void> {
    await this.fieldsTab.click();
  }

  async clickBlocksTab(): Promise<void> {
    await this.blocksTab.click();
  }

  async clickOutlineTab(): Promise<void> {
    await this.outlineTab.click();
  }

  // --- Component Library ---

  /** Open the component library by clicking the blocks tab */
  async openComponentLibrary(): Promise<boolean> {
    if (await this.blocksTab.isVisible({ timeout: 3000 }).catch(() => false)) {
      await this.blocksTab.click();
      return true;
    }
    return false;
  }

  get libraryBlocksTab(): Locator {
    return this.page.locator('[data-testid="library-tab-blocks"]');
  }

  get libraryComponentsTab(): Locator {
    return this.page.locator('[data-testid="library-tab-components"]');
  }

  get librarySearch(): Locator {
    return this.page.locator('[data-testid="library-search"]');
  }

  get libraryCount(): Locator {
    return this.page.locator('[data-testid="library-count"]');
  }

  /** Switch to Smart Components tab within the library */
  async switchToSmartComponents(): Promise<void> {
    await this.libraryComponentsTab.click();
  }

  /** Switch to Blocks tab within the library */
  async switchToBlocks(): Promise<void> {
    await this.libraryBlocksTab.click();
  }

  /** Search in the component library */
  async searchComponents(query: string): Promise<void> {
    await this.librarySearch.fill(query);
  }

  // --- Canvas ---

  get canvas(): Locator {
    return this.page.locator('[data-testid="canvas-editor"], [data-testid="designer-canvas"]').first();
  }

  /** All sortable blocks on the canvas */
  get blocks(): Locator {
    return this.page.locator(
      '[data-testid="sortable-block"], [data-testid^="canvas-block-"], [aria-roledescription="sortable"], [roledescription="sortable"]',
    );
  }

  /** Get a specific block by index */
  block(index: number): Locator {
    return this.blocks.nth(index);
  }

  /** Click a block to select it */
  async selectBlock(index = 0): Promise<boolean> {
    const block = this.block(index);
    if (await block.isVisible({ timeout: 3000 }).catch(() => false)) {
      await block.click();
      return true;
    }
    return false;
  }

  /** Get count of blocks on the canvas */
  async getBlockCount(): Promise<number> {
    return this.blocks.count();
  }

  // --- Properties Panel ---

  get propertiesPanel(): Locator {
    return this.page
      .locator(
        '[data-testid="designer-properties-panel"], [data-testid="floors-properties-panel"], [data-testid="block-config-panel"], text=Select a block',
      )
      .first();
  }

  get propertiesEmpty(): Locator {
    return this.page.locator('[data-testid="properties-empty"]');
  }

  /** Check if properties panel shows empty state */
  async hasEmptyProperties(): Promise<boolean> {
    return this.propertiesEmpty.isVisible({ timeout: 2000 }).catch(() => false);
  }
}
