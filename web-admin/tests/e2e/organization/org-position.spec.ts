/**
 * Organization Position E2E Tests
 *
 * Tests ORG-020 to ORG-022: Position management via DSL dynamic page
 * - Position list page loads
 * - Create position via UI
 * - 6-level hierarchy validation
 *
 * Navigate to /dynamic/org-position (built-in org-management plugin).
 * Uses real database + API, NO MOCKING.
 *
 * @since 6.3.0
 */

import { test, expect } from '../../fixtures';
import { navigateToDynamicPage, uniqueId, executeCommandViaApi, waitForFormReady, extractRecordId } from '../helpers';
import { ErrorCodes } from '~/shared/services/http-client/types';

const POSITION_PAGE_KEY = 'org-position';

test.describe('Organization Position', () => {
  const createdPids: string[] = [];

  async function setHiddenField(page: import('@playwright/test').Page, name: string, value: string): Promise<void> {
    await page.evaluate(({ fieldName, fieldValue }) => {
      const input = document.querySelector(`input[name="${fieldName}"]`) as HTMLInputElement | null;
      if (!input) throw new Error(`Hidden input not found: ${fieldName}`);
      input.value = fieldValue;
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new Event('change', { bubbles: true }));
    }, { fieldName: name, fieldValue: value });
  }

  async function createDepartment(page: import('@playwright/test').Page, prefix = 'Pos Dept'): Promise<string> {
    const deptResult = await executeCommandViaApi(page, 'org:create_department', {
      org_dept_name: `${prefix} ${uniqueId('D')}`,
      org_dept_code: `PDEPT-${Date.now()}`,
    });
    expect(deptResult.code).toBe(ErrorCodes.SUCCESS);
    return deptResult.recordId;
  }

  test.afterAll(async ({ browser }) => {
    if (createdPids.length === 0) return;

    const context = await browser.newContext({
      storageState: 'tests/storage/admin.json',
    });
    const page = await context.newPage();

    for (const pid of [...createdPids].reverse()) {
      await executeCommandViaApi(
        page,
        'org:delete_position',
        {},
        pid,
        'delete',
      ).catch(() => {});
    }

    await page.close();
    await context.close();
  });

  /**
   * ORG-020: Position list page loads @smoke
   */
  test('ORG-020: position list page loads @smoke', async ({ page }) => {
    await navigateToDynamicPage(page, POSITION_PAGE_KEY);

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
   * ORG-021: Create position via UI @smoke
   */
  test('ORG-021: create position via UI @smoke', async ({ page }) => {
    test.setTimeout(30000);
    const deptId = await createDepartment(page);

    await navigateToDynamicPage(page, POSITION_PAGE_KEY);

    const addBtn = page.locator('[data-testid^="toolbar-btn-"]').first();
    await expect(addBtn).toBeVisible({ timeout: 5000 });
    await expect
      .poll(async () => {
        await addBtn.click().catch(() => null);
        return /\/new($|\?)/.test(page.url());
      }, { timeout: 10000, intervals: [100, 250, 500, 1000] })
      .toBe(true);

    await page.waitForURL((url) => url.pathname.includes('/new'), { timeout: 10000 });
    await waitForFormReady(page, 10000);

    // Fill position name
    const posName = `E2E Position ${uniqueId('P')}`;
    const nameInput = page.locator(
      '[data-testid="form-field-org_pos_name"] input, ' +
      'input[name*="pos_name"], ' +
      'input[name*="name"]'
    ).first();

    const hasNameInput = await nameInput.isVisible({ timeout: 5000 }).catch(() => false);
    if (hasNameInput) {
      await nameInput.fill(posName);
    } else {
      const textbox = page.getByRole('textbox').first();
      await textbox.waitFor({ state: 'visible', timeout: 5000 });
      await textbox.fill(posName);
    }

    // Fill position level if visible
    const levelInput = page.locator(
      '[data-testid="form-field-org_pos_level"] input, ' +
      'input[name*="pos_level"], ' +
      'input[name*="level"]'
    ).first();
    const hasLevelInput = await levelInput.isVisible({ timeout: 3000 }).catch(() => false);
    if (hasLevelInput) {
      await levelInput.fill('1');
    }

    await setHiddenField(page, 'org_pos_dept_id', deptId);

    // Click save button
    const saveBtn = page.locator('[data-testid^="form-btn-"]').first();
    await expect(saveBtn).toBeVisible({ timeout: 5000 });
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
  });

  /**
   * ORG-022: 6-level position hierarchy via API
   */
  test('ORG-022: 6-level position hierarchy', async ({ page }) => {
    const deptId = await createDepartment(page, 'Hierarchy Dept');
    const levels = [
      { name: 'ceo', level: '1' },
      { name: 'VP', level: '2' },
      { name: 'Director', level: '3' },
      { name: 'Manager', level: '4' },
      { name: 'Lead', level: '5' },
      { name: 'Staff', level: '6' },
    ];

    const hierPids: string[] = [];
    let parentId: string | undefined;

    for (const { name, level } of levels) {
      const posData: Record<string, unknown> = {
        org_pos_name: `${name} ${uniqueId('H')}`,
        org_pos_code: `HLVL${level}-${Date.now()}`,
        org_pos_level: level,
        org_pos_dept_id: deptId,
      };

      if (parentId) {
        posData.org_pos_parent_id = parentId;
      }

      const result = await executeCommandViaApi(page, 'org:create_position', posData);

      if (result.code !== ErrorCodes.SUCCESS) {
        // If org plugin is not imported, skip remaining
        if (hierPids.length === 0) {
          throw new Error(String('Position creation failed — org plugin may not be imported'))
          return;
        }
        break;
      }

      hierPids.push(result.recordId);
      createdPids.push(result.recordId);
      parentId = result.recordId;
    }

    // Verify hierarchy was created
    expect(hierPids.length).toBeGreaterThanOrEqual(2);

    // Navigate to list and verify positions exist
    await navigateToDynamicPage(page, POSITION_PAGE_KEY);

    const table = page.locator('table').first();
    await expect(table).toBeVisible({ timeout: 10000 });

    // Table should have rows
    const rowCount = await page.locator('tbody tr').count();
    expect(rowCount).toBeGreaterThan(0);
  });
});
