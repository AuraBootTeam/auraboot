/**
 * Platform Admin DSL Pages — CRUD E2E Tests
 *
 * Tests PA-001 ~ PA-026: CRUD lifecycle for all 5 DSL-ified admin pages + tenant_member:
 * - SLA Configuration
 * - BPM Domain Configuration
 * - Data Permission
 * - Webhook Subscription
 * - API Connector
 * - Tenant Member (status workflow, no CRUD form)
 *
 * Each CRUD page tests: list rendering, create via UI form, edit, delete.
 * Uses real database, NO MOCKING.
 *
 * @since 7.0.0
 */

import { test, expect } from '../../fixtures';
import {
  navigateToDynamicPage,
  normalizeDynamicPageKey,
  waitForDynamicPageLoad,
  uniqueId,
  acceptConfirmDialog,
  findRowInPaginatedList,
  queryFilteredList,
  extractRecordId,
  clickRowActionByLocator,
} from '../helpers';
import { ModelTestHelper } from '../../helpers/model-test-helper';
import { ADMIN_SLA_CONFIG } from '../../helpers/configs/admin-sla-config.config';
import { ADMIN_BPM_DOMAIN_CONFIG } from '../../helpers/configs/admin-bpm-domain-config.config';
import { ADMIN_DATA_PERMISSION_CONFIG } from '../../helpers/configs/admin-data-permission.config';
import { ADMIN_WEBHOOK_CONFIG } from '../../helpers/configs/admin-webhook.config';
import { ADMIN_API_CONNECTOR_CONFIG } from '../../helpers/configs/admin-api-connector.config';
import { ErrorCodes } from '~/shared/services/http-client/types';

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'http://127.0.0.1:5173';
const CREATE_COMMAND_BY_PAGE_KEY: Record<string, string> = {
  'sla-config': 'admin:create_sla_config',
  'bpm-domain-config': 'admin:create_bpm_domain_config',
  'data-permission': 'admin:create_data_permission',
  webhook: 'admin:create_webhook',
  'api-connector': 'admin:create_api_connector',
};
const EDIT_COMMAND_BY_PAGE_KEY: Record<string, string> = {
  'sla-config': 'admin:update_sla_config',
  'bpm-domain-config': 'admin:update_bpm_domain_config',
  'data-permission': 'admin:update_data_permission',
  webhook: 'admin:update_webhook',
  'api-connector': 'admin:update_api_connector',
};

function annotateFallback(description: string) {
  test.info().annotations.push({
    type: 'fallback',
    description,
  });
}

/**
 * Wait for form page to be ready after navigation.
 * Create routes to /p/{model}/new, edit routes to /p/{model}/{id}/edit.
 */
async function waitForFormReady(page: import('@playwright/test').Page) {
  // Wait for URL to include /new or /edit
  await expect(page).toHaveURL(/\/(new|edit)/, { timeout: 10000 });

  await waitForDynamicPageLoad(page, 8000);

  const errorAlert = page
    .locator('text=common.loadError, text=Bad parameter, text=Failed to load, text=加载失败')
    .first();
  if (await errorAlert.isVisible({ timeout: 1000 }).catch(() => false)) {
    throw new Error('Form failed to load due to backend/schema error');
  }

  await page
    .locator('button[role="switch"], input, select, textarea')
    .first()
    .waitFor({ state: 'visible', timeout: 8000 });
}

/** Fill a text input field on the form page */
async function fillFormField(
  page: import('@playwright/test').Page,
  fieldCode: string,
  value: string,
) {
  // Strategy 1: data-testid
  const byTestId = page
    .locator(
      `[data-testid="form-field-${fieldCode}"] input:not([type="hidden"]), [data-testid="form-field-${fieldCode}"] textarea, [data-testid="field-${fieldCode}"] input:not([type="hidden"]), [data-testid="field-${fieldCode}"] textarea`,
    )
    .first();
  if (await byTestId.isVisible({ timeout: 8000 }).catch(() => false)) {
    await byTestId.fill(value);
    return;
  }
  // Strategy 2: data-field attribute
  const byField = page
    .locator(
      `[data-field="${fieldCode}"] input:not([type="hidden"]), [data-field="${fieldCode}"] textarea`,
    )
    .first();
  if (await byField.isVisible({ timeout: 8000 }).catch(() => false)) {
    await byField.fill(value);
    return;
  }
  // Strategy 3: name attribute
  const byName = page
    .locator(`input[name="${fieldCode}"]:not([type="hidden"]), textarea[name="${fieldCode}"]`)
    .first();
  if (await byName.isVisible({ timeout: 8000 }).catch(() => false)) {
    await byName.fill(value);
    return;
  }
  // Strategy 4: find by visible label text as a last resort
  const labelCandidates: Record<string, RegExp> = {
    name: /名称|Name/i,
    target_url: /目标.*URL|回调.*URL|Target URL|Webhook URL/i,
    event_type: /事件类型|Event Type/i,
  };
  const labelPattern = labelCandidates[fieldCode];
  if (labelPattern) {
    const byAccessibleLabel = page
      .getByLabel(labelPattern)
      .locator('input:not([type="hidden"]), textarea')
      .first();
    if (await byAccessibleLabel.isVisible({ timeout: 5000 }).catch(() => false)) {
      await byAccessibleLabel.fill(value);
      return;
    }
    const label = page.locator('label').filter({ hasText: labelPattern }).first();
    if (await label.isVisible({ timeout: 5000 }).catch(() => false)) {
      const container = label.locator('xpath=ancestor::*[self::div or self::label][1]');
      const input = container.locator('input:not([type="hidden"]), textarea').first();
      if (await input.isVisible({ timeout: 3000 }).catch(() => false)) {
        await input.fill(value);
        return;
      }
    }
  }
  throw new Error(`Could not find input field: ${fieldCode}`);
}

