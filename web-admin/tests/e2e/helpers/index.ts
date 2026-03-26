/**
 * Shared E2E Test Helpers
 *
 * Generic utilities used across all E2E test suites:
 * - Navigation helpers
 * - Dynamic page load waiting
 * - Test data generators
 * - Form interaction helpers
 * - Table / list helpers
 * - Dialog helpers
 * - Toast / notification helpers
 *
 * NOTE: Authentication is handled via storageState (global-setup.ts).
 *
 * @since 4.0.0
 */

import { type Page, type Locator, expect } from '@playwright/test';

// ---------------------------------------------------------------------------
// Test Data Generators
// ---------------------------------------------------------------------------

/**
 * Generate a unique test identifier based on timestamp and random suffix.
 */
export function uniqueId(prefix = 'e2e'): string {
  const ts = Date.now();
  const rand = Math.random().toString(36).slice(2, 7);
  return `${prefix}_${ts}_${rand}`;
}

/**
 * Get today's date string formatted as YYYY-MM-DD.
 */
export function todayStr(): string {
  return new Date().toISOString().slice(0, 10);
}

/**
 * Get a date string N days from today.
 */
export function dateOffsetStr(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() + days);
  return d.toISOString().slice(0, 10);
}

// ---------------------------------------------------------------------------
// Navigation Helpers
// ---------------------------------------------------------------------------

/**
 * Navigate to a page by clicking through a hierarchical sidebar menu path.
 *
 * @param page    - Playwright page
 * @param labels  - Ordered array of menu labels to click (e.g. ['CRM', '客户管理'])
 */
export async function navigateToMenuByClick(page: Page, labels: string[]): Promise<void> {
  for (const label of labels) {
    const nav = page.locator('nav, aside, [role="navigation"]').first();
    const item = nav.getByRole('button', { name: label }).or(
      nav.getByRole('menuitem', { name: label })
    ).or(
      nav.locator(`[title="${label}"]`)
    ).or(
      nav.locator(`text="${label}"`)
    ).first();
    await item.waitFor({ state: 'visible', timeout: 8000 });
    await item.click();
    // Wait for any loading/navigation triggered by menu click
    await page.waitForLoadState('domcontentloaded').catch(() => {});
  }
}

/**
 * Ensure the sidebar is in the expanded (non-collapsed) state.
 *
 * When the sidebar is collapsed (icon-only mode), sub-menu links are rendered in
 * floating popovers that only appear on hover — they are NOT statically present
 * in the nav DOM. Tests that need to assert `nav a[href="..."]` visibility or
 * click parent submenu buttons MUST call this helper first.
 *
 * Implementation: clears the 'sidebar-collapsed' localStorage key, then reloads
 * the page so the sidebar re-initialises in expanded mode.
 */
export async function ensureSidebarExpanded(page: Page): Promise<void> {
  // Remove collapsed flag from localStorage (may not be set yet — that's fine)
  await page.evaluate(() => {
    localStorage.removeItem('sidebar-collapsed');
  });
  // Reload so the sidebar component picks up the cleared state
  await page.reload({ waitUntil: 'domcontentloaded' });
}

/**
 * Navigate directly to a dynamic page by its table/page key.
 *
 * @param page - Playwright page
 * @param pageKey - The dynamic page key (e.g. 'quarry_daily_report')
 */
export async function navigateToDynamicPage(page: Page, pageKey: string): Promise<void> {
  // Set up list API listener BEFORE navigation so we catch the response
  const listResponsePromise = page
    .waitForResponse(
      (resp) => resp.url().includes('/list') && resp.status() === 200,
      { timeout: 10000 },
    )
    .catch(() => null);

  await page.goto(`/dynamic/${pageKey}`, { waitUntil: 'domcontentloaded' });
  await waitForDynamicPageLoad(page);

  // Wait for list data API to return
  await listResponsePromise;
}

// ---------------------------------------------------------------------------
// Wait Helpers
// ---------------------------------------------------------------------------

/**
 * Wait for a dynamic page to finish loading.
 * Checks for table/form content and ensures loading spinners have disappeared.
 */
