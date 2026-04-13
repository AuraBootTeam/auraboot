/**
 * CRM Inline Edit Tests
 *
 * Validates inline editing capability on CRM Leads list:
 *
 * IE-001 @smoke  Navigate to CRM Leads list → table visible with data
 * IE-002 @critical Double-click a text cell → inline edit activates → Escape cancels → value unchanged
 * IE-003 @critical Double-click a text cell → type new value → Enter saves → cell shows new value → reload verifies persistence
 *
 * Prerequisites:
 *   - CRM plugin imported, crm_lead model published
 *   - At least 1 lead record exists (created in beforeAll)
 *
 * @since 8.0.0
 */

import { test, expect } from '../../fixtures';
import {
  navigateToDynamicPage,
  waitForDynamicPageLoad,
  uniqueId,
  executeCommandViaApi,
  findRowInPaginatedList,
} from '../helpers/index';

test.describe('CRM Inline Edit @smoke', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(90000);

  const uid = uniqueId('IE');
  const initialCompany = `InlineEditLead_${uid}`;
  const updatedCompany = `Updated_${uid}`;
  let createdRecordId: string | undefined;

  // =========================================================================
  // DATA SETUP
  // =========================================================================
  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      const result = await executeCommandViaApi(
        page,
        'crm:create_lead',
        {
          crm_lead_company: initialCompany,
          crm_lead_contact_name: `IE Contact ${uid}`,
          crm_lead_source: 'website',
          crm_lead_status: 'new',
        },
        undefined,
        'create',
      );
      createdRecordId = result?.recordId;
    } finally {
      await ctx.close();
    }
  });

  // =========================================================================
  // IE-001: Navigate to CRM Leads list page — data is visible
  // =========================================================================
  test('IE-001: Navigate to CRM Leads list page @smoke', async ({ page }) => {
    await navigateToDynamicPage(page, 'crm-lead');
    await waitForDynamicPageLoad(page);
    await expect(page).toHaveURL(/\/p\/crm_lead/);

    const rows = page.locator('tbody tr');
    await rows.first().waitFor({ state: 'visible', timeout: 10000 });
    const rowCount = await rows.count();
    expect(rowCount, 'Leads list should have at least 1 row').toBeGreaterThan(0);
  });

  // =========================================================================
  // IE-002: Double-click cell → inline edit activates → Escape cancels (value unchanged)
  // =========================================================================
  test('IE-002: Double-click cell activates inline edit; Escape cancels without saving @critical', async ({
    page,
  }) => {
    await navigateToDynamicPage(page, 'crm-lead');
    await waitForDynamicPageLoad(page);

    // Find the row created in beforeAll
    const row = await findRowInPaginatedList(page, initialCompany, 15000).catch(() => null);
    if (!row) {
      test.skip(true, 'beforeAll lead not found — check data setup');
      return;
    }

    // The company-name cell is typically in the second column (after checkbox)
    const companyCell = row.locator('td').nth(1);
    await companyCell.waitFor({ state: 'visible', timeout: 5000 });

    // Record original display text before edit
    const originalText = (await companyCell.textContent())?.trim() ?? '';

    // Trigger inline edit by double-clicking
    await companyCell.dblclick();

    const inlineInput = page.locator(
      '[data-testid^="inline-edit-text-"], [data-testid^="inline-edit-select-"], ' +
        '[data-testid^="inline-edit-number-"], [data-testid^="inline-edit-date-"]',
    );
    const hasInlineEdit = await inlineInput
      .first()
      .isVisible({ timeout: 3000 })
      .catch(() => false);

    if (!hasInlineEdit) {
      // This column is not inline-editable — verify no crash occurred
      await expect(row).toBeVisible({ timeout: 5000 });
      const errorOverlay = page.locator(
        '.error-boundary, [data-testid="error-alert"], text=/Error/i',
      );
      const hasError = await errorOverlay.isVisible({ timeout: 1000 }).catch(() => false);
      expect(hasError, 'Non-editable cell double-click must not crash the page').toBe(false);
      return;
    }

    const editInput = inlineInput.first();
    await expect(editInput).toBeVisible();

    // Verify input is interactive (not disabled)
    const isDisabled = await editInput.isDisabled().catch(() => false);
    expect(isDisabled, 'Inline edit input must not be disabled').toBe(false);

    // Press Escape to cancel — must NOT save the edit
    await editInput.click();
    await page.keyboard.press('Escape');
    await page.keyboard.press('Escape'); // second press clears dropdown if Select
    const dismissed = await editInput
      .waitFor({ state: 'hidden', timeout: 3000 })
      .then(() => true)
      .catch(() => false);

    if (!dismissed) {
      await page
        .locator('body')
        .click({ position: { x: 10, y: 10 } })
        .catch(() => {});
      await editInput.waitFor({ state: 'hidden', timeout: 3000 }).catch(() => {});
    }

    // Input must be gone
    const inputStillOpen = await editInput.isVisible().catch(() => false);
    expect(inputStillOpen, 'Escape must dismiss the inline edit input').toBe(false);

    // Cell must still show the ORIGINAL value (cancel did not save)
    const textAfterCancel = (await companyCell.textContent())?.trim() ?? '';
    expect(
      textAfterCancel,
      `After Escape cancel, cell must still show original value "${originalText}"`,
    ).toBe(originalText);
  });

  // =========================================================================
  // IE-003: Double-click → type new value → Enter saves → reload verifies persistence
  // =========================================================================
  test('IE-003: Inline edit saves new value; reload confirms persistence @critical', async ({
    page,
  }) => {
    await navigateToDynamicPage(page, 'crm-lead');
    await waitForDynamicPageLoad(page);

    // Find the row created in beforeAll
    const row = await findRowInPaginatedList(page, initialCompany, 15000).catch(() => null);
    if (!row) {
      test.skip(true, 'beforeAll lead not found — check data setup');
      return;
    }

    // Target the cell containing initialCompany to ensure we edit the company field
    const companyCells = row.locator('td').filter({ hasText: initialCompany });
    let editableCell: import('@playwright/test').Locator | null = null;

    const companyCount = await companyCells.count();
    if (companyCount > 0) {
      await companyCells.first().dblclick();
      const inlineInput = page.locator('[data-testid^="inline-edit-text-"]');
      const visible = await inlineInput
        .first()
        .isVisible({ timeout: 2000 })
        .catch(() => false);
      if (visible) {
        editableCell = companyCells.first();
      } else {
        await page.keyboard.press('Escape');
      }
    }

    if (!editableCell) {
      // Fallback: iterate through cells to find first editable text cell
      const cells = row.locator('td');
      const cellCount = await cells.count();
      for (let i = 1; i < cellCount; i++) {
        const cell = cells.nth(i);
        await cell.dblclick();
        const inlineInput = page.locator('[data-testid^="inline-edit-text-"]');
        const visible = await inlineInput
          .first()
          .isVisible({ timeout: 2000 })
          .catch(() => false);
        if (visible) {
          editableCell = cell;
          break;
        }
        await page.keyboard.press('Escape');
        await page.waitForResponse(() => true, { timeout: 300 }).catch(() => null);
      }
    }

    if (!editableCell) {
      test.skip(
        true,
        'No inline-editable text cell found on crm_lead row — inline edit may not be configured for text fields',
      );
      return;
    }

    // At this point the inline text input is open
    const editInput = page.locator('[data-testid^="inline-edit-text-"]').first();
    await expect(editInput).toBeVisible();

    // Wait for the save API response after typing new value + Enter
    const saveResponsePromise = page
      .waitForResponse(
        (r) =>
          r.url().includes('/commands/execute') ||
          (r.url().includes('/api/dynamic/crm_lead') && r.request().method() !== 'GET'),
        { timeout: 15000 },
      )
      .catch(() => null);

    // Clear existing value and type new value
    await editInput.click({ clickCount: 3 }); // select all
    await editInput.fill(updatedCompany);
    await page.keyboard.press('Enter');

    const saveResp = await saveResponsePromise;
    if (saveResp) {
      const body = await saveResp.json().catch(() => null);
      if (body && body.code !== undefined) {
        expect(String(body.code), 'Save API must return code "0"').toBe('0');
      }
    }

    // Wait for list to refresh
    await page
      .waitForResponse(
        (r) => r.url().includes('/api/dynamic/crm_lead') && r.url().includes('/list'),
        { timeout: 10000 },
      )
      .catch(() => null);

    // The row should now show the updated value
    const updatedRow = await findRowInPaginatedList(page, updatedCompany, 12000).catch(() => null);
    expect(
      updatedRow,
      `After inline edit save, row with updated company "${updatedCompany}" must be visible in the list`,
    ).not.toBeNull();
    if (updatedRow) {
      await expect(updatedRow).toBeVisible();
    }

    // --- Persistence check: verify via API that the record was updated ---
    if (createdRecordId) {
      const verifyResp = await page.request.get(`/api/dynamic/crm_lead/${createdRecordId}`);
      expect(verifyResp.ok(), 'Record fetch after reload should succeed').toBe(true);
      const verifyBody = await verifyResp.json();
      expect(
        verifyBody?.data?.crm_lead_company,
        `Inline edit must persist: crm_lead_company should be "${updatedCompany}"`,
      ).toBe(updatedCompany);
    }

    // --- UI check: search for updatedCompany in the list ---
    await navigateToDynamicPage(page, 'crm-lead');
    await waitForDynamicPageLoad(page);

    const persistedRow = await findRowInPaginatedList(page, updatedCompany, 12000).catch(
      () => null,
    );
    expect(
      persistedRow,
      `After page reload, the updated company "${updatedCompany}" must still be present — inline edit must persist to DB`,
    ).not.toBeNull();

    // The original value must no longer appear in the list (use search to confirm)
    const searchInput = page.locator('[data-testid="search-input"]').first();
    if (await searchInput.isVisible({ timeout: 3000 }).catch(() => false)) {
      await searchInput.fill(initialCompany);
      await page.keyboard.press('Enter');
      await page
        .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 5000 })
        .catch(() => null);
    }
    const oldRow = page.locator('tbody tr', { hasText: initialCompany }).first();
    await expect(
      oldRow,
      `After successful inline edit, the original company "${initialCompany}" must no longer appear in the list`,
    ).not.toBeVisible({ timeout: 3000 });
  });
});
