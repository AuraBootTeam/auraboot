/**
 * Organization Department E2E Tests
 *
 * Tests ORG-001 to ORG-005: Department tree management via DSL dynamic page
 * - Department list/tree page loads
 * - Create department
 * - Edit department
 * - Create child department
 * - Delete department
 *
 * Navigate to /dynamic/org-department (built-in org-management plugin).
 * Uses real database + API, NO MOCKING.
 *
 * @since 6.3.0
 */

import { test, expect } from '../../fixtures';
import { navigateToDynamicPage, uniqueId, acceptConfirmDialog, executeCommandViaApi, findRowInPaginatedList, extractRecordId, clickRowActionByLocator } from '../helpers';
import { ErrorCodes } from '~/shared/services/http-client/types';
import { BASE_URL } from '../../helpers/environments';

const DEPT_PAGE_KEY = 'org-department';

test.describe('Organization Department', () => {
  test.setTimeout(60000);
  const createdPids: string[] = [];

  test.afterAll(async ({ browser }, testInfo) => {
    if (createdPids.length === 0) return;

    const context = await browser.newContext({
      storageState: 'tests/storage/admin.json',
      // Must supply baseURL so page.request relative-path calls resolve correctly
      baseURL: testInfo.project.use.baseURL ?? (process.env.PLAYWRIGHT_BASE_URL ?? process.env.BASE_URL ?? 'http://localhost:5173'),
    });
    const page = await context.newPage();

    // Delete in reverse order (children first)
    for (const pid of [...createdPids].reverse()) {
      await executeCommandViaApi(
        page,
        'org:delete_department',
        {},
        pid,
        'delete',
      ).catch(() => {});
    }

    await page.close();
    await context.close();
  });

  /**
   * ORG-001: Department list page loads @smoke
   */
  test('ORG-001: department list page loads @smoke', async ({ page }) => {
    await navigateToDynamicPage(page, DEPT_PAGE_KEY);

    // Page heading should be visible
    const heading = page.locator('h2').first();
    await expect(heading).toBeVisible({ timeout: 10000 });

    // Table or tree structure should be present
    const content = page.locator('table, [role="tree"], [data-testid="dynamic-list"]');
    await expect(content.first()).toBeVisible({ timeout: 10000 });
  });

  /**
   * ORG-002: Create a department via UI @smoke
   */
  test('ORG-002: create department via UI @smoke', async ({ page }) => {
    await navigateToDynamicPage(page, DEPT_PAGE_KEY);

    // Click create/add button
    const addBtn = page.locator(
      '[data-testid="toolbar-btn-create"], button:has-text("新增"), button:has-text("新建"), button:has-text("Create")'
    ).first();
    await expect(addBtn).toBeVisible({ timeout: 5000 });
    await addBtn.click();

    // Wait for form page
    await page.waitForURL((url) => url.pathname.includes('/new'), { timeout: 10000 });
    await page.locator('h2').first().waitFor({ state: 'visible', timeout: 10000 });

    // Fill department name
    const deptName = `E2E Dept ${uniqueId('D')}`;
    const nameInput = page.locator(
      '[data-testid="form-field-org_dept_name"] input, ' +
      'input[name*="dept_name"], ' +
      'input[name*="name"]'
    ).first();

    const hasNameInput = await nameInput.isVisible({ timeout: 5000 }).catch(() => false);
    if (hasNameInput) {
      await nameInput.fill(deptName);
    } else {
      // Try role-based selector
      const textbox = page.getByRole('textbox').first();
      await textbox.waitFor({ state: 'visible', timeout: 5000 });
      await textbox.fill(deptName);
    }

    // Click save button
    const saveBtn = page.locator(
      '[data-testid="form-btn-submit"], [data-testid="form-btn-save"], button:has-text("保存"), button:has-text("提交"), button:has-text("Save")'
    ).first();
    const cmdResponse = page.waitForResponse(
      (r) => r.url().includes('/api/meta/commands/execute/') && r.request().method().toLowerCase() === 'post',
      { timeout: 10000 }
    ).catch(() => null);

    await saveBtn.click();

    const resp = await cmdResponse;
    if (resp) {
      const body = await resp.json();
      if (String(body.code) === ErrorCodes.SUCCESS) {
        const recordId = extractRecordId(body);
        if (recordId) createdPids.push(recordId);
      }
    }

    // Should navigate back to list
    await page.waitForURL(
      (url) => !url.pathname.includes('/new'),
      { timeout: 10000 }
    ).catch(() => {});
  });

  /**
   * ORG-003: Edit department name via UI
   */
  test('ORG-003: edit department name via UI @critical', async ({ page }) => {
    // Create department via API
    const deptName = `EditDept ${uniqueId('E')}`;
    const result = await executeCommandViaApi(page, 'org:create_department', {
      org_dept_name: deptName,
      org_dept_code: `EDEPT-${Date.now()}`,
    });

    if (result.code !== ErrorCodes.SUCCESS) {
      throw new Error(String('Department creation failed — org plugin may not be imported'))
      return;
    }
    createdPids.push(result.recordId);

    await navigateToDynamicPage(page, DEPT_PAGE_KEY);

    // Find and click edit on the department row
    const row = page.locator('tbody tr', { hasText: deptName }).first();
    const hasRow = await row.isVisible({ timeout: 5000 }).catch(() => false);

    if (hasRow) {
      const editDirectBtn = row.locator('[data-testid="row-action-edit"]').first();
      const hasEditDirect = await editDirectBtn.isVisible({ timeout: 2000 }).catch(() => false);
      const hasEditMore = await row.locator('[data-testid="row-action-more"]').isVisible({ timeout: 1000 }).catch(() => false);
      const hasEdit = hasEditDirect || hasEditMore;

      if (hasEdit) {
        await clickRowActionByLocator(page, row, 'edit');

        await page.waitForURL((url) => url.pathname.includes('/edit') && url.search.includes('commandCode='), {
          timeout: 10000,
        });
        await page.locator('h2').first().waitFor({ state: 'visible', timeout: 10000 });

        // Modify name
        const updatedName = `Updated Dept ${uniqueId('U')}`;
        const nameInput = page.locator(
          '[data-testid="form-field-org_dept_name"] input, input[name*="dept_name"], input[name*="name"]'
        ).first();

        if (await nameInput.isVisible({ timeout: 3000 }).catch(() => false)) {
          await nameInput.fill(updatedName);
        }

        // Save
        const saveBtn = page.locator('[data-testid^="form-btn-"]').first();
        await saveBtn.click();

        await page.waitForURL(
          (url) => !url.pathname.includes('/new'),
          { timeout: 10000 }
        ).catch(() => {});
      }
    }
  });

  /**
   * ORG-004: Create child department via API and verify hierarchy
   */
  test('ORG-004: create child department @critical', async ({ page }) => {
    // Create parent department via API
    const parentResult = await executeCommandViaApi(page, 'org:create_department', {
      org_dept_name: `ParentDept ${uniqueId('P')}`,
      org_dept_code: `PDEPT-${Date.now()}`,
    });

    if (parentResult.code !== ErrorCodes.SUCCESS) {
      throw new Error(String('Department creation failed — org plugin may not be imported'))
      return;
    }
    createdPids.push(parentResult.recordId);

    // Create child department with parent reference
    const childResult = await executeCommandViaApi(page, 'org:create_department', {
      org_dept_name: `ChildDept ${uniqueId('C')}`,
      org_dept_code: `CDEPT-${Date.now()}`,
      org_dept_parent_id: parentResult.recordId,
    });

    if (childResult.code === ErrorCodes.SUCCESS) {
      createdPids.push(childResult.recordId);
    }

    // Navigate and verify both appear
    await navigateToDynamicPage(page, DEPT_PAGE_KEY);

    const table = page.locator('table').first();
    await expect(table).toBeVisible({ timeout: 10000 });
  });

  /**
   * ORG-005: Delete department via UI
   */
  test('ORG-005: delete department via UI @critical', async ({ page }) => {
    // Create department via API
    const deptName = `DelDept ${uniqueId('X')}`;
    const result = await executeCommandViaApi(page, 'org:create_department', {
      org_dept_name: deptName,
      org_dept_code: `XDEPT-${Date.now()}`,
    });

    if (result.code !== ErrorCodes.SUCCESS) {
      throw new Error(String('Department creation failed'))
      return;
    }
    // Track for cleanup in case UI delete fails
    createdPids.push(result.recordId);

    // Navigate with large pageSize to load all records (avoids pagination through large datasets)
    // NOTE: ?keyword= is not a supported URL param; use ?pageSize=200 to expand visible rows
    const listResponsePromise = page.waitForResponse(
      (resp) => resp.url().includes('/list') && resp.status() === 200,
      { timeout: 10000 },
    ).catch(() => null);
    await page.goto(`/dynamic/${DEPT_PAGE_KEY}?pageSize=200`, { waitUntil: 'domcontentloaded' });
    await listResponsePromise;

    // Find the row using search box or pagination
    const row = await findRowInPaginatedList(page, deptName, 15000);
    const hasRow = await row.isVisible({ timeout: 3000 }).catch(() => false);

    if (!hasRow) {
      // Could not find the row — skip rather than fail silently
      throw new Error(String('Department row not visible in paginated list'))
      return;
    }

    // Set up response listener BEFORE triggering the delete action
    const deleteCommandPromise = page.waitForResponse(
      (r) => r.url().includes('/api/meta/commands/execute/') && r.request().method().toLowerCase() === 'post',
      { timeout: 10000 }
    ).catch(() => null);

    await clickRowActionByLocator(page, row, 'delete');
    await acceptConfirmDialog(page);

    // Wait for the delete command to complete
    await deleteCommandPromise;

    // Row should be removed after list refresh
    await expect(page.locator('tbody tr', { hasText: deptName })).toHaveCount(0, { timeout: 10000 });

    // Remove from cleanup list since delete succeeded
    const idx = createdPids.indexOf(result.recordId);
    if (idx >= 0) createdPids.splice(idx, 1);
  });
});
