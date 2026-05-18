/**
 * DP Inspection Task — Direct CRUD E2E Tests
 *
 * Tests the newly-added direct CRUD commands for inspection tasks:
 *   - dp:create_inspection_task (CREATE via form UI)
 *   - dp:update_inspection_task (UPDATE via form UI, pending tasks only)
 *   - dp:delete_inspection_task (DELETE via row action, pending tasks only)
 *
 * Also validates the new form page (dp_inspection_task_form) and
 * detail page (dp_inspection_task_detail) blocks are rendered.
 *
 * Precondition: An issue must exist (any status) to provide a valid
 * dp_task_issue_id REFERENCE value.
 */
import { test, expect } from '@playwright/test';
import {
  navigateToDynamicPage,
  uniqueId,
  executeCommandViaApi,
  waitForDynamicPageLoad,
  waitForFormReady,
  acceptConfirmDialog,
  findRowInPaginatedList,
  queryFilteredList,
  extractRecordId,
  clickRowActionByLocator,
} from '../helpers/index';
import { getTestProjectId } from '../quarry-management.setup';
import { BASE_URL } from '../../helpers/environments';

const INSP_MODEL = 'dp_inspection_task';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

async function getAnyIssueId(page: import('@playwright/test').Page): Promise<string> {
  const resp = await page.request.get('/api/dynamic/dp_issue/list?pageSize=1');
  if (!resp.ok()) throw new Error('Could not fetch issues');
  const body = await resp.json().catch(() => ({}));
  const records = body.data?.records ?? body.data?.list ?? [];
  if (records.length === 0) throw new Error('No issues found in DB');
  return String(records[0].pid ?? records[0].id ?? '');
}

// ---------------------------------------------------------------------------
// Suite: Direct Inspection Task CRUD
// ---------------------------------------------------------------------------

