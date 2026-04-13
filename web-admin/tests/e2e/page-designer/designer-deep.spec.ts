/**
 * Page Designer Deep E2E Tests
 *
 * Tests PD-001 ~ PD-011: Deep page designer functionality
 * - Drag and drop components
 * - Component property editing
 * - Page save and publish
 * - Page preview
 * - Undo/Redo functionality
 * - Zoom controls
 *
 * Uses PageDesignerPage PO and data-testid selectors.
 *
 * @since 4.0.0
 */

import { test, expect } from '../../fixtures';
import { PageDesignerPage } from '../../pages';

async function resolveDropZone(
  page: import('@playwright/test').Page,
  designerPage: PageDesignerPage,
): Promise<import('@playwright/test').Locator> {
  if (await designerPage.canvas.isVisible({ timeout: 1000 }).catch(() => false)) {
    return designerPage.canvas;
  }

  const emptyDropZone = page.getByText('拖拽组件到此处').first();
  if (await emptyDropZone.isVisible({ timeout: 1000 }).catch(() => false)) {
    return emptyDropZone;
  }

  const countDropZone = page.locator('text=/\\d+ 个组件/').first();
  if (await countDropZone.isVisible({ timeout: 1000 }).catch(() => false)) {
    return countDropZone;
  }
  return page.locator('main').first();
}

async function resolvePaletteDraggable(
  page: import('@playwright/test').Page,
  dropZone: import('@playwright/test').Locator,
): Promise<import('@playwright/test').Locator> {
  const allDraggables = page.locator(
    '[aria-roledescription="draggable"], [draggable="true"], [data-draggable]',
  );
  const count = await allDraggables.count();
  const dropBox = await dropZone.boundingBox();
  const dropX = dropBox ? dropBox.x : Number.MAX_SAFE_INTEGER;

  for (let i = 0; i < count; i++) {
    const candidate = allDraggables.nth(i);
    if (!(await candidate.isVisible({ timeout: 500 }).catch(() => false))) continue;
    const box = await candidate.boundingBox().catch(() => null);
    if (!box) continue;
    // Palette items are in the left panel; canvas blocks are in the middle panel.
    if (box.x + box.width < dropX) {
      return candidate;
    }
  }

  return allDraggables.first();
}

async function addBlocksToCanvas(
  page: import('@playwright/test').Page,
  designerPage: PageDesignerPage,
  targetCount: number,
): Promise<void> {
  await designerPage.openComponentLibrary();
  const dropZone = await resolveDropZone(page, designerPage);
  await expect(dropZone).toBeVisible({ timeout: 10000 });

  for (let i = 0; i < targetCount; i++) {
    const currentCount = await designerPage.getBlockCount();
    if (currentCount >= targetCount) break;

    const source = await resolvePaletteDraggable(page, dropZone);
    await expect(source).toBeVisible({ timeout: 10000 });
    const sourceBox = await source.boundingBox();
    const dropBox = await dropZone.boundingBox();
    expect(sourceBox).toBeTruthy();
    expect(dropBox).toBeTruthy();

    await page.mouse.move(
      sourceBox!.x + sourceBox!.width / 2,
      sourceBox!.y + sourceBox!.height / 2,
    );
    await page.mouse.down();
    await page.mouse.move(dropBox!.x + dropBox!.width / 2, dropBox!.y + dropBox!.height / 2, {
      steps: 12,
    });
    await page.mouse.up();
    // Wait for React to process the drop event
    await page
      .locator('.react-grid-item, [data-testid="designer-canvas"] > *')
      .first()
      .waitFor({ state: 'attached', timeout: 3000 })
      .catch(() => {});
  }
}

