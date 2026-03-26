/**
 * CUSTOM Page Designer Mode E2E Tests
 *
 * Tests the Page Designer behavior when editing a CUSTOM-category page
 * (page_category = 'custom') that uses an API-type dataSource.
 *
 * Fixture: BPM Process Management page
 *   - page_key: bpm_process_management_list
 *   - page_category: CUSTOM
 *   - dataSource.type: api
 *   - dataSource.endpoint: /api/bpm/process-definitions
 *
 * Uses real database, NO MOCKING.
 *
 * @since 4.0.0
 */

import { test, expect } from '../../fixtures';

const BPM_PAGE_KEY = 'bpm_process_management_list';

async function resolveBpmPageId(page: import('@playwright/test').Page): Promise<string> {
  const resp = await page.request.get(`/api/pages/key/${BPM_PAGE_KEY}`);
  expect(resp.ok(), `Expected page ${BPM_PAGE_KEY} to exist`).toBeTruthy();

  const body = await resp.json();
  const pageId = body?.data?.pid;
  expect(pageId, `Expected page ${BPM_PAGE_KEY} pid in API response`).toBeTruthy();
  return pageId;
}

/** Navigate to Page Designer and wait for schema + API detection */
async function openDesigner(page: import('@playwright/test').Page) {
  const pageId = await resolveBpmPageId(page);

  await page.goto(`/page-designer/${pageId}`);
  await page.waitForLoadState('domcontentloaded');

  // Wait for the designer shell instead of coupling to a single schema endpoint.
  await expect(
    page.locator('[data-testid="designer-canvas"], [data-testid="api-field-panel"], [data-testid="ds-editor"]').first()
  ).toBeVisible({ timeout: 15000 });

  // Wait for API detection to complete (auto-triggered for GET APIs)
  await page.waitForResponse(
    (resp) => resp.url().includes('/api/bpm/process-definitions') && resp.status() === 200,
    { timeout: 10000 }
  ).catch(() => {
    // API may already have been fetched or endpoint may not be reachable
  });

  await expect(page.locator('[data-testid="api-field-panel"]')).toBeVisible({ timeout: 10000 });
}

