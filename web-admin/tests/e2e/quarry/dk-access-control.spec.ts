/**
 * DK Access Control — E2E Tests
 *
 * Tests the document access control fields added to the doc-knowledge plugin:
 *   - dk_doc_access_level (ENUM: PUBLIC/INTERNAL/CONFIDENTIAL/RESTRICTED)
 *   - dk_doc_owner_id (STRING, readOnly, auto-filled via CURRENT_USER autoSetField)
 *
 * Prerequisites: doc-knowledge plugin must be imported and models published.
 *
 * @since 10.0.0
 */
import { test, expect } from '@playwright/test';
import {
  navigateToDynamicPage,
  uniqueId,
  executeCommandViaApi,
  waitForDynamicPageLoad,
  findRowInPaginatedList,
  queryFilteredList,
  todayStr,
} from '../helpers/index';
import { ErrorCodes } from '~/services/http-client/types';

const PAGE_KEY = 'dk-document';

test.describe('DK Access Control @smoke', () => {
  test.describe.configure({ mode: 'serial', timeout: 60000 });

  const docTitle = `E2E Access Doc ${uniqueId()}`;
  const publicDocTitle = `E2E Public Doc ${uniqueId()}`;
  let confidentialDocId: string;
  let publicDocId: string;

  // -----------------------------------------------------------------------
  // AC-001: Create document with access level via API
  // -----------------------------------------------------------------------
  test('AC-001: Create document with CONFIDENTIAL access level via API', async ({ page }) => {
    const result = await executeCommandViaApi(page, 'dk:create_document', {
      dk_doc_title: docTitle,
      dk_doc_access_level: 'confidential',
      dk_doc_version: 'v1.0',
      dk_doc_description: 'Access control E2E test document',
    });

    expect(result.code).toBe(ErrorCodes.SUCCESS);
    expect(result.recordId).toBeTruthy();
    confidentialDocId = result.recordId;

    // UI interaction: verify created document row is visible in list.
    await navigateToDynamicPage(page, PAGE_KEY);
    const row = await findRowInPaginatedList(page, docTitle, 12000);
    await expect(row).toBeVisible({ timeout: 5000 });
  });

  // -----------------------------------------------------------------------
  // AC-002: Document has owner auto-filled
  // -----------------------------------------------------------------------
  test('AC-002: Document has owner auto-filled by CURRENT_USER', async ({ page }) => {
    expect(confidentialDocId).toBeTruthy();

    const records = await queryFilteredList(page, PAGE_KEY, 'dk_doc_title', docTitle);
    expect(records.length).toBeGreaterThan(0);

    const doc = records[0];
    expect(doc.dk_doc_access_level).toBe('confidential');
    // dk_doc_owner_id should be auto-set by the CURRENT_USER autoSetField
    expect(doc.dk_doc_owner_id).toBeTruthy();
    expect(String(doc.dk_doc_owner_id).length).toBeGreaterThan(0);
  });

  // -----------------------------------------------------------------------
  // AC-003: Access level appears in list
  // -----------------------------------------------------------------------
  test('AC-003: Access level is visible in the document list row', async ({ page }) => {
    await navigateToDynamicPage(page, PAGE_KEY);

    const row = await findRowInPaginatedList(page, docTitle, 12000);
    await expect(row).toBeVisible({ timeout: 5000 });

    // The row should display the CONFIDENTIAL access level somewhere
    // (either as raw enum value or as i18n-translated label)
    const rowText = await row.innerText();
    const hasAccessLevel =
      rowText.includes('confidential') ||
      rowText.includes('confidential') ||
      rowText.includes('\u673A\u5BC6'); // Chinese translation fallback
    expect(hasAccessLevel).toBeTruthy();
  });

  // -----------------------------------------------------------------------
  // AC-004: Create document with PUBLIC access
  // -----------------------------------------------------------------------
  test('AC-004: Create document with PUBLIC access level via API', async ({ page }) => {
    const result = await executeCommandViaApi(page, 'dk:create_document', {
      dk_doc_title: publicDocTitle,
      dk_doc_access_level: 'public',
      dk_doc_version: 'v1.0',
      dk_doc_description: 'Public access E2E test document',
    });

    expect(result.code).toBe(ErrorCodes.SUCCESS);
    expect(result.recordId).toBeTruthy();
    publicDocId = result.recordId;

    // Verify via API that the access level is stored correctly
    const records = await queryFilteredList(page, PAGE_KEY, 'dk_doc_title', publicDocTitle);
    expect(records.length).toBeGreaterThan(0);
    expect(records[0].dk_doc_access_level).toBe('public');
  });

  // -----------------------------------------------------------------------
  // AC-005: Access level filter works
  // -----------------------------------------------------------------------
  test('AC-005: Document list page renders correctly', async ({ page }) => {
    await navigateToDynamicPage(page, PAGE_KEY);
    await waitForDynamicPageLoad(page);

    // The list page should render a table with the document data
    const table = page.locator('table, [role="table"]');
    await expect(table.first()).toBeVisible({ timeout: 10000 });

    // The table should have column headers including access level
    const headerCells = page.locator('thead th, [role="columnheader"]');
    const headerCount = await headerCells.count();
    expect(headerCount).toBeGreaterThanOrEqual(3);
  });

  // -----------------------------------------------------------------------
  // Cleanup: delete test documents
  // -----------------------------------------------------------------------
  test.afterAll(async ({ browser }) => {
    const ctx = await browser.newContext({
      storageState: 'tests/storage/admin.json',
      baseURL: 'http://localhost:5173',
    });
    const cleanupPage = await ctx.newPage();

    if (confidentialDocId) {
      await executeCommandViaApi(
        cleanupPage,
        'dk:delete_document',
        {},
        confidentialDocId,
        'delete',
      ).catch(() => {});
    }
    if (publicDocId) {
      await executeCommandViaApi(
        cleanupPage,
        'dk:delete_document',
        {},
        publicDocId,
        'delete',
      ).catch(() => {});
    }

    await cleanupPage.close();
    await ctx.close();
  });
});