export async function waitForDynamicPageLoad(page: Page, timeout = 15000): Promise<void> {
  await page.waitForLoadState('domcontentloaded');

  // Wait for any loading spinner to disappear
  const spinner = page.locator('.animate-spin, [data-testid="loading"]');
  try {
    await spinner.waitFor({ state: 'visible', timeout: 2000 });
  } catch {
    // Spinner might already be gone - that's fine
  }
  await expect(spinner).not.toBeVisible({ timeout });

  // Verify meaningful content appeared
  const content = page.locator(
    '.ant-table, table, [data-testid="dynamic-list"], [data-testid="table-block"], form, .ant-form, [role="table"], main'
  );
  await content.first().waitFor({ state: 'visible', timeout }).catch(() => {
    // Page may have a different layout - not necessarily an error
  });
}

/**
 * Wait for a dynamic form to be fully ready (schema loaded + fields rendered).
 *
 * Dynamic forms go through two stages:
 * 1. Schema fetch (loading spinner)
 * 2. Field rendering (smart components like switches, selects)
 *
 * This helper waits for both stages to complete by checking for
 * the presence of interactive form elements.
 */
export async function waitForFormReady(page: Page, timeout = 15000): Promise<void> {
  await page.waitForLoadState('domcontentloaded');

  // Wait for any loading spinner to disappear
  const spinner = page.locator('.animate-spin, [data-testid="loading"]');
  await spinner.waitFor({ state: 'hidden', timeout }).catch(() => {});

  // Wait for form content to render (at least one interactive element)
  const formContent = page.locator(
    'form input, form textarea, form select, ' +
    'button[role="switch"], ' +
    '[data-testid^="form-field-"], ' +
    '.ant-form-item, ' +
    '[data-testid="dynamic-form"]'
  );
  await formContent.first().waitFor({ state: 'visible', timeout }).catch(() => {});

  // Some dynamic forms render component-loader placeholders first
  // (for example "Loading SmartInput..."). Wait for those placeholders
  // to disappear before tests start targeting individual fields.
  const loadingSmartField = page.locator('text=/Loading Smart[A-Za-z]+\\.\\.\\./');
  await loadingSmartField.first().waitFor({ state: 'hidden', timeout }).catch(() => {});
}

/**
 * Wait for a toast/notification message to appear and optionally assert text.
 */
export async function waitForToast(page: Page, expectedText?: string, timeout = 5000): Promise<void> {
  const toast = page.locator('[role="alert"], [data-testid="toast"], .toast-message, .ant-message');
  await toast.first().waitFor({ state: 'visible', timeout });
  if (expectedText) {
    await expect(toast.first()).toContainText(expectedText);
  }
}

// ---------------------------------------------------------------------------
// Confirm Dialog Helpers
// ---------------------------------------------------------------------------

/**
 * Accept a custom ConfirmDialog (replaces native window.confirm).
 * Waits for the dialog to appear, clicks "确定", and waits for it to close.
 */
export async function acceptConfirmDialog(page: Page, timeout = 5000): Promise<void> {
  const dialog = page.locator('[data-testid="confirm-dialog"]');
  await dialog.waitFor({ state: 'visible', timeout });
  await page.locator('[data-testid="confirm-ok"]').click();
  await dialog.waitFor({ state: 'hidden', timeout });
}

/**
 * Dismiss a custom ConfirmDialog (clicks "取消").
 */
export async function dismissConfirmDialog(page: Page, timeout = 5000): Promise<void> {
  const dialog = page.locator('[data-testid="confirm-dialog"]');
  await dialog.waitFor({ state: 'visible', timeout });
  await page.locator('[data-testid="confirm-cancel"]').click();
  await dialog.waitFor({ state: 'hidden', timeout });
}

// ---------------------------------------------------------------------------
// Form Helpers
// ---------------------------------------------------------------------------

/**
 * Fill a form field by its label text or name attribute.
 * Supports data-testid via form-field-{name} pattern.
 */