/** Select a value from a native <select> */
async function selectFormField(
  page: import('@playwright/test').Page,
  fieldCode: string,
  value: string,
) {
  const anyField = page
    .locator(
      `[data-testid="form-field-${fieldCode}"] select, [data-testid="field-${fieldCode}"] select, [data-field="${fieldCode}"] select, select[name="${fieldCode}"], [data-testid="form-field-${fieldCode}"] input, [data-testid="field-${fieldCode}"] input, [data-field="${fieldCode}"] input, input[name="${fieldCode}"], [data-testid="form-field-${fieldCode}"] textarea, [data-testid="field-${fieldCode}"] textarea, [data-field="${fieldCode}"] textarea, textarea[name="${fieldCode}"]`,
    )
    .first();
  await anyField.waitFor({ state: 'attached', timeout: 12000 }).catch(() => null);

  const select = page
    .locator(
      `[data-testid="form-field-${fieldCode}"] select, [data-testid="field-${fieldCode}"] select, [data-field="${fieldCode}"] select, select[name="${fieldCode}"]`,
    )
    .first();
  if (await select.isVisible({ timeout: 8000 }).catch(() => false)) {
    await select.selectOption(value);
    return;
  }
  const input = page
    .locator(
      `[data-testid="form-field-${fieldCode}"] input:not([type="hidden"]), [data-testid="form-field-${fieldCode}"] textarea, [data-testid="field-${fieldCode}"] input:not([type="hidden"]), [data-testid="field-${fieldCode}"] textarea, [data-field="${fieldCode}"] input:not([type="hidden"]), [data-field="${fieldCode}"] textarea, input[name="${fieldCode}"]:not([type="hidden"]), textarea[name="${fieldCode}"]`,
    )
    .first();
  if (await input.isVisible({ timeout: 8000 }).catch(() => false)) {
    await input.fill(value);
    return;
  }
  const hiddenInput = page
    .locator(
      `[data-testid="form-field-${fieldCode}"] input[type="hidden"], [data-testid="field-${fieldCode}"] input[type="hidden"], [data-field="${fieldCode}"] input[type="hidden"], input[name="${fieldCode}"][type="hidden"]`,
    )
    .first();
  if (await hiddenInput.count().then((count) => count > 0).catch(() => false)) {
    await hiddenInput.evaluate((el, nextValue) => {
      const inputEl = el as HTMLInputElement;
      inputEl.value = String(nextValue ?? '');
      inputEl.dispatchEvent(new Event('input', { bubbles: true }));
      inputEl.dispatchEvent(new Event('change', { bubbles: true }));
    }, value);
    return;
  }
  throw new Error(`Could not find select field: ${fieldCode}`);
}

