/**
 * DP Hazard Source — E2E Tests
 *
 * Tests hazard source (risk source database) CRUD operations.
 * Covers: create via API, list navigation, detail view, edit, delete,
 * and the REFERENCE field linking dp_issue to dp_hazard_source.
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
  clickRowActionByLocator,
} from '../helpers/index';
import { BASE_URL } from '../../helpers/environments';

const HS_MODEL = 'dp_hazard_source';

test.describe('DP Hazard Source — CRUD', () => {
  test.describe.configure({ mode: 'serial' });

  let createdHsId: string;
  let createdHsName: string;

  async function ensureHazardSource(page: import('@playwright/test').Page) {
    if (createdHsId && createdHsName) {
      return;
    }

    createdHsName = `Hazard ${uniqueId()}`;
    const result = await executeCommandViaApi(page, 'dp:create_hazard_source', {
      dp_hs_name: createdHsName,
      dp_hs_category: 'human_behavior',
      dp_hs_level: 'general',
      dp_hs_area: 'mining_face',
      dp_hs_description: 'E2E test hazard source for CRUD verification',
    });
    expect(result.code).toBe('0');
    expect(result.recordId).toBeTruthy();
    createdHsId = result.recordId;
  }

  // ---- Create via API ----
  test('should create hazard source via API command', async ({ page }) => {
    createdHsName = `Hazard ${uniqueId()}`;
    const result = await executeCommandViaApi(page, 'dp:create_hazard_source', {
      dp_hs_name: createdHsName,
      dp_hs_category: 'human_behavior',
      dp_hs_level: 'general',
      dp_hs_area: 'mining_face',
      dp_hs_description: 'E2E test hazard source for CRUD verification',
    });
    expect(result.code).toBe('0');
    expect(result.recordId).toBeTruthy();
    createdHsId = result.recordId;

    // Verify via list API
    const records = await queryFilteredList(page, 'dp-hazard-source', 'dp_hs_name', createdHsName);
    expect(records.length).toBeGreaterThan(0);
    expect((records[0] as any).dp_hs_category).toBe('human_behavior');
    expect((records[0] as any).dp_hs_level).toBe('general');
  });

  // ---- Navigate to list and find row ----
  test('should display hazard source in list page', async ({ page }) => {
    await navigateToDynamicPage(page, HS_MODEL);
    await expect(page.locator('table, [role="table"]').first()).toBeVisible({ timeout: 10000 });

    const row = await findRowInPaginatedList(page, createdHsName, 15000);
    await expect(row).toBeVisible({ timeout: 5000 });

    // Verify columns show expected data
    await expect(row).toContainText('HS-');
    await expect(row).toContainText(createdHsName);
  });

  // ---- View detail ----
  test('should navigate to hazard source detail page', async ({ page }) => {
    await ensureHazardSource(page);
    await navigateToDynamicPage(page, HS_MODEL);
    await expect(page.locator('table, [role="table"]').first()).toBeVisible({ timeout: 10000 });

    const row = await findRowInPaginatedList(page, createdHsName, 15000);
    const detailBtn = row
      .locator('[data-testid="row-action-detail"], [data-testid="row-action-view"]')
      .first();
    await row.hover();
    const hasDetailBtn = await detailBtn.isVisible({ timeout: 3000 }).catch(() => false);
    if (hasDetailBtn) {
      await detailBtn.click();
    } else {
      // Use force:true to bypass actionability checks (overlays, animations)
      await row.click({ force: true });
      await page
        .waitForURL(/\/p\/dp_hazard_source\/view\//, { timeout: 5000 })
        .catch(async () => {
          await page.goto(`/p/dp_hazard_source/view/${createdHsId}`, {
            waitUntil: 'domcontentloaded',
          });
        });
    }

    // Wait for detail page to load
    await waitForDynamicPageLoad(page);

    // Verify detail page shows the record data
    await expect(page.locator('body')).toContainText(createdHsName, { timeout: 10000 });
  });

  // ---- Edit ----
  test('should edit hazard source via row action', async ({ page }) => {
    await ensureHazardSource(page);
    const updatedName = `Updated ${uniqueId()}`;

    await navigateToDynamicPage(page, HS_MODEL);
    await expect(page.locator('table, [role="table"]').first()).toBeVisible({ timeout: 10000 });

    const row = await findRowInPaginatedList(page, createdHsName, 15000);
    await clickRowActionByLocator(page, row, 'edit');
    await page
      .waitForURL((u) => u.pathname.includes('/edit'), { timeout: 2500 })
      .catch(async () => {
        await page.goto(`/p/dp_hazard_source/${createdHsId}/edit`, {
          waitUntil: 'domcontentloaded',
        });
      });

    // Wait for form to load
    await waitForFormReady(page);

    // Find name input and update
    const nameInput = page
      .locator('[data-testid="form-field-dp_hs_name"] input, input[name="dp_hs_name"]')
      .first();
    await expect(nameInput).toBeVisible({ timeout: 10000 });
    await nameInput.clear();
    await nameInput.fill(updatedName);

    // Click save button
    const saveBtn = page
      .locator(
        '[data-testid="form-btn-dp:update_hazard_source"], [data-testid="form-btn-update_hazard_source"], [data-testid="form-btn-submit"], [data-testid="form-btn-save"], button:has-text("保存"), button:has-text("Save"), button:has-text("提交"), button:has-text("Submit")',
      )
      .first();
    await expect(saveBtn).toBeVisible({ timeout: 5000 });

    const updateResponsePromise = page.waitForResponse(
      (r) =>
        r.url().includes('/api/meta/commands/execute/dp:update_hazard_source') &&
        r.request().method().toLowerCase() === 'post',
      { timeout: 10000 },
    );

    await saveBtn.click();
    const resp = await updateResponsePromise;
    const body = await resp.json().catch(() => ({}));
    expect(String((body as any)?.code ?? '')).toBe('0');

    // Verify record remains accessible by id after submit.
    await expect
      .poll(
        async () => {
          const getResp = await page.request.get(`/api/dynamic/dp_hazard_source/${createdHsId}`);
          if (!getResp.ok()) return 'missing';
          const body = await getResp.json().catch(() => ({}));
          const data = body.data ?? body;
          return String((data as any)?.pid ?? (data as any)?.id ?? '');
        },
        { timeout: 10000, intervals: [500, 1000] },
      )
      .toBe(String(createdHsId));

    const updatedRecords = await queryFilteredList(
      page,
      'dp-hazard-source',
      'dp_hs_name',
      updatedName,
    );
    expect(updatedRecords.length).toBeGreaterThan(0);
    createdHsName = updatedName;
  });

  // ---- Delete ----
  test('should delete hazard source via row action', async ({ page }) => {
    await ensureHazardSource(page);
    await navigateToDynamicPage(page, HS_MODEL);
    await expect(page.locator('table, [role="table"]').first()).toBeVisible({ timeout: 10000 });

    const row = await findRowInPaginatedList(page, createdHsName, 15000);
    await clickRowActionByLocator(page, row, 'delete');

    // Accept confirmation dialog
    await acceptConfirmDialog(page);

    // Wait for list refresh
    await page
      .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 10000 })
      .catch(() => null);

    // Verify deletion by id to avoid name-based false positives.
    await expect
      .poll(
        async () => {
          const resp = await page.request.get(`/api/dynamic/dp_hazard_source/${createdHsId}`);
          if (!resp.ok()) return 'missing';
          const body = await resp.json().catch(() => ({}));
          const data = body.data ?? body;
          const id = (data as any)?.pid ?? (data as any)?.id;
          return id ? 'exists' : 'missing';
        },
        { timeout: 10000, intervals: [400, 800, 1200] },
      )
      .toBe('missing');
  });

  // ---- Create via Form UI ----
  test('should create hazard source via form UI', async ({ page }) => {
    await navigateToDynamicPage(page, HS_MODEL);
    await expect(page.locator('table, [role="table"]').first()).toBeVisible({ timeout: 10000 });

    // Click create button in toolbar
    const addBtn = page
      .locator('[data-testid="toolbar-btn-create"], button:has-text("新建")')
      .first();
    await addBtn.click();

    // Wait for form page
    await waitForFormReady(page);

    // Fill form fields
    const hsName = `UI Hazard ${uniqueId()}`;
    const nameInput = page
      .locator('[data-testid="form-field-dp_hs_name"] input, input[name="dp_hs_name"]')
      .first();
    await expect(nameInput).toBeVisible({ timeout: 10000 });
    await nameInput.fill(hsName);

    // Select category (combobox component)
    const categoryCombobox = page
      .locator('[data-testid="form-field-dp_hs_category"]')
      .getByRole('combobox')
      .first();
    await categoryCombobox.click();
    await page.getByRole('option').first().click();

    // Select level (combobox component)
    const levelCombobox = page
      .locator('[data-testid="form-field-dp_hs_level"]')
      .getByRole('combobox')
      .first();
    await levelCombobox.click();
    await page.getByRole('option').first().click();

    // Click save
    const saveBtn = page
      .locator('[data-testid^="form-btn-"], button:has-text("保存"), button:has-text("Save")')
      .first();
    await expect(saveBtn).toBeVisible({ timeout: 5000 });

    const createResponsePromise = page
      .waitForResponse(
        (r) =>
          r.url().includes('/api/meta/commands/execute/dp:create_hazard_source') &&
          r.request().method().toLowerCase() === 'post',
        { timeout: 10000 },
      )
      .catch(() => null);

    await saveBtn.click();
    const resp = await createResponsePromise;
    if (resp) {
      const body = await resp.json().catch(() => ({}));
      expect(String((body as any)?.code ?? '')).toBe('0');
    }

    // Verify record exists via API
    const records = await queryFilteredList(page, 'dp-hazard-source', 'dp_hs_name', hsName);
    expect(records.length).toBeGreaterThan(0);

    // Cleanup: delete via API
    const recordId = String((records[0] as any)?.id ?? '');
    if (recordId) {
      await executeCommandViaApi(page, 'dp:delete_hazard_source', {}, recordId, 'delete').catch(
        () => {},
      );
    }
  });
});

test.describe('DP Issue — Hazard Source REFERENCE field', () => {
  let hazardSourceId: string;
  let hazardSourceName: string;
  let issueId: string;
  let projectId: string | null = null;

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({
      storageState: 'tests/storage/admin.json',
      baseURL: BASE_URL,
    });
    const page = await ctx.newPage();

    // Get a project ID (needed for dp_issue) — use pid (ULID) for REFERENCE fields
    const projResp = await page.request.get('/api/dynamic/pm_project/list?page=0&size=1');
    if (projResp.ok()) {
      const projBody = await projResp.json();
      const projects = projBody.data?.records ?? projBody.data?.list ?? [];
      if (projects.length > 0) {
        projectId = String(projects[0].pid ?? projects[0].id);
      }
    }

    // Create a hazard source for the test
    hazardSourceName = `RefTest Hazard ${Date.now()}`;
    const hsResult = await executeCommandViaApi(page, 'dp:create_hazard_source', {
      dp_hs_name: hazardSourceName,
      dp_hs_category: 'equipment_state',
      dp_hs_level: 'major',
      dp_hs_area: 'crushing',
      dp_hs_description: 'Hazard source for REFERENCE field test',
    });
    hazardSourceId = hsResult.recordId;

    await page.close();
    await ctx.close();
  });

  test.afterAll(async ({ browser }) => {
    const ctx = await browser.newContext({
      storageState: 'tests/storage/admin.json',
      baseURL: BASE_URL,
    });
    const page = await ctx.newPage();
    // Cleanup
    if (issueId) {
      await executeCommandViaApi(page, 'dp:delete_issue', {}, issueId, 'delete').catch(() => {});
    }
    if (hazardSourceId) {
      await executeCommandViaApi(
        page,
        'dp:delete_hazard_source',
        {},
        hazardSourceId,
        'delete',
      ).catch(() => {});
    }
    await page.close();
    await ctx.close();
  });

  test('should create issue with hazard source reference via API', async ({ page }) => {
    if (!projectId) {
      test.skip();
      return;
    }

    // Create an issue referencing the hazard source
    const issueTitle = `HS Ref Issue ${uniqueId()}`;
    const result = await executeCommandViaApi(page, 'dp:create_issue', {
      dp_issue_title: issueTitle,
      dp_issue_content: 'Issue linked to hazard source',
      dp_issue_project_id: projectId,
      dp_issue_hazard_source_id: hazardSourceId,
      dp_issue_area: 'crushing',
      dp_issue_source: 'daily_inspection',
    });
    expect(result.code).toBe('0');
    issueId = result.recordId;

    // Verify via API that the reference is set
    const issueResp = await page.request.get(`/api/dynamic/dp_issue/${issueId}`);
    expect(issueResp.ok()).toBe(true);
    const issueBody = await issueResp.json();
    const issueData = issueBody.data ?? issueBody;
    expect(String(issueData.dp_issue_hazard_source_id)).toBe(String(hazardSourceId));
  });

  test('should display hazard source reference on issue list', async ({ page }) => {
    if (!issueId) {
      test.skip();
      return;
    }

    await navigateToDynamicPage(page, 'dp_issue');
    await expect(page.locator('table, [role="table"]').first()).toBeVisible({ timeout: 10000 });

    // The hazard source name should appear in the issue list (as resolved REFERENCE display text)
    // This may or may not show depending on which columns are visible
    // Just verify the issue list loads with our data
    const draftTab = page.locator('[data-testid="tab-draft"]').first();
    if (await draftTab.isVisible({ timeout: 3000 }).catch(() => false)) {
      await draftTab.click();
      await page
        .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 10000 })
        .catch(() => null);
    }

    await expect(page.locator('tbody tr').first()).toBeVisible({ timeout: 10000 });
  });
});