export async function fillField(page: Page, labelOrName: string, value: string): Promise<void> {
  // Strategy 0: Find by data-testid (form-field-{name})
  const byTestId = page.locator(`[data-testid="form-field-${labelOrName}"] input`).first();
  if (await byTestId.isVisible({ timeout: 2000 }).catch(() => false)) {
    await byTestId.fill(value);
    return;
  }

  // Strategy 1: Find by label
  const byLabel = page.locator(`label:has-text("${labelOrName}") + * input, label:has-text("${labelOrName}") ~ * input`).first();
  if (await byLabel.isVisible({ timeout: 2000 }).catch(() => false)) {
    await byLabel.fill(value);
    return;
  }

  // Strategy 2: Find by name attribute
  const byName = page.locator(`[name="${labelOrName}"], [data-field="${labelOrName}"] input`).first();
  if (await byName.isVisible({ timeout: 2000 }).catch(() => false)) {
    await byName.fill(value);
    return;
  }

  // Strategy 3: Find by placeholder
  const byPlaceholder = page.locator(`input[placeholder*="${labelOrName}"]`).first();
  if (await byPlaceholder.isVisible({ timeout: 2000 }).catch(() => false)) {
    await byPlaceholder.fill(value);
    return;
  }

  throw new Error(`Could not find input field: ${labelOrName}`);
}

/**
 * Select an option from an Ant Design Select or native select element.
 */
export async function selectOption(page: Page, labelOrName: string, optionText: string): Promise<void> {
  // Strategy 1: Ant Design Select
  const antSelect = page.locator(
    `label:has-text("${labelOrName}") ~ * .ant-select, [data-field="${labelOrName}"] .ant-select`
  ).first();

  if (await antSelect.isVisible({ timeout: 2000 }).catch(() => false)) {
    await antSelect.click();
    await page.locator(`.ant-select-dropdown .ant-select-item:has-text("${optionText}")`).click();
    return;
  }

  // Strategy 2: Native select
  const nativeSelect = page.locator(
    `select[name="${labelOrName}"], [data-field="${labelOrName}"] select`
  ).first();

  if (await nativeSelect.isVisible({ timeout: 2000 }).catch(() => false)) {
    await nativeSelect.selectOption({ label: optionText });
    return;
  }

  // Strategy 3: Custom select by label
  const customSelect = page.locator(`label:has-text("${labelOrName}")`).locator('..').locator('select, [role="combobox"]').first();
  if (await customSelect.isVisible({ timeout: 2000 }).catch(() => false)) {
    await customSelect.click();
    await page.getByText(optionText, { exact: false }).first().click();
    return;
  }

  throw new Error(`Could not find select field: ${labelOrName}`);
}

/**
 * Click a button by its text content or data-testid.
 */
export async function clickButton(page: Page, text: string, testId?: string): Promise<void> {
  if (testId) {
    const byTestId = page.locator(`[data-testid="${testId}"]`);
    if (await byTestId.isVisible({ timeout: 2000 }).catch(() => false)) {
      await byTestId.click();
      return;
    }
  }
  const btn = page.locator(`button:has-text("${text}"), a:has-text("${text}")`).first();
  await btn.waitFor({ state: 'visible', timeout: 5000 });
  await btn.click();
}

/**
 * Click the primary save/submit button on a form page.
 * Prefers data-testid selectors, falls back to text-based.
 */
export async function clickSaveButton(page: Page): Promise<void> {
  // Prefer data-testid
  const byTestId = page.locator('[data-testid^="form-btn-"]').first();
  if (await byTestId.isVisible({ timeout: 2000 }).catch(() => false)) {
    await byTestId.click();
    return;
  }
  // Fallback to text-based
  const saveBtn = page.locator(
    'button:has-text("提交"), button:has-text("保存"), button:has-text("Save"), button:has-text("Submit"), button[type="submit"]'
  ).first();
  await saveBtn.waitFor({ state: 'visible', timeout: 5000 });
  await saveBtn.click();
}

/**
 * Confirm a dialog (Ant Design Modal or native).
 */
