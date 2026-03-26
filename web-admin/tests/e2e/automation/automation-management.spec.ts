/**
 * E2E Automation Management
 *
 * Tests AM-001 ~ AM-006: Automation CRUD and operations via UI
 * - AM-001: List page loads and renders automation items
 * - AM-002: Navigate to create page and verify form elements
 * - AM-003: Toggle automation enable/disable via button
 * - AM-004: Open execution logs dialog
 * - AM-005: Navigate to editor page and verify form data
 * - AM-006: Delete automation with confirmation dialog
 *
 * API is used only for data setup (beforeAll) and cleanup (afterAll).
 * Uses real database, NO MOCKING.
 *
 * Note: i18n may show raw keys (e.g. "automation.list.enable" instead of "Enable").
 * Selectors use regex patterns to match both raw keys and English fallback text.
 *
 * @since 6.0.0
 */

import { test, expect } from '../../fixtures';
import { uniqueId } from '../quarry-management.setup';
import { AutomationListPage } from '../../pages/AutomationListPage';

// ---------------------------------------------------------------------------
// API helpers — used ONLY for data setup & cleanup
// ---------------------------------------------------------------------------

async function createAutomationViaApi(
  page: import('@playwright/test').Page,
  name?: string
): Promise<{ pid: string; name: string }> {
  const automationName = name ?? `Test Auto ${uniqueId()}`;
  const resp = await page.request.post(`/api/automations`, {
    data: {
      name: automationName,
      description: 'E2E test automation',
      triggerType: 'on_record_create',
      modelCode: 'e2et_order',
      actions: [
        { type: 'send_notification', config: { message: 'e2e test' }, sequence: 0, label: 'Notify' },
      ],
      enabled: false,
    },
  });
  const body = await resp.json();
  if (String(body.code) !== '0') {
    throw new Error(`Failed to create automation: ${body.message || JSON.stringify(body)}`);
  }
  return { pid: body.data.pid, name: automationName };
}