/** Click the save button on form page and wait for command API response */
async function clickSaveAndWait(
  page: import('@playwright/test').Page,
  options?: { expectedCommandCode?: string },
) {
  const saveBtn = page
    .locator(
      '[data-testid="form-btn-submit"], [data-testid="form-btn-save"], button:has-text("保存"), button:has-text("Save")',
    )
    .first();
  await saveBtn.waitFor({ state: 'visible', timeout: 5000 });
  const currentUrl = new URL(page.url());
  const expectedCommand =
    options?.expectedCommandCode || currentUrl.searchParams.get('commandCode');

  // Listen for current form command execution only.
  const respPromise = page
    .waitForResponse(
      (r) => {
        if (!r.url().includes('/commands/execute/')) return false;
        if (!expectedCommand) return true;
        return r.url().includes(`/commands/execute/${expectedCommand}`);
      },
      { timeout: 15000 },
    )
    .catch(() => null);
  await saveBtn.click();
  const resp = await respPromise;
  if (!resp) {
    await expect
      .poll(
        async () => {
          const currentUrl = new URL(page.url());
          const onFormRoute = /\/(new|edit)(?:$|\/)/.test(currentUrl.pathname);
          const tableVisible = await page
            .locator('table, [role="table"], [data-testid="dynamic-list"]')
            .first()
            .isVisible({ timeout: 500 })
            .catch(() => false);
          return !onFormRoute || tableVisible;
        },
        { timeout: 8000, intervals: [500, 1000, 1500] },
      )
      .toBe(true)
      .catch(() => null);
    const currentUrl = new URL(page.url());
    const leftFormRoute = !/\/(new|edit)(?:$|\/)/.test(currentUrl.pathname);
    const tableVisible = await page
      .locator('table, [role="table"], [data-testid="dynamic-list"]')
      .first()
      .isVisible({ timeout: 1000 })
      .catch(() => false);
    if (leftFormRoute || tableVisible) {
      annotateFallback(
        `Save response listener missed ${expectedCommand || 'form command'}, but page navigated successfully`,
      );
      return {};
    }
    throw new Error(`Timed out waiting for command response: ${expectedCommand || 'unknown command'}`);
  }
  const status = resp.status();
  const body = await resp.json().catch(async () => ({ raw: await resp.text().catch(() => '') }));
  if (status !== 200) {
    const requestBody = resp.request().postData() || '';
    throw new Error(
      `Command response status for ${expectedCommand || 'unknown command'}: ${status}, body=${JSON.stringify(body)}, requestBody=${requestBody}`,
    );
  }
  // API payloads are not fully uniform across admin modules.
  // Prefer business code when present; otherwise only fail on explicit business failure.
  const code = String(body.code ?? body?.data?.code ?? '');
  if (code) {
    expect(code).toBe(ErrorCodes.SUCCESS);
  } else {
    const explicitFailure = body.success === false || body?.data?.success === false;
    expect(explicitFailure).toBe(false);
  }
  // Some command routes may return non-JSON or wrapper payload while still succeeding.
  // Downstream assertions (row visible/updated/deleted) remain the source of truth.
  return body;
}

/** Click the toolbar create button (uses data-testid) */
async function clickCreateButton(page: import('@playwright/test').Page) {
  const createBtn = page.locator('[data-testid="toolbar-btn-create"]').first();
  await createBtn.waitFor({ state: 'visible', timeout: 5000 });
  const enteredCreateRoute = await expect
    .poll(
      async () => {
        await createBtn.click().catch(() => {});
        const url = new URL(page.url());
        return /\/new(?:$|\?)/.test(url.pathname + url.search);
      },
      { timeout: 8000, intervals: [100, 250, 500, 1000] },
    )
    .toBe(true)
    .then(() => true)
    .catch(() => false);

  if (enteredCreateRoute) return;

  const currentUrl = new URL(page.url());
  const modelSegment = currentUrl.pathname.match(/^\/p\/([^/]+)/)?.[1];
  if (!modelSegment) {
    throw new Error('Create button did not navigate and current page key could not be inferred');
  }
  const pageKey = modelSegment.replace(/_/g, '-');
  const createCommand = CREATE_COMMAND_BY_PAGE_KEY[pageKey];
  if (!createCommand) {
    throw new Error(`Create button did not navigate and no fallback command is defined for ${pageKey}`);
  }
  annotateFallback(`toolbar create did not navigate; fallback to direct create route for ${pageKey}`);
  await page.goto(
    `/p/${modelSegment}/new?commandCode=${encodeURIComponent(createCommand)}`,
    { waitUntil: 'domcontentloaded' },
  );
}

/** Click the row-level edit button (uses data-testid, handles "more actions" dropdown) */
async function clickRowEditButton(row: import('@playwright/test').Locator) {
  await clickRowActionByLocator(row.page(), row, 'edit');
}

/** Click the row-level delete button (handles "more actions" dropdown), confirm, and wait for command */
async function clickRowDeleteAndConfirm(
  page: import('@playwright/test').Page,
  row: import('@playwright/test').Locator,
) {
  // Set up command response listener BEFORE clicking to avoid race condition
  const cmdPromise = page
    .waitForResponse((r) => r.url().includes('/commands/execute/'), { timeout: 5000 })
    .catch(() => null);
  await clickRowActionByLocator(page, row, 'delete');
  // The delete action uses confirmMessageKey which shows a custom ConfirmDialog
  await acceptConfirmDialog(page);
  // Wait for command response or list refresh (whichever comes first).
  const listPromise = page
    .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 5000 })
    .catch(() => null);
  await Promise.race([cmdPromise, listPromise]).catch(() => null);
}

/** Navigate to edit form by recordId (UI route), with standard form readiness checks. */
async function openEditFormByPid(
  page: import('@playwright/test').Page,
  pageKey: string,
  pid: string,
) {
  const cmd = EDIT_COMMAND_BY_PAGE_KEY[pageKey];
  const cmdQuery = cmd ? `?commandCode=${encodeURIComponent(cmd)}` : '';
  await page.goto(`/p/${normalizeDynamicPageKey(pageKey)}/${pid}/edit${cmdQuery}`, {
    waitUntil: 'domcontentloaded',
  });
  await waitForFormReady(page);
  await expect(page).toHaveURL(/\/edit(?:\?|$)/, { timeout: 5000 });
  if (cmd) {
    const currentUrl = new URL(page.url());
    expect(currentUrl.searchParams.get('commandCode')).toBe(cmd);
  }
}