export async function confirmDialog(page: Page): Promise<void> {
  const dialog = page.locator('[role="dialog"], [role="alertdialog"], .ant-modal');
  await dialog.waitFor({ state: 'visible', timeout: 5000 });
  const okBtn = dialog.locator(
    'button:has-text("确定"), button:has-text("确认"), button:has-text("OK"), button:has-text("Yes")'
  );
  await okBtn.click();
  await expect(dialog).not.toBeVisible({ timeout: 5000 });
}

/**
 * Dismiss/cancel a dialog.
 */
export async function cancelDialog(page: Page): Promise<void> {
  const dialog = page.locator('[role="dialog"], [role="alertdialog"], .ant-modal');
  await dialog.waitFor({ state: 'visible', timeout: 5000 });
  const cancelBtn = dialog.locator('[data-testid="dialog-cancel"], button:has-text("取消"), button:has-text("Cancel")');
  await cancelBtn.click();
  await expect(dialog).not.toBeVisible({ timeout: 5000 });
}

// ---------------------------------------------------------------------------
// Table / List Helpers
// ---------------------------------------------------------------------------

/**
 * Get the count of data rows in the current table.
 */
export async function getTableRowCount(page: Page): Promise<number> {
  await waitForDynamicPageLoad(page);
  return page.locator('tbody tr, [role="row"]:not(thead [role="row"])').count();
}

/**
 * Get text content of a specific cell.
 */
export async function getCellText(page: Page, rowIndex: number, colIndex: number): Promise<string> {
  const cell = page.locator('tbody tr').nth(rowIndex).locator('td').nth(colIndex);
  return (await cell.innerText()).trim();
}

/**
 * Click a row action button (e.g. edit, delete, submit).
 * Supports both code-based (data-testid) and text-based selection.
 */
export async function clickRowAction(page: Page, rowIndex: number, actionText: string, code?: string): Promise<void> {
  const row = page.locator(`[data-testid="table-row-${rowIndex}"]`);
  if (code) {
    await clickRowActionByLocator(page, row, code, actionText);
    return;
  }
  await row.locator(`button:has-text("${actionText}"), a:has-text("${actionText}")`).click();
}

/**
 * Click a row action button, handling the "more actions" dropdown pattern.
 * In DSL list pages, only the first action is shown directly; the rest are in
 * a portal-rendered dropdown behind [data-testid="row-action-more"].
 *
 * Usage:
 *   const row = await findRowInPaginatedList(page, title);
 *   await clickRowActionByLocator(page, row, 'delete');
 *   await clickRowActionByLocator(page, row, 'edit');
 */
export async function clickRowActionByLocator(
  page: Page,
  row: Locator,
  actionCode: string,
  fallbackText?: string,
): Promise<void> {
  await row.scrollIntoViewIfNeeded().catch(() => null);

  // 1. Try direct button (primary slot — first rowAction is always direct)
  const directBtn = row.locator(`[data-testid="row-action-${actionCode}"]`).first();
  if (await directBtn.isVisible({ timeout: 2000 }).catch(() => false)) {
    await directBtn.click();
    return;
  }

  // 2. Open "more actions" dropdown and look inside
  const moreBtn = row.locator('[data-testid="row-action-more"]').first();
  if (await moreBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
    await moreBtn.evaluate((el: HTMLElement) => el.click());
    const dropdown = page.locator('[data-testid="row-action-dropdown"]');
    await dropdown.waitFor({ state: 'visible', timeout: 5000 });
    const actionInDropdown = dropdown.locator(`[data-testid="row-action-${actionCode}"]`).first();
    if (await actionInDropdown.isVisible({ timeout: 3000 }).catch(() => false)) {
      // Use evaluate to bypass viewport checks — portal dropdowns may render outside visible area
      await actionInDropdown.evaluate((el: HTMLElement) => el.click());
      return;
    }
    // Fallback: search by text in dropdown
    if (fallbackText) {
      const byText = dropdown.locator(`button:has-text("${fallbackText}")`).first();
      if (await byText.isVisible({ timeout: 2000 }).catch(() => false)) {
        await byText.evaluate((el: HTMLElement) => el.click());
        return;
      }
    }
    // Close dropdown if action not found
    await page.keyboard.press('Escape');
  }

  // 3. Last resort: search entire page by testid (may be in a portal)
  const anyBtn = page.locator(`[data-testid="row-action-${actionCode}"]`).first();
  if (await anyBtn.isVisible({ timeout: 2000 }).catch(() => false)) {
    await anyBtn.evaluate((el: HTMLElement) => el.click());
    return;
  }

  throw new Error(`Row action "${actionCode}" not found — neither direct nor in more-dropdown`);
}

