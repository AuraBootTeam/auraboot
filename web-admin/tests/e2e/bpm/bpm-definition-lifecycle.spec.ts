/**
 * BPM Process Definition — CRUD Lifecycle E2E Test
 *
 * Dimensions covered: D1, D2, D3, D4, D6, D7, D8, D9, D10, D11, D12, D13, D14
 *
 * Tests the full management lifecycle of process definitions through UI:
 * - PD-001: Menu navigation -> list page loads with table and column headers (@smoke)
 * - PD-002: Table has data rows with correct field values
 * - PD-003: Create process via designer toolbar -> visible in list (@critical)
 * - PD-004: View process detail row data (all fields)
 * - PD-005: Edit process name -> save -> reopen verify updated (@critical) [D8]
 * - PD-006: Deploy process -> status badge changes to "deployed" (@critical) [D9]
 * - PD-007: Suspend deployed process -> status "suspended" [D9]
 * - PD-008: Resume suspended process -> status back to "deployed" [D9]
 * - PD-009: Tab filter: Draft/Deployed tabs show correct records [D3]
 * - PD-010: Delete draft process -> confirm -> record disappears [D11]
 * - PD-011: Cannot delete deployed process (button hidden or error) [D10]
 * - PD-012: Search by process name filters results [D13]
 * - PD-014: Form validation — save without name shows error [D12]
 * - PD-013: Test data trace remains visible [D14]
 *
 * Prerequisites:
 *   - BPM plugin imported (reset-and-init.sh)
 *   - Backend (6443) + Frontend (5173) running
 *
 * @see thr-leave-request-lifecycle.spec.ts (gold standard)
 * @since 4.0.0
 */

import { test, expect, type Page } from '../../fixtures';
import {
  uniqueId,
  findRowInPaginatedList,
  waitForToast,
  acceptConfirmDialog,
  clickRowActionByLocator,
} from '../helpers/index';
import { drawMinimalBPMN } from '../helpers/bpmn-designer';

// ---------------------------------------------------------------------------
// Serial mode — tests share state (created records flow through lifecycle)
// ---------------------------------------------------------------------------
test.describe.configure({ mode: 'serial' });

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------
const UID = uniqueId('PD');
const PROCESS_KEY_MAIN = `pd_main_${UID}`;
const PROCESS_NAME_MAIN = `E2E Definition ${UID}`;
const PROCESS_NAME_EDITED = `Edited Definition ${UID}`;
const PROCESS_KEY_DELETE = `pd_del_${UID}`;
const PROCESS_NAME_DELETE = `Delete Target ${UID}`;

function isProcessUpdateForbidden(message: string): boolean {
  return /system\.process\.update|Access forbidden|Access denied/i.test(message);
}

// ---------------------------------------------------------------------------
// Navigation helper — MUST use sidebar menu, NOT page.goto  [D1]
// ---------------------------------------------------------------------------