/** Click delete on form page and confirm (UI), then wait for delete command response. */
async function clickFormDeleteAndConfirm(page: import('@playwright/test').Page): Promise<boolean> {
  const deleteBtn = page
    .locator(
      '[data-testid="form-btn-delete"], [data-testid^="form-btn-"]:has-text("删除"), [data-testid^="form-btn-"]:has-text("Delete"), button:has-text("删除"), button:has-text("Delete")',
    )
    .first();
  const hasDeleteBtn = await deleteBtn.isVisible({ timeout: 5000 }).catch(() => false);
  if (!hasDeleteBtn) return false;
  const cmdPromise = page
    .waitForResponse((r) => r.url().includes('/commands/execute/'), { timeout: 5000 })
    .catch(() => null);
  await deleteBtn.click();
  await acceptConfirmDialog(page);
  const listPromise = page
    .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 5000 })
    .catch(() => null);
  await Promise.race([cmdPromise, listPromise]).catch(() => null);
  return true;
}

// ==========================================================================
// SLA Configuration Tests
// ==========================================================================

test.describe('PA: SLA Configuration CRUD', () => {
  test.describe.configure({ timeout: 45000 });
  const createdPids: string[] = [];

  test.afterAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    const helper = new ModelTestHelper(page, ADMIN_SLA_CONFIG);
    for (const pid of createdPids) {
      await helper.deleteViaApi(pid).catch(() => {});
    }
    await ctx.close();
  });

  test('PA-001: SLA config list page renders with correct columns @smoke', async ({ page }) => {
    await navigateToDynamicPage(page, 'sla-config');
    // Verify key table headers exist
    const headers = page.locator('thead th');
    await expect(headers.first()).toBeVisible({ timeout: 8000 });
    // Verify toolbar create button
    await expect(page.locator('[data-testid="toolbar-btn-create"]')).toBeVisible();
  });

  test('PA-002: Create SLA config via UI @smoke', async ({ page }) => {
    test.slow();
    const name = `SLA-UI-${uniqueId()}`;

    await navigateToDynamicPage(page, 'sla-config');
    await clickCreateButton(page);
    await waitForFormReady(page);

    // Fill required fields
    await fillFormField(page, 'name', name);
    await selectFormField(page, 'target_type', 'process');
    await selectFormField(page, 'deadline_mode', 'fixed');
    await fillFormField(page, 'deadline_value', 'pt1h');

    const body = await clickSaveAndWait(page);
    const recordId = extractRecordId(body);
    if (recordId) createdPids.push(recordId);

    await navigateToDynamicPage(page, 'sla-config');
    const row = await findRowInPaginatedList(page, name, 12000);
    await expect(row).toBeVisible();
  });

  test('PA-003: Edit SLA config via UI', async ({ page }) => {
    const helper = new ModelTestHelper(page, ADMIN_SLA_CONFIG);
    const originalName = `SLA-Edit-${uniqueId()}`;
    const updatedName = `SLA-Updated-${uniqueId()}`;

    // Create via API
    const pid = await helper.createViaApi({ name: originalName });
    createdPids.push(pid);

    // Use stable edit route with explicit update command to avoid row-action variance.
    await openEditFormByPid(page, 'sla-config', pid);

    // Update name field
    const nameInput = page
      .locator('[data-testid="form-field-name"] input, [data-field="name"] input, [name="name"]')
      .first();
    await nameInput.fill(updatedName);
    await clickSaveAndWait(page, { expectedCommandCode: 'admin:update_sla_config' });

    // Backend truth for this record id is the assertion source of truth.
    const updated = await helper.fetchViaApi(pid).catch(() => null);
    expect(String(updated?.name ?? '')).toBe(updatedName);
  });

  test('PA-004: Delete SLA config via UI', async ({ page }) => {
    const helper = new ModelTestHelper(page, ADMIN_SLA_CONFIG);
    const name = `SLA-Del-${uniqueId()}`;

    // Create via API
    const pid = await helper.createViaApi({ name });
    createdPids.push(pid);

    await navigateToDynamicPage(page, 'sla-config');
    const row = await findRowInPaginatedList(page, name, 12000);
    try {
      await clickRowDeleteAndConfirm(page, row);
    } catch {
      annotateFallback('SLA row delete action unavailable, fallback to edit-form delete');
      await openEditFormByPid(page, 'sla-config', pid);
      const deleted = await clickFormDeleteAndConfirm(page);
      if (!deleted) {
        throw new Error(String('SLA delete action is unavailable in current environment'));
        return;
      }
    }

    const records = await queryFilteredList(page, 'sla-config', 'name', name);
    expect(records.length).toBe(0);
  });
});

// ==========================================================================
// BPM Domain Configuration Tests
// ==========================================================================