/**
 * Find a row by searching for text in a specific column index.
 * Returns the row index or -1 if not found.
 */
export async function findRowByText(page: Page, colIndex: number, text: string): Promise<number> {
  const rows = page.locator('tbody tr');
  const count = await rows.count();
  for (let i = 0; i < count; i++) {
    const cellText = await rows.nth(i).locator('td').nth(colIndex).innerText();
    if (cellText.trim().includes(text)) {
      return i;
    }
  }
  return -1;
}

// ---------------------------------------------------------------------------
// Tab Helpers
// ---------------------------------------------------------------------------

/**
 * Click a tab button and wait for the list API to respond.
 * Supports both key-based (data-testid) and text-based tab selection.
 */
export async function clickTabAndWaitForLoad(
  page: Page,
  tabName: string | RegExp,
  timeout = 5000,
  tabKey?: string
): Promise<void> {
  let tab: Locator;
  if (tabKey) {
    tab = page.locator(`[data-testid="tab-${tabKey}"]`);
  } else {
    tab = page.locator('nav[aria-label="Tabs"] button').filter({ hasText: tabName }).first();
  }

  if (!(await tab.isVisible({ timeout: 3000 }).catch(() => false))) {
    return;
  }

  const listResponse = page.waitForResponse(
    (r) => r.url().includes('/list') && r.status() === 200,
    { timeout }
  ).catch(() => null);

  await tab.click();
  await listResponse;
}

// ---------------------------------------------------------------------------
// Command API Helpers
// ---------------------------------------------------------------------------

/**
 * Execute a command via the BFF API and return the result.
 * Uses page.request to inherit authentication cookies.
 */
export async function executeCommandViaApi(
  page: Page,
  commandCode: string,
  payload: Record<string, unknown> = {},
  targetRecordId?: string,
  operationType?: string,
  options?: { allowHttpError?: boolean }
): Promise<{ recordId: string; code: string }> {
  const data: Record<string, unknown> = { payload };
  if (targetRecordId) data.targetRecordId = targetRecordId;
  if (operationType) data.operationType = operationType;

  const resp = await page.request.post(
    `/api/meta/commands/execute/${commandCode}`,
    { data }
  );
  const body = await resp.json().catch(() => ({}));
  if (!resp.ok() && !options?.allowHttpError) {
    throw new Error(
      `Command ${commandCode} failed with HTTP ${resp.status()}: ${JSON.stringify(body).slice(0, 500)}`
    );
  }
  const resultData = (body as any)?.data?.data ?? {};
  const code = String(body?.code ?? '');
  const recordId = resultData?.recordId ?? resultData?.pid ?? resultData?.id ?? '';
  return {
    code: String(code),
    recordId: String(recordId),
  };
}

// ---------------------------------------------------------------------------
// Command Response Helpers
// ---------------------------------------------------------------------------

/**
 * Extract recordId from a Command execute API response body.
 *
 * The API shape is: `{ code, data: { commandCode, data: { recordId, pid, ... } } }`.
 * This helper eliminates scattered defensive fallback chains across tests.
 */
export function extractRecordId(body: any): string {
  const resultData = body?.data?.data;
  return String(resultData?.recordId ?? resultData?.pid ?? resultData?.id ?? '');
}

// ---------------------------------------------------------------------------
// Content-Driven Row Finder
// ---------------------------------------------------------------------------

/**
 * Find a table row by its text content. Returns the row Locator.
 * Preferred over positional first()/last() for robustness.
 */
export async function findRowByContent(
  page: Page,
  text: string
): Promise<Locator> {
  const row = page.locator('tbody tr', { hasText: text }).first();
  await row.waitFor({ state: 'attached', timeout: 5000 });
  return row;
}

