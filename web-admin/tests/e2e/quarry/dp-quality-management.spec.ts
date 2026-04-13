/**
 * DP Quality Management — E2E Tests
 *
 * Tests the full lifecycle for the dual-prevention quality management models:
 *   1. Quality Standard — CRUD (simpler model, tested first)
 *   2. Quality Checkpoint — CRUD + state transitions (pending -> PASSED/failed/CONDITIONAL)
 *   3. Cross-model — Checkpoint references project correctly
 *
 * Prerequisites:
 *   - dual-prevention plugin must be imported and models published
 *   - pm-project plugin available (for REFERENCE field testing)
 *
 * @since 11.0.0
 */
import { test, expect } from '@playwright/test';
import {
  navigateToDynamicPage,
  uniqueId,
  executeCommandViaApi,
  waitForDynamicPageLoad,
  waitForFormReady,
  acceptConfirmDialog,
  todayStr,
  clickRowActionByLocator,
} from '../helpers/index';
import { getTestProjectId } from '../quarry-management.setup';
import { ErrorCodes } from '~/shared/services/http-client/types';

// ---------------------------------------------------------------------------
// Page keys (hyphenated for URL / API compatibility)
// ---------------------------------------------------------------------------
const PAGE = {
  STANDARD: 'dp-quality-standard',
  CHECKPOINT: 'dp-quality-checkpoint',
} as const;

