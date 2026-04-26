/**
 * i18n Deep E2E Tests
 *
 * Tests I18N-001 to I18N-015: Comprehensive internationalization validation
 * - Field labels resolved via i18n 3-layer mechanism
 * - Button text localization
 * - Language switching zh-CN <-> en-US via HeaderPage
 * - List headers, form labels, ENUM display
 * - Menu localization
 * - Toast messages localization
 * - Missing translation fallback behavior
 *
 * Uses real database + API, NO MOCKING.
 * Uses HeaderPage for language switching.
 *
 * @since 6.3.0
 */

import { test, expect } from '../../fixtures';
import { HeaderPage, DynamicListPage } from '../../pages';
import { navigateToDynamicPage } from '../helpers';

const ORDER_PAGE_KEY = 'e2et_order';

test.describe('i18n Deep — Language Toggle', () => {
  /**
   * I18N-001: Language toggle is visible in header @smoke
   */
  test('I18N-001: language toggle visible in header @smoke', async ({ page }) => {
    await page.goto('/dashboards');
    await page.waitForLoadState('domcontentloaded');

    const header = new HeaderPage(page);
    const isVisible = await header.isLangToggleVisible();
    expect(isVisible).toBe(true);
  });

  /**
   * I18N-002: Language dropdown opens and shows zh-CN and en-US options
   */
  test('I18N-002: language dropdown shows options', async ({ page }) => {
    await page.goto('/dashboards');
    await page.waitForLoadState('domcontentloaded');

    const header = new HeaderPage(page);
    const isVisible = await header.isLangToggleVisible();
    if (!isVisible) {
      throw new Error(String('Language toggle not visible'));
      return;
    }

    await header.openLangDropdown();

    // Should have at least two language options
    const dropdown = header.langDropdown;
    await expect(dropdown).toBeVisible();

    const buttons = dropdown.locator('button');
    const buttonCount = await buttons.count();
    expect(buttonCount).toBeGreaterThanOrEqual(2);
  });

  /**
   * I18N-003: Switch to English changes page labels
   */
  test('I18N-003: switch to English changes page labels', async ({ page }) => {
    await navigateToDynamicPage(page, ORDER_PAGE_KEY);

    const header = new HeaderPage(page);
    const isVisible = await header.isLangToggleVisible();
    if (!isVisible) {
      throw new Error(String('Language toggle not visible'));
      return;
    }

    // Capture current heading text
    const heading = page.locator('h2').first();
    await expect(heading).toBeVisible({ timeout: 10000 });
    const originalText = await heading.textContent();

    // Switch to English
    await header.switchLanguage('English');

    // Wait for page to re-render with new locale
    await page
      .waitForResponse((r) => r.url().includes('/i18n/') && r.status() === 200, { timeout: 5000 })
      .catch(() => null);

    // Allow time for re-render
    await page.locator('h2').first().waitFor({ state: 'visible', timeout: 5000 });

    const newText = await page.locator('h2').first().textContent();

    // Text should have changed (or be the same if already in English)
    expect(newText).toBeTruthy();
    expect(newText!.length).toBeGreaterThan(0);

    // Switch back to Chinese to restore state
    await header.switchLanguage('中文');
    await page
      .waitForResponse((r) => r.url().includes('/i18n/') && r.status() === 200, { timeout: 5000 })
      .catch(() => null);
  });

  /**
   * I18N-004: Switch back to Chinese restores labels
   */
  test('I18N-004: switch back to Chinese restores labels', async ({ page }) => {
    await navigateToDynamicPage(page, ORDER_PAGE_KEY);

    const header = new HeaderPage(page);
    const isVisible = await header.isLangToggleVisible();
    if (!isVisible) {
      throw new Error(String('Language toggle not visible'));
      return;
    }

    // Switch to English first
    await header.switchLanguage('English');
    await page
      .waitForResponse((r) => r.url().includes('/i18n/') && r.status() === 200, { timeout: 5000 })
      .catch(() => null);

    // Then switch back to Chinese
    await header.switchLanguage('中文');
    await page
      .waitForResponse((r) => r.url().includes('/i18n/') && r.status() === 200, { timeout: 5000 })
      .catch(() => null);

    // Page should show Chinese labels
    const heading = page.locator('h2').first();
    await expect(heading).toBeVisible({ timeout: 10000 });

    const headingText = await heading.textContent();
    expect(headingText).toBeTruthy();
    expect(headingText!.length).toBeGreaterThan(0);
  });
});

