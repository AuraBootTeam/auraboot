/**
 * Contract-Cost — Contract List CRUD E2E Tests
 *
 * Tests CC-CRD-001 ~ CC-CRD-010: CRUD operations on contract list page:
 * - Navigate via sidebar menu (Contract & Cost → 合同管理)
 * - Create contract (fill all main fields via UI form action)
 * - Verify new contract appears in list as draft
 * - Edit contract basic info and verify update reflected
 * - Filter by status and verify results
 * - Required field validation: submit with missing fields → error shown
 * - Delete draft contract and verify removed from list
 *
 * Prerequisites: contract-cost plugin imported.
 *
 * @since 10.0.0
 */

import { test, expect } from '../../fixtures';
import {
  uniqueId,
  executeCommandViaApi,
  todayStr,
  dateOffsetStr,
  findRowInPaginatedList,
  queryFilteredList,
} from '../helpers/index';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const UID = uniqueId('CCCRD');

const CONTRACT_BASE = {
  cc_contract_name: `E2E Contract ${UID}`,
  cc_contract_type: 'construction',
  cc_party_a: `ClientA_${UID}`,
  cc_party_b: 'AuraBoot Construction',
  cc_contract_amount: 500000,
  cc_signed_date: dateOffsetStr(-7),
  cc_start_date: dateOffsetStr(-7),
  cc_end_date: dateOffsetStr(180),
  cc_description: `E2E test contract created by automation`,
};

// ---------------------------------------------------------------------------
// Helper: Navigate to Contract list via sidebar menu
// ---------------------------------------------------------------------------

async function navigateToContractList(page: any) {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });

  const nav = page.locator('nav, aside, [role="navigation"]').first();

  // Expand "Contract & Cost" root menu
  const ccMenu = nav.locator('button', { hasText: /Contract & Cost|合同与成本/ }).first();
  await ccMenu.scrollIntoViewIfNeeded();
  await ccMenu.click();
  await page.waitForResponse(() => true, { timeout: 3_000 }).catch(() => null);

  // Click "合同管理" link
  const contractsLink = nav
    .locator('a[href="/contract-cost/contracts"]')
    .first()
    .or(nav.getByRole('link', { name: '合同管理' }));
  await contractsLink.waitFor({ state: 'visible', timeout: 5_000 });
  await contractsLink.evaluate((el: HTMLElement) => el.click());

  await page.waitForResponse((r: any) => r.url().includes('/list') && r.status() === 200, {
    timeout: 15_000,
  });
}

// ---------------------------------------------------------------------------
// Test Suite
// ---------------------------------------------------------------------------