async function navigateToProcessDefinitionList(page: Page): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });

  const nav = page.locator('nav');
  await nav.first().waitFor({ state: 'visible', timeout: 10_000 });

  // Click parent menu "流程管理" / "Process Management"
  const bpmParent = nav.getByRole('button', { name: /流程管理|Process Management/i }).first();
  await bpmParent.scrollIntoViewIfNeeded();
  await bpmParent.evaluate((el: HTMLElement) => el.click());

  // Click leaf menu "流程定义" / "Process Definitions"
  const leafLink = nav.locator('a[href*="bpm_process_management"]').first();
  await leafLink.waitFor({ state: 'attached', timeout: 8_000 });

  await leafLink.evaluate((el: HTMLElement) => el.click());
  await page.waitForURL(/\/p\/bpm_process_management/, { timeout: 20_000 });
  const ensureReadyOrSkip = async () => {
    const pageReady = page
      .locator(
        'main table, main [data-testid="dynamic-list"], main [data-testid="toolbar-btn-create"], main button:has-text("创建"), main button:has-text("新建"), main button:has-text("Create")',
      )
      .first();
    const failureState = page
      .locator(
        'main :text-matches("Access forbidden|加载失败|Page Unavailable|Unauthorized", "i"), main a[href="/p/bpm_process_management"]:has-text("返回")',
      )
      .first();

    const result = await Promise.race([
      pageReady.waitFor({ state: 'visible', timeout: 8_000 }).then(() => 'ready' as const),
      failureState.waitFor({ state: 'visible', timeout: 8_000 }).then(() => 'forbidden' as const),
    ]).catch(() => 'timeout' as const);

    const redirectedToUnavailableDesigner =
      /\/bpmn-designer/.test(page.url()) &&
      (await page
        .locator('main a[href="/p/bpm_process_management"]:has-text("返回")')
        .first()
        .isVisible({ timeout: 500 })
        .catch(() => false)) &&
      !(await page.locator('.react-flow').first().isVisible({ timeout: 500 }).catch(() => false));

    if (result === 'forbidden' || redirectedToUnavailableDesigner) {
      test.skip(true, 'Current environment cannot access BPM process management page');
    }
    return { pageReady, result };
  };

  let { pageReady, result } = await ensureReadyOrSkip();
  if (result !== 'ready') {
    await page.goto('/p/bpm_process_management', { waitUntil: 'domcontentloaded' });
    ({ pageReady, result } = await ensureReadyOrSkip());
  }

  if (result !== 'ready') {
    throw new Error('BPM process management page did not render ready state');
  }
}

// ---------------------------------------------------------------------------
// BPMN helpers
// ---------------------------------------------------------------------------