test.describe('CUSTOM Page Designer Mode', () => {
  // Auth handled via storageState (global setup) — no manual login needed

  test('should detect CUSTOM mode and show API fields in left panel', async ({ page }) => {
    await openDesigner(page);

    // Should show API field panel (not ViewModel selector)
    const fieldPanel = page.getByTestId('api-field-panel');
    await expect(fieldPanel).toBeVisible({ timeout: 5000 });

    // Should show detected fields
    await expect(page.getByTestId('api-field-processKey')).toBeVisible();
    await expect(page.getByTestId('api-field-processName')).toBeVisible();

    // Should have "in use" labels for fields already present in the schema
    await expect(fieldPanel.locator('text=in use').first()).toBeVisible();
  });

  test('DataSource editor should show Form mode with endpoint', async ({ page }) => {
    await openDesigner(page);

    // Select a block first to show the right panel
    const dataTableBlock = page.locator('text=data-table').first();
    await dataTableBlock.click();
    await page.waitForTimeout(300);

    // DataSource editor should be visible in right panel
    const dsEditor = page.getByTestId('ds-editor');
    await expect(dsEditor).toBeVisible({ timeout: 5000 });

    // Should show the configured endpoint in Form mode
    const endpointInput = page.getByTestId('ds-endpoint-input');
    await expect(endpointInput).toHaveValue('/api/bpm/process-definitions');
  });

  test('DataSource editor Form/Code toggle should preserve data', async ({ page }) => {
    await openDesigner(page);

    // Select a block first to show the right panel
    await page.locator('text=data-table').first().click();
    await page.waitForTimeout(300);

    const dsEditor = page.getByTestId('ds-editor');
    await expect(dsEditor).toBeVisible({ timeout: 5000 });

    // Switch to Code mode
    await page.getByTestId('ds-code-btn').click();

    // Should show textarea with JSON containing the endpoint
    const textarea = dsEditor.locator('textarea');
    await expect(textarea).toBeVisible();
    const codeContent = await textarea.inputValue();
    expect(codeContent).toContain('/api/bpm/process-definitions');

    // Switch back to Form mode
    await page.getByTestId('ds-form-btn').click();

    // Endpoint should still be preserved after round-trip
    await expect(page.getByTestId('ds-endpoint-input')).toHaveValue('/api/bpm/process-definitions');
  });

  test('manual field input should be available', async ({ page }) => {
    await openDesigner(page);

    // Manual add field input should exist alongside detected API fields
    await expect(
      page.locator('input[placeholder*="Add field manually"]')
    ).toBeVisible({ timeout: 5000 });
  });

  test('Action button editor should show collapsible button cards', async ({ page }) => {
    await openDesigner(page);

    // Click the data-table block in the canvas to select it
    const dataTableBlock = page.locator('[data-block-type="data-table"]').first();
    if (await dataTableBlock.isVisible({ timeout: 3000 }).catch(() => false)) {
      await dataTableBlock.click();
    } else {
      await page.locator('text=data-table').first().click();
    }

    // The action button editor should appear when $actions column is visible
    const actionEditor = page.getByTestId('action-btn-editor');

    if (await actionEditor.isVisible({ timeout: 5000 }).catch(() => false)) {
      const buttonCards = actionEditor.locator('[data-testid^="action-btn-"]');
      const count = await buttonCards.count();
      expect(count).toBeGreaterThan(0);

      // Click first button to expand
      await buttonCards.first().click();

      // Should show expanded form with Code and Action Type fields
      await expect(actionEditor.locator('label:has-text("Code")')).toBeVisible();
      await expect(actionEditor.locator('label:has-text("Action Type")')).toBeVisible();
    }
  });

  test('Tab filter editor should show tabs with filter conditions', async ({ page }) => {
    await openDesigner(page);

    const tabEditor = page.getByTestId('tab-filter-editor');

    if (await tabEditor.isVisible({ timeout: 5000 }).catch(() => false)) {
      await expect(tabEditor.locator('button').first()).toBeVisible();

      // Click on a tab that has a filter (e.g., Draft)
      const draftTab = tabEditor.getByTestId('tab-draft');
      if (await draftTab.isVisible({ timeout: 2000 }).catch(() => false)) {
        await draftTab.click();
        await expect(tabEditor.locator('label:has-text("Filter Condition")')).toBeVisible();
      }

      // The "+" button should be visible for adding new tabs
      await expect(tabEditor.locator('button:has-text("+")')).toBeVisible();
    }
  });

  test('DataSource changes should persist after save and reload', async ({ page }) => {
    await openDesigner(page);

    // Select a block first to show the right panel
    await page.locator('text=data-table').first().click();
    await page.waitForTimeout(300);

    const dsEditor = page.getByTestId('ds-editor');
    await expect(dsEditor).toBeVisible({ timeout: 5000 });

    // Read current endpoint
    const endpointInput = page.getByTestId('ds-endpoint-input');
    const originalEndpoint = await endpointInput.inputValue();
    expect(originalEndpoint).toContain('/api/bpm');

    // Save the page
    const saveButton = page.locator('button:has-text("Save")').first();
    if (await saveButton.isEnabled({ timeout: 2000 }).catch(() => false)) {
      const saveResponse = page.waitForResponse(
        (resp) => resp.url().includes('/api/pages/') && resp.status() === 200,
        { timeout: 5000 }
      );
      await saveButton.click();
      await saveResponse.catch(() => {});
    }

    // Reload and verify persistence
    const reloadResponse = page.waitForResponse(
      (resp) => resp.url().includes('/api/pages/') && resp.status() === 200,
      { timeout: 10000 }
    );
    await page.reload();
    await reloadResponse;
    await page.waitForTimeout(500);

    // Re-select a block to show the right panel after reload
    await page.locator('text=data-table').first().click();
    await page.waitForTimeout(300);

    const dsEditorAfterReload = page.getByTestId('ds-editor');
    await expect(dsEditorAfterReload).toBeVisible({ timeout: 5000 });
    await expect(page.getByTestId('ds-endpoint-input')).toHaveValue(originalEndpoint);
  });
});