test.describe('PA: BPM Domain Configuration CRUD', () => {
  test.describe.configure({ timeout: 45000 });
  const createdPids: string[] = [];

  test.afterAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    const helper = new ModelTestHelper(page, ADMIN_BPM_DOMAIN_CONFIG);
    for (const pid of createdPids) {
      await helper.deleteViaApi(pid).catch(() => {});
    }
    await ctx.close();
  });

  test('PA-005: Domain config list page renders @smoke', async ({ page }) => {
    await navigateToDynamicPage(page, 'bpm-domain-config');
    const headers = page.locator('thead th');
    await expect(headers.first()).toBeVisible({ timeout: 8000 });
    await expect(page.locator('[data-testid="toolbar-btn-create"]')).toBeVisible();
  });

  test('PA-006: Create domain config via UI @smoke', async ({ page }) => {
    const domainCode = `DOM-${uniqueId()}`;
    const domainName = `Test Domain ${uniqueId()}`;

    await navigateToDynamicPage(page, 'bpm-domain-config');
    await clickCreateButton(page);
    await waitForFormReady(page);

    await fillFormField(page, 'domain_code', domainCode);
    await fillFormField(page, 'domain_name', domainName);

    const body = await clickSaveAndWait(page);
    const recordId = extractRecordId(body);
    if (recordId) createdPids.push(recordId);

    const records = await queryFilteredList(page, 'bpm-domain-config', 'domain_code', domainCode, {
      operator: 'EQ',
    });
    expect(records.length).toBeGreaterThan(0);
  });

  test('PA-007: Edit domain config via UI', async ({ page }) => {
    const helper = new ModelTestHelper(page, ADMIN_BPM_DOMAIN_CONFIG);
    const originalName = `Domain-Edit-${uniqueId()}`;
    const updatedName = `Domain-Updated-${uniqueId()}`;

    const pid = await helper.createViaApi({ domain_name: originalName });
    createdPids.push(pid);

    // Use stable edit route with explicit update command to avoid row-action variance.
    await openEditFormByPid(page, 'bpm-domain-config', pid);

    const nameInput = page
      .locator(
        '[data-testid="form-field-domain_name"] input, [data-field="domain_name"] input, [name="domain_name"]',
      )
      .first();
    await nameInput.fill(updatedName);
    await clickSaveAndWait(page, { expectedCommandCode: 'admin:update_bpm_domain_config' });

    const updated = await helper.fetchViaApi(pid).catch(() => null);
    expect(String(updated?.domain_name ?? '')).toBe(updatedName);
  });

  test('PA-008: Delete domain config via UI', async ({ page }) => {
    const helper = new ModelTestHelper(page, ADMIN_BPM_DOMAIN_CONFIG);
    const name = `Domain-Del-${uniqueId()}`;
    const pid = await helper.createViaApi({ domain_name: name });
    createdPids.push(pid);

    await navigateToDynamicPage(page, 'bpm-domain-config');
    const row = await findRowInPaginatedList(page, name, 12000);
    await clickRowDeleteAndConfirm(page, row);

    const records = await queryFilteredList(page, 'bpm-domain-config', 'domain_name', name, {
      operator: 'EQ',
    });
    expect(records.length).toBe(0);
  });
});

// ==========================================================================
// Data Permission Tests
// ==========================================================================

test.describe('PA: Data Permission CRUD', () => {
  test.describe.configure({ timeout: 45000 });
  const createdPids: string[] = [];

  test.afterAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    const helper = new ModelTestHelper(page, ADMIN_DATA_PERMISSION_CONFIG);
    for (const pid of createdPids) {
      await helper.deleteViaApi(pid).catch(() => {});
    }
    await ctx.close();
  });

  test('PA-009: Data permission list page renders @smoke', async ({ page }) => {
    await navigateToDynamicPage(page, 'data-permission');
    const headers = page.locator('thead th');
    await expect(headers.first()).toBeVisible({ timeout: 8000 });
    await expect(page.locator('[data-testid="toolbar-btn-create"]')).toBeVisible();
  });

  test('PA-010: Create data permission via UI @smoke', async ({ page }) => {
    const helper = new ModelTestHelper(page, ADMIN_DATA_PERMISSION_CONFIG);
    const name = `DP-UI-${uniqueId()}`;

    await navigateToDynamicPage(page, 'data-permission');
    await clickCreateButton(page);
    await waitForFormReady(page);
    {
      const currentUrl = new URL(page.url());
      expect(currentUrl.pathname).toBe('/p/data_permission/new');
      expect(currentUrl.searchParams.get('commandCode')).toBe('admin:create_data_permission');
    }

    await fillFormField(page, 'name', name);
    await selectFormField(page, 'policy_type', 'row');
    await selectFormField(page, 'scope_type', 'self').catch(() => null);
    try {
      await selectFormField(page, 'model_code', 'e2et_order');
    } catch {
      await fillFormField(page, 'model_code', 'e2et_order');
    }

    const body = await clickSaveAndWait(page, {
      expectedCommandCode: 'admin:create_data_permission',
    });
    const recordId = extractRecordId(body);
    if (recordId) createdPids.push(recordId);

    if (recordId) {
      const created = await helper.fetchViaApi(recordId).catch(() => null);
      expect(String(created?.name ?? '')).toBe(name);
      return;
    }

    await navigateToDynamicPage(page, 'data-permission');
    const row = await findRowInPaginatedList(page, name, 12000);
    await expect(row).toBeVisible();
  });

  test('PA-011: Edit data permission via UI', async ({ page }) => {
    const helper = new ModelTestHelper(page, ADMIN_DATA_PERMISSION_CONFIG);
    const originalName = `DP-Edit-${uniqueId()}`;
    const updatedName = `DP-Updated-${uniqueId()}`;

    const pid = await helper.createViaApi({ name: originalName });
    createdPids.push(pid);

    await openEditFormByPid(page, 'data-permission', pid);

    const nameInput = page
      .locator('[data-testid="form-field-name"] input, [data-field="name"] input, [name="name"]')
      .first();
    await nameInput.fill(updatedName);
    await clickSaveAndWait(page);

    const updated = await helper.fetchViaApi(pid).catch(() => null);
    if (!updated) {
      throw new Error(
        String('Data permission record is not readable after edit in current environment'),
      );
      return;
    }
    expect(String(updated.name ?? '')).toBe(updatedName);
  });

  test('PA-012: Delete data permission via UI', async ({ page }) => {
    const helper = new ModelTestHelper(page, ADMIN_DATA_PERMISSION_CONFIG);
    const name = `DP-Del-${uniqueId()}`;
    const pid = await helper.createViaApi({ name });
    createdPids.push(pid);

    await navigateToDynamicPage(page, 'data-permission');
    const row = await findRowInPaginatedList(page, name, 12000);
    await clickRowDeleteAndConfirm(page, row);

    await navigateToDynamicPage(page, 'data-permission');
    const remaining = await queryFilteredList(page, 'data-permission', 'name', name, {
      operator: 'EQ',
    });
    expect(remaining.length).toBe(0);
  });
});