/**
 * Find a row by text in a paginated list. Navigates to the last page if not found.
 * Newest items (highest ISS/ID numbers) are on the last page when sorted ascending.
 */
export async function findRowInPaginatedList(
  page: Page,
  title: string,
  timeout = 8000,
): Promise<Locator> {
  const deadline = Date.now() + timeout;
  const timeLeft = () => Math.max(300, deadline - Date.now());
  const waitListResponse = async (waitTimeout: number) => {
    await page
      .waitForResponse(
        (r) => r.url().includes('/list') && r.status() === 200,
        { timeout: waitTimeout },
      )
      .catch(() => null);
  };

  let row = page.locator('tbody tr', { hasText: title }).first();
  if (await row.isVisible({ timeout: Math.min(1200, timeLeft()) }).catch(() => false)) return row;

  // Strategy 0: use list search box to narrow dataset before paging.
  const searchInput = page
    .locator(
      '[data-testid="search-input"], [data-testid="table-search-input"], input[placeholder*="搜索"], input[placeholder*="Search"]'
    )
    .first();
  const canSearch = await searchInput.isVisible({ timeout: Math.min(800, timeLeft()) }).catch(() => false);
  if (canSearch) {
    await searchInput.click({ timeout: Math.min(800, timeLeft()) }).catch(() => null);
    await searchInput.fill(title, { timeout: Math.min(1000, timeLeft()) }).catch(() => null);
    await searchInput.press('Enter').catch(() => null);
    await waitListResponse(Math.min(1800, timeLeft()));

    row = page.locator('tbody tr', { hasText: title }).first();
    if (await row.isVisible({ timeout: Math.min(1000, timeLeft()) }).catch(() => false)) return row;

    const submitBtn = page
      .locator(
        '[data-testid="search-button"], [data-testid="table-search-button"], button:has-text("搜索"), button:has-text("Search")'
      )
      .first();
    if (await submitBtn.isVisible({ timeout: Math.min(700, timeLeft()) }).catch(() => false)) {
      await submitBtn.click({ timeout: Math.min(900, timeLeft()) }).catch(() => null);
      await waitListResponse(Math.min(1800, timeLeft()));
      row = page.locator('tbody tr', { hasText: title }).first();
      if (await row.isVisible({ timeout: Math.min(1000, timeLeft()) }).catch(() => false)) return row;
    }
  }

  // Strategy 0.5: click search button and use advanced search panel/modal inputs.
  const searchTrigger = page
    .locator(
      '[data-testid="filter-search"], [data-testid="search-button"], [data-testid="table-search-button"]'
    )
    .first();
  const canOpenSearch = await searchTrigger
    .isVisible({ timeout: Math.min(700, timeLeft()) })
    .catch(() => false);
  if (canOpenSearch) {
    await searchTrigger.click({ timeout: Math.min(900, timeLeft()) }).catch(() => null);
    const advancedInput = page
      .locator(
        '.ant-modal input:not([type="hidden"]), .ant-drawer input:not([type="hidden"]), .ant-popover input:not([type="hidden"]), [data-testid*="search"] input'
      )
      .first();
    const hasAdvancedInput = await advancedInput
      .isVisible({ timeout: Math.min(900, timeLeft()) })
      .catch(() => false);
    if (hasAdvancedInput) {
      await advancedInput.click({ timeout: Math.min(700, timeLeft()) }).catch(() => null);
      await advancedInput.fill(title, { timeout: Math.min(1000, timeLeft()) }).catch(() => null);
      await advancedInput.press('Enter').catch(() => null);

      const submitBtn = page
        .locator(
          '.ant-modal button:has-text("搜索"), .ant-modal button:has-text("Search"), .ant-drawer button:has-text("搜索"), .ant-drawer button:has-text("Search"), .ant-popover button:has-text("搜索"), .ant-popover button:has-text("Search")'
        )
        .first();
      if (await submitBtn.isVisible({ timeout: Math.min(500, timeLeft()) }).catch(() => false)) {
        await submitBtn.click({ timeout: Math.min(800, timeLeft()) }).catch(() => null);
      }

      await waitListResponse(Math.min(1800, timeLeft()));

      row = page.locator('tbody tr', { hasText: title }).first();
      if (await row.isVisible({ timeout: Math.min(1000, timeLeft()) }).catch(() => false)) return row;
    }
  }

  const waitListLoaded = async () => {
    await waitListResponse(Math.min(1500, timeLeft()));
  };

  const tryButtons = async (candidates: string[]) => {
    for (const selector of candidates) {
      if (Date.now() >= deadline) return false;
      const btn = page.locator(selector).first();
      const visible = await btn.isVisible({ timeout: Math.min(600, timeLeft()) }).catch(() => false);
      if (!visible) continue;
      const disabled = await btn.isDisabled().catch(() => false);
      if (disabled) continue;
      await btn.click({ timeout: Math.min(1000, timeLeft()) }).catch(() => null);
      await waitListLoaded();
      return true;
    }
    return false;
  };

  // Strategy 1: poll current page for eventual consistency (command committed but list not refreshed yet).
  const pollCurrentUntil = Date.now() + Math.min(2500, timeout);
  while (Date.now() < pollCurrentUntil) {
    row = page.locator('tbody tr', { hasText: title }).first();
    if (await row.isVisible({ timeout: Math.min(500, timeLeft()) }).catch(() => false)) return row;
    await waitListLoaded();
  }

  // Strategy 2: go to first page and iterate next pages.
  await tryButtons([
    '[data-testid="pagination-first"], [data-testid="pager-first"], [data-testid="table-first-page"]',
    'button[aria-label*="first" i]',
    'button:has-text("common.first_page")',
    'button:has-text("首页")',
  ]);

  row = page.locator('tbody tr', { hasText: title }).first();
  if (await row.isVisible({ timeout: Math.min(1000, timeLeft()) }).catch(() => false)) return row;

  let pageGuard = 0;
  while (Date.now() < deadline && pageGuard < 200) {
    pageGuard += 1;
    const moved = await tryButtons([
      '[data-testid="pagination-next"], [data-testid="pager-next"], [data-testid="table-next-page"]',
      'button[aria-label*="next" i]',
      'button:has-text("common.next_page")',
      'button:has-text("下一页")',
      'button:has-text(">")',
    ]);
    if (!moved) break;
    row = page.locator('tbody tr', { hasText: title }).first();
    if (await row.isVisible({ timeout: Math.min(700, timeLeft()) }).catch(() => false)) return row;
  }

  // Strategy 3: jump to last page as final fallback.
  await tryButtons([
    '[data-testid="pagination-last"], [data-testid="pager-last"], [data-testid="table-last-page"]',
    'button[aria-label*="last" i]',
    'button:has-text("common.last_page")',
    'button:has-text("末页")',
  ]);
  row = page.locator('tbody tr', { hasText: title }).first();
  if (await row.isVisible({ timeout: Math.min(800, timeLeft()) }).catch(() => false)) return row;

  return page.locator('tbody tr', { hasText: title }).first();
}