// ---------------------------------------------------------------------------
// 1. Quality Standard — CRUD
// ---------------------------------------------------------------------------
test.describe('DP Quality Standard -- CRUD', () => {
  test.describe.configure({ mode: 'serial', timeout: 60000 });

  const stdName = `E2E Standard ${uniqueId()}`;
  const stdCode = `QS-${uniqueId()}`;
  const updatedName = `${stdName} Updated`;

  test('QS-001: Create quality standard via UI', async ({ page }) => {
    await navigateToDynamicPage(page, PAGE.STANDARD);
    await expect(page.locator('table, [role="table"]').first()).toBeVisible({ timeout: 10000 });

    // Click create button
    const addBtn = page
      .locator(
        '[data-testid="toolbar-btn-create"], button:has-text("New"), button:has-text("Create"), button:has-text("新建")',
      )
      .first();
    await addBtn.click();

    // Wait for form to render
    await waitForFormReady(page);

    // Fill name
    const nameInput = page
      .locator('[data-testid="form-field-dp_qs_name"] input, input[name="dp_qs_name"]')
      .first();
    await expect(nameInput).toBeVisible({ timeout: 10000 });
    await nameInput.fill(stdName);

    // Fill code
    const codeInput = page
      .locator('[data-testid="form-field-dp_qs_code"] input, input[name="dp_qs_code"]')
      .first();
    if (await codeInput.isVisible({ timeout: 3000 }).catch(() => false)) {
      await codeInput.fill(stdCode);
    }

    // Select category (ENUM)
    const categoryField = page
      .locator('[data-testid="form-field-dp_qs_category"] select, select[name="dp_qs_category"]')
      .first();
    if (await categoryField.isVisible({ timeout: 3000 }).catch(() => false)) {
      const options = await categoryField.locator('option').allTextContents();
      if (options.length > 1) {
        await categoryField.selectOption({ index: 1 }); // FOUNDATION
      }
    }

    // Fill content
    const contentField = page
      .locator('[data-testid="form-field-dp_qs_content"] textarea, textarea[name="dp_qs_content"]')
      .first();
    if (await contentField.isVisible({ timeout: 3000 }).catch(() => false)) {
      await contentField.fill('E2E quality standard content for testing');
    }

    // Fill version
    const versionInput = page
      .locator('[data-testid="form-field-dp_qs_version"] input, input[name="dp_qs_version"]')
      .first();
    if (await versionInput.isVisible({ timeout: 3000 }).catch(() => false)) {
      await versionInput.fill('v1.0');
    }

    // Submit form
    const submitBtn = page
      .locator(
        '[data-testid="form-btn-submit"], [data-testid="form-btn-save"], button:has-text("Submit"), button:has-text("Save"), button:has-text("保存")',
      )
      .first();
    await expect(submitBtn).toBeVisible({ timeout: 5000 });

    const saveResponse = page.waitForResponse(
      (r) =>
        r.url().includes('/api/meta/commands/execute/') &&
        r.request().method().toLowerCase() === 'post' &&
        r.status() === 200,
      { timeout: 15000 },
    );
    await submitBtn.click();
    await saveResponse;

    await navigateToDynamicPage(page, PAGE.STANDARD);
    const standardRow = page.locator('tbody tr', { hasText: stdName }).first();
    await expect(standardRow).toBeVisible({ timeout: 10000 });
  });

  test('QS-002: Verify standard appears in list', async ({ page }) => {
    await navigateToDynamicPage(page, PAGE.STANDARD);
    await expect(page.locator('table, [role="table"]').first()).toBeVisible({ timeout: 10000 });
    const standardRow = page.locator('tbody tr', { hasText: stdName }).first();
    await expect(standardRow).toBeVisible({ timeout: 10000 });
  });

  test('QS-003: Update standard via UI', async ({ page }) => {
    await navigateToDynamicPage(page, PAGE.STANDARD);
    let standardRow = page.locator('tbody tr', { hasText: stdName }).first();
    const hasStandardRow = await standardRow.isVisible({ timeout: 5000 }).catch(() => false);
    if (!hasStandardRow) {
      const createResult = await executeCommandViaApi(page, 'dp:create_standard', {
        dp_qs_name: stdName,
        dp_qs_code: stdCode,
        dp_qs_description: 'E2E quality standard content for testing',
        dp_qs_version: 'v1.0',
      });
      expect(createResult.code).toBe(ErrorCodes.SUCCESS);
      await navigateToDynamicPage(page, PAGE.STANDARD);
      standardRow = page.locator('tbody tr', { hasText: stdName }).first();
    }
    await expect(standardRow).toBeVisible({ timeout: 10000 });

    await clickRowActionByLocator(page, standardRow, 'edit');

    await waitForFormReady(page);
    await page.waitForFunction(
      () => {
        const inputs = document.querySelectorAll('form input[type="text"], form input:not([type])');
        return Array.from(inputs).some((el) => (el as HTMLInputElement).value.length > 0);
      },
      { timeout: 10000 },
    );

    // Update the name field
    const nameInput = page
      .locator('[data-testid="form-field-dp_qs_name"] input, input[name="dp_qs_name"]')
      .first();
    await expect(nameInput).toBeVisible({ timeout: 5000 });
    await nameInput.click();
    await nameInput.evaluate((input: HTMLInputElement) => {
      input.value = '';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new Event('change', { bubbles: true }));
    });
    await nameInput.fill(updatedName);
    await expect(nameInput).toHaveValue(updatedName, { timeout: 3000 });

    // Submit form
    const submitBtn = page
      .locator(
        '[data-testid="form-btn-submit"], [data-testid="form-btn-save"], button:has-text("Submit"), button:has-text("Save"), button:has-text("保存")',
      )
      .first();
    await expect(submitBtn).toBeVisible({ timeout: 5000 });

    const saveResponse = page
      .waitForResponse(
        (r) =>
          r.url().includes('/api/meta/commands/execute/') &&
          r.request().method().toLowerCase() === 'post' &&
          r.status() === 200,
        { timeout: 15000 },
      )
      .catch(() => null);
    await submitBtn.click();
    const resp = await saveResponse;
    if (resp) {
      const body = await resp.json().catch(() => ({}));
      expect(String(body.code ?? '0')).toBe(ErrorCodes.SUCCESS);
    }

    await navigateToDynamicPage(page, PAGE.STANDARD);
    const updatedRow = page.locator('tbody tr', { hasText: updatedName }).first();
    await expect(updatedRow).toBeVisible({ timeout: 10000 });
  });

  test('QS-004: Delete standard via API cleanup', async ({ page }) => {
    await navigateToDynamicPage(page, PAGE.STANDARD);
    const standardRow = page.locator('tbody tr', { hasText: updatedName }).first();
    await expect(standardRow).toBeVisible({ timeout: 10000 });

    await clickRowActionByLocator(page, standardRow, 'delete');
    await acceptConfirmDialog(page);

    await navigateToDynamicPage(page, PAGE.STANDARD);
    await expect(page.locator('tbody tr', { hasText: updatedName }).first()).not.toBeVisible({
      timeout: 10000,
    });
  });

  test.afterAll(async ({ browser }) => {
    const ctx = await browser.newContext({
      storageState: 'tests/storage/admin.json',
      baseURL: 'http://localhost:5173',
    });
    const p = await ctx.newPage();
    await ctx.close();
  });
});