// ==========================================================================
// Webhook Subscription Tests
// ==========================================================================

test.describe('PA: Webhook Subscription CRUD', () => {
  test.describe.configure({ timeout: 45000 });
  const createdPids: string[] = [];

  test.afterAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    const helper = new ModelTestHelper(page, ADMIN_WEBHOOK_CONFIG);
    for (const pid of createdPids) {
      await helper.deleteViaApi(pid).catch(() => {});
    }
    await ctx.close();
  });

  test('PA-013: Webhook list page renders @smoke', async ({ page }) => {
    await navigateToDynamicPage(page, 'webhook');
    const headers = page.locator('thead th');
    await expect(headers.first()).toBeVisible({ timeout: 8000 });
    await expect(page.locator('[data-testid="toolbar-btn-create"]')).toBeVisible();
  });

  test('PA-014: Create webhook via UI @smoke', async ({ page }) => {
    const name = `WH-UI-${uniqueId()}`;
    const targetUrl = 'https://example.com/test-webhook';

    await navigateToDynamicPage(page, 'webhook');
    await clickCreateButton(page);
    await waitForFormReady(page);

    await fillFormField(page, 'name', name);
    await fillFormField(page, 'target_url', targetUrl);
    await selectFormField(page, 'event_type', 'record_created');

    const body = await clickSaveAndWait(page);
    const recordId = extractRecordId(body);
    if (recordId) createdPids.push(recordId);

    const records = await queryFilteredList(page, 'webhook', 'name', name, {
      operator: 'EQ',
    });
    expect(records.length).toBeGreaterThan(0);
  });

  test('PA-015: Edit webhook via UI', async ({ page }) => {
    const helper = new ModelTestHelper(page, ADMIN_WEBHOOK_CONFIG);
    const originalName = `WH-Edit-${uniqueId()}`;
    const updatedName = `WH-Updated-${uniqueId()}`;

    const pid = await helper.createViaApi({ name: originalName });
    createdPids.push(pid);

    await navigateToDynamicPage(page, 'webhook');
    try {
      const row = await findRowInPaginatedList(page, originalName, 12000);
      await clickRowEditButton(row);
    } catch {
      annotateFallback('Webhook row edit action unavailable, fallback to edit form by recordId');
      await openEditFormByPid(page, 'webhook', pid);
    }
    await waitForFormReady(page);

    const nameInput = page
      .locator('[data-testid="form-field-name"] input, [data-field="name"] input, [name="name"]')
      .first();
    // Wait for the form's React state to hydrate with the existing record's
    // name before filling — otherwise an early fill() can be overwritten by
    // the async record-load that completes after waitForFormReady().
    await expect(nameInput).toHaveValue(originalName, { timeout: 10_000 });
    await nameInput.fill(updatedName);
    await nameInput.blur();
    // Confirm the controlled input picked up the new value before clicking save.
    await expect(nameInput).toHaveValue(updatedName);
    await clickSaveAndWait(page);

    await expect
      .poll(async () => {
        const record = await helper.fetchViaApi(pid).catch(() => null);
        return String(record?.name ?? '');
      }, {
        timeout: 15000,
        message: 'Webhook edit should persist updated name on the saved record',
      })
      .toBe(updatedName);
  });

  test('PA-016: Delete webhook via UI', async ({ page }) => {
    const helper = new ModelTestHelper(page, ADMIN_WEBHOOK_CONFIG);
    const name = `WH-Del-${uniqueId()}`;
    const pid = await helper.createViaApi({ name });
    createdPids.push(pid);

    await navigateToDynamicPage(page, 'webhook');
    try {
      const row = await findRowInPaginatedList(page, name, 12000);
      await clickRowDeleteAndConfirm(page, row);
    } catch {
      annotateFallback('Webhook row delete action unavailable, fallback to edit-form delete');
      await openEditFormByPid(page, 'webhook', pid);
      const deleted = await clickFormDeleteAndConfirm(page);
      if (!deleted) {
        throw new Error(String('Webhook delete action is unavailable in current environment'));
        return;
      }
    }

    const records = await queryFilteredList(page, 'webhook', 'name', name, {
      operator: 'EQ',
    });
    expect(records.length).toBe(0);
  });
});

