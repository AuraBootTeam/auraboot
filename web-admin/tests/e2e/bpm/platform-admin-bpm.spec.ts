/** Independent aura-bpm contracts; requires its plugin runtime. Preserves PA-001..008. */
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
import { ADMIN_SLA_CONFIG } from '../../helpers/configs/admin-sla-config.config';
import { ADMIN_BPM_DOMAIN_CONFIG } from '../../helpers/configs/admin-bpm-domain-config.config';
import { ADMIN_DATA_PERMISSION_CONFIG } from '../../helpers/configs/admin-data-permission.config';
import { ADMIN_WEBHOOK_CONFIG } from '../../helpers/configs/admin-webhook.config';
import { ADMIN_API_CONNECTOR_CONFIG } from '../../helpers/configs/admin-api-connector.config';
import { ErrorCodes } from '~/shared/services/http-client/types';
import { BASE_URL } from '../../helpers/environments';

import { annotateFallback, waitForFormReady, fillFormField, selectFormField, clickSaveAndWait, clickCreateButton, clickRowEditButton, clickRowDeleteAndConfirm, openEditFormByPid, clickFormDeleteAndConfirm } from '../../helpers/platform-admin-form';

// ==========================================================================
// SLA Configuration Tests
// ==========================================================================

test.describe('PA: SLA Configuration CRUD', () => {
  test.describe.configure({ timeout: 45000 });
  const createdPids: string[] = [];

  test.afterAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
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
    await fillControlledInput(nameInput, updatedName);
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

    await expect
      .poll(
        async () => {
          const records = await queryFilteredList(page, 'sla-config', 'name', name);
          return records.length;
        },
        { timeout: 15000, intervals: [500, 1000, 1500] },
      )
      .toBe(0);
  });
});

// ==========================================================================
// BPM Domain Configuration Tests
// ==========================================================================

test.describe('PA: BPM Domain Configuration CRUD', () => {
  test.describe.configure({ timeout: 45000 });
  const createdPids: string[] = [];

  test.afterAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
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
    await waitForFormReady(page, ['domain_code', 'domain_name']);

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
    await fillControlledInput(nameInput, updatedName);
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


test.describe('BPM administration sidebar', () => {
  test('PA-BPM-MENU-01: both product administration entries are visible', async ({ page }) => {
    await page.goto(`${BASE_URL}/dashboards`);
    const sidebar = page.locator('nav');
    await expect(sidebar.locator('a[href="/p/sla_config"]')).toBeVisible();
    await expect(sidebar.locator('a[href="/p/bpm_domain_config"]')).toBeVisible();
  });
  test('PA-BPM-MENU-02: product administration entries are unique', async ({ page }) => {
    await page.goto(`${BASE_URL}/dashboards`);
    for (const path of ['/p/sla_config', '/p/bpm_domain_config']) {
      await expect(page.locator(`nav a[href="${path}"]`)).toHaveCount(1);
    }
  });
});