async function deleteAutomationViaApi(
  page: import('@playwright/test').Page,
  pid: string
): Promise<void> {
  await page.request.delete(`/api/automations/${pid}`);
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

test.describe('Automation Management', () => {
  let testAutomation: { pid: string; name: string };

  test.beforeAll(async ({ browser }) => {
    const context = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await context.newPage();
    testAutomation = await createAutomationViaApi(page);
    await page.close();
    await context.close();
  });

  test.afterAll(async ({ browser }) => {
    const context = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await context.newPage();
    await deleteAutomationViaApi(page, testAutomation.pid).catch(() => {});
    await page.close();
    await context.close();
  });

  /**
   * AM-001: Automation list page should load and render items
   */
  test('AM-001: automation list page should load and render', async ({ page }) => {
    const ap = new AutomationListPage(page);
    await page.goto(`/automations`);
    await page.waitForLoadState('domcontentloaded');

    // Check for server error (e.g. missing permission)
    const errorMsg = page.locator('.text-red-500').first();
    if (await errorMsg.isVisible({ timeout: 3000 }).catch(() => false)) {
      const errText = await errorMsg.textContent();
      throw new Error(String(`Automation page error: ${errText}`))
      return;
    }

    // Verify page title via data-testid
    await expect(ap.pageTitle).toBeVisible({ timeout: 10000 });

    // Verify "Create Automation" link exists via data-testid
    await expect(ap.createButton).toBeVisible({ timeout: 5000 });

    // Verify test automation appears in list
    await expect(page.getByText(testAutomation.name)).toBeVisible({ timeout: 5000 });
  });

  /**
   * AM-002: Navigate to create page and verify form elements
   */
  test('AM-002: should navigate to create page and show form', async ({ page }) => {
    const ap = new AutomationListPage(page);
    await page.goto(`/automations`);
    await page.waitForLoadState('domcontentloaded');

    // Click "Create Automation" link via data-testid
    await expect(ap.createButton).toBeVisible({ timeout: 10000 });
    await ap.createButton.click();

    // Verify navigation to /automation/new
    await page.waitForURL(/\/automation\/new/, { timeout: 10000 });

    // Verify name input — zh-CN "自动化名称", en-US "Automation name"
    const nameInput = page.locator('input[placeholder*="名称"], input[placeholder*="Automation name"]').first();
    await expect(nameInput).toBeVisible({ timeout: 5000 });

    // Verify description input — zh-CN "描述（可选）", en-US "Description (optional)"
    const descInput = page.locator('input[placeholder*="描述"], input[placeholder*="escription"]').first();
    await expect(descInput).toBeVisible({ timeout: 5000 });

    // Verify name input is empty (new automation)
    await expect(nameInput).toHaveValue('');
  });

  /**
   * AM-003: Toggle automation enable/disable via UI button
   *
   * The toggle button text contains "enable" or "disable" (possibly as i18n key).
   * Wait for DOM state change after toggle rather than intercepting network responses,
   * since revalidation happens via SSR route loader (not visible as API call).
   */
  test('AM-003: should toggle automation enable/disable', async ({ page }) => {
    const ap = new AutomationListPage(page);
    await page.goto(`/automations`);
    await page.waitForLoadState('domcontentloaded');
    await page.getByText(testAutomation.name).waitFor({ state: 'visible', timeout: 10000 });

    // Find the item row via data-testid
    await expect(ap.automationRow(testAutomation.pid)).toBeVisible({ timeout: 5000 });

    // Initially disabled — verify either button text or status badge indicates disabled
    const toggleBtn = ap.toggleButton(testAutomation.pid);
    await expect(toggleBtn).toBeVisible({ timeout: 3000 });
    await expect(ap.statusBadge(testAutomation.pid)).toContainText(/disabled|已禁用/i);

    const readEnabled = async (): Promise<boolean | null> => {
      const resp = await page.request.get(`/api/automations/${testAutomation.pid}`);
      if (!resp.ok()) return null;
      const body = await resp.json().catch(() => ({}));
      const data = body?.data ?? body;
      return typeof data?.enabled === 'boolean' ? data.enabled : null;
    };

    const beforeEnabled = await readEnabled();

    // Click Enable button and verify persisted state change.
    await ap.toggle(testAutomation.pid);
    await expect.poll(readEnabled, { timeout: 10000 }).not.toBe(beforeEnabled);

    // Toggle back — click Disable button
    await ap.toggle(testAutomation.pid);

    // Verify state changed back to original value
    await expect.poll(readEnabled, { timeout: 10000 }).toBe(beforeEnabled);
  });

  /**
   * AM-004: View execution logs dialog
   *
   * Opens the logs dialog for the test automation and verifies it renders.
   * Since the automation hasn't been triggered, expects empty state message.
   */
  test('AM-004: should open execution logs dialog', async ({ page }) => {
    const ap = new AutomationListPage(page);
    await page.goto(`/automations`);
    await page.waitForLoadState('domcontentloaded');
    await page.getByText(testAutomation.name).waitFor({ state: 'visible', timeout: 10000 });

    // Click Logs button and wait for dialog
    await ap.openLogs(testAutomation.pid);

    // Verify dialog contains automation name
    await expect(ap.logDialog.getByText(testAutomation.name)).toBeVisible({ timeout: 3000 });

    // Verify empty state — zh-CN "暂无执行日志", en-US "No execution logs yet"
    await expect(
      ap.logDialog.getByText(/暂无执行日志|No execution logs/i)
    ).toBeVisible({ timeout: 5000 });

    // Close dialog
    await ap.closeLogs();
  });

  /**
   * AM-005: Navigate to editor page and verify form data is loaded
   */
  test('AM-005: should navigate to editor and show form data', async ({ page }) => {
    const ap = new AutomationListPage(page);
    await page.goto(`/automations`);
    await page.waitForLoadState('domcontentloaded');
    await page.getByText(testAutomation.name).waitFor({ state: 'visible', timeout: 10000 });

    // Click Edit link via data-testid
    await ap.editLink(testAutomation.pid).click();

    // Verify navigation to editor page
    await page.waitForURL(new RegExp(`/automation/${testAutomation.pid}`), { timeout: 10000 });
    await page.waitForLoadState('domcontentloaded');

    // Verify name input has the automation name
    // zh-CN placeholder "自动化名称", en-US "Automation name"
    const nameInput = page.locator('input[placeholder*="名称"], input[placeholder*="Automation name"]').first();
    await expect(nameInput).toBeVisible({ timeout: 10000 });
    await expect(nameInput).toHaveValue(testAutomation.name);

    // Verify description input has the description
    // zh-CN placeholder "描述（可选）", en-US "Description (optional)"
    const descInput = page.locator('input[placeholder*="描述"], input[placeholder*="escription"]').first();
    await expect(descInput).toBeVisible({ timeout: 5000 });
    await expect(descInput).toHaveValue('E2E test automation');
  });

  /**
   * AM-006: Delete automation with confirmation dialog
   *
   * Creates a separate automation specifically for deletion testing.
   * Verifies: click Delete -> confirm dialog -> item removed from list.
   */
  test('AM-006: should delete automation with confirmation', async ({ page }) => {
    const ap = new AutomationListPage(page);

    // Create a separate automation for deletion
    const toDelete = await createAutomationViaApi(page, `Delete Me ${uniqueId()}`);

    await page.goto(`/automations`);
    await page.waitForLoadState('domcontentloaded');

    // Verify the automation to delete appears
    await page.getByText(toDelete.name).waitFor({ state: 'visible', timeout: 10000 });

    await ap.deleteAutomation(toDelete.pid);

    // Verify the automation row is removed from the list
    await expect(page.getByText(toDelete.name)).toBeHidden({ timeout: 10000 });
    await expect(ap.pageTitle).toBeVisible({ timeout: 5000 });
  });
});