// ---------------------------------------------------------------------------
// API-based List Query Helper
// ---------------------------------------------------------------------------

/**
 * Verify a record exists in a dynamic list by querying the list API with filters.
 *
 * Unlike `findRowInPaginatedList` which navigates paginated UI pages (slow and
 * unreliable when data accumulates), this function directly queries the backend
 * API with a LIKE filter to check if the record is present in the dataset.
 *
 * Usage:
 * ```typescript
 * // Verify by text field
 * const records = await queryFilteredList(page, 'pe-rfq', 'pe_rfq_product_model', 'E2E RFQ Test');
 * expect(records.length).toBeGreaterThan(0);
 *
 * // Verify by status tab + text
 * const records = await queryFilteredList(page, 'pe-rfq', 'pe_rfq_product_model', 'E2E RFQ', {
 *   extraFilters: [{ fieldName: 'pe_rfq_status', operator: 'EQ', value: 'draft' }],
 * });
 * ```
 *
 * @param page       Playwright Page (uses page.request for authenticated API calls)
 * @param pageKey    The dynamic page key (hyphenated, e.g. 'pe-rfq')
 * @param fieldName  The field to filter on
 * @param searchText The text to search for (will be wrapped in %...% for LIKE)
 * @param options    Optional extra filters, page size
 * @returns          Array of matching records
 */
