/**
 * Contract-Cost — Contract Status Flow E2E Tests
 *
 * Tests CC-STF-001 ~ CC-STF-012: Complete contract status lifecycle:
 * - Navigate via sidebar menu (Contract & Cost → 合同管理)
 * - Status flow: draft → review → signed → executing → settled → closed
 * - Reject flow: draft → review → (reject) → draft
 * - Illegal transitions: closed contract cannot be re-activated
 * - Each state transition verified via both API and UI list view
 *
 * Contract status flow (from commands.json):
 *   draft
 *     --submit_review-->  review
 *     <--reject_contract-- review
 *     --approve_contract-> signed
 *     --start_execution--> executing
 *     --settle_contract--> settled
 *     --close_contract-->  closed
 *
 * Prerequisites: contract-cost plugin imported, project-management plugin for project dependency.
 *
 * @since 10.0.0
 */

import { test, expect } from '../../fixtures';
import {
  uniqueId,
  executeCommandViaApi,
  dateOffsetStr,
  todayStr,
  findRowInPaginatedList,
  queryFilteredList,
} from '../helpers/index';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const UID = uniqueId('CCSTF');

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
// Helper: Verify contract status in list UI
// ---------------------------------------------------------------------------

async function verifyContractStatusInList(page: any, contractName: string, expectedStatus: string) {
  await navigateToContractList(page);
  const row = await findRowInPaginatedList(page, contractName);
  expect(row, `Contract row for "${contractName}" must be visible`).toBeTruthy();
  const rowText = await row!.textContent();
  // Status labels are i18n rendered but we check for expectedStatus string or Chinese equivalent
  const statusPatterns: Record<string, string[]> = {
    draft: ['草稿', 'draft'],
    review: ['审核中', '待审核', 'review', '待审批'],
    signed: ['已签署', 'signed', '签署'],
    executing: ['执行中', 'executing', '进行中'],
    settled: ['已结算', 'settled', '结算'],
    closed: ['已关闭', 'closed', '关闭'],
    rejected: ['已拒绝', 'rejected', '拒绝'],
  };
  const patterns = statusPatterns[expectedStatus] ?? [expectedStatus];
  const matchesStatus = patterns.some((p) => rowText?.includes(p));
  expect(
    matchesStatus,
    `Row text "${rowText}" should contain status "${expectedStatus}" (or Chinese equivalent)`,
  ).toBe(true);
}

// ---------------------------------------------------------------------------
// Test Suite
// ---------------------------------------------------------------------------