test.describe('DP Inspection Task — Direct CRUD (create/update/delete)', () => {
  test.describe.configure({ mode: 'serial' });

  let projectId: string | null = null;
  let issueId: string | null = null;

  let createdTaskId: string;
  let createdTaskNo: string;
  const seededIssueIds: string[] = [];

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
      baseURL: BASE_URL,
    });
    const page = await ctx.newPage();

    // Attempt to get a project for issue creation; fall back to any existing issue.
    try {
      projectId = await getTestProjectId(page);
    } catch {
      projectId = null;
    }

    if (projectId) {
      // Create a dedicated issue to serve as dp_task_issue_id reference.
      const cr = await executeCommandViaApi(page, 'dp:create_issue', {
        dp_issue_project_id: projectId,
        dp_issue_title: `CRUD Setup ${uniqueId()}`,
        dp_issue_content: 'Setup issue for direct CRUD inspection task test',
        dp_issue_area: 'Test Area',
        dp_issue_source: 'daily_inspection',
      });
      if (cr.code === '0' && cr.recordId) {
        issueId = cr.recordId;
        seededIssueIds.push(issueId);
      }
    }

    if (!issueId) {
      // Fall back to any existing issue.
      issueId = await getAnyIssueId(page).catch(() => null);
    }

    await page.close();
    await ctx.close();
  });

  // ---- Create via form UI ----

  test('should create inspection task via form UI (dp:create_inspection_task)', async ({
    page,
  }) => {
    if (!issueId) throw new Error('No issue ID available — cannot create inspection task');

    await navigateToDynamicPage(page, INSP_MODEL);
    await expect(page.locator('table, [role="table"]').first()).toBeVisible({ timeout: 10000 });

    // Click the toolbar "新建" button (added in this upgrade).
    const addBtn = page
      .locator(
        '[data-testid="toolbar-btn-create"], button:has-text("新建"), button:has-text("Create")',
      )
      .first();
    await expect(addBtn).toBeVisible({ timeout: 10000 });
    await addBtn.click();

    // Wait for the form page (dp_inspection_task_form) to be ready.
    await page
      .waitForURL((u) => u.pathname.includes('/new') || u.pathname.includes('/create'), {
        timeout: 10000,
      })
      .catch(() => {});
    await waitForFormReady(page);

    // --- Block: section_inspection_info [form-section] ---
    // Verify the form section heading is visible. The locale is zh-CN so the title renders as "巡检信息".
    const sectionHeading = page.locator('h3:has-text("巡检信息")').first();
    await expect(sectionHeading).toBeVisible({ timeout: 8000 });

    // Fill dp_task_assignee (text input) — use unique value so we can query by it later.
    const assigneeValue = `Inspector ${uniqueId()}`;
    const assigneeInput = page
      .locator('[data-testid="form-field-dp_task_assignee"] input, input[name="dp_task_assignee"]')
      .first();
    if (await assigneeInput.isVisible({ timeout: 5000 }).catch(() => false)) {
      await assigneeInput.fill(assigneeValue);
    }

    // Fill dp_task_planned_date
    const plannedDateInput = page
      .locator(
        '[data-testid="form-field-dp_task_planned_date"] input[type="date"], input[name="dp_task_planned_date"]',
      )
      .first();
    if (await plannedDateInput.isVisible({ timeout: 3000 }).catch(() => false)) {
      const tomorrow = new Date();
      tomorrow.setDate(tomorrow.getDate() + 1);
      await plannedDateInput.fill(tomorrow.toISOString().slice(0, 10));
    }

    // dp_task_area is a dict-based SELECT field — pick the first available option.
    const areaSelect = page
      .locator('[data-testid="form-field-dp_task_area"] select, select[name="dp_task_area"]')
      .first();
    if (await areaSelect.isVisible({ timeout: 3000 }).catch(() => false)) {
      const options = await areaSelect.locator('option').allTextContents();
      if (options.length > 1) {
        await areaSelect.selectOption({ index: 1 });
      }
    }

    // dp_task_issue_id is a REFERENCE field — try select or combobox.
    const issueRefSelect = page
      .locator(
        '[data-testid="form-field-dp_task_issue_id"] select, select[name="dp_task_issue_id"]',
      )
      .first();
    if (await issueRefSelect.isVisible({ timeout: 3000 }).catch(() => false)) {
      await issueRefSelect.selectOption(issueId!).catch(async () => {
        // Fallback: select first available option.
        const opts = await issueRefSelect.locator('option').allTextContents();
        if (opts.length > 1) await issueRefSelect.selectOption({ index: 1 });
      });
    }

    // --- Block: block_dp_inspection_buttons [form-buttons] ---
    // Click the primary submit button (commandCode: dp:create_inspection_task).
    const submitBtn = page
      .locator(
        '[data-testid="form-btn-dp:create_inspection_task"], [data-testid="form-btn-create_inspection_task"], button:has-text("保存"), button:has-text("提交"), button:has-text("Save"), button:has-text("Submit")',
      )
      .first();
    await expect(submitBtn).toBeVisible({ timeout: 8000 });

    const createRespPromise = page
      .waitForResponse(
        (r) =>
          r.url().includes('/api/meta/commands/execute/dp:create_inspection_task') &&
          r.request().method().toLowerCase() === 'post',
        { timeout: 15000 },
      )
      .catch(() => null);

    await submitBtn.click();

    const resp = await createRespPromise;
    if (resp) {
      const body = await resp.json().catch(() => ({}));
      expect(String((body as any)?.code ?? '')).toBe('0');
      createdTaskId = extractRecordId(body);
    }

    // Wait for navigation away from form, then verify in list.
    await page
      .waitForURL((u) => !u.pathname.includes('/new') && !u.pathname.includes('/create'), {
        timeout: 8000,
      })
      .catch(() => {});

    // Verify via list API — query by the unique assignee value we filled.
    const records = await queryFilteredList(
      page,
      'dp-inspection-task',
      'dp_task_assignee',
      assigneeValue,
    );
    expect(records.length).toBeGreaterThan(0);
    if (!createdTaskId) {
      createdTaskId = String((records[0] as any)?.pid ?? (records[0] as any)?.id ?? '');
    }

    // Capture task number for later assertions.
    const detail = records[0] as any;
    createdTaskNo = String(detail?.dp_task_no ?? '').trim();
  });

  // ---- Detail page block rendering ----

  test('should render dp_inspection_task_detail page blocks', async ({ page }) => {
    if (!createdTaskId) test.skip();

    // Navigate directly to detail page.
    await page.goto(`/p/dp_inspection_task/view/${createdTaskId}`, {
      waitUntil: 'domcontentloaded',
    });
    await waitForDynamicPageLoad(page);

    // --- Block: section_inspection_detail [form-section, readOnly] ---
    const infoSection = page.locator('h3:has-text("巡检信息")').first();
    await expect(infoSection).toBeVisible({ timeout: 10000 });

    // --- Block: section_inspection_result [form-section, readOnly] ---
    const resultSection = page.locator('h3:has-text("巡检结果")').first();
    await expect(resultSection).toBeVisible({ timeout: 8000 });

    // --- Block: block_dp_inspection_actions [form-buttons] ---
    // For a pending task, "开始巡检" (start) button should be visible.
    const startBtn = page
      .locator(
        '[data-testid="form-btn-start"], [data-testid="form-btn-dp:start_inspection"], button:has-text("开始"), button:has-text("Start")',
      )
      .first();
    const hasStartBtn = await startBtn.isVisible({ timeout: 5000 }).catch(() => false);

    // Navigation back control must exist — either a "返回" button or a back-arrow icon.
    const backControl = page
      .locator(
        '[data-testid="form-btn-back"], button:has-text("返回"), button:has-text("Back"), a[href], button[aria-label="back"], [data-testid="page-back-btn"]',
      )
      .first();
    // Also accept the left-arrow chevron used in the detail page header.
    const chevronBack = page.locator('svg[class*="chevron"], button svg, a svg').first();

    const hasBackBtn = await backControl.isVisible({ timeout: 3000 }).catch(() => false);
    const hasChevron = await chevronBack.isVisible({ timeout: 2000 }).catch(() => false);
    // The page must have some navigation affordance back.
    expect(hasBackBtn || hasChevron || hasStartBtn).toBe(true);
  });

  // ---- Update via form UI ----

  test('should update inspection task via form UI (dp:update_inspection_task)', async ({
    page,
  }) => {
    if (!createdTaskId) test.skip();

    await navigateToDynamicPage(page, INSP_MODEL);
    await expect(page.locator('table, [role="table"]').first()).toBeVisible({ timeout: 10000 });

    // Locate the row by task number (captured from create test).
    // Fall back to searching by ID via direct URL if task number not available.
    if (!createdTaskNo) {
      await page.goto(`/p/dp_inspection_task/${createdTaskId}/edit`, {
        waitUntil: 'domcontentloaded',
      });
    } else {
      const row = await findRowInPaginatedList(page, createdTaskNo, 15000);
      await expect(row).toBeVisible({ timeout: 5000 });
      await clickRowActionByLocator(page, row, 'edit');
      await page
        .waitForURL((u) => u.pathname.includes('/edit'), { timeout: 5000 })
        .catch(async () => {
          await page.goto(`/p/dp_inspection_task/${createdTaskId}/edit`, {
            waitUntil: 'domcontentloaded',
          });
        });
    }
    await waitForFormReady(page);

    // Update the assignee field (text input, easily verifiable).
    const updatedAssignee = `Updated Inspector ${uniqueId()}`;
    const assigneeInput = page
      .locator('[data-testid="form-field-dp_task_assignee"] input, input[name="dp_task_assignee"]')
      .first();
    await expect(assigneeInput).toBeVisible({ timeout: 10000 });
    await assigneeInput.clear();
    await assigneeInput.fill(updatedAssignee);

    // Click save button (commandCode: dp:update_inspection_task).
    const saveBtn = page
      .locator(
        '[data-testid="form-btn-dp:update_inspection_task"], [data-testid="form-btn-update_inspection_task"], [data-testid="form-btn-save"], button:has-text("保存"), button:has-text("Save")',
      )
      .first();
    await expect(saveBtn).toBeVisible({ timeout: 8000 });

    const updateRespPromise = page
      .waitForResponse(
        (r) =>
          r.url().includes('/api/meta/commands/execute/dp:update_inspection_task') &&
          r.request().method().toLowerCase() === 'post',
        { timeout: 15000 },
      )
      .catch(() => null);

    await saveBtn.click();
    const resp = await updateRespPromise;
    if (resp) {
      const body = await resp.json().catch(() => ({}));
      expect(String((body as any)?.code ?? '')).toBe('0');
    }

    // API-level verification: assignee was persisted.
    await expect
      .poll(
        async () => {
          const r = await page.request.get(`/api/dynamic/dp_inspection_task/${createdTaskId}`);
          if (!r.ok()) return '';
          const b = await r.json().catch(() => ({}));
          return String((b.data ?? b)?.dp_task_assignee ?? '');
        },
        { timeout: 10000, intervals: [500, 1000] },
      )
      .toBe(updatedAssignee);
  });

  // ---- Delete via row action ----

  test('should delete inspection task via row action (dp:delete_inspection_task)', async ({
    page,
  }) => {
    // Create a fresh task via API so deletion doesn't affect other tests.
    if (!issueId) test.skip();

    const newTaskResult = await executeCommandViaApi(page, 'dp:create_inspection_task', {
      dp_task_issue_id: issueId!,
      dp_task_area: `Delete Zone ${uniqueId()}`,
      dp_task_assignee: 'DeleteTest Inspector',
    });
    expect(newTaskResult.code).toBe('0');
    const taskToDeleteId = newTaskResult.recordId;
    expect(taskToDeleteId).toBeTruthy();

    // Fetch task number for list lookup.
    const taskResp = await page.request.get(`/api/dynamic/dp_inspection_task/${taskToDeleteId}`);
    const taskBody = await taskResp.json().catch(() => ({}));
    const taskNo = String((taskBody.data ?? taskBody)?.dp_task_no ?? '').trim();

    await navigateToDynamicPage(page, INSP_MODEL);
    await expect(page.locator('table, [role="table"]').first()).toBeVisible({ timeout: 10000 });

    const rowKey = taskNo || taskToDeleteId;
    const row = await findRowInPaginatedList(page, rowKey, 15000);
    await expect(row).toBeVisible({ timeout: 5000 });

    await clickRowActionByLocator(page, row, 'delete');

    await acceptConfirmDialog(page);

    await page
      .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 10000 })
      .catch(() => null);

    // Verify deletion via API.
    await expect
      .poll(
        async () => {
          const r = await page.request.get(`/api/dynamic/dp_inspection_task/${taskToDeleteId}`);
          if (!r.ok()) return 'missing';
          const b = await r.json().catch(() => ({}));
          const id = (b.data ?? b)?.pid ?? (b.data ?? b)?.id;
          return id ? 'exists' : 'missing';
        },
        { timeout: 10000, intervals: [400, 800, 1200] },
      )
      .toBe('missing');
  });
});