// ---------------------------------------------------------------------------
// 2. Quality Checkpoint — CRUD + State Transitions
// ---------------------------------------------------------------------------
test.describe('DP Quality Checkpoint -- CRUD & Lifecycle', () => {
  test.describe.configure({ mode: 'serial', timeout: 60000 });

  let projectId: string;
  const today = todayStr();
  const cpName = `E2E Checkpoint ${uniqueId()}`;
  const updatedName = `${cpName} Updated`;

  async function setHiddenField(
    page: import('@playwright/test').Page,
    name: string,
    value: string,
  ): Promise<void> {
    await page.evaluate(
      ({ fieldName, fieldValue }) => {
        const input = document.querySelector(
          `input[name="${fieldName}"]`,
        ) as HTMLInputElement | null;
        if (!input) return;
        input.value = fieldValue;
        input.dispatchEvent(new Event('input', { bubbles: true }));
        input.dispatchEvent(new Event('change', { bubbles: true }));
      },
      { fieldName: name, fieldValue: value },
    );
  }

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({
      storageState: 'tests/storage/admin.json',
      baseURL: 'http://localhost:5173',
    });
    const p = await ctx.newPage();
    try {
      projectId = await getTestProjectId(p);
    } catch {
      // If pm plugin unavailable, skip project-dependent tests
    }
    await p.close();
    await ctx.close();
  });

  test('QC-001: Create checkpoint via UI (with project reference)', async ({ page }) => {
    await navigateToDynamicPage(page, PAGE.CHECKPOINT);
    await expect(page.locator('table, [role="table"]').first()).toBeVisible({ timeout: 10000 });

    // Click create button
    const addBtn = page
      .locator(
        '[data-testid="toolbar-btn-create"], button:has-text("New"), button:has-text("Create"), button:has-text("新建")',
      )
      .first();
    await addBtn.click();

    // Wait for form to render
    await waitForFormReady(page);

    // Fill name
    const nameInput = page
      .locator('[data-testid="form-field-dp_qc_name"] input, input[name="dp_qc_name"]')
      .first();
    await expect(nameInput).toBeVisible({ timeout: 10000 });
    await nameInput.fill(cpName);

    if (projectId) {
      await setHiddenField(page, 'dp_qc_project_id', projectId);
    }

    // Select category (ENUM)
    const categoryField = page
      .locator('[data-testid="form-field-dp_qc_category"] select, select[name="dp_qc_category"]')
      .first();
    if (await categoryField.isVisible({ timeout: 3000 }).catch(() => false)) {
      const options = await categoryField.locator('option').allTextContents();
      if (options.length > 1) {
        await categoryField.selectOption({ index: 1 }); // FOUNDATION
      }
    }

    // Fill inspector
    const inspectorInput = page
      .locator('[data-testid="form-field-dp_qc_inspector"] input, input[name="dp_qc_inspector"]')
      .first();
    if (await inspectorInput.isVisible({ timeout: 3000 }).catch(() => false)) {
      await inspectorInput.fill('Zhang Inspector');
    }

    // Fill inspect date
    const dateInput = page
      .locator(
        '[data-testid="form-field-dp_qc_inspection_date"] input, input[name="dp_qc_inspection_date"]',
      )
      .first();
    if (await dateInput.isVisible({ timeout: 3000 }).catch(() => false)) {
      await dateInput.fill(today);
    }

    // Fill reference standard
    const standardInput = page
      .locator('[data-testid="form-field-dp_qc_standard"] input, input[name="dp_qc_standard"]')
      .first();
    if (await standardInput.isVisible({ timeout: 3000 }).catch(() => false)) {
      await standardInput.fill('GB/T Quality Standard');
    }

    // Fill remark
    const descField = page
      .locator('[data-testid="form-field-dp_qc_remark"] textarea, textarea[name="dp_qc_remark"]')
      .first();
    if (await descField.isVisible({ timeout: 3000 }).catch(() => false)) {
      await descField.fill('E2E checkpoint description for quality inspection');
    }

    // Submit form
    const submitBtn = page
      .locator(
        '[data-testid="form-btn-submit"], [data-testid="form-btn-save"], button:has-text("Submit"), button:has-text("Save"), button:has-text("保存")',
      )
      .first();
    await expect(submitBtn).toBeVisible({ timeout: 5000 });

    await submitBtn.click();

    await navigateToDynamicPage(page, PAGE.CHECKPOINT);
    const checkpointRow = page.locator('tbody tr', { hasText: cpName }).first();
    await expect(checkpointRow).toBeVisible({ timeout: 10000 });
    await expect(checkpointRow).toContainText('pending');
  });

  test('QC-002: Verify checkpoint exists and list page loads', async ({ page }) => {
    // Verify list page loads
    await navigateToDynamicPage(page, PAGE.CHECKPOINT);
    await expect(page.locator('table, [role="table"]').first()).toBeVisible({ timeout: 10000 });
    const checkpointRow = page.locator('tbody tr', { hasText: cpName }).first();
    await expect(checkpointRow).toBeVisible({ timeout: 10000 });
  });

  test('QC-003: Update checkpoint via UI', async ({ page }) => {
    await navigateToDynamicPage(page, PAGE.CHECKPOINT);
    const checkpointRow = page.locator('tbody tr', { hasText: cpName }).first();
    await expect(checkpointRow).toBeVisible({ timeout: 10000 });

    await clickRowActionByLocator(page, checkpointRow, 'edit');

    await waitForFormReady(page);
    await page.waitForFunction(
      () => {
        const inputs = document.querySelectorAll('form input[type="text"], form input:not([type])');
        return Array.from(inputs).some((el) => (el as HTMLInputElement).value.length > 0);
      },
      { timeout: 10000 },
    );

    // Update name
    const nameInput = page
      .locator('[data-testid="form-field-dp_qc_name"] input, input[name="dp_qc_name"]')
      .first();
    await expect(nameInput).toBeVisible({ timeout: 5000 });
    await nameInput.clear();
    await nameInput.fill(updatedName);

    // Submit
    const submitBtn = page
      .locator(
        '[data-testid="form-btn-submit"], [data-testid="form-btn-save"], button:has-text("Submit"), button:has-text("Save"), button:has-text("保存")',
      )
      .first();
    await expect(submitBtn).toBeVisible({ timeout: 5000 });

    await submitBtn.click();

    await navigateToDynamicPage(page, PAGE.CHECKPOINT);
    const updatedRow = page.locator('tbody tr', { hasText: updatedName }).first();
    await expect(updatedRow).toBeVisible({ timeout: 10000 });
  });

  test('QC-004: Pass checkpoint via API (pending -> PASSED)', async ({ page }) => {
    await navigateToDynamicPage(page, PAGE.CHECKPOINT);
    const row = page.locator('tbody tr', { hasText: updatedName }).first();
    await expect(row).toBeVisible({ timeout: 10000 });
    await clickRowActionByLocator(page, row, 'pass');
    await acceptConfirmDialog(page);
  });

  test('QC-005: Verify passed checkpoint status', async ({ page }) => {
    await navigateToDynamicPage(page, PAGE.CHECKPOINT);
    const row = page.locator('tbody tr', { hasText: updatedName }).first();
    await expect(row).toBeVisible({ timeout: 10000 });
    await expect(row).toContainText('passed');
  });

  test('QC-006: Create another checkpoint and fail it (pending -> failed)', async ({ page }) => {
    const failName = `E2E Fail Checkpoint ${uniqueId()}`;

    const result = await executeCommandViaApi(page, 'dp:create_checkpoint', {
      dp_qc_name: failName,
      dp_qc_location: 'Building B - Foundation',
      dp_qc_category: 'structure',
      dp_qc_inspector: 'Li Inspector',
      dp_qc_inspect_date: today,
      dp_qc_description: 'Checkpoint to test failed path',
      ...(projectId ? { dp_qc_project_id: projectId } : {}),
    });
    expect(result.code).toBe(ErrorCodes.SUCCESS);

    await navigateToDynamicPage(page, PAGE.CHECKPOINT);
    const row = page.locator('tbody tr', { hasText: failName }).first();
    await expect(row).toBeVisible({ timeout: 10000 });
    await clickRowActionByLocator(page, row, 'fail');
    await acceptConfirmDialog(page);
  });

  test('QC-007: Verify failed checkpoint status', async ({ page }) => {
    const failName = `E2E Fail Checkpoint`;
    await navigateToDynamicPage(page, PAGE.CHECKPOINT);
    await expect(
      page.locator('tbody tr', { hasText: 'E2E Fail Checkpoint' }).first(),
    ).toContainText('failed');
  });

  test('QC-008: Create checkpoint and conditional pass (pending -> CONDITIONAL)', async ({
    page,
  }) => {
    const condName = `E2E Conditional Checkpoint ${uniqueId()}`;

    const result = await executeCommandViaApi(page, 'dp:create_checkpoint', {
      dp_qc_name: condName,
      dp_qc_location: 'Building C - MEP Area',
      dp_qc_category: 'mep',
      dp_qc_inspector: 'Wang Inspector',
      dp_qc_inspect_date: today,
      dp_qc_description: 'Checkpoint to test CONDITIONAL path',
      ...(projectId ? { dp_qc_project_id: projectId } : {}),
    });
    expect(result.code).toBe(ErrorCodes.SUCCESS);

    await navigateToDynamicPage(page, PAGE.CHECKPOINT);
    const row = page.locator('tbody tr', { hasText: condName }).first();
    await expect(row).toBeVisible({ timeout: 10000 });
    await clickRowActionByLocator(page, row, 'conditional');
    await acceptConfirmDialog(page);
  });

  test('QC-009: Verify conditional pass status', async ({ page }) => {
    await navigateToDynamicPage(page, PAGE.CHECKPOINT);
    await expect(
      page.locator('tbody tr', { hasText: 'E2E Conditional Checkpoint' }).first(),
    ).toContainText('conditional');
  });

  test('QC-010: Delete checkpoint via API cleanup', async ({ page }) => {
    await navigateToDynamicPage(page, PAGE.CHECKPOINT);
    for (const name of [updatedName, 'E2E Fail Checkpoint', 'E2E Conditional Checkpoint']) {
      const row = page.locator('tbody tr', { hasText: name }).first();
      if (await row.isVisible({ timeout: 2000 }).catch(() => false)) {
        const deleteBtn = row.locator('[data-testid="row-action-delete"]').first();
        if (await deleteBtn.isVisible({ timeout: 2000 }).catch(() => false)) {
          await clickRowActionByLocator(page, row, 'delete');
          await acceptConfirmDialog(page);
        }
      }
    }
  });

  test.afterAll(async ({ browser }) => {
    const ctx = await browser.newContext({
      storageState: 'tests/storage/admin.json',
      baseURL: 'http://localhost:5173',
    });
    const p = await ctx.newPage();
    await navigateToDynamicPage(p, PAGE.CHECKPOINT).catch(() => {});
    await ctx.close();
  });
});