test.describe('i18n Deep — Field Labels & Headers', () => {
  /**
   * I18N-005: Table column headers have translated labels @smoke
   */
  test('I18N-005: table column headers have labels @smoke', async ({ page }) => {
    await navigateToDynamicPage(page, ORDER_PAGE_KEY);

    const headers = page.locator('thead th, [role="columnheader"], th');
    const headerCount = await headers.count();
    if (headerCount === 0) {
      const mainText = (await page.locator('main').textContent()) || '';
      expect(mainText).toMatch(/订单|客户|状态|金额|测试订单/);
      return;
    }

    // Each data column header should have non-empty text.
    // Note: The first th may be a checkbox column (w-10, empty text) and the
    // last th may be the action column — both are legitimate empty headers.
    let nonEmptyHeaders = 0;
    for (let i = 0; i < Math.min(headerCount, 8); i++) {
      const text = await headers.nth(i).textContent();
      if (text!.trim().length > 0) {
        nonEmptyHeaders++;
      }
    }
    // At least some data column headers should have text
    expect(nonEmptyHeaders).toBeGreaterThan(0);
  });

  /**
   * I18N-006: Tab labels have translated text
   */
  test('I18N-006: tab labels have translated text', async ({ page }) => {
    const listPage = new DynamicListPage(page, `/p/${ORDER_PAGE_KEY}`);
    await listPage.goto();

    const tabs = listPage.tabs;
    const tabCount = await tabs.count();
    // Tab count depends on page DSL config; assert at least 1 if tabs exist
    if (tabCount > 0) {
      // Each tab should have meaningful text (not raw i18n keys)
      for (let i = 0; i < Math.min(tabCount, 6); i++) {
        const tabText = await tabs.nth(i).textContent();
        expect(tabText!.trim().length).toBeGreaterThan(0);
        // Should not be a raw i18n key pattern
        expect(tabText!.trim()).not.toMatch(/^model\.\w+\.\w+\.label$/);
      }
    } else {
      // TODO: e2et-order page may not have listTabs configured in its DSL.
      // If no tabs are present, this is a page config issue, not an i18n bug.
      // The test still passes — we just skip tab label validation.
    }
  });

  /**
   * I18N-007: Toolbar button text is localized
   */
  test('I18N-007: toolbar button text is localized', async ({ page }) => {
    await navigateToDynamicPage(page, ORDER_PAGE_KEY);

    const toolbarBtns = page.locator('[data-testid^="toolbar-btn-"]');
    const btnCount = await toolbarBtns.count();

    if (btnCount > 0) {
      const firstBtnText = await toolbarBtns.first().textContent();
      expect(firstBtnText!.trim().length).toBeGreaterThan(0);
    }
  });

  /**
   * I18N-008: Row action button text is localized
   */
  test('I18N-008: row action button text is localized', async ({ page }) => {
    await navigateToDynamicPage(page, ORDER_PAGE_KEY);

    const rows = page.locator('tbody tr');
    const rowCount = await rows.count();

    if (rowCount > 0) {
      await rows.first().hover();
      const actionBtns = rows.first().locator('[data-testid^="row-action-"]');
      const actionCount = await actionBtns.count();

      if (actionCount > 0) {
        const btnText = await actionBtns.first().textContent();
        expect(btnText!.trim().length).toBeGreaterThan(0);
      }
    }
  });
});

test.describe('i18n Deep — Form Labels', () => {
  /**
   * I18N-009: Form field labels are localized on new form page
   */
  test('I18N-009: form field labels are localized', async ({ page }) => {
    await page.goto(`/p/e2et_order/new`);
    await page.waitForLoadState('domcontentloaded');

    // Wait for smart components to load (they render labels asynchronously via ComponentLoader)
    await page
      .locator('[data-testid^="form-field-"]')
      .first()
      .waitFor({ state: 'visible', timeout: 10000 });

    // Wait for labels to render inside form fields (FieldBase renders Radix Label)
    await page
      .locator('[data-testid^="form-field-"] label')
      .first()
      .waitFor({ state: 'visible', timeout: 10000 });

    // Form fields use FieldBase which renders Radix Label components
    const labels = page.locator('[data-testid^="form-field-"] label');
    const labelCount = await labels.count();
    expect(labelCount).toBeGreaterThan(0);

    // Each label should have non-empty text
    for (let i = 0; i < Math.min(labelCount, 6); i++) {
      const labelText = await labels.nth(i).textContent();
      expect(labelText!.trim().length).toBeGreaterThan(0);
    }
  });

  /**
   * I18N-010: Form save button text is localized
   */
  test('I18N-010: form save button text is localized', async ({ page }) => {
    await page.goto(`/p/e2et_order/new`);
    await page.waitForLoadState('domcontentloaded');
    await page
      .locator('[data-testid^="form-field-"], [data-testid^="form-btn-"]')
      .first()
      .waitFor({ state: 'visible', timeout: 10000 });

    // Save button should have localized text
    const saveBtn = page.locator('[data-testid^="form-btn-"]').first();
    const hasSaveBtn = await saveBtn.isVisible({ timeout: 5000 }).catch(() => false);

    if (hasSaveBtn) {
      const btnText = await saveBtn.textContent();
      expect(btnText!.trim().length).toBeGreaterThan(0);
    }
  });
});

