/**
 * DashboardDesignerPage - Page Object for the Dashboard Designer.
 *
 * Encapsulates three-panel layout (palette, canvas, properties),
 * toolbar actions, widget management, and configuration panels.
 *
 * @since 4.0.0
 */

import { type Page, type Locator, expect } from '@playwright/test';
import { BasePage } from './BasePage';

export class DashboardDesignerPage extends BasePage {
  constructor(page: Page) {
    super(page);
  }

  // --- Navigation ---

  async goto(): Promise<void> {
    // Retry navigation if the first attempt hits ERR_CONNECTION_REFUSED
    for (let attempt = 0; attempt < 3; attempt++) {
      try {
        await this.page.goto('/dashboard-designer', {
          timeout: 15000,
          waitUntil: 'domcontentloaded',
        });
        break;
      } catch (err) {
        if (attempt === 2) throw err;
        // Brief pause before retry (without using waitForTimeout)
        await this.page.waitForLoadState('domcontentloaded').catch(() => {});
      }
    }
    await this.waitForLoad();
    // Wait for the primary UI regions that indicate the designer shell is usable.
    await expect(this.toolbar).toBeVisible({ timeout: 15000 });
    await expect(this.palette).toBeVisible({ timeout: 15000 });
    await expect(this.canvas).toBeVisible({ timeout: 15000 });
    await expect(this.propertyPanel).toBeVisible({ timeout: 15000 });
  }

  // --- Top-level layout ---

  get root(): Locator {
    return this.page.locator('[data-testid="widget-palette"], [data-testid="designer-canvas"]');
  }

  get heading(): Locator {
    return this.page
      .locator(
        '[data-testid="designer-toolbar"] h1, [data-testid="designer-toolbar"] [role="heading"]',
      )
      .first();
  }

  // --- Toolbar ---

  get toolbar(): Locator {
    return this.page.locator('[data-testid="designer-toolbar"]');
  }

  get undoButton(): Locator {
    return this.toolbar.locator('[data-testid="designer-toolbar-btn-undo"]');
  }

  get redoButton(): Locator {
    return this.toolbar.locator('[data-testid="designer-toolbar-btn-redo"]');
  }

  get validateButton(): Locator {
    return this.toolbar.locator('[data-testid="toolbar-btn-validate"]');
  }

  get settingsButton(): Locator {
    return this.toolbar.locator('[data-testid="toolbar-btn-settings"]');
  }

  get saveButton(): Locator {
    return this.toolbar.locator('[data-testid="designer-toolbar-btn-save"]');
  }

  get publishButton(): Locator {
    return this.toolbar.locator('[data-testid="toolbar-btn-publish"]');
  }

  get unpublishButton(): Locator {
    return this.toolbar.locator('[data-testid="toolbar-btn-unpublish"]');
  }

  get statusBadge(): Locator {
    return this.toolbar.locator('span.rounded');
  }

  get dirtyIndicator(): Locator {
    return this.toolbar.locator('text=/未保存|Unsaved/i');
  }

  async undo(): Promise<void> {
    await this.undoButton.click();
  }

  async redo(): Promise<void> {
    const canClickRedo = await this.redoButton.isEnabled().catch(() => false);
    if (canClickRedo) {
      await this.redoButton.click();
      return;
    }
    await this.canvas.click({ position: { x: 10, y: 10 } }).catch(() => {});
    await this.page.keyboard.press('Meta+Shift+z').catch(() => {});
    await this.page.keyboard.press('Control+y').catch(() => {});
  }

  async save(): Promise<void> {
    const saveResponsePromise = this.page
      .waitForResponse(
        (resp) =>
          resp.url().includes('/dashboards') && resp.request().method().toLowerCase() === 'post',
        { timeout: 10000 },
      )
      .catch(() => null);
    const canClickSave = await this.saveButton.isEnabled().catch(() => false);
    if (canClickSave) {
      await this.saveButton.click();
    } else {
      await this.page.keyboard.press('Control+S');
    }
    await saveResponsePromise;
  }

  async waitUntilSaved(): Promise<void> {
    await expect
      .poll(
        async () => {
          const disabled = await this.saveButton.isDisabled().catch(() => false);
          const label = (await this.saveButton.textContent().catch(() => ''))?.trim() || '';
          return disabled && /save|保存/i.test(label);
        },
        { timeout: 10000 },
      )
      .toBe(true);
  }

  // --- Widget Palette (left panel) ---

  get palette(): Locator {
    return this.page.locator('[data-testid="widget-palette"]');
  }

  get paletteHeading(): Locator {
    return this.palette.locator('h2:has-text("组件库")');
  }

  /** Get a palette item by widget name */
  paletteItem(name: string): Locator {
    return this.palette
      .locator('div[draggable="true"]')
      .filter({ has: this.page.locator('span', { hasText: name }) })
      .first();
  }