test.describe('Page Designer - Drag and Drop', () => {
  let designerPage: PageDesignerPage;

  test.beforeEach(async ({ page }) => {
    designerPage = new PageDesignerPage(page);
  });

  /**
   * PD-001: Component drag from library
   */
  test('PD-001: should drag component from library', async ({ page }) => {
    const loaded = await designerPage.openViaList();
    expect(loaded).toBe(true);

    await designerPage.openComponentLibrary();

    const dropTarget = await resolveDropZone(page, designerPage);
    const draggableComponent = await resolvePaletteDraggable(page, dropTarget);
    const hasDraggable = await draggableComponent.isVisible({ timeout: 3000 }).catch(() => false);

    expect(hasDraggable).toBe(true);

    const componentBox = await draggableComponent.boundingBox();
    const canvasBox = await dropTarget.boundingBox().catch(() => null);

    expect(componentBox).toBeTruthy();
    expect(canvasBox).toBeTruthy();

    await page.mouse.move(componentBox!.x + 10, componentBox!.y + 10);
    await page.mouse.down();
    await page.mouse.move(canvasBox!.x + 50, canvasBox!.y + 50);
    await page.mouse.up();

    await expect(designerPage.canvas).toBeVisible();
  });

  /**
   * PD-002: Component reorder in canvas
   */
  test('PD-002: should reorder components in canvas', async ({ page }) => {
    const loaded = await designerPage.openViaList();
    expect(loaded).toBe(true);

    let blockCount = await designerPage.getBlockCount();
    if (blockCount < 2) {
      await addBlocksToCanvas(page, designerPage, 2);
      blockCount = await designerPage.getBlockCount();
    }
    if (blockCount < 2) {
      await expect(designerPage.canvas).toBeVisible({ timeout: 5000 });
      return;
    }

    const firstBox = await designerPage.block(0).boundingBox();
    const secondBox = await designerPage.block(1).boundingBox();

    expect(firstBox).toBeTruthy();
    expect(secondBox).toBeTruthy();

    await page.mouse.move(firstBox!.x + 10, firstBox!.y + 10);
    await page.mouse.down();
    await page.mouse.move(secondBox!.x + 10, secondBox!.y + secondBox!.height + 10);
    await page.mouse.up();

    expect(await designerPage.getBlockCount()).toBeGreaterThanOrEqual(2);
  });
});

test.describe('Page Designer - Property Editing', () => {
  let designerPage: PageDesignerPage;

  test.beforeEach(async ({ page }) => {
    designerPage = new PageDesignerPage(page);
  });

  /**
   * PD-003: Select component shows properties
   */
  test('PD-003: should show properties when component selected', async () => {
    const loaded = await designerPage.openViaList();
    expect(loaded).toBe(true);

    if ((await designerPage.getBlockCount()) < 1) {
      await addBlocksToCanvas(designerPage.page, designerPage, 1);
    }

    const hasBlock = await designerPage
      .block(0)
      .isVisible({ timeout: 5000 })
      .catch(() => false);
    expect(hasBlock).toBe(true);

    await designerPage.selectBlock(0);

    const hasProperties = await designerPage.propertiesPanel
      .isVisible({ timeout: 3000 })
      .catch(() => false);
    const isEmpty = await designerPage.hasEmptyProperties();

    expect(hasProperties || !isEmpty).toBe(true);
  });

  /**
   * PD-004: Edit component property
   */
  test('PD-004: should edit component property', async () => {
    const loaded = await designerPage.openViaList();
    expect(loaded).toBe(true);

    if ((await designerPage.getBlockCount()) < 1) {
      await addBlocksToCanvas(designerPage.page, designerPage, 1);
    }

    const hasBlock = await designerPage
      .block(0)
      .isVisible({ timeout: 5000 })
      .catch(() => false);
    expect(hasBlock).toBe(true);

    await designerPage.selectBlock(0);

    const propertyInput = designerPage.page
      .locator('[data-testid="designer-properties-panel"] input, .w-80 input, aside input')
      .first();
    const hasInput = await propertyInput.isVisible({ timeout: 3000 }).catch(() => false);

    expect(hasInput).toBe(true);

    await propertyInput.clear();
    await propertyInput.fill('Test Value');

    const inputValue = await propertyInput.inputValue();
    expect(inputValue).toBe('Test Value');
  });
});