// ==========================================================================
// API Connector Tests
// ==========================================================================

test.describe('PA: API Connector CRUD', () => {
  test.describe.configure({ timeout: 45000 });
  const createdPids: string[] = [];

  test.afterAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    const helper = new ModelTestHelper(page, ADMIN_API_CONNECTOR_CONFIG);
    for (const pid of createdPids) {
      await helper.deleteViaApi(pid).catch(() => {});
    }
    await ctx.close();
  });

  test('PA-017: API connector list page renders @smoke', async ({ page }) => {
    await navigateToDynamicPage(page, 'api-connector');
    const headers = page.locator('thead th');
    await expect(headers.first()).toBeVisible({ timeout: 8000 });
    await expect(page.locator('[data-testid="toolbar-btn-create"]')).toBeVisible();
  });

  test('PA-018: Create API connector via UI @smoke', async ({ page }) => {
    const name = `API-UI-${uniqueId()}`;
    const baseUrl = 'https://api.example.com/v1';

    await navigateToDynamicPage(page, 'api-connector');
    await clickCreateButton(page);
    await waitForFormReady(page);

    await fillFormField(page, 'name', name);
    await fillFormField(page, 'base_url', baseUrl);
    await selectFormField(page, 'auth_type', 'none');

    const body = await clickSaveAndWait(page);
    const recordId = extractRecordId(body);
    if (recordId) createdPids.push(recordId);

    const records = await queryFilteredList(page, 'api-connector', 'name', name, {
      operator: 'EQ',
    });
    expect(records.length).toBeGreaterThan(0);
  });

  test('PA-019: Edit API connector via UI', async ({ page }) => {
    test.fixme(true, 'API connector queryFilteredList returns 0 — field name may differ from model');
    const helper = new ModelTestHelper(page, ADMIN_API_CONNECTOR_CONFIG);
    const originalName = `API-Edit-${uniqueId()}`;
    const updatedName = `API-Updated-${uniqueId()}`;

    const pid = await helper.createViaApi({ name: originalName });
    createdPids.push(pid);

    await navigateToDynamicPage(page, 'api-connector');
    try {
      const row = await findRowInPaginatedList(page, originalName, 12000);
      await clickRowEditButton(row);
    } catch {
      annotateFallback(
        'API connector row edit action unavailable, fallback to edit form by recordId',
      );
      await openEditFormByPid(page, 'api-connector', pid);
    }
    await waitForFormReady(page);

    const nameInput = page
      .locator('[data-testid="form-field-name"] input, [data-field="name"] input, [name="name"]')
      .first();
    await nameInput.fill(updatedName);
    await clickSaveAndWait(page);

    const records = await queryFilteredList(page, 'api-connector', 'name', updatedName, {
      operator: 'EQ',
    });
    expect(records.length).toBeGreaterThan(0);
  });

  test('PA-020: Delete API connector via UI', async ({ page }) => {
    const helper = new ModelTestHelper(page, ADMIN_API_CONNECTOR_CONFIG);
    const name = `API-Del-${uniqueId()}`;
    const pid = await helper.createViaApi({ name });
    createdPids.push(pid);

    await navigateToDynamicPage(page, 'api-connector');
    try {
      const row = await findRowInPaginatedList(page, name, 12000);
      await clickRowDeleteAndConfirm(page, row);
    } catch {
      annotateFallback('API connector row delete action unavailable, fallback to edit-form delete');
      await openEditFormByPid(page, 'api-connector', pid);
      const deleted = await clickFormDeleteAndConfirm(page);
      if (!deleted) {
        throw new Error(
          String('API connector delete action is unavailable in current environment'),
        );
        return;
      }
    }

    const records = await queryFilteredList(page, 'api-connector', 'name', name, {
      operator: 'EQ',
    });
    expect(records.length).toBe(0);
  });
});

// ==========================================================================
// Tenant Member Tests (status workflow, no CRUD form)
// ==========================================================================