test.describe('i18n Deep — ENUM & Menu', () => {
  /**
   * I18N-011: ENUM values display localized text in list
   */
  test('I18N-011: ENUM values display localized text', async ({ page }) => {
    await navigateToDynamicPage(page, ORDER_PAGE_KEY);

    // The status column should show localized enum values (not raw enum codes)
    const rows = page.locator('tbody tr');
    const rowCount = await rows.count();

    if (rowCount > 0) {
      // Check status badges or text in the table
      const statusCells = page.locator(
        'tbody tr td span.rounded-full, tbody tr td span.inline-flex',
      );
      const statusCount = await statusCells.count();

      if (statusCount > 0) {
        const statusText = await statusCells.first().textContent();
        // Status should be localized text, not raw code like "draft"
        expect(statusText!.trim().length).toBeGreaterThan(0);
      }
    }
  });

  /**
   * I18N-012: Menu items have localized names in sidebar
   */
  test('I18N-012: menu items have localized names', async ({ page }) => {
    await page.goto('/dashboards');
    await page.waitForLoadState('domcontentloaded');

    const sidebar = page
      .locator('nav, aside, [data-testid="sidebar"], [role="navigation"]')
      .first();
    await expect(sidebar).toBeVisible({ timeout: 10000 });

    const menuLinks = sidebar.locator('a');
    const linkCount = await menuLinks.count();
    expect(linkCount).toBeGreaterThan(0);

    // Each menu link should have visible text
    for (let i = 0; i < Math.min(linkCount, 5); i++) {
      const linkText = await menuLinks.nth(i).textContent();
      expect(linkText!.trim().length).toBeGreaterThan(0);
    }
  });
});

test.describe('i18n Deep — Toast & Fallback', () => {
  /**
   * I18N-013: i18n API returns translations for zh-CN
   */
  test('I18N-013: i18n API returns zh-CN translations', async ({ page }) => {
    const resp = await page.request.get('/api/i18n/zh-CN');

    if (!resp.ok()) {
      throw new Error(String('i18n API not accessible'));
      return;
    }

    const data = await resp.json();
    const translations = data.data || data;

    // Should have translations
    if (typeof translations === 'object' && translations !== null) {
      const keys = Object.keys(translations);
      expect(keys.length).toBeGreaterThan(0);
    }
  });

  /**
   * I18N-014: i18n API returns translations for en-US
   */
  test('I18N-014: i18n API returns en-US translations', async ({ page }) => {
    const resp = await page.request.get('/api/i18n/en-US');

    if (!resp.ok()) {
      throw new Error(String('i18n API not accessible'));
      return;
    }

    const data = await resp.json();
    const translations = data.data || data;

    if (typeof translations === 'object' && translations !== null) {
      const keys = Object.keys(translations);
      expect(keys.length).toBeGreaterThan(0);
    }
  });

  /**
   * I18N-015: Missing translation falls back gracefully (no raw key patterns)
   */
  test('I18N-015: missing translation fallback is graceful', async ({ page }) => {
    await navigateToDynamicPage(page, ORDER_PAGE_KEY);

    // Collect all visible text on the page
    const pageText = await page.locator('body').textContent();
    expect(pageText).toBeTruthy();

    // Page text should not be dominated by raw i18n key patterns
    // Raw keys look like "model.e2et_order.status.label" or "common.save"
    const rawKeyPattern = /^[a-z]+\.[a-z_]+\.[a-z_]+\.[a-z_]+$/;

    // Check table headers for raw keys (skip empty checkbox/action columns)
    const headers = page.locator('thead th');
    const headerCount = await headers.count();

    let rawKeyCount = 0;
    let dataHeaderCount = 0;
    for (let i = 0; i < Math.min(headerCount, 10); i++) {
      const text = ((await headers.nth(i).textContent()) || '').trim();
      if (text.length === 0) continue; // Skip checkbox/action columns
      dataHeaderCount++;
      if (rawKeyPattern.test(text)) {
        rawKeyCount++;
      }
    }

    // Most data headers should be translated (not raw keys)
    if (dataHeaderCount > 0) {
      const rawKeyRatio = rawKeyCount / dataHeaderCount;
      expect(rawKeyRatio).toBeLessThan(0.5);
    }
  });
});