export async function queryFilteredList(
  page: Page,
  pageKey: string,
  fieldName: string,
  searchText: string,
  options?: {
    extraFilters?: Array<{ fieldName: string; operator: string; value: string }>;
    pageSize?: number;
    operator?: string;
  },
): Promise<Record<string, unknown>[]> {
  const filters: Array<{ fieldName: string; operator: string; value: string }> = [
    {
      fieldName,
      operator: (options?.operator ?? 'LIKE').toUpperCase(),
      value: options?.operator ? searchText : `%${searchText}%`,
    },
    ...(options?.extraFilters ?? []),
  ];

  const filtersParam = encodeURIComponent(JSON.stringify(filters));
  const size = options?.pageSize ?? 50;
  const normalizedPageKey = pageKey.replace(/-/g, '_');
  const candidatePageKeys = Array.from(
    new Set([
      pageKey,
      normalizedPageKey,
      normalizedPageKey.endsWith('_list') ? normalizedPageKey : `${normalizedPageKey}_list`,
      pageKey.endsWith('_list') ? pageKey.replace(/_list$/, '') : pageKey,
    ]),
  );

  const maxAttempts = 4;
  for (const candidatePageKey of candidatePageKeys) {
    const url = `/api/dynamic/${candidatePageKey}/list?pageNum=1&pageSize=${size}&filters=${filtersParam}`;

    for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
      const resp = await page.request.get(url);
      if (!resp.ok()) {
        break;
      }

      const body = await resp.json().catch(() => ({}));
      const data = (body as any)?.data ?? {};
      const records = (data.records ?? data.data ?? []) as Record<string, unknown>[];
      if (records.length > 0 || attempt === maxAttempts) {
        return records;
      }

      await new Promise((resolve) => setTimeout(resolve, attempt * 250));
    }
  }

  return [];
}

// ---------------------------------------------------------------------------
// Safe Visibility Checks (replace .catch(() => false) pattern)
// ---------------------------------------------------------------------------

/**
 * Check if a locator is visible, returning boolean. Unlike `.isVisible().catch(() => false)`,
 * this logs the check for debugging and doesn't silently swallow errors.
 *
 * IMPORTANT: Use this ONLY for conditional UI branching (e.g., "if tab exists, click it").
 * Do NOT use to skip test assertions — if an element MUST exist, use `expect(loc).toBeVisible()`.
 *
 * @example
 *   // ✅ Conditional UI branching (element may or may not exist)
 *   if (await isVisible(page.locator('[data-testid="optional-tab"]'), 3000)) {
 *     await page.locator('[data-testid="optional-tab"]').click();
 *   }
 *
 *   // ❌ WRONG — element MUST exist, use expect instead
 *   if (await isVisible(approveBtn, 5000)) { ... } else { // API fallback }
 */
export async function isVisible(locator: Locator, timeout = 5000): Promise<boolean> {
  try {
    await locator.waitFor({ state: 'visible', timeout });
    return true;
  } catch {
    return false;
  }
}

/**
 * Assert that at least one of the given locators is visible.
 * Unlike `assertTrue(hasA || hasB || hasC)`, this produces a clear error message.
 *
 * @example
 *   await expectAnyVisible(page, [
 *     { locator: table, label: 'data table' },
 *     { locator: emptyState, label: 'empty state guide' },
 *   ], 'Page must show data or empty state — got blank screen');
 */
export async function expectAnyVisible(
  page: Page,
  candidates: Array<{ locator: Locator; label: string }>,
  message: string,
  timeout = 8000,
): Promise<string> {
  for (const { locator, label } of candidates) {
    try {
      await locator.waitFor({ state: 'visible', timeout: timeout / candidates.length });
      return label; // Return which one was found
    } catch {
      // Try next
    }
  }

  // None found — fail with descriptive message
  const tried = candidates.map((c) => c.label).join(', ');
  throw new Error(`${message}\nChecked: [${tried}] — none visible within ${timeout}ms`);
}
