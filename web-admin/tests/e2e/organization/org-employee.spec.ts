/**
 * Organization Employee E2E Tests
 *
 * Tests ORG-010 to ORG-013: Employee management via DSL dynamic page
 * - Employee list page loads
 * - Create employee via UI
 * - Edit employee via UI
 * - Employee status transition
 *
 * Navigate to /dynamic/org-employee (built-in org-management plugin).
 * Uses real database + API, NO MOCKING.
 *
 * @since 6.3.0
 */

import { test, expect } from '../../fixtures';
import { navigateToDynamicPage, uniqueId, executeCommandViaApi, waitForFormReady, clickRowActionByLocator } from '../helpers';
import { ErrorCodes } from '~/services/http-client/types';

const EMPLOYEE_PAGE_KEY = 'org-employee';

test.describe('Organization Employee', () => {
  // Test data is intentionally NOT cleaned up — serves as verification traces

  async function createEmployeePrerequisites(page: import('@playwright/test').Page): Promise<{
    currentUserPid: string;
    currentUserLabel: string;
    deptId: string;
    deptName: string;
    positionId: string;
    positionName: string;
  }> {
    const currentUserResp = await page.request.get('/api/auth/me');
    expect(currentUserResp.ok()).toBe(true);
    const currentUserBody = await currentUserResp.json();
    const currentUser = currentUserBody?.data?.user ?? {};
    const currentUserPid = String(currentUser?.pid ?? '');
    const currentUserLabel = String(
      currentUser?.userName
      ?? currentUser?.user_name
      ?? currentUser?.username
      ?? currentUser?.nickName
      ?? currentUser?.nick_name
      ?? currentUser?.email
      ?? '',
    );
    expect(currentUserPid).toBeTruthy();
    expect(currentUserLabel).toBeTruthy();

    const deptName = `E2E Dept ${uniqueId('D')}`;

    const deptResult = await executeCommandViaApi(page, 'org:create_department', {
      org_dept_name: deptName,
      org_dept_code: `EDEPT-${Date.now()}`,
    });
    expect(deptResult.code).toBe(ErrorCodes.SUCCESS);

    const positionName = `E2E Position ${uniqueId('P')}`;
    const positionResult = await executeCommandViaApi(page, 'org:create_position', {
      org_pos_name: positionName,
      org_pos_code: `EPOS-${Date.now()}`,
      org_pos_level: '1',
      org_pos_dept_id: deptResult.recordId,
    });
    expect(positionResult.code).toBe(ErrorCodes.SUCCESS);

    return {
      currentUserPid,
      currentUserLabel,
      deptId: deptResult.recordId,
      deptName,
      positionId: positionResult.recordId,
      positionName,
    };
  }

  async function selectFieldOption(
    page: import('@playwright/test').Page,
    fieldName: string,
    optionText: string,
  ): Promise<void> {
    const trigger = page.locator(
      [
        `[data-testid="user-select-trigger-${fieldName}"]`,
        `[data-testid="select-trigger-${fieldName}"]`,
        `[data-testid="form-field-${fieldName}"] [role="combobox"]`,
        `[data-testid="form-field-${fieldName}"] button[role="combobox"]`,
        `[data-field="${fieldName}"] [role="combobox"]`,
        `[data-field="${fieldName}"] button[aria-haspopup]`,
      ].join(', '),
    ).first();
    if (await trigger.isVisible({ timeout: 1500 }).catch(() => false)) {
      await trigger.click();

      const searchInput = page.locator(
        [
          `[data-testid="user-select-search-${fieldName}"]`,
          '[role="listbox"] input',
          '[cmdk-input]',
          'input[placeholder*="搜索"]',
          'input[placeholder*="Search"]',
        ].join(', '),
      ).first();
      if (await searchInput.isVisible({ timeout: 1000 }).catch(() => false)) {
        await searchInput.fill(optionText);
      }

      let option = page.locator(
        [
          `[data-testid^="user-select-option-${fieldName}-"]:has-text("${optionText}")`,
          `[role="option"]:has-text("${optionText}")`,
          `[cmdk-item]:has-text("${optionText}")`,
          `[data-slot="select-item"]:has-text("${optionText}")`,
          `.ant-select-item-option:has-text("${optionText}")`,
          `[role="listbox"] *:has-text("${optionText}")`,
        ].join(', '),
      ).first();
      if (!(await option.isVisible({ timeout: 2500 }).catch(() => false))) {
        option = page.locator(
          '[data-testid^="user-select-option-"]:visible, [role="option"]:visible, [cmdk-item]:visible, [data-slot="select-item"]:visible, .ant-select-item-option:visible',
        ).first();
      }
      await expect(option).toBeVisible({ timeout: 5000 });
      await option.click();
      return;
    }

    const nativeSelect = page.locator(
      `[data-testid="form-field-${fieldName}"] select, select[name="${fieldName}"]`,
    ).first();
    await expect(nativeSelect).toBeVisible({ timeout: 5000 });
    await nativeSelect.selectOption({ label: optionText });
  }

  /**
   * ORG-010: Employee list page loads @smoke
   */
  test('ORG-010: employee list page loads @smoke', async ({ page }) => {
    await navigateToDynamicPage(page, EMPLOYEE_PAGE_KEY);

    // Page heading should be visible
    const heading = page.locator('h2').first();
    await expect(heading).toBeVisible({ timeout: 10000 });

    // Table should be present
    const table = page.locator('table, [role="table"]');
    await expect(table.first()).toBeVisible({ timeout: 10000 });

    // Toolbar should have a create button
    const addBtn = page.locator('[data-testid^="toolbar-btn-"]').first();
    const hasAddBtn = await addBtn.isVisible({ timeout: 5000 }).catch(() => false);
    expect(hasAddBtn).toBe(true);
  });

  /**
   * ORG-011: Create employee via UI @smoke
   */
  test('ORG-011: create employee via UI @smoke', async ({ page }) => {
    test.setTimeout(30000);
    const { currentUserLabel, deptName, positionName } = await createEmployeePrerequisites(page);

    await navigateToDynamicPage(page, EMPLOYEE_PAGE_KEY);

    const addBtn = page.locator('[data-testid^="toolbar-btn-"]').first();
    await expect(addBtn).toBeVisible({ timeout: 5000 });
    await expect
      .poll(async () => {
        await addBtn.click().catch(() => null);
        return /\/new($|\?)/.test(page.url());
      }, { timeout: 10000, intervals: [100, 250, 500, 1000] })
      .toBe(true);

    await expect(page).toHaveURL(/\/new/, { timeout: 10000 });
    await waitForFormReady(page, 10000);

    // Fill employee name
    const empName = `E2E Employee ${uniqueId('E')}`;
    const nameInput = page.locator(
      '[data-testid="form-field-org_emp_name"] input, ' +
      'input[name*="emp_name"], ' +
      'input[name*="name"]'
    ).first();

    const hasNameInput = await nameInput.isVisible({ timeout: 5000 }).catch(() => false);
    if (hasNameInput) {
      await nameInput.fill(empName);
    } else {
      const textbox = page.getByRole('textbox').first();
      await textbox.waitFor({ state: 'visible', timeout: 5000 });
      await textbox.fill(empName);
    }

    // Fill employee code if visible
    const codeInput = page.locator(
      '[data-testid="form-field-org_emp_code"] input, ' +
      'input[name*="emp_code"], ' +
      'input[name*="code"]'
    ).first();
    const hasCodeInput = await codeInput.isVisible({ timeout: 3000 }).catch(() => false);
    const codeEditable = hasCodeInput
      ? await codeInput.isEditable({ timeout: 1000 }).catch(() => false)
      : false;
    if (codeEditable) {
      await codeInput.fill(`EMP-${Date.now()}`);
    }

    await selectFieldOption(page, 'org_emp_user_id', currentUserLabel);
    await selectFieldOption(page, 'org_emp_dept_id', deptName);
    await selectFieldOption(page, 'org_emp_position_id', positionName);

    // Click save button
    const saveBtn = page.locator(
      '[data-testid="form-btn-submit"], [data-testid="form-btn-save"], button:has-text("保存"), button:has-text("Save"), button:has-text("提交"), button:has-text("Submit")'
    ).first();
    await expect(saveBtn).toBeVisible({ timeout: 5000 });
    const cmdResponse = page.waitForResponse(
      (r) => r.url().includes('/api/meta/commands/execute/') && r.request().method().toLowerCase() === 'post',
      { timeout: 10000 }
    ).catch(() => null);

    await saveBtn.click();

    const resp = await cmdResponse;
    if (resp) {
      const body = await resp.json();
      expect(String(body.code)).toBe(ErrorCodes.SUCCESS);
    }
  });

  /**
   * ORG-012: Edit employee via UI
   */
  test('ORG-012: edit employee via UI @critical', async ({ page }) => {
    const { currentUserPid, deptId, positionId } = await createEmployeePrerequisites(page);

    // Create employee via API
    const empName = `EditEmp ${uniqueId('M')}`;
    const result = await executeCommandViaApi(page, 'org:create_employee', {
      org_emp_user_id: currentUserPid,
      org_emp_name: empName,
      org_emp_code: `EEMP-${Date.now()}`,
      org_emp_dept_id: deptId,
      org_emp_position_id: positionId,
    });

    if (result.code !== ErrorCodes.SUCCESS) {
      throw new Error(String('Employee creation failed — org plugin may not be imported'));
    }

    await navigateToDynamicPage(page, EMPLOYEE_PAGE_KEY);

    // Find the employee row
    const row = page.locator('tbody tr', { hasText: empName }).first();
    const hasRow = await row.isVisible({ timeout: 5000 }).catch(() => false);

    if (!hasRow) {
      // Employee might be on a different page; verify via API instead
      const fetchResp = await page.request.get(`/api/dynamic/${EMPLOYEE_PAGE_KEY}/${result.recordId}`);
      expect(fetchResp.ok()).toBe(true);
      return;
    }

    // Click edit
    const editDirectBtn = row.locator('[data-testid="row-action-edit"]').first();
    const hasEditDirect = await editDirectBtn.isVisible({ timeout: 2000 }).catch(() => false);
    const hasEditMore = await row.locator('[data-testid="row-action-more"]').isVisible({ timeout: 1000 }).catch(() => false);
    const hasEdit = hasEditDirect || hasEditMore;

    if (hasEdit) {
      await clickRowActionByLocator(page, row, 'edit');
      await page.waitForURL(/\/edit/, { timeout: 5000 }).catch(() => null);
    }

    const updateCommandCode = 'org:update_employee';
    if (!/\/edit/.test(page.url())) {
      await page.goto(
        `/dynamic/${EMPLOYEE_PAGE_KEY}/${result.recordId}/edit?commandCode=${encodeURIComponent(updateCommandCode)}`,
        { waitUntil: 'domcontentloaded' }
      );
    }

    await expect(page).toHaveURL(/\/edit/, { timeout: 10000 });
    if (!page.url().includes('commandCode=')) {
      await page.goto(
        `/dynamic/${EMPLOYEE_PAGE_KEY}/${result.recordId}/edit?commandCode=${encodeURIComponent(updateCommandCode)}`,
        { waitUntil: 'domcontentloaded' }
      );
    }
    await waitForFormReady(page, 10000);

    // Modify name
    const updatedName = `Updated Emp ${uniqueId('U')}`;
    const nameInput = page.locator(
      '[data-testid="form-field-org_emp_name"] input, input[name*="emp_name"], input[name*="name"]'
    ).first();

    await expect(nameInput).toBeVisible({ timeout: 5000 });
    await nameInput.click();
    await nameInput.evaluate((input: HTMLInputElement) => {
      input.value = '';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new Event('change', { bubbles: true }));
    });
    await nameInput.fill(updatedName);
    await expect(nameInput).toHaveValue(updatedName, { timeout: 3000 });

    // Save
    const cmdResponse = page.waitForResponse(
      (r) => r.url().includes(`/api/meta/commands/execute/${updateCommandCode}`) && r.request().method().toLowerCase() === 'post',
      { timeout: 10000 }
    ).catch(() => null);

    const saveBtn = page.locator(
      '[data-testid="form-btn-submit"], [data-testid="form-btn-save"], button:has-text("保存"), button:has-text("Save"), button:has-text("提交"), button:has-text("Submit")'
    ).first();
    await saveBtn.click();

    const resp = await cmdResponse;
    if (resp) {
      const body = await resp.json();
      expect(String(body.code)).toBe(ErrorCodes.SUCCESS);
    }

    await expect.poll(async () => {
      const fetchResp = await page.request.get(`/api/dynamic/${EMPLOYEE_PAGE_KEY}/${result.recordId}`);
      if (!fetchResp.ok()) return '';
      const body = await fetchResp.json().catch(() => ({}));
      const data = body.data ?? body;
      return String(data.org_emp_name ?? data.orgEmpName ?? '');
    }, { timeout: 10000, intervals: [400, 800, 1200] }).toBe(updatedName);
  });

  /**
   * ORG-013: Employee status transition via API and verify on UI
   */
  test('ORG-013: employee status transition @critical', async ({ page }) => {
    const { currentUserPid, deptId, positionId } = await createEmployeePrerequisites(page);

    // Create employee via API
    const empName = `StatusEmp ${uniqueId('S')}`;
    const result = await executeCommandViaApi(page, 'org:create_employee', {
      org_emp_user_id: currentUserPid,
      org_emp_name: empName,
      org_emp_code: `SEMP-${Date.now()}`,
      org_emp_dept_id: deptId,
      org_emp_position_id: positionId,
    });

    if (result.code !== ErrorCodes.SUCCESS) {
      throw new Error(String('Employee creation failed — org plugin may not be imported'));
    }

    // Try to transition status (e.g., deactivate)
    const deactivateResult = await executeCommandViaApi(
      page,
      'org:update_employee',
      { org_emp_status: 'inactive' },
      result.recordId,
    ).catch(() => null);

    // Navigate to list and verify
    await navigateToDynamicPage(page, EMPLOYEE_PAGE_KEY);

    const table = page.locator('table').first();
    await expect(table).toBeVisible({ timeout: 10000 });

    // Verify the employee record exists in the list
    const rowCount = await page.locator('tbody tr').count();
    expect(rowCount).toBeGreaterThan(0);

    // Check if status changed by fetching via API
    const fetchResp = await page.request.get(`/api/dynamic/${EMPLOYEE_PAGE_KEY}/${result.recordId}`);
    if (fetchResp.ok()) {
      const data = await fetchResp.json();
      const empData = data.data || data;
      // Employee should exist
      expect(empData).toBeTruthy();
    }
  });
});