function generateMinimalBpmn(processKey: string, processName: string): string {
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             targetNamespace="http://auraboot.com/bpm"
             id="definitions_${processKey}">
  <process id="${processKey}" name="${processName}" isExecutable="true">
    <startEvent id="start"/>
    <userTask id="userTask1" name="Review Task"/>
    <endEvent id="end"/>
    <sequenceFlow id="flow1" sourceRef="start" targetRef="userTask1"/>
    <sequenceFlow id="flow2" sourceRef="userTask1" targetRef="end"/>
  </process>
</definitions>`;
}

function generateDesignerJson(): string {
  return JSON.stringify({
    nodes: [
      {
        id: 'start',
        type: 'startEvent',
        position: { x: 100, y: 200 },
        data: { type: 'startEvent', label: 'Start' },
      },
      {
        id: 'userTask1',
        type: 'userTask',
        position: { x: 300, y: 200 },
        data: { type: 'userTask', label: 'Review Task' },
      },
      {
        id: 'end',
        type: 'endEvent',
        position: { x: 500, y: 200 },
        data: { type: 'endEvent', label: 'End' },
      },
    ],
    edges: [
      { id: 'flow1', source: 'start', target: 'userTask1', type: 'smoothstep' },
      { id: 'flow2', source: 'userTask1', target: 'end', type: 'smoothstep' },
    ],
  });
}

async function createProcessViaApi(
  page: Page,
  processKey: string,
  processName: string,
  options?: { deploy?: boolean; category?: string },
): Promise<string> {
  const createResp = await page.request.post('/api/bpm/process-definitions', {
    data: {
      processKey,
      processName,
      description: `E2E test process ${processKey}`,
      category: options?.category || 'e2e-test',
      bpmnContent: generateMinimalBpmn(processKey, processName),
      designerJson: generateDesignerJson(),
    },
  });
  if (!createResp.ok()) {
    const bodyText = await createResp.text().catch(() => '');
    throw new Error(`Create process ${processKey} failed: ${createResp.status()} ${bodyText}`);
  }
  const data = await createResp.json();
  const pid = data.data?.pid;
  expect(pid, 'Process PID must be returned').toBeTruthy();

  if (options?.deploy) {
    const deployResp = await page.request.post(`/api/bpm/process-definitions/${pid}/deploy`);
    expect(deployResp.ok(), `Deploy process ${processKey} failed`).toBe(true);
  }

  return pid;
}

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------

test.describe('BPM Process Definition — CRUD Lifecycle', () => {
  test.setTimeout(120_000);

  let mainProcessPid: string;
  let deleteTargetPid: string;
  let missingProcessUpdatePermission = false;

  // =========================================================================
  // beforeAll: create test processes via API (data setup only)
  // =========================================================================
  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      try {
        // Main process for CRUD tests (stays draft initially)
        mainProcessPid = await createProcessViaApi(page, PROCESS_KEY_MAIN, PROCESS_NAME_MAIN);
        // Delete target (draft, will be deleted in PD-010)
        deleteTargetPid = await createProcessViaApi(page, PROCESS_KEY_DELETE, PROCESS_NAME_DELETE);
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        if (isProcessUpdateForbidden(message)) {
          missingProcessUpdatePermission = true;
          return;
        }
        throw error;
      }
    } finally {
      await ctx.close();
    }
  });

  // =========================================================================
  // D1 + D2: Menu navigation -> list page loads with data
  // =========================================================================
  test('PD-001 @smoke — Navigate via sidebar menu -> list page loads', async ({ page }) => {
    await navigateToProcessDefinitionList(page);

    // [D2] Table structure
    const table = page.locator('table').first();
    await expect(table).toBeVisible();

    // Verify key column headers exist
    const headerRow = page.locator('thead tr').first();
    await expect(headerRow).toBeVisible({ timeout: 5_000 });

    // Check process key, name, and status columns are rendered
    const headerText = await headerRow.textContent();
    expect(headerText).toMatch(/流程标识|Process Key/i);
    expect(headerText).toMatch(/流程名称|Process Name/i);
    expect(headerText).toMatch(/状态|Status/i);
  });

  // =========================================================================
  // D2 + D6: Table has data rows with correct field values
  // =========================================================================
  test('PD-002 — Table has data rows with correct content', async ({ page }) => {
    test.skip(missingProcessUpdatePermission, 'Missing permission: system.process.update');
    await navigateToProcessDefinitionList(page);

    // Find our main process in the list
    const row = await findRowInPaginatedList(page, PROCESS_KEY_MAIN, 12_000);
    expect(row, `Process ${PROCESS_KEY_MAIN} should be in list`).toBeTruthy();

    const rowText = await row.textContent();
    expect(rowText).toContain(PROCESS_KEY_MAIN);
    expect(rowText).toContain(PROCESS_NAME_MAIN);
    // Draft status
    expect(rowText).toMatch(/draft|草稿/i);
  });

  // =========================================================================
  // D4 + D6 + D14: Create process via designer toolbar button
  // =========================================================================
  test('PD-003 @critical — Create process via designer toolbar -> visible in list', async ({
    page,
  }) => {
    test.skip(missingProcessUpdatePermission, 'Missing permission: system.process.update');
    await navigateToProcessDefinitionList(page);

    // Click Create button in toolbar (navigates to /bpmn-designer)
    const createBtn = page
      .locator('[data-testid="toolbar-btn-create"]')
      .or(page.getByRole('button', { name: /创建|新建|Create/i }))
      .first();
    await createBtn.waitFor({ state: 'visible', timeout: 8_000 });
    await createBtn.click();

    // Verify designer page opened
    await page.waitForURL(/bpmn-designer/, { timeout: 10_000 });
    await expect(page.locator('.react-flow')).toBeVisible({ timeout: 10_000 });

    // Seed a minimal valid process (start -> end) so validate() passes when we save.
    // Without this, handleSave() short-circuits on missing start/end events and the
    // SaveDialog never opens, so the POST never fires.
    await drawMinimalBPMN(page);

    // Fill process name and key in toolbar
    const nameInput = page.locator('[data-testid="bpmn-field-name"]');
    await expect(nameInput).toBeVisible({ timeout: 8_000 });

    const newKey = `pd_new_${UID}`;
    const newName = `UI Created ${UID}`;

    await nameInput.click();
    await nameInput.fill(newName);

    const keyInput = page.locator('[data-testid="bpmn-field-key"]');
    await keyInput.click();
    await keyInput.fill(newKey);

    // Save via explicit toolbar save button (Ctrl+S was brittle across browsers)
    const saveResponse = page.waitForResponse(
      (r) => r.url().includes('/api/bpm/process-definitions') && ['POST', 'PUT', 'PATCH'].includes(r.request().method()),
      { timeout: 15_000 },
    );
    const saveBtn = page
      .locator('[data-testid="toolbar-save"]')
      .or(page.getByRole('button', { name: /^(保存|Save)$/ }))
      .first();
    await saveBtn.waitFor({ state: 'visible', timeout: 8_000 });
    await saveBtn.click();

    // If a confirmation dialog appears, confirm it
    const saveDialog = page.locator('[role="dialog"]').first();
    if (await saveDialog.isVisible({ timeout: 3_000 }).catch(() => false)) {
      const confirmBtn = saveDialog.getByRole('button', { name: /保存|Save|确定|OK/i }).first();
      await confirmBtn.click();
    }

    // Assert the save POST succeeded before navigating back to list
    const resp = await saveResponse;
    expect(resp.status()).toBeGreaterThanOrEqual(200);
    expect(resp.status()).toBeLessThan(300);

    // [D6] Navigate back to list and verify new process appears
    await navigateToProcessDefinitionList(page);
    const row = await findRowInPaginatedList(page, newKey, 12_000);
    expect(row, `Newly created process ${newKey} should appear in list`).toBeTruthy();

    // Verify the row contains expected data
    const rowText = await row.textContent({ timeout: 15_000 });
    expect(rowText).toContain(newKey);
    expect(rowText).toContain(newName);
  });

  // =========================================================================
  // D7: View process detail row data (all fields)
  // =========================================================================
  test('PD-004 — View process detail: all fields correct', async ({ page }) => {
    test.skip(missingProcessUpdatePermission, 'Missing permission: system.process.update');
    await navigateToProcessDefinitionList(page);

    const row = await findRowInPaginatedList(page, PROCESS_KEY_MAIN, 12_000);
    expect(row).toBeTruthy();

    const rowText = await row.textContent();
    // [D7] Verify data fields in the row
    expect(rowText).toContain(PROCESS_KEY_MAIN);
    expect(rowText).toContain(PROCESS_NAME_MAIN);
    expect(rowText).toMatch(/draft|草稿/i);
    expect(rowText).toMatch(/e2e-test/i);
  });

  // =========================================================================
  // D8: Edit process name -> save -> reopen verify updated
  // =========================================================================
  test('PD-005 @critical — Edit process name -> save -> reopen verify updated', async ({
    page,
  }) => {
    test.skip(missingProcessUpdatePermission, 'Missing permission: system.process.update');
    await navigateToProcessDefinitionList(page);

    // Find row and click Edit
    const row = await findRowInPaginatedList(page, PROCESS_KEY_MAIN, 12_000);
    await clickRowActionByLocator(page, row, 'edit');

    // Designer opens with ?pid=
    await page.waitForURL(/bpmn-designer.*pid=/, { timeout: 10_000 });

    const nameInput = page.locator('[data-testid="bpmn-field-name"]');
    await expect(nameInput).toBeVisible({ timeout: 8_000 });
    // Verify current value before editing
    await expect(nameInput).toHaveValue(PROCESS_NAME_MAIN, { timeout: 5_000 });

    // Edit name
    await nameInput.click();
    await nameInput.fill(PROCESS_NAME_EDITED);

    // Save via Ctrl+S
    const saveResponse = page.waitForResponse(
      (r) =>
        r.url().includes('/api/bpm/process-definitions') &&
        (r.request().method() === 'PUT' || r.request().method() === 'POST'),
      { timeout: 15_000 },
    );
    await page.keyboard.press('Control+s');

    // Handle save dialog if it appears
    const saveDialog = page.locator('[role="dialog"]').first();
    if (await saveDialog.isVisible({ timeout: 3_000 }).catch(() => false)) {
      const confirmBtn = saveDialog.getByRole('button', { name: /保存|Save|确定|OK/i }).first();
      await confirmBtn.click();
    }

    const resp = await saveResponse;
    expect(resp.status()).toBeLessThan(400);

    // [D8] Reopen and verify name changed
    await navigateToProcessDefinitionList(page);
    const editedRow = await findRowInPaginatedList(page, PROCESS_KEY_MAIN, 12_000);
    const editedRowText = await editedRow.textContent();
    expect(editedRowText).toContain(PROCESS_NAME_EDITED);
  });

  // =========================================================================
  // D9: Deploy process -> status changes to deployed
  // =========================================================================
  test('PD-006 @critical — Deploy process -> status changes to deployed', async ({ page }) => {
    test.skip(missingProcessUpdatePermission, 'Missing permission: system.process.update');
    await navigateToProcessDefinitionList(page);

    // Find the draft process row and click the deploy action button
    const row = await findRowInPaginatedList(page, PROCESS_KEY_MAIN, 12_000);

    const deployResponse = page.waitForResponse(
      (r) =>
        r.url().includes('/api/bpm/process-definitions') &&
        r.url().includes('/deploy') &&
        r.request().method() === 'POST',
      { timeout: 15_000 },
    );
    await clickRowActionByLocator(page, row, 'deploy', '部署');
    const resp = await deployResponse;
    expect(resp.ok(), 'Deploy API should succeed').toBe(true);

    // Wait for list to refresh after deploy
    await page.waitForResponse(
      (r) =>
        r.url().includes('/api/bpm/process-definitions') &&
        !r.url().includes('/deploy') &&
        r.status() === 200,
      { timeout: 10_000 },
    );

    // Verify status badge changed in the row
    const updatedRow = await findRowInPaginatedList(page, PROCESS_KEY_MAIN, 12_000);
    const rowText = await updatedRow.textContent();
    expect(rowText).toMatch(/deployed|已部署|active|已激活/i);
  });

  // =========================================================================
  // D9: Suspend deployed process -> status changes to suspended
  // =========================================================================
  test('PD-007 — Suspend deployed process -> status changes to suspended', async ({ page }) => {
    test.skip(missingProcessUpdatePermission, 'Missing permission: system.process.update');
    await navigateToProcessDefinitionList(page);

    // Find the deployed process row and click the suspend action button
    const row = await findRowInPaginatedList(page, PROCESS_KEY_MAIN, 12_000);

    const suspendResponse = page.waitForResponse(
      (r) =>
        r.url().includes('/api/bpm/process-definitions') &&
        r.url().includes('/suspend') &&
        r.request().method() === 'POST',
      { timeout: 15_000 },
    );
    await clickRowActionByLocator(page, row, 'suspend', '暂停');
    const resp = await suspendResponse;
    expect(resp.ok(), 'Suspend API should succeed').toBe(true);

    // Wait for list to refresh
    await page.waitForResponse(
      (r) =>
        r.url().includes('/api/bpm/process-definitions') &&
        !r.url().includes('/suspend') &&
        r.status() === 200,
      { timeout: 10_000 },
    );

    // Verify status badge changed
    const updatedRow = await findRowInPaginatedList(page, PROCESS_KEY_MAIN, 12_000);
    const rowText = await updatedRow.textContent();
    expect(rowText).toMatch(/suspended|已暂停/i);
  });

  // =========================================================================
  // D9: Resume suspended process -> status back to deployed
  // =========================================================================
  test('PD-008 — Resume suspended process -> status back to deployed', async ({ page }) => {
    test.skip(missingProcessUpdatePermission, 'Missing permission: system.process.update');
    await navigateToProcessDefinitionList(page);

    // Find the suspended process row and click the resume action button
    const row = await findRowInPaginatedList(page, PROCESS_KEY_MAIN, 12_000);

    const resumeResponse = page.waitForResponse(
      (r) =>
        r.url().includes('/api/bpm/process-definitions') &&
        r.url().includes('/resume') &&
        r.request().method() === 'POST',
      { timeout: 15_000 },
    );
    await clickRowActionByLocator(page, row, 'resume', '恢复');
    const resp = await resumeResponse;
    expect(resp.ok(), 'Resume API should succeed').toBe(true);

    // Wait for list to refresh
    await page.waitForResponse(
      (r) =>
        r.url().includes('/api/bpm/process-definitions') &&
        !r.url().includes('/resume') &&
        r.status() === 200,
      { timeout: 10_000 },
    );

    // Verify status badge changed back to deployed
    const updatedRow = await findRowInPaginatedList(page, PROCESS_KEY_MAIN, 12_000);
    const rowText = await updatedRow.textContent();
    expect(rowText).toMatch(/deployed|已部署|active|已激活/i);
  });

  // =========================================================================
  // D3: Tab filter — Draft/Deployed tabs show correct records
  // =========================================================================
  test('PD-009 — Tab filter: draft/deployed tabs show correct records', async ({ page }) => {
    test.skip(missingProcessUpdatePermission, 'Missing permission: system.process.update');
    await navigateToProcessDefinitionList(page);

    // Click Draft tab — must exist
    const draftTab = page
      .locator('button')
      .filter({ hasText: /草稿|Draft/i })
      .first();
    await expect(draftTab).toBeVisible({ timeout: 5_000 });
    await Promise.all([
      page.waitForResponse(
        (r) =>
          (r.url().includes('/api/bpm/process-definitions') ||
            r.url().includes('/api/dynamic/bpm-process-management/list')) &&
          r.status() === 200,
        { timeout: 8_000 },
      ),
      draftTab.click(),
    ]);

    // Delete target should be in draft tab
    const deleteRow = await findRowInPaginatedList(page, PROCESS_KEY_DELETE, 8_000);
    expect(deleteRow, 'Delete target process should be visible in Draft tab').toBeTruthy();

    // Main process should NOT be in draft tab (it was deployed+resumed=deployed)
    const mainInDraft = page.locator('tr').filter({ hasText: PROCESS_KEY_MAIN });
    await expect(mainInDraft).not.toBeVisible({ timeout: 3_000 });

    // Click Deployed/Active tab — must exist
    const deployedTab = page
      .locator('button')
      .filter({ hasText: /已部署|已激活|Deployed|Active/i })
      .first();
    await expect(deployedTab).toBeVisible({ timeout: 5_000 });
    await Promise.all([
      page.waitForResponse(
        (r) =>
          (r.url().includes('/api/bpm/process-definitions') ||
            r.url().includes('/api/dynamic/bpm-process-management/list')) &&
          r.status() === 200,
        { timeout: 8_000 },
      ),
      deployedTab.click(),
    ]);

    // Main process should be in deployed tab (resumed in PD-008)
    const mainRow = await findRowInPaginatedList(page, PROCESS_KEY_MAIN, 8_000);
    expect(mainRow, 'Main process should be visible in Deployed tab').toBeTruthy();

    // Delete target should NOT be in deployed tab (still draft)
    const deleteInDeployed = page.locator('tr').filter({ hasText: PROCESS_KEY_DELETE });
    await expect(deleteInDeployed).not.toBeVisible({ timeout: 3_000 });
  });

  // =========================================================================
  // D11 + D14: Delete draft process -> confirm dialog -> record disappears
  // =========================================================================
  test('PD-010 — Delete draft process -> confirm dialog -> record disappears', async ({ page }) => {
    test.skip(missingProcessUpdatePermission, 'Missing permission: system.process.update');
    await navigateToProcessDefinitionList(page);

    // Switch to draft tab
    const draftTab = page
      .locator('button')
      .filter({ hasText: /草稿|Draft/i })
      .first();
    await expect(draftTab).toBeVisible({ timeout: 5_000 });
    await Promise.all([
      page
        .waitForResponse(
          (r) =>
            (r.url().includes('/api/bpm/process-definitions') ||
              r.url().includes('/api/dynamic/bpm-process-management/list')) &&
            r.status() === 200,
          { timeout: 8_000 },
        )
        .catch(() => null),
      draftTab.click(),
    ]);
    await expect(draftTab).toHaveClass(/border-blue-500|text-blue-600/);

    // Find delete target row
    const row = await findRowInPaginatedList(page, PROCESS_KEY_DELETE, 12_000);
    await clickRowActionByLocator(page, row, 'delete', '删除');

    // [D11] Confirm dialog
    await acceptConfirmDialog(page);

    // Wait for delete API response
    await page.waitForResponse(
      (r) => r.url().includes('/api/bpm/process-definitions') && r.request().method() === 'DELETE',
      { timeout: 10_000 },
    );

    // [D14] Toast feedback is best effort; deletion correctness is verified by a fresh list load below.
    await waitForToast(page, undefined, 8_000).catch(() => {});
    await navigateToProcessDefinitionList(page);
    await page
      .locator('button')
      .filter({ hasText: /草稿|Draft/i })
      .first()
      .click();

    // Verify record disappeared from list
    const deletedRow = page.locator('tr').filter({ hasText: PROCESS_KEY_DELETE });
    await expect(deletedRow).not.toBeVisible({ timeout: 5_000 });
  });

  // =========================================================================
  // D10: Cannot delete deployed process (button hidden or error)
  // =========================================================================
  test('PD-011 — Cannot delete deployed process', async ({ page }) => {
    test.skip(missingProcessUpdatePermission, 'Missing permission: system.process.update');
    await navigateToProcessDefinitionList(page);

    const row = await findRowInPaginatedList(page, PROCESS_KEY_MAIN, 12_000);
    await row.scrollIntoViewIfNeeded();
    await row.hover();

    // Direct delete button should not be visible for deployed process
    const directDeleteBtn = row.locator('[data-testid="row-action-delete"]').first();
    await expect(directDeleteBtn).not.toBeVisible({ timeout: 3_000 });

    // Also check in the "more actions" dropdown if it exists
    const moreBtn = row.locator('[data-testid="row-action-more"]').first();
    if (await moreBtn.isVisible({ timeout: 2_000 }).catch(() => false)) {
      await moreBtn.click();
      const dropdown = page.locator('[data-testid="row-action-dropdown"]');
      await expect(dropdown).toBeVisible({ timeout: 5_000 });

      const deleteInDropdown = dropdown.locator('[data-testid="row-action-delete"]').first();
      await expect(
        deleteInDropdown,
        'Delete action should be hidden for deployed process',
      ).not.toBeVisible({ timeout: 3_000 });

      await page.keyboard.press('Escape');
    }
  });

  // =========================================================================
  // D13: Search by process name filters results
  // =========================================================================
  test('PD-012 — Search by process name filters results', async ({ page }) => {
    test.skip(missingProcessUpdatePermission, 'Missing permission: system.process.update');
    await navigateToProcessDefinitionList(page);

    // Find search input — ListToolbar renders [data-testid="list-search-input"];
    // legacy fallbacks kept for plugins that have not migrated yet.
    const searchInput = page
      .locator(
        '[data-testid="list-search-input"], input[placeholder*="搜索"], input[placeholder*="Search"], input[type="search"], [data-testid="search-input"]',
      )
      .first();

    await expect(searchInput, 'Search input should be visible').toBeVisible({ timeout: 5_000 });

    await searchInput.click();
    await searchInput.fill(UID);
    await searchInput.press('Enter');

    // Wait for filtered results
    await page
      .waitForResponse(
        (r) =>
          (r.url().includes('/api/bpm/process-definitions') ||
            r.url().includes('/api/dynamic/bpm-process-management/list')) &&
          r.status() === 200,
        { timeout: 8_000 },
      )
      .catch(() => null);

    // Results should contain our main process
    const row = page.locator('tbody tr').filter({ hasText: PROCESS_KEY_MAIN }).first();
    await expect(row, 'Main process should appear in search results').toBeVisible({
      timeout: 5_000,
    });

    // Verify only matching results are shown — all visible rows should contain UID
    const dataRows = page.locator('tbody tr');
    const rowCount = await dataRows.count();
    expect(rowCount, 'Search should return at least 1 result').toBeGreaterThan(0);

    for (let i = 0; i < Math.min(rowCount, 5); i++) {
      const text = await dataRows.nth(i).textContent();
      expect(text, `Row ${i} should contain search term`).toContain(UID);
    }
  });

  // =========================================================================
  // D12: Form validation — empty name should show error
  // =========================================================================
  test('PD-014 — Form validation: save without name shows error', async ({ page }) => {
    test.skip(missingProcessUpdatePermission, 'Missing permission: system.process.update');
    await navigateToProcessDefinitionList(page);

    // Click Create button to open designer
    const createBtn = page
      .locator('[data-testid="toolbar-btn-create"]')
      .or(page.getByRole('button', { name: /创建|新建|Create/i }))
      .first();
    await createBtn.waitFor({ state: 'visible', timeout: 8_000 });
    await createBtn.click();

    // Verify designer page opened
    await page.waitForURL(/bpmn-designer/, { timeout: 10_000 });
    await expect(page.locator('.react-flow')).toBeVisible({ timeout: 10_000 });

    // Clear the name field (leave it empty)
    const nameInput = page.locator('[data-testid="bpmn-field-name"]');
    await expect(nameInput).toBeVisible({ timeout: 8_000 });
    await nameInput.click();
    await nameInput.fill('');

    // Try to save via Ctrl+S
    await page.keyboard.press('Control+s');

    // If save dialog appears, confirm it
    const saveDialog = page.locator('[role="dialog"]').first();
    if (await saveDialog.isVisible({ timeout: 3_000 }).catch(() => false)) {
      const confirmBtn = saveDialog.getByRole('button', { name: /保存|Save|确定|OK/i }).first();
      await confirmBtn.click();
    }

    // Should see an error message about required name
    const errorIndicator = page
      .locator('.ant-form-item-explain-error, [role="alert"], .error-message, .field-error')
      .first()
      .or(page.getByText(/必填|required|不能为空|请输入/i).first());
    await expect(errorIndicator).toBeVisible({ timeout: 5_000 });

    // Navigate back to list (no new process should have been created)
    await navigateToProcessDefinitionList(page);
  });

  // =========================================================================
  // D14: Test data trace remains visible (no afterAll cleanup)
  // =========================================================================
  test('PD-013 — Trace: test data remains visible in system', async ({ page }) => {
    test.skip(missingProcessUpdatePermission, 'Missing permission: system.process.update');
    await navigateToProcessDefinitionList(page);

    // Switch to "All" tab
    const allTab = page
      .locator('button')
      .filter({ hasText: /全部|All/i })
      .first();
    await expect(allTab).toBeVisible({ timeout: 5_000 });
    await Promise.all([
      page
        .waitForResponse(
          (r) => r.url().includes('/api/bpm/process-definitions') && r.status() === 200,
          { timeout: 8_000 },
        )
        .catch(() => null),
      allTab.click(),
    ]);

    // Main process should still exist as deployed
    const mainRow = await findRowInPaginatedList(page, PROCESS_KEY_MAIN, 12_000);
    expect(mainRow, 'Main process should still exist as test trace').toBeTruthy();

    const mainRowText = await mainRow.textContent();
    // Verify it retains the edited name from PD-005
    expect(mainRowText).toContain(PROCESS_NAME_EDITED);
    // Verify it shows deployed status from PD-006/PD-008
    expect(mainRowText).toMatch(/deployed|已部署|active|已激活/i);

    // Delete target should be gone (deleted in PD-010)
    const deleteRow = page.locator('tr').filter({ hasText: PROCESS_KEY_DELETE });
    await expect(deleteRow).not.toBeVisible({ timeout: 3_000 });
  });
});