test.describe('CC Contract — Status Flow', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(120_000);

  let projectPid: string;

  // Main contract: will go through full lifecycle
  let contractPid: string;
  let contractName: string;

  // Secondary contract: draft → review → reject → back to draft
  let contractRejectPid: string;
  let contractRejectName: string;

  // =========================================================================
  // Setup: Create project and seed contracts
  // =========================================================================

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      // Create and activate project
      const proj = await executeCommandViaApi(
        page,
        'pm:create_project',
        { pm_project_name: `CCSTFProject_${UID}` },
        undefined,
        'create',
      );
      projectPid = proj.recordId;
      expect(projectPid, 'Project must be created').toBeTruthy();
      await executeCommandViaApi(page, 'pm:activate_project', {}, projectPid, 'update');

      // Main contract (full lifecycle)
      contractName = `E2E FullFlow ${UID}`;
      const contract = await executeCommandViaApi(
        page,
        'cc:create_contract',
        {
          cc_contract_name: contractName,
          cc_contract_project_id: projectPid,
          cc_contract_type: 'design',
          cc_party_a: `Client_${UID}`,
          cc_party_b: 'AuraBoot Ltd.',
          cc_contract_amount: 1000000,
          cc_signed_date: dateOffsetStr(-30),
          cc_start_date: dateOffsetStr(-30),
          cc_end_date: dateOffsetStr(360),
          cc_description: `Full lifecycle contract for E2E test ${UID}`,
        },
        undefined,
        'create',
      );
      contractPid = contract.recordId;
      expect(contractPid, 'Main contract must be created').toBeTruthy();

      // Reject-test contract
      contractRejectName = `E2E RejectFlow ${UID}`;
      const contractReject = await executeCommandViaApi(
        page,
        'cc:create_contract',
        {
          cc_contract_name: contractRejectName,
          cc_contract_project_id: projectPid,
          cc_contract_type: 'construction',
          cc_contract_amount: 200000,
        },
        undefined,
        'create',
      );
      contractRejectPid = contractReject.recordId;
      expect(contractRejectPid).toBeTruthy();
    } finally {
      await ctx.close();
    }
  });

  // =========================================================================
  // CC-STF-001: Navigate to contract list via sidebar (smoke)
  // =========================================================================

  test('CC-STF-001: Navigate to contract list and verify seeded contracts visible', async ({
    page,
  }) => {
    await navigateToContractList(page);

    // Layer 1: page loaded
    await expect(page).toHaveURL(/\/contract-cost\/contracts/, { timeout: 10_000 });

    // Layer 2: our seeded contracts are visible
    const mainRow = await findRowInPaginatedList(page, contractName);
    expect(mainRow, 'Main contract must be visible in list').toBeTruthy();

    const rejectRow = await findRowInPaginatedList(page, contractRejectName);
    expect(rejectRow, 'Reject-test contract must be visible in list').toBeTruthy();
  });

  // =========================================================================
  // CC-STF-002: Main contract initial state is draft
  // =========================================================================

  test('CC-STF-002: Main contract initial status is draft', async ({ page }) => {
    const resp = await page.request.get(`/api/dynamic/cc_contract/${contractPid}`);
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const record = body.data ?? body;
    expect(record.cc_contract_status, 'Initial status must be draft').toBe('draft');
  });

  // =========================================================================
  // CC-STF-003: Submit review — draft → review
  // =========================================================================

  test('CC-STF-003: Submit for review transitions contract to review status', async ({ page }) => {
    const result = await executeCommandViaApi(
      page,
      'cc:submit_review',
      {},
      contractPid,
      'state_transition',
    );
    expect(result.code, 'submit_review must return code 0').toBe('0');

    // Layer 3: API confirms status
    const resp = await page.request.get(`/api/dynamic/cc_contract/${contractPid}`);
    const body = await resp.json();
    const record = body.data ?? body;
    expect(record.cc_contract_status, 'Status must be "review" after submit').toBe('review');

    // Layer 2: list shows review status
    await verifyContractStatusInList(page, contractName, 'review');
  });

  // =========================================================================
  // CC-STF-004: Approve contract — review → signed
  // =========================================================================

  test('CC-STF-004: Approve contract transitions to signed status', async ({ page }) => {
    const result = await executeCommandViaApi(
      page,
      'cc:approve_contract',
      {},
      contractPid,
      'state_transition',
    );
    expect(result.code, 'approve_contract must return code 0').toBe('0');

    // Layer 3: status is signed
    const resp = await page.request.get(`/api/dynamic/cc_contract/${contractPid}`);
    const body = await resp.json();
    const record = body.data ?? body;
    expect(record.cc_contract_status, 'Status must be "signed" after approval').toBe('signed');

    // Layer 2: list reflects signed status
    await verifyContractStatusInList(page, contractName, 'signed');
  });

  // =========================================================================
  // CC-STF-005: Start execution — signed → executing
  // =========================================================================

  test('CC-STF-005: Start execution transitions contract to executing status', async ({ page }) => {
    const result = await executeCommandViaApi(
      page,
      'cc:start_execution',
      {},
      contractPid,
      'state_transition',
    );
    expect(result.code, 'start_execution must return code 0').toBe('0');

    // Layer 3: status is executing
    const resp = await page.request.get(`/api/dynamic/cc_contract/${contractPid}`);
    const body = await resp.json();
    const record = body.data ?? body;
    expect(record.cc_contract_status, 'Status must be "executing" after start_execution').toBe(
      'executing',
    );

    // Layer 2: list reflects executing status
    await verifyContractStatusInList(page, contractName, 'executing');
  });

  // =========================================================================
  // CC-STF-006: Settle contract — executing → settled
  // =========================================================================

  test('CC-STF-006: Settle contract transitions to settled status', async ({ page }) => {
    const result = await executeCommandViaApi(
      page,
      'cc:settle_contract',
      {},
      contractPid,
      'state_transition',
    );
    expect(result.code, 'settle_contract must return code 0').toBe('0');

    // Layer 3: status is settled
    const resp = await page.request.get(`/api/dynamic/cc_contract/${contractPid}`);
    const body = await resp.json();
    const record = body.data ?? body;
    expect(record.cc_contract_status, 'Status must be "settled" after settle').toBe('settled');

    // Layer 2: list reflects settled status
    await verifyContractStatusInList(page, contractName, 'settled');
  });

  // =========================================================================
  // CC-STF-007: Close contract — settled → closed
  // =========================================================================

  test('CC-STF-007: Close contract transitions to closed status', async ({ page }) => {
    const result = await executeCommandViaApi(
      page,
      'cc:close_contract',
      {},
      contractPid,
      'state_transition',
    );
    expect(result.code, 'close_contract must return code 0').toBe('0');

    // Layer 3: status is closed
    const resp = await page.request.get(`/api/dynamic/cc_contract/${contractPid}`);
    const body = await resp.json();
    const record = body.data ?? body;
    expect(record.cc_contract_status, 'Status must be "closed" after close').toBe('closed');

    // Layer 2: list reflects closed status
    await verifyContractStatusInList(page, contractName, 'closed');
  });

  // =========================================================================
  // CC-STF-008: Illegal operation — closed contract cannot be re-submitted
  // =========================================================================

  test('CC-STF-008: Closed contract cannot be re-submitted for review (illegal transition)', async ({
    page,
  }) => {
    expect(contractPid).toBeTruthy();

    const illegalResult = await executeCommandViaApi(
      page,
      'cc:submit_review',
      {},
      contractPid,
      'state_transition',
      { allowHttpError: true },
    );
    expect(illegalResult.code, 'submit_review on closed contract must fail').not.toBe('0');

    // Closed status must remain unchanged
    const resp = await page.request.get(`/api/dynamic/cc_contract/${contractPid}`);
    const body = await resp.json();
    const record = body.data ?? body;
    expect(
      record.cc_contract_status,
      'Status must remain closed after illegal transition attempt',
    ).toBe('closed');
  });

  // =========================================================================
  // CC-STF-009: Illegal operation — closed contract cannot be deleted
  // =========================================================================

  test('CC-STF-009: Closed contract cannot be deleted (delete restricted to draft)', async ({
    page,
  }) => {
    const deleteResult = await executeCommandViaApi(
      page,
      'cc:delete_contract',
      {},
      contractPid,
      'delete',
      { allowHttpError: true },
    );
    expect(deleteResult.code, 'Delete on closed contract must fail').not.toBe('0');
  });

  // =========================================================================
  // CC-STF-010: Reject flow — draft → review → reject → draft
  // =========================================================================

  test('CC-STF-010: Reject review returns contract to draft status', async ({ page }) => {
    // Submit for review
    await executeCommandViaApi(page, 'cc:submit_review', {}, contractRejectPid, 'state_transition');

    // Verify in review
    const respReview = await page.request.get(`/api/dynamic/cc_contract/${contractRejectPid}`);
    const bodyReview = await respReview.json();
    expect((bodyReview.data ?? bodyReview).cc_contract_status).toBe('review');

    // Reject
    const rejectResult = await executeCommandViaApi(
      page,
      'cc:reject_contract',
      {},
      contractRejectPid,
      'state_transition',
    );
    expect(rejectResult.code, 'reject_contract must return code 0').toBe('0');

    // Layer 3: back to draft
    const resp = await page.request.get(`/api/dynamic/cc_contract/${contractRejectPid}`);
    const body = await resp.json();
    const record = body.data ?? body;
    expect(record.cc_contract_status, 'Status must return to "draft" after rejection').toBe(
      'draft',
    );

    // Layer 2: list shows draft
    await verifyContractStatusInList(page, contractRejectName, 'draft');
  });

  // =========================================================================
  // CC-STF-011: Filter by status — executing contracts visible when filtered
  // =========================================================================

  test('CC-STF-011: Filtering by status=closed returns our closed contract', async ({ page }) => {
    const records = await queryFilteredList(page, 'cc-contract', 'cc_contract_status', 'closed', {
      operator: 'EQ',
    });
    expect(records.length, 'Filtering by closed must return at least 1').toBeGreaterThan(0);
    const ourRecord = records.find((r: any) => r.pid === contractPid || r.id === contractPid);
    expect(ourRecord, 'Our closed contract must appear in filtered results').toBeTruthy();
  });

  // =========================================================================
  // CC-STF-012: Data integrity — full lifecycle data persists (no cleanup)
  // =========================================================================

  test('CC-STF-012: Verify full lifecycle data persists correctly', async ({ page }) => {
    // Main contract: closed at end
    const closedRecords = await queryFilteredList(
      page,
      'cc-contract',
      'cc_contract_name',
      contractName,
      { operator: 'EQ' },
    );
    expect(closedRecords.length, 'Main contract must exist').toBeGreaterThanOrEqual(1);
    expect(closedRecords[0].cc_contract_status, 'Main contract must be closed').toBe('closed');
    expect(Number(closedRecords[0].cc_contract_amount), 'Contract amount must be preserved').toBe(
      1000000,
    );

    // Reject-test contract: back to draft
    const draftRecords = await queryFilteredList(
      page,
      'cc-contract',
      'cc_contract_name',
      contractRejectName,
      { operator: 'EQ' },
    );
    expect(draftRecords.length, 'Reject-test contract must exist').toBeGreaterThanOrEqual(1);
    expect(draftRecords[0].cc_contract_status, 'Reject-test contract must be draft').toBe('draft');
  });
});