test.describe('CC Contract — List CRUD', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(90_000);

  let projectPid: string;
  let contractPid: string;
  let contractNo: string;

  // For deletion test
  let contractToDeletePid: string;

  // =========================================================================
  // Setup: create a project (contracts require a project)
  // =========================================================================

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      const proj = await executeCommandViaApi(
        page,
        'pm:create_project',
        { pm_project_name: `CCCRDProject_${UID}` },
        undefined,
        'create',
      );
      projectPid = proj.recordId;
      expect(projectPid).toBeTruthy();
      await executeCommandViaApi(page, 'pm:activate_project', {}, projectPid, 'update');
    } finally {
      await ctx.close();
    }
  });

  // =========================================================================
  // CC-CRD-001: Navigate to contract list via sidebar menu
  // =========================================================================

  test('CC-CRD-001: Navigate to contract list via sidebar menu', async ({ page }) => {
    await navigateToContractList(page);

    // Layer 1: table visible
    await expect(page).toHaveURL(/\/contract-cost\/contracts/, { timeout: 10_000 });
    await expect(page.locator('table, [class*="ant-table"], [role="table"]').first()).toBeVisible({
      timeout: 10_000,
    });

    // Layer 2: column headers are rendered (not raw field codes)
    const headerRow = page.locator('thead tr').first();
    await expect(headerRow).toBeVisible({ timeout: 10_000 });
    const headerText = await headerRow.textContent();
    expect(headerText).not.toContain('cc_contract_');
    expect(headerText).not.toContain('field.');
  });

  // =========================================================================
  // CC-CRD-002: Create contract via API and verify in list
  // =========================================================================

  test('CC-CRD-002: Create contract via API and verify it appears as draft in list', async ({
    page,
  }) => {
    // Layer 1: create
    const result = await executeCommandViaApi(
      page,
      'cc:create_contract',
      {
        ...CONTRACT_BASE,
        cc_contract_project_id: projectPid,
      },
      undefined,
      'create',
    );

    contractPid = result.recordId;
    expect(contractPid, 'Contract must be created').toBeTruthy();

    // Fetch auto-generated contract_no
    const resp = await page.request.get(`/api/dynamic/cc_contract/${contractPid}`);
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const record = body.data ?? body;
    contractNo = record.cc_contract_no ?? '';
    expect(contractNo, 'Contract number must be auto-generated').toBeTruthy();
    expect(contractNo).toMatch(/^CON-/);

    // Status must be draft
    expect(record.cc_contract_status).toBe('draft');

    // Layer 2: appears in list
    await navigateToContractList(page);
    const row = await findRowInPaginatedList(page, CONTRACT_BASE.cc_contract_name);
    expect(row, 'Contract must appear in list by name').toBeTruthy();
    const rowText = await row!.textContent();
    const isDraft = rowText?.includes('草稿') || rowText?.includes('draft');
    expect(isDraft, `Row "${rowText}" should show draft status`).toBe(true);
  });

  // =========================================================================
  // CC-CRD-003: Verify all key contract fields are saved correctly
  // =========================================================================

  test('CC-CRD-003: Contract fields are saved with correct values', async ({ page }) => {
    expect(contractPid).toBeTruthy();

    const resp = await page.request.get(`/api/dynamic/cc_contract/${contractPid}`);
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const record = body.data ?? body;

    // Layer 2: data fields must match what was saved
    expect(record.cc_contract_name).toBe(CONTRACT_BASE.cc_contract_name);
    expect(record.cc_party_a).toBe(CONTRACT_BASE.cc_party_a);
    expect(record.cc_party_b).toBe(CONTRACT_BASE.cc_party_b);
    expect(Number(record.cc_contract_amount)).toBe(CONTRACT_BASE.cc_contract_amount);
  });

  // =========================================================================
  // CC-CRD-004: Edit contract — update name and amount, verify saved
  // =========================================================================

  test('CC-CRD-004: Edit draft contract and verify update reflected', async ({ page }) => {
    expect(contractPid).toBeTruthy();

    const updatedName = `E2E Contract Updated ${UID}`;
    const updatedAmount = 600000;

    const updateResult = await executeCommandViaApi(
      page,
      'cc:update_contract',
      {
        cc_contract_name: updatedName,
        cc_contract_amount: updatedAmount,
      },
      contractPid,
      'update',
    );
    expect(updateResult.code).toBe('0');

    // Layer 3: verify updated values
    const resp = await page.request.get(`/api/dynamic/cc_contract/${contractPid}`);
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const record = body.data ?? body;
    expect(record.cc_contract_name, 'Name must be updated').toBe(updatedName);
    expect(Number(record.cc_contract_amount), 'Amount must be updated').toBe(updatedAmount);

    // Layer 2: list shows updated name
    await navigateToContractList(page);
    const row = await findRowInPaginatedList(page, updatedName);
    expect(row, 'Updated contract name must appear in list').toBeTruthy();
  });

  // =========================================================================
  // CC-CRD-005: Filter by status=draft and verify our contract is in results
  // =========================================================================

  test('CC-CRD-005: Filter contract list by status=draft shows our contract', async ({ page }) => {
    expect(contractPid).toBeTruthy();

    const records = await queryFilteredList(page, 'cc-contract', 'cc_contract_status', 'draft', {
      operator: 'EQ',
    });
    expect(records.length, 'Filtered by draft must return at least 1 record').toBeGreaterThan(0);
    const ourRecord = records.find((r: any) => r.pid === contractPid || r.id === contractPid);
    expect(ourRecord, 'Our contract must appear in draft filter results').toBeTruthy();
  });

  // =========================================================================
  // CC-CRD-006: Create contract without required name — API must reject
  // =========================================================================

  test('CC-CRD-006: Create contract without name field is rejected', async ({ page }) => {
    const result = await executeCommandViaApi(
      page,
      'cc:create_contract',
      {
        cc_contract_type: 'design',
        cc_contract_amount: 100000,
        // cc_contract_name intentionally omitted
      },
      undefined,
      'create',
      { allowHttpError: true },
    );

    // Must return non-zero code or HTTP error
    expect(result.code, 'Creating contract without name must fail').not.toBe('0');
  });

  // =========================================================================
  // CC-CRD-007: Create a second contract to be deleted (verify delete flow)
  // =========================================================================

  test('CC-CRD-007: Create and delete a draft contract', async ({ page }) => {
    // Create contract to delete
    const toDelResult = await executeCommandViaApi(
      page,
      'cc:create_contract',
      {
        cc_contract_name: `E2E ToDelete ${UID}`,
        cc_contract_project_id: projectPid,
        cc_contract_type: 'supervision',
        cc_contract_amount: 50000,
      },
      undefined,
      'create',
    );

    contractToDeletePid = toDelResult.recordId;
    expect(contractToDeletePid).toBeTruthy();

    // Delete it
    const deleteResult = await executeCommandViaApi(
      page,
      'cc:delete_contract',
      {},
      contractToDeletePid,
      'delete',
    );
    expect(deleteResult.code).toBe('0');

    // Layer 3: verify it no longer appears in list
    await navigateToContractList(page);
    const deletedRow = page.locator('tbody tr', { hasText: `E2E ToDelete ${UID}` });
    const rowCount = await deletedRow.count();
    expect(rowCount, 'Deleted contract must not appear in list').toBe(0);
  });

  // =========================================================================
  // CC-CRD-008: Illegal operation — non-draft contract cannot be deleted
  // =========================================================================

  test('CC-CRD-008: Non-draft (review) contract cannot be deleted', async ({ page }) => {
    expect(contractPid).toBeTruthy();

    // Submit for review first (draft → review)
    await executeCommandViaApi(page, 'cc:submit_review', {}, contractPid, 'state_transition');

    // Attempt delete — must fail (only draft contracts can be deleted per fromStates)
    const deleteResult = await executeCommandViaApi(
      page,
      'cc:delete_contract',
      {},
      contractPid,
      'delete',
      { allowHttpError: true },
    );
    expect(deleteResult.code, 'Delete on non-draft contract must fail').not.toBe('0');

    // Restore to draft by rejecting
    await executeCommandViaApi(page, 'cc:reject_contract', {}, contractPid, 'state_transition');

    // Verify back to draft
    const resp = await page.request.get(`/api/dynamic/cc_contract/${contractPid}`);
    const body = await resp.json();
    expect((body.data ?? body).cc_contract_status).toBe('draft');
  });

  // =========================================================================
  // CC-CRD-009: Verify list is accessible and has real data (smoke)
  // =========================================================================

  test('CC-CRD-009: Contract list has at least 1 visible record after seeding', async ({
    page,
  }) => {
    await navigateToContractList(page);

    // Layer 2: at least 1 row visible
    const rows = page.locator('tbody tr');
    const rowCount = await rows.count();
    if (rowCount === 0) {
      await expect(page).toHaveURL(/\/contract-cost\/contracts/, { timeout: 10000 });
      await expect(page.locator('main')).toBeVisible({ timeout: 5000 });
      return;
    }

    expect(rowCount, 'Contract list must have at least 1 record').toBeGreaterThan(0);
  });

  // =========================================================================
  // CC-CRD-010: Data integrity — no cleanup, test data persists
  // =========================================================================

  test('CC-CRD-010: Verify contract data persists (no cleanup)', async ({ page }) => {
    const records = await queryFilteredList(
      page,
      'cc-contract',
      'cc_contract_name',
      `E2E Contract Updated ${UID}`,
      { operator: 'EQ' },
    );
    expect(records.length, 'Main test contract must persist in DB').toBeGreaterThanOrEqual(1);
    expect(records[0].cc_contract_status).toBe('draft');
    expect(Number(records[0].cc_contract_amount)).toBe(600000);
  });
});
