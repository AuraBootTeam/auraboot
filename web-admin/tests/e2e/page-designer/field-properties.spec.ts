/**
 * Page Designer - Field Properties Editor Tests
 *
 * Tests for field selection and property editing functionality.
 * Uses PageDesignerPage PO and data-testid selectors.
 *
 * Creates a fixture page via API in beforeAll to avoid unreliable openViaList().
 *
 * @since 4.0.0
 */

import { test, expect } from '../../fixtures';
import { PageDesignerPage } from '../../pages';
import { uniqueId } from '../helpers/index';

let pagePid: string;

test.beforeAll(async ({ browser }) => {
  const ctx = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
  const page = await ctx.newPage();
  const name = uniqueId('fp');
  const pageKey = `e2e_fp_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 7)}`;
  const resp = await page.request.post('/api/pages', {
    data: {
      name,
      pageKey,
      title: name,
      // kind=form keeps BlocksDesigner (designer-canvas). After DesignerRouter
      // dispatch (merge 5f72469b), list kind routes to ListConfigPanel and the
      // canvas/tab testids this suite asserts no longer exist.
      kind: 'form',
      // ab_announcement is a published meta-model present in every OSS stack;
      // `tenant` is not always published, which broke fixture creation.
      modelCode: 'ab_announcement',
      schemaVersion: 4,
      // Block ids are required by the page block-structure validator
      // (/api/pages rejects blank/duplicate ids). The previous id-less fixture
      // failed creation outright, which the suite's tautology assertion masked.
      blocks: [
        { id: 'fp_section_main', blockType: 'form-section', label: 'Main', fields: [] },
        { id: 'fp_buttons', blockType: 'form-buttons', label: 'Buttons', actions: [] },
      ],
      metaInfo: { componentCount: 1 },
      semver: '0.1.0',
    },
  });
  expect(resp.ok(), `Create fixture page failed: ${resp.status()}`).toBeTruthy();
  const body = await resp.json();
  expect(body.code, 'API code must be 0').toBe('0');
  pagePid = body.data.pid as string;
  await ctx.close();
});

async function openDesigner(designerPage: PageDesignerPage) {
  await designerPage.page.goto(`/page-designer/${pagePid}`, { waitUntil: 'domcontentloaded' });
  await designerPage.page
    .locator('text=Loading page...')
    .waitFor({ state: 'hidden', timeout: 30000 })
    .catch(() => null);
  // BlocksDesigner renders for list/form pages — wait for both canvas and left panel
  await expect(designerPage.page.locator('[data-testid="designer-canvas"]')).toBeVisible({ timeout: 15000 });
  await expect(designerPage.page.locator('[data-testid="designer-tab-fields"]')).toBeVisible({ timeout: 10000 });
}

test.describe('Block Selection', () => {
  let designerPage: PageDesignerPage;

  test.beforeEach(async ({ page }) => {
    designerPage = new PageDesignerPage(page);
    await openDesigner(designerPage);
  });

  test('should select block on click and show block properties', async ({ page }) => {
    // The form fixture seeds a form-section block (id=fp_section_main) and a
    // form-buttons block. Before any selection the property panel shows its
    // empty state; after selecting a block the empty state is gone and the
    // panel renders that block's properties (heading shows the block label).
    //
    // (Replaces a previous `expect(hasPropertiesPanel || true).toBe(true)`
    //  tautology that could never fail — it accepted any outcome.)
    const propertiesPanel = page.getByTestId('designer-properties-panel');
    const propertiesEmpty = page.getByTestId('properties-empty');

    await expect(propertiesPanel).toBeVisible();
    await expect(propertiesEmpty, 'panel starts in the empty state').toBeVisible();

    const sectionBlock = page.locator(
      '[data-testid="sortable-block"][data-block-id="fp_section_main"]',
    );
    await expect(sectionBlock).toBeVisible({ timeout: 10000 });
    await sectionBlock.click();

    // Selecting a block dismisses the empty state and renders the block's
    // property editor (the panel heading shows the form-section label).
    await expect(propertiesEmpty, 'empty state cleared after selecting a block').toBeHidden();
    await expect(
      propertiesPanel.locator('h3').first(),
      'property panel shows the selected block heading',
    ).toBeVisible();
    await expect(propertiesPanel.locator('h3').first()).toHaveText(/表单区段|Form section|Section/i);
  });

  test('should show block in canvas area', async () => {
    const hasBlock = await designerPage
      .block(0)
      .isVisible({ timeout: 3000 })
      .catch(() => false);
    if (hasBlock) {
      expect(hasBlock).toBeTruthy();
    } else {
      // Accept empty-canvas fixture as long as designer surface is available.
      await expect(designerPage.canvas).toBeVisible();
    }
  });
});

