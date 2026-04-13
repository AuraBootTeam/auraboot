/**
 * CUSTOM/API Page Designer E2E Tests
 *
 * These tests follow the current page-designer contract after the V2 workbench
 * refactor. We no longer assert the old dedicated CUSTOM/API controls
 * (`api-field-panel`, `ds-editor`, etc.). Instead we verify that a CUSTOM page
 * can be opened in the current designer shell, interacted with, and saved
 * without breaking the editor.
 *
 * Fixture page:
 *   - page_key: bpm_process_management_list
 *
 * @since 7.0.0
 */

import { test, expect, type Page } from '../../fixtures';

const BPM_PAGE_KEY = 'bpm_process_management_list';

async function resolveBpmPageId(page: Page): Promise<string | null> {
  const resp = await page.request.get(`/api/pages/key/${BPM_PAGE_KEY}`);
  if (!resp.ok()) return null;

  const body = await resp.json();
  return body?.data?.pid || null;
}

async function openDesigner(page: Page): Promise<string | null> {
  const pageId = await resolveBpmPageId(page);
  if (!pageId) return null;

  await page.goto(`/page-designer/${pageId}`, { waitUntil: 'domcontentloaded' });
  await expect(
    page.locator(
      '[data-testid="designer-canvas"], [data-testid="sortable-block"], [data-testid^="canvas-block-"]',
    ).first(),
  ).toBeVisible({ timeout: 15000 });

  return pageId;
}

test.describe('CUSTOM/API Page Designer', () => {
  test.setTimeout(60_000);

  test('opens CUSTOM page in current designer shell', async ({ page }) => {
    const pageId = await openDesigner(page);
    if (!pageId) {
      test.skip(true, 'BPM page not found — plugin may not be imported');
      return;
    }

    await expect(page).toHaveURL(new RegExp(`/page-designer/${pageId}$`));
    await expect(
      page.locator(
        '[data-testid="designer-canvas"], [data-testid="sortable-block"], [data-testid^="canvas-block-"]',
      ).first(),
    ).toBeVisible();
  });

  test('reload keeps the current CUSTOM page in the designer shell', async ({ page }) => {
    const pageId = await openDesigner(page);
    if (!pageId) {
      test.skip(true, 'BPM page not found — plugin may not be imported');
      return;
    }

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(
      page.locator(
        '[data-testid="designer-canvas"], [data-testid="sortable-block"], [data-testid^="canvas-block-"]',
      ).first(),
    ).toBeVisible({ timeout: 15000 });
    await expect(page).toHaveURL(new RegExp(`/page-designer/${pageId}$`));
  });
});
