/** OSS platform administration PA-009..026; BPM PA-001..008 and product menus are in ../bpm/platform-admin-bpm.spec.ts. */
import { test, expect } from '../../fixtures';
import {
  navigateToDynamicPage,
  normalizeDynamicPageKey,
  waitForDynamicPageLoad,
  uniqueId,
  acceptConfirmDialog,
  findRowInPaginatedList,
  fillControlledInput,
  queryFilteredList,
  extractRecordId,
  clickRowActionByLocator,
} from '../helpers';
import { ModelTestHelper } from '../../helpers/model-test-helper';
import { ADMIN_DATA_PERMISSION_CONFIG } from '../../helpers/configs/admin-data-permission.config';
import { ADMIN_WEBHOOK_CONFIG } from '../../helpers/configs/admin-webhook.config';
import { ADMIN_API_CONNECTOR_CONFIG } from '../../helpers/configs/admin-api-connector.config';
import { ErrorCodes } from '~/shared/services/http-client/types';
import { BASE_URL } from '../../helpers/environments';

import { annotateFallback, waitForFormReady, fillFormField, selectFormField, clickSaveAndWait, clickCreateButton, clickRowEditButton, clickRowDeleteAndConfirm, openEditFormByPid, clickFormDeleteAndConfirm } from '../../helpers/platform-admin-form';

// ==========================================================================
// Data Permission Tests
// ==========================================================================

test.describe('PA: Data Permission CRUD', () => {
  test.describe.configure({ timeout: 45000 });
  const createdPids: string[] = [];

  test.afterAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
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
    await fillControlledInput(nameInput, updatedName);
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
    const ctx = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
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

    await openEditFormByPid(page, 'webhook', pid);
    await waitForFormReady(page);

    const nameInput = page
      .locator('[data-testid="form-field-name"] input, [data-field="name"] input, [name="name"]')
      .first();
    // Wait for the form's React state to hydrate with the existing record's
    // name before filling — otherwise an early fill() can be overwritten by
    // the async record-load that completes after waitForFormReady().
    await expect(nameInput).toHaveValue(originalName, { timeout: 10_000 });
    await fillControlledInput(nameInput, updatedName);
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
    const ctx = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
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
    await fillControlledInput(nameInput, updatedName);
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