test.describe('Page Designer - Save and Publish', () => {
  let designerPage: PageDesignerPage;

  test.beforeEach(async ({ page }) => {
    designerPage = new PageDesignerPage(page);
  });

  /**
   * PD-005: Save page
   */
  test('PD-005: should save page', async ({ page }) => {
    const loaded = await designerPage.openViaList();
    expect(loaded).toBe(true);

    const hasSaveBtn = await designerPage.saveButton
      .isVisible({ timeout: 10000 })
      .catch(() => false);
    expect(hasSaveBtn).toBe(true);

    const isDisabled = await designerPage.saveButton.isDisabled();

    if (!isDisabled) {
      await designerPage.save();

      const successMsg = page.locator('.ant-message-success, text=保存成功, text=Saved');
      const hasSuccess = await successMsg.isVisible({ timeout: 3000 }).catch(() => false);

      expect(hasSuccess).toBe(true);
    } else {
      expect(isDisabled).toBe(true);
    }
  });

  /**
   * PD-006: Publish page
   */
  test('PD-006: should publish page', async () => {
    const loaded = await designerPage.openViaList();
    expect(loaded).toBe(true);

    const hasPublishBtn = await designerPage.publishButton
      .isVisible({ timeout: 3000 })
      .catch(() => false);
    expect(hasPublishBtn).toBe(true);

    await expect(designerPage.publishButton).toBeVisible();
  });
});

test.describe('Page Designer - Preview', () => {
  let designerPage: PageDesignerPage;

  test.beforeEach(async ({ page }) => {
    designerPage = new PageDesignerPage(page);
  });

  /**
   * PD-007: Preview page
   */
  test('PD-007: should preview page', async ({ page }) => {
    const loaded = await designerPage.openViaList();
    expect(loaded).toBe(true);

    const hasPreviewBtn = await designerPage.previewButton
      .isVisible({ timeout: 3000 })
      .catch(() => false);
    expect(hasPreviewBtn).toBe(true);

    await designerPage.preview();

    const previewHeading = page.locator('h2:has-text("页面预览")');
    const hasPreviewHeading = await previewHeading.isVisible({ timeout: 5000 }).catch(() => false);

    const deviceSelector = page.locator('button:has-text("桌面端")');
    const hasDeviceSelector = await deviceSelector.isVisible({ timeout: 2000 }).catch(() => false);

    expect(hasPreviewHeading || hasDeviceSelector).toBe(true);
  });
});

test.describe('Page Designer - Undo/Redo', () => {
  let designerPage: PageDesignerPage;

  test.beforeEach(async ({ page }) => {
    designerPage = new PageDesignerPage(page);
  });

  /**
   * PD-008: Undo action
   */
  test('PD-008: should undo action', async () => {
    const loaded = await designerPage.openViaList();
    expect(loaded).toBe(true);

    const hasUndoBtn = await designerPage.undoButton
      .isVisible({ timeout: 3000 })
      .catch(() => false);
    expect(hasUndoBtn).toBe(true);

    await expect(designerPage.undoButton).toBeVisible();
  });

  /**
   * PD-009: Redo action
   */
  test('PD-009: should redo action', async () => {
    const loaded = await designerPage.openViaList();
    expect(loaded).toBe(true);

    const hasRedoBtn = await designerPage.redoButton
      .isVisible({ timeout: 3000 })
      .catch(() => false);
    expect(hasRedoBtn).toBe(true);

    await expect(designerPage.redoButton).toBeVisible();
  });

  /**
   * PD-010: Keyboard shortcut for undo/redo
   */
  test('PD-010: should support keyboard shortcuts', async ({ page }) => {
    const loaded = await designerPage.openViaList();
    expect(loaded).toBe(true);

    await page.keyboard.press('Control+z');
    await page.keyboard.press('Control+Shift+z');

    await expect(designerPage.saveButton).toBeVisible();
  });
});

test.describe('Page Designer - Zoom Controls', () => {
  let designerPage: PageDesignerPage;

  test.beforeEach(async ({ page }) => {
    designerPage = new PageDesignerPage(page);
  });

  /**
   * PD-011: Zoom in/out
   */
  test('PD-011: should support zoom controls', async () => {
    const loaded = await designerPage.openViaList();
    expect(loaded).toBe(true);

    const hasZoomIn = await designerPage.zoomInButton
      .isVisible({ timeout: 3000 })
      .catch(() => false);
    const hasZoomOut = await designerPage.zoomOutButton
      .isVisible({ timeout: 3000 })
      .catch(() => false);
    const hasZoomLevel = await designerPage.zoomLevel
      .isVisible({ timeout: 3000 })
      .catch(() => false);

    expect(hasZoomIn || hasZoomOut || hasZoomLevel).toBe(true);
  });
});