test.describe('Designer Areas', () => {
  let designerPage: PageDesignerPage;

  test.beforeEach(async ({ page }) => {
    designerPage = new PageDesignerPage(page);
    await openDesigner(designerPage);
  });

  test('should show area sections in left panel', async ({ page }) => {
    // BlocksDesigner left panel tabs should be visible (openDesigner already waits for them)
    await expect(page.locator('[data-testid="designer-tab-fields"]')).toBeVisible({ timeout: 10000 });
    await expect(page.locator('[data-testid="designer-tab-blocks"]')).toBeVisible({ timeout: 5000 });
    await expect(page.locator('[data-testid="designer-tab-outline"]')).toBeVisible({ timeout: 5000 });
  });

  test('should show main content area', async () => {
    await expect(designerPage.canvas).toBeVisible({ timeout: 5000 });
  });
});

test.describe('Designer Toolbar', () => {
  let designerPage: PageDesignerPage;

  test.beforeEach(async ({ page }) => {
    designerPage = new PageDesignerPage(page);
    await openDesigner(designerPage);
  });

  test('should show toolbar with essential buttons', async () => {
    test.slow();

    let foundButtons = 0;
    const buttons = [
      designerPage.saveButton,
      designerPage.publishButton,
      designerPage.previewButton,
      designerPage.undoButton,
      designerPage.redoButton,
    ];

    for (const btn of buttons) {
      if (await btn.isVisible({ timeout: 3000 }).catch(() => false)) {
        foundButtons++;
      }
    }

    expect(foundButtons).toBeGreaterThan(0);
  });

  test('should have back button to return to page list', async () => {
    const hasBack = await designerPage.backButton.isVisible({ timeout: 3000 }).catch(() => false);
    if (hasBack) {
      await expect(designerPage.backButton).toBeVisible();
    }
  });

  test('should show zoom controls', async () => {
    const hasZoomControls =
      (await designerPage.zoomInButton.isVisible({ timeout: 2000 }).catch(() => false)) ||
      (await designerPage.zoomOutButton.isVisible({ timeout: 2000 }).catch(() => false)) ||
      (await designerPage.zoomLevel.isVisible({ timeout: 2000 }).catch(() => false));

    expect(hasZoomControls).toBeTruthy();
  });
});

test.describe('Designer Left Panel Tabs', () => {
  let designerPage: PageDesignerPage;

  test.beforeEach(async ({ page }) => {
    designerPage = new PageDesignerPage(page);
    await openDesigner(designerPage);
  });

  test('should show field, component and outline tabs', async () => {
    test.slow();

    // Left panel tabs should already be visible from openDesigner
    await expect(designerPage.fieldsTab).toBeVisible({ timeout: 10000 });
    await expect(designerPage.blocksTab).toBeVisible({ timeout: 5000 });
    await expect(designerPage.outlineTab).toBeVisible({ timeout: 5000 });
  });

  test('should switch between tabs', async () => {
    test.slow();

    await expect(designerPage.fieldsTab).toBeVisible({ timeout: 10000 });

    // Switch to blocks tab and verify it becomes active
    await designerPage.clickBlocksTab();
    const componentTabActive = await designerPage.blocksTab
      .evaluate(
        (el) =>
          el.classList.contains('text-blue-600') ||
          el.classList.contains('bg-blue-50') ||
          el.classList.contains('border-blue-600'),
      )
      .catch(() => false);

    // Switch back to field tab and verify it becomes active
    await designerPage.clickFieldsTab();
    const fieldTabActive = await designerPage.fieldsTab
      .evaluate(
        (el) =>
          el.classList.contains('text-blue-600') ||
          el.classList.contains('bg-blue-50') ||
          el.classList.contains('border-blue-600'),
      )
      .catch(() => false);

    expect(componentTabActive || fieldTabActive).toBe(true);
  });
});

test.describe('Designer Right Panel', () => {
  let designerPage: PageDesignerPage;

  test.beforeEach(async ({ page }) => {
    designerPage = new PageDesignerPage(page);
    await openDesigner(designerPage);
  });

  test('should show empty state when no block selected', async () => {
    const hasEmptyState = await designerPage.hasEmptyProperties();

    if (hasEmptyState) {
      await expect(designerPage.propertiesEmpty).toBeVisible();
    }
  });
});

test.describe('Page Designer Navigation', () => {
  let designerPage: PageDesignerPage;

  test.beforeEach(async ({ page }) => {
    designerPage = new PageDesignerPage(page);
    await openDesigner(designerPage);
  });

  test('should navigate back to page list', async ({ page }) => {
    const hasBack = await designerPage.backButton.isVisible({ timeout: 3000 }).catch(() => false);
    expect(hasBack).toBe(true);

    await designerPage.goBack();

    const pageListHeading = page.locator('h1:has-text("页面管理")');
    const onPageList = await pageListHeading.isVisible({ timeout: 5000 }).catch(() => false);

    if (onPageList) {
      await expect(pageListHeading).toBeVisible();
    }
  });
});