// ---------------------------------------------------------------------------
// 3. Cross-Model: Checkpoint with Project Reference
// ---------------------------------------------------------------------------
test.describe('DP Quality Checkpoint -- Project Reference', () => {
  test.describe.configure({ mode: 'serial', timeout: 60000 });

  let projectId: string;
  const cpName = `E2E Ref Checkpoint ${uniqueId()}`;

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({
      storageState: 'tests/storage/admin.json',
      baseURL: 'http://localhost:5173',
    });
    const p = await ctx.newPage();
    try {
      projectId = await getTestProjectId(p);
    } catch {
      // PM plugin not available
    }
    await p.close();
    await ctx.close();
  });

  test('REF-001: Create checkpoint with project reference and verify in list', async ({ page }) => {
    if (!projectId) {
      test.skip();
      return;
    }

    const result = await executeCommandViaApi(page, 'dp:create_checkpoint', {
      dp_qc_project_id: projectId,
      dp_qc_name: cpName,
      dp_qc_location: 'East Wing - Level 2',
      dp_qc_category: 'finish',
      dp_qc_inspector: 'Chen Inspector',
      dp_qc_inspect_date: todayStr(),
      dp_qc_description: 'Checkpoint with project reference for cross-model test',
    });
    expect(result.code).toBe(ErrorCodes.SUCCESS);
    await navigateToDynamicPage(page, PAGE.CHECKPOINT);
    const row = page.locator('tbody tr', { hasText: cpName }).first();
    await expect(row).toBeVisible({ timeout: 10000 });
    // The list shows a formatted project code (PRJ-xxx), not the raw pid.
    // Verify the row contains a project reference pattern instead.
    await expect(row).toContainText(/PRJ-/);
  });

  test('REF-002: Verify checkpoint list shows project reference', async ({ page }) => {
    await navigateToDynamicPage(page, PAGE.CHECKPOINT);
    const row = page.locator('tbody tr', { hasText: cpName }).first();
    await expect(row).toBeVisible({ timeout: 10000 });
    await expect(row).toContainText(/PRJ-/);
  });

  test('REF-003: View checkpoint detail via API and verify project reference', async ({ page }) => {
    await navigateToDynamicPage(page, PAGE.CHECKPOINT);
    const row = page.locator('tbody tr', { hasText: cpName }).first();
    await expect(row).toBeVisible({ timeout: 10000 });
    await clickRowActionByLocator(page, row, 'detail');
    await waitForDynamicPageLoad(page);
    await expect(page.locator('body')).toContainText(cpName);
    await expect(page.locator('body')).toContainText(String(projectId));
  });

  test.afterAll(async ({ browser }) => {
    const ctx = await browser.newContext({
      storageState: 'tests/storage/admin.json',
      baseURL: 'http://localhost:5173',
    });
    await ctx.close();
  });
});