  /** Add a widget from palette with retry (click may not register during hydration) */
  async addWidget(widgetName: string): Promise<void> {
    const item = this.paletteItem(widgetName);
    await item.waitFor({ state: 'visible', timeout: 5000 });

    const widgetHeading = this.propertyPanel.locator(`h2:has-text("${widgetName}")`);
    const beforeCount = await this.widgets.count();

    for (let attempt = 0; attempt < 5; attempt++) {
      // Ensure palette item is stable before clicking (avoid React re-render swallowing click)
      await item.waitFor({ state: 'attached', timeout: 2000 });
      await this.page
        .locator('vite-error-overlay')
        .evaluateAll((nodes) => {
          for (const n of nodes) n.remove();
        })
        .catch(() => {});
      const clicked = await item
        .click({ force: attempt > 2 })
        .then(() => true)
        .catch(() => false);
      if (!clicked) {
        await item.dispatchEvent('click').catch(() => {});
      }
      const added = await expect
        .poll(
          async () => {
            const hasHeading = await widgetHeading.isVisible({ timeout: 500 }).catch(() => false);
            const nowCount = await this.widgets.count();
            return hasHeading || nowCount > beforeCount;
          },
          { timeout: 6000 },
        )
        .toBe(true)
        .then(() => true)
        .catch(() => false);
      if (added) break;
    }

    await expect
      .poll(
        async () => {
          const hasHeading = await widgetHeading.isVisible({ timeout: 500 }).catch(() => false);
          const nowCount = await this.widgets.count();
          return hasHeading || nowCount > beforeCount;
        },
        { timeout: 12000 },
      )
      .toBe(true);
    // Wait for property panel sections to fully render after widget selection
    await this.page.waitForLoadState('domcontentloaded');
  }

  // --- Canvas (center panel) ---

  get canvas(): Locator {
    return this.page.locator('[data-testid="designer-canvas"]');
  }

  get canvasEmptyState(): Locator {
    return this.canvas.locator('text=从左侧拖拽组件到这里');
  }

  get widgets(): Locator {
    return this.canvas.locator('.react-grid-item');
  }

  widget(index: number): Locator {
    return this.widgets.nth(index);
  }

  async getWidgetCount(): Promise<number> {
    return this.widgets.count();
  }

  // --- Property Panel (right panel) ---

  get propertyPanel(): Locator {
    return this.page.locator('[data-testid="widget-property-panel"]');
  }

  get propertyPanelHeading(): Locator {
    return this.propertyPanel.locator('h2:has-text("属性")');
  }

  get propertyPanelEmpty(): Locator {
    return this.propertyPanel.locator('text=选择一个组件查看属性');
  }

  get deleteButton(): Locator {
    return this.propertyPanel.locator('button[title="删除"]');
  }

  get duplicateButton(): Locator {
    return this.propertyPanel.locator('button[title="复制"]');
  }

  /** Get a section header in the property panel */
  sectionHeader(name: string): Locator {
    return this.propertyPanel.locator(`h3:has-text("${name}")`);
  }

  /** Get a label in the property panel */
  propertyLabel(name: string): Locator {
    return this.propertyPanel.locator(`label:has-text("${name}")`);
  }

  /** Get a span text in the property panel */
  propertyText(text: string): Locator {
    return this.propertyPanel.locator(`span:has-text("${text}")`);
  }

  /** Get a checkbox label container in the property panel */
  checkboxLabel(text: string): Locator {
    return this.propertyPanel.locator(`label:has(span:has-text("${text}"))`);
  }

  // --- Settings Dialog ---

  get settingsDialogTitle(): Locator {
    return this.page
      .locator('[role="dialog"] h2')
      .filter({ hasText: /设置|Settings/i })
      .first();
  }

  get settingsTitleInput(): Locator {
    // The title input is the first text input inside the settings dialog.
    return this.page.locator('[role="dialog"] input[type="text"]').first();
  }

  get settingsDescriptionInput(): Locator {
    return this.page.locator('[role="dialog"] textarea').first();
  }

  async openSettings(): Promise<void> {
    await expect(this.settingsButton).toBeVisible({ timeout: 8000 });
    const dialog = this.page.locator('[role="dialog"][aria-modal="true"]').first();
    for (let attempt = 0; attempt < 3; attempt += 1) {
      await this.settingsButton.click({ force: attempt > 0 });
      const visible = await dialog.isVisible({ timeout: 3000 }).catch(() => false);
      if (visible) break;
    }
    await expect(dialog).toBeVisible({ timeout: 8000 });
    await expect(this.settingsTitleInput).toBeVisible({ timeout: 5000 });
  }

  async closeSettings(): Promise<void> {
    const dialog = this.page.locator('[role="dialog"][aria-modal="true"]').first();
    // Cancel button may show translated text or i18n key fallback
    const cancelBtn = dialog.locator('button').filter({ hasText: /取消|cancel/i });
    await cancelBtn.click();
    await expect(dialog).not.toBeVisible();
  }

  async saveSettings(): Promise<void> {
    const dialog = this.page.locator('[role="dialog"][aria-modal="true"]').first();
    const saveBtn = dialog
      .locator(
        'button:has-text("保存"), button:has-text("Save"), button:has-text("确定"), button.bg-blue-600',
      )
      .first();
    await expect(saveBtn).toBeVisible({ timeout: 5000 });
    await saveBtn.click();
    // Settings save is client-side only (no API call), wait for dialog to close
    await expect(dialog).not.toBeVisible({ timeout: 5000 });
  }
}