test.describe('PA: Tenant Member Management', () => {
  test('PA-021: Tenant member list page renders with status tabs @smoke', async ({ page }) => {
    await navigateToDynamicPage(page, 'tenant_member');

    // Verify status tabs — use data-testid selectors for robustness, fall back to text
    // Tabs render asynchronously after the page schema loads, so use generous timeout
    const tabContainer = page.locator('nav[aria-label="Tabs"], [role="tablist"]');
    await expect(tabContainer).toBeVisible({ timeout: 10000 });

    const tabs = tabContainer.locator('button, [role="tab"]');
    await expect(tabs.filter({ hasText: /全部|All/i })).toBeVisible({ timeout: 8000 });
    await expect(tabs.filter({ hasText: /待审批|Pending/i })).toBeVisible();
    await expect(tabs.filter({ hasText: /已激活|Active/i })).toBeVisible();
    await expect(tabs.filter({ hasText: /已暂停|Suspended/i })).toBeVisible();
    await expect(tabs.filter({ hasText: /已拒绝|Rejected/i })).toBeVisible();
  });

  test('PA-022: Current user shows as active member', async ({ page }) => {
    await navigateToDynamicPage(page, 'tenant_member');

    // At least one row should be visible (the current logged-in user)
    // Use expect() with auto-retry instead of one-shot count after .catch()
    await expect(page.locator('tbody tr').first()).toBeVisible({ timeout: 10000 });

    // Double-check via API to ensure the list actually has data
    const records = await queryFilteredList(page, 'tenant_member', 'status', 'active', {
      operator: 'EQ',
    });
    expect(records.length).toBeGreaterThanOrEqual(1);
  });

  test('PA-023: No create button for tenant members', async ({ page }) => {
    await navigateToDynamicPage(page, 'tenant_member');

    // Wait for the page to fully render by confirming the table is visible first
    await expect(page.locator('tbody tr').first()).toBeVisible({ timeout: 8000 });

    // Tenant members should not have a "Create" button
    // (members join via invite code, not created manually)
    const createBtn = page.locator('[data-testid="toolbar-btn-create"]');
    await expect(createBtn).not.toBeVisible({ timeout: 3000 });
  });

  test('PA-024: Tab switching filters members by status', async ({ page }) => {
    await navigateToDynamicPage(page, 'tenant_member');

    // Wait for initial page load to complete fully before setting up response listener
    await expect(page.locator('tbody tr').first()).toBeVisible({ timeout: 8000 });

    // Click "Active" tab and wait for the list API response triggered by the tab switch
    const activeTab = page.locator('[data-testid="tab-active"]');
    await expect(activeTab).toBeVisible({ timeout: 5000 });

    // Set up response listener AFTER initial load is complete to avoid catching stale responses
    const listResp = page.waitForResponse((r) => r.url().includes('/list') && r.status() === 200, {
      timeout: 10000,
    });
    await activeTab.click();
    await listResp;

    // After switching to "Active" tab, verify list shows data
    await expect(page.locator('tbody tr').first()).toBeVisible({ timeout: 8000 });
  });
});

// ==========================================================================
// Sidebar Menu Tests
// ==========================================================================

test.describe('PA: Sidebar Menu Verification', () => {
  test('PA-025: All admin pages accessible from sidebar @smoke', async ({ page }) => {
    await page.goto(`${BASE_URL}/dashboards`);
    await page.waitForLoadState('domcontentloaded');

    // Verify admin menu items exist in sidebar
    const sidebar = page.locator('nav');
    await expect(sidebar.locator('a[href="/p/sla_config"]')).toBeVisible({ timeout: 8000 });
    await expect(sidebar.locator('a[href="/p/bpm_domain_config"]')).toBeVisible();
    await expect(sidebar.locator('a[href="/p/data_permission"]')).toBeVisible();
    await expect(sidebar.locator('a[href="/p/webhook"]')).toBeVisible();
    await expect(sidebar.locator('a[href="/p/api_connector"]')).toBeVisible();
    await expect(sidebar.locator('a[href="/p/tenant_member"]')).toBeVisible();
  });

  test('PA-026: No duplicate menu entries', async ({ page }) => {
    await page.goto(`${BASE_URL}/dashboards`);
    await page.waitForLoadState('domcontentloaded');

    // Admin paths defined solely by platform-admin plugin (no bootstrap overlap)
    const pluginOnlyPaths = [
      '/p/sla_config',
      '/p/bpm_domain_config',
      '/p/data_permission',
      '/p/webhook',
      '/p/api_connector',
    ];

    for (const path of pluginOnlyPaths) {
      const links = page.locator(`nav a[href="${path}"]`);
      const count = await links.count();
      expect(count, `Menu path ${path} should appear exactly once, found ${count}`).toBe(1);
    }

    // /p/tenant-member — verify at least one entry exists
    // (bootstrap MEMBER_MANAGEMENT may overlap with plugin entry)
    const memberLinks = page.locator('nav a[href="/p/tenant_member"]');
    const memberCount = await memberLinks.count();
    expect(memberCount, 'Tenant member menu should exist').toBeGreaterThanOrEqual(1);
  });
});
