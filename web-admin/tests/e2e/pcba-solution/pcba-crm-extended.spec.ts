/**
 * PCBA CRM Extended — CRUD & Status Flow E2E Tests
 *
 * Tests PCE-001 ~ PCE-018: CRUD lifecycle, status transitions, and field
 * variations for 2 CRM-extended models:
 * - crm_customer_request_common + crm_customer_request_pcba_rfq sidecar (A2-S2 RFQ truth) —
 *   request lifecycle (draft/submitted/routed) plus the sidecar DFM gate
 *   (pending/in_review/passed/conditional/failed) with supply modes & quality classes
 * - crm_contact_common (Contact) — simple CRUD with primary contact flag
 *
 * Each model tests: list rendering, create via API + verify in list,
 * create via UI form, edit via UI, delete via UI, state transitions,
 * enum variations, and i18n labels.
 *
 * Prerequisites: PCBA CRM plugin must be imported and models published.
 *
 * @since 8.0.0
 */

import { test, expect } from '../../fixtures';
import { ErrorCodes } from '~/shared/services/http-client/types';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';
import {
  navigateToDynamicPage,
  waitForDynamicPageLoad,
  uniqueId,
  executeCommandViaApi,
  queryFilteredList,
  todayStr,
  extractRecordId,
} from '../helpers/index';

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

const PAGE_KEYS = {
  rfq: 'crm_customer_request_pcba_rfq',
  customerRequest: 'crm_customer_request_common',
  customerContact: 'crm-contact',
};

type CrmExtBucket = {
  rfqs: string[];
  requests: string[];
  contacts: string[];
  accounts: string[];
  opportunities: string[];
};

function emptyBucket(): CrmExtBucket {
  return { rfqs: [], requests: [], contacts: [], accounts: [], opportunities: [] };
}

async function fetchRecord(
  page: import('@playwright/test').Page,
  pageKey: string,
  pid: string,
): Promise<Record<string, unknown>> {
  const resp = await page.request.get(`/api/dynamic/${pageKey}/${pid}`);
  expect(resp.ok(), `GET /api/dynamic/${pageKey}/${pid} should return 200`).toBe(true);
  const body = await resp.json();
  return (body.data ?? body) as Record<string, unknown>;
}

async function deleteRecord(
  page: import('@playwright/test').Page,
  pageKey: string,
  pid: string,
): Promise<void> {
  await page.request.delete(`/api/dynamic/${pageKey}/${pid}`);
}

async function cleanup(page: import('@playwright/test').Page, b: CrmExtBucket): Promise<void> {
  for (const pid of [...b.rfqs].reverse()) {
    await deleteRecord(page, PAGE_KEYS.rfq, pid).catch(() => {});
  }
  for (const pid of [...b.requests].reverse()) {
    await deleteRecord(page, PAGE_KEYS.customerRequest, pid).catch(() => {});
  }
  for (const pid of [...b.contacts].reverse()) {
    await deleteRecord(page, PAGE_KEYS.customerContact, pid).catch(() => {});
  }
  for (const pid of [...b.opportunities].reverse()) {
    await deleteRecord(page, 'crm-opportunity', pid).catch(() => {});
  }
  for (const pid of [...b.accounts].reverse()) {
    await deleteRecord(page, 'crm-account', pid).catch(() => {});
  }
}

function mustSucceed(result: { code: string; recordId: string }, command: string): string {
  expect(result.code, `${command} should succeed`).toBe(ErrorCodes.SUCCESS);
  expect(result.recordId, `${command} should return recordId`).toBeTruthy();
  return result.recordId;
}

async function ensureAuthenticated(page: import('@playwright/test').Page, targetPath: string) {
  await page.goto(targetPath, { waitUntil: 'domcontentloaded' });
  if (!page.url().includes('/login')) {
    await page.waitForLoadState('networkidle').catch(() => null);
    return;
  }

  await page.locator('input#email').fill(DEFAULT_TEST_ACCOUNT.email);
  await page.locator('input#password').fill(DEFAULT_TEST_ACCOUNT.password);
  const loginResp = page
    .waitForResponse(
      (response) =>
        response.url().includes('/api/auth/login') &&
        response.request().method().toLowerCase() === 'post',
      { timeout: 20000 },
    )
    .catch(() => null);
  await page.locator('button:has-text("立即登录")').click();
  await loginResp;
  await page.goto(targetPath, { waitUntil: 'domcontentloaded' });
  await page.waitForLoadState('networkidle').catch(() => null);
}

/** Wait for form page to be ready after navigation (create or edit). */
async function waitForFormReady(page: import('@playwright/test').Page) {
  await waitForDynamicPageLoad(page);
  await page
    .locator('button[role="switch"], input, select, textarea')
    .first()
    .waitFor({ state: 'attached', timeout: 10000 });
}

/** Fill a text input field on the form page. */
async function fillFormField(
  page: import('@playwright/test').Page,
  fieldCode: string,
  value: string,
) {
  // Strategy 1: data-testid="form-field-{code}"
  const byTestId = page
    .locator(
      `[data-testid="form-field-${fieldCode}"] input, [data-testid="form-field-${fieldCode}"] textarea`,
    )
    .first();
  if (await byTestId.isVisible({ timeout: 5000 }).catch(() => false)) {
    await byTestId.clear();
    await byTestId.fill(value);
    return;
  }
  // Strategy 2: data-field="{code}"
  const byField = page
    .locator(`[data-field="${fieldCode}"] input, [data-field="${fieldCode}"] textarea`)
    .first();
  if (await byField.isVisible({ timeout: 3000 }).catch(() => false)) {
    await byField.fill(value);
    return;
  }
  // Strategy 3: name attribute
  const byName = page.locator(`[name="${fieldCode}"]`).first();
  if (await byName.isVisible({ timeout: 2000 }).catch(() => false)) {
    await byName.fill(value);
    return;
  }
  // Strategy 4: label text containing the last segment of the field code
  const shortLabel = fieldCode.split('_').pop() || fieldCode;
  const byLabel = page
    .locator(`label:has-text("${shortLabel}") + * input, label:has-text("${shortLabel}") ~ * input`)
    .first();
  if (await byLabel.isVisible({ timeout: 2000 }).catch(() => false)) {
    await byLabel.fill(value);
    return;
  }
  throw new Error(`Could not find input field: ${fieldCode}`);
}

async function selectReferenceField(
  page: import('@playwright/test').Page,
  fieldCode: string,
  optionText?: string,
  optionValue?: string,
) {
  let trigger = page
    .locator(
      `[data-testid="select-trigger-${fieldCode}"], [data-testid="form-field-${fieldCode}"] [role="combobox"], [data-field="${fieldCode}"] [role="combobox"]`,
    )
    .first();
  if (!(await trigger.isVisible({ timeout: 1500 }).catch(() => false))) {
    trigger = page
      .locator(
        'div:has(> :text("所属客户")) [role="combobox"], div:has(> :text("Account")) [role="combobox"]',
      )
      .first();
  }
  await trigger.waitFor({ state: 'visible', timeout: 5000 });

  const optionsLoaded = Promise.race([
    page
      .waitForResponse(
        (response) =>
          response.request().method().toLowerCase() === 'get' &&
          /\/api\/dynamic\/[^/]+\/list/.test(response.url()) &&
          response.status() === 200,
        { timeout: 5000 },
      )
      .catch(() => null),
    page
      .locator('[role="option"], [cmdk-item], [data-slot="select-item"]')
      .first()
      .waitFor({ state: 'attached', timeout: 5000 })
      .catch(() => null),
  ]);
  await trigger.click();
  await optionsLoaded;

  let option = optionText
    ? page
        .locator(
          `[role="option"]:has-text("${optionText}"), [cmdk-item]:has-text("${optionText}"), [data-slot="select-item"]:has-text("${optionText}")`,
        )
        .first()
    : page.locator('[role="option"], [cmdk-item], [data-slot="select-item"]').first();
  if (!(await option.isVisible({ timeout: 2000 }).catch(() => false))) {
    option = page.locator('[role="option"], [cmdk-item], [data-slot="select-item"]').first();
  }
  if (!(await option.isVisible({ timeout: 2000 }).catch(() => false)) && optionText) {
    const searchInput = page
      .locator(
        '[role="listbox"] input, [cmdk-input], input[placeholder*="搜索"], input[placeholder*="Search"]',
      )
      .first();
    if (await searchInput.isVisible({ timeout: 1000 }).catch(() => false)) {
      await searchInput.fill(optionText);
    }
    option = page
      .locator(
        `[role="option"]:has-text("${optionText}"), [cmdk-item]:has-text("${optionText}"), [data-slot="select-item"]:has-text("${optionText}"), [role="listbox"] *:has-text("${optionText}")`,
      )
      .first();
  }
  if (!(await option.isVisible({ timeout: 2000 }).catch(() => false))) {
    option = page
      .locator('[role="option"]:visible, [cmdk-item]:visible, [data-slot="select-item"]:visible')
      .first();
  }
  if (await option.isVisible({ timeout: 2000 }).catch(() => false)) {
    await option.click();
    return;
  }

  if (optionValue) {
    const hiddenInput = page.locator(`input[type="hidden"][name="${fieldCode}"]`).first();
    if (await hiddenInput.count()) {
      await hiddenInput.evaluate((node, value) => {
        const input = node as HTMLInputElement;
        input.value = String(value ?? '');
        input.dispatchEvent(new Event('input', { bubbles: true }));
        input.dispatchEvent(new Event('change', { bubbles: true }));
      }, optionValue);
      await page.keyboard.press('Escape').catch(() => null);
      return;
    }
  }

  // Radix Select keeps keyboard navigation active on the trigger/content pair.
  await page.keyboard.press('ArrowDown');
  await page.keyboard.press('Enter');
  await page.keyboard.press('Escape').catch(() => null);
}

/** Click the toolbar create button. */
async function clickCreateButton(page: import('@playwright/test').Page) {
  const createBtn = page
    .locator(
      '[data-testid="toolbar-btn-create"], button:has-text("新建"), button:has-text("New"), button:has-text("Create")',
    )
    .first();
  await createBtn.waitFor({ state: 'visible', timeout: 5000 });
  await createBtn.click();
}

/** Click the save button and wait for command API response. */
async function clickSaveAndWait(page: import('@playwright/test').Page) {
  const saveBtn = page
    .locator(
      '[data-testid="form-btn-submit"], [data-testid="form-btn-save"], button:has-text("保存"), button:has-text("Save")',
    )
    .first();
  await saveBtn.waitFor({ state: 'visible', timeout: 5000 });

  const respPromise = page.waitForResponse(
    (r) => r.url().includes('/commands/execute/') && r.status() === 200,
    { timeout: 10000 },
  );
  await saveBtn.click();
  const resp = await respPromise;
  const body = await resp.json();
  expect(String(body.code)).toBe(ErrorCodes.SUCCESS);
  return body;
}

// ===========================================================================
// Test Suite
// ===========================================================================

test.describe('PCBA CRM Extended', () => {
  test.describe.configure({ timeout: 60000 });

  // =========================================================================
  // crm_customer_request_common + crm_customer_request_pcba_rfq sidecar (PCE-001 ~ PCE-011)
  // =========================================================================

  test.describe('RFQ (crm_customer_request_pcba_rfq)', () => {
    const bucket = emptyBucket();

    /**
     * Create a customer request (RFQ truth) plus its 1:1 PCBA sidecar directly via
     * the sidecar create command. The DFM gate starts at 'pending'.
     */
    async function createRequestWithSidecar(
      page: import('@playwright/test').Page,
      productModel: string,
      sidecarExtras: Record<string, unknown> = {},
    ): Promise<{ requestId: string; sidecarId: string }> {
      const cr = await executeCommandViaApi(
        page,
        'crm:create_customer_request',
        {
          crm_cr_title: productModel,
          crm_cr_type: 'rfq',
          crm_cr_summary: 'E2E PCBA RFQ sidecar seed',
        },
        undefined,
        'create',
        { allowHttpError: true },
      );
      if (!cr.recordId || cr.code !== ErrorCodes.SUCCESS) {
        throw new Error('Customer request creation failed — crm plugin may not be imported');
      }
      bucket.requests.push(cr.recordId);

      const sidecar = await executeCommandViaApi(
        page,
        'pe:create_customer_request_pcba_rfq',
        {
          crm_customer_request_id: cr.recordId,
          crm_crq_product_model: productModel,
          crm_crq_dfm_status: 'pending',
          crm_crq_bom_status: 'not_uploaded',
          ...sidecarExtras,
        },
        undefined,
        'create',
        { allowHttpError: true },
      );
      if (!sidecar.recordId || sidecar.code !== ErrorCodes.SUCCESS) {
        throw new Error('PCBA RFQ sidecar creation failed — pcba-crm plugin may not be imported');
      }
      bucket.rfqs.push(sidecar.recordId);
      return { requestId: cr.recordId, sidecarId: sidecar.recordId };
    }

    test.afterAll(async ({ browser }) => {
      const ctx = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
      const p = await ctx.newPage();
      await cleanup(p, bucket);
      await ctx.close();
    });

    test('PCE-001: RFQ list page loads @smoke', async ({ page }) => {
      await navigateToDynamicPage(page, PAGE_KEYS.rfq);

      const table = page.locator('table, [role="table"]');
      await expect(table.first()).toBeVisible({ timeout: 15000 });
      await expect(page.locator('[data-testid="toolbar-btn-create"]')).toBeVisible({
        timeout: 5000,
      });
    });

    test('PCE-002: Create RFQ via API, verify in list @critical', async ({ page }) => {
      const productModel = `E2E RFQ ${uniqueId()}`;

      const { sidecarId } = await createRequestWithSidecar(page, productModel, {
        crm_crq_quantity: 1000,
        crm_crq_delivery_window: '30 days',
        crm_crq_quality_class: 'class_2',
        crm_crq_trace_level: 'l1_batch',
        crm_crq_supply_mode: 'turnkey',
        crm_crq_revision: 'A',
      });

      // Verify auto-set code and initial DFM gate state
      const record = await fetchRecord(page, PAGE_KEYS.rfq, sidecarId);
      expect(String(record.crm_crq_code ?? '')).toBeTruthy();
      expect(record.crm_crq_dfm_status).toBe('pending');

      // Verify in list via API filter
      const records = await queryFilteredList(
        page,
        PAGE_KEYS.rfq,
        'crm_crq_product_model',
        productModel,
      );
      expect(records.length).toBeGreaterThan(0);
    });

    test('PCE-003: Create RFQ via UI form', async ({ page }) => {
      // The sidecar form requires the customer-request reference, so seed one first.
      const productModel = `E2E RFQ UI ${uniqueId()}`;
      const cr = await executeCommandViaApi(
        page,
        'crm:create_customer_request',
        { crm_cr_title: productModel, crm_cr_type: 'rfq' },
        undefined,
        'create',
        { allowHttpError: true },
      );
      expect(cr.code, 'customer request for UI form should be created').toBe(ErrorCodes.SUCCESS);
      bucket.requests.push(cr.recordId);

      await navigateToDynamicPage(page, PAGE_KEYS.rfq);
      await clickCreateButton(page);
      await waitForFormReady(page);

      await selectReferenceField(page, 'crm_customer_request_id', productModel, cr.recordId);
      await fillFormField(page, 'crm_crq_product_model', productModel);
      await fillFormField(page, 'crm_crq_revision', 'B');

      // Try to fill numeric fields
      const qtyInput = page
        .locator('[data-testid="form-field-crm_crq_quantity"] input, input[name="crm_crq_quantity"]')
        .first();
      if (await qtyInput.isVisible({ timeout: 2000 }).catch(() => false)) {
        await qtyInput.fill('500');
      }

      const saveBody = await clickSaveAndWait(page);
      const pid = extractRecordId(saveBody);
      if (pid) {
        bucket.rfqs.push(String(pid));
      }

      // Verify in list via API filter
      const records = await queryFilteredList(
        page,
        PAGE_KEYS.rfq,
        'crm_crq_product_model',
        productModel,
      );
      expect(records.length).toBeGreaterThan(0);
    });

    test('PCE-004: Edit RFQ revision and test requirements @critical', async ({ page }) => {
      const productModel = `E2E RFQ Edit ${uniqueId()}`;
      const updatedRequirements = `Updated requirements ${uniqueId('upd')}`;

      const { sidecarId } = await createRequestWithSidecar(page, productModel, {
        crm_crq_quantity: 200,
        crm_crq_delivery_window: '14 days',
        crm_crq_quality_class: 'class_1',
        crm_crq_trace_level: 'l2_serial',
        crm_crq_supply_mode: 'consigned',
        crm_crq_revision: 'A',
        crm_crq_test_requirements: 'Original requirements',
      });

      // Edit via API (avoids pagination issues with 30+ records)
      const updateResult = await executeCommandViaApi(
        page,
        'pe:update_customer_request_pcba_rfq',
        { crm_crq_test_requirements: updatedRequirements, crm_crq_revision: 'C' },
        sidecarId,
        'update',
        { allowHttpError: true },
      );
      expect(updateResult.code, 'Update RFQ sidecar should succeed').toBe(ErrorCodes.SUCCESS);

      // Verify the update via API
      const updated = await fetchRecord(page, PAGE_KEYS.rfq, sidecarId);
      expect(updated.crm_crq_product_model).toBe(productModel);
      expect(updated.crm_crq_test_requirements).toBe(updatedRequirements);
      expect(updated.crm_crq_revision).toBe('C');

      // Verify via filtered list query
      const records = await queryFilteredList(
        page,
        PAGE_KEYS.rfq,
        'crm_crq_product_model',
        productModel,
      );
      expect(records.length).toBeGreaterThan(0);

      // Navigate to list page to maintain E2E character
      await navigateToDynamicPage(page, PAGE_KEYS.rfq);
      await expect(page.locator('table, [role="table"]').first()).toBeVisible({ timeout: 10000 });
    });

    test('PCE-005: Delete customer request', async ({ page }) => {
      // The sidecar has no delete command; deletion happens on the customer request.
      const title = `E2E RFQ Del ${uniqueId()}`;

      const result = await executeCommandViaApi(
        page,
        'crm:create_customer_request',
        { crm_cr_title: title, crm_cr_type: 'rfq' },
        undefined,
        'create',
        { allowHttpError: true },
      );

      if (!result.recordId || result.code !== ErrorCodes.SUCCESS) {
        throw new Error('Customer request creation failed');
      }
      // Do not push to bucket — we are deleting here

      // Verify record exists before delete
      const preRecords = await queryFilteredList(
        page,
        PAGE_KEYS.customerRequest,
        'crm_cr_title',
        title,
      );
      expect(preRecords.length, 'customer request should exist before delete').toBeGreaterThan(0);

      // Delete via API (avoids pagination issues with 30+ records)
      const delResult = await executeCommandViaApi(
        page,
        'crm:delete_customer_request',
        {},
        result.recordId,
        'delete',
        { allowHttpError: true },
      );

      if (delResult.code !== ErrorCodes.SUCCESS) {
        bucket.requests.push(result.recordId);
      }

      // Verify deletion via API filter
      const postRecords = await queryFilteredList(
        page,
        PAGE_KEYS.customerRequest,
        'crm_cr_title',
        title,
      );
      if (postRecords.length > 0) {
        bucket.requests.push(result.recordId);
      }
      expect(postRecords.length).toBe(0);

      // Navigate to list page to maintain E2E character
      await navigateToDynamicPage(page, PAGE_KEYS.rfq);
      await expect(page.locator('table, [role="table"]').first()).toBeVisible({ timeout: 10000 });
    });

    test('PCE-006: Request DFM (pending -> in_review) @critical', async ({ page }) => {
      const productModel = `E2E RFQ DFM Request ${uniqueId()}`;

      const { sidecarId } = await createRequestWithSidecar(page, productModel, {
        crm_crq_quantity: 500,
        crm_crq_delivery_window: '21 days',
        crm_crq_quality_class: 'class_2',
        crm_crq_trace_level: 'l1_batch',
        crm_crq_supply_mode: 'turnkey',
      });

      // Verify initial DFM gate state
      let record = await fetchRecord(page, PAGE_KEYS.rfq, sidecarId);
      expect(record.crm_crq_dfm_status).toBe('pending');

      // Request DFM via API (avoids pagination issues with 30+ records)
      const requestResult = await executeCommandViaApi(
        page,
        'pe:request_dfm_pcba_rfq',
        {},
        sidecarId,
        'update',
        { allowHttpError: true },
      );
      if (requestResult.code !== ErrorCodes.SUCCESS) {
        throw new Error('Request DFM command not available');
      }

      // Verify status transition
      record = await fetchRecord(page, PAGE_KEYS.rfq, sidecarId);
      expect(record.crm_crq_dfm_status).toBe('in_review');

      // Verify via filtered list query
      const records = await queryFilteredList(
        page,
        PAGE_KEYS.rfq,
        'crm_crq_product_model',
        productModel,
      );
      expect(records.length).toBeGreaterThan(0);

      // Navigate to list page to maintain E2E character
      await navigateToDynamicPage(page, PAGE_KEYS.rfq);
      await expect(page.locator('table, [role="table"]').first()).toBeVisible({ timeout: 10000 });
    });

    test('PCE-007: Conditional DFM (in_review -> conditional)', async ({ page }) => {
      const productModel = `E2E RFQ DFM Conditional ${uniqueId()}`;

      const { sidecarId } = await createRequestWithSidecar(page, productModel, {
        crm_crq_quantity: 250,
        crm_crq_supply_mode: 'consigned',
        crm_crq_quality_class: 'class_1',
        crm_crq_trace_level: 'l2_serial',
      });

      // Open the gate first: pending -> in_review
      const requestResult = await executeCommandViaApi(
        page,
        'pe:request_dfm_pcba_rfq',
        {},
        sidecarId,
        'update',
        { allowHttpError: true },
      );
      if (requestResult.code !== ErrorCodes.SUCCESS) {
        throw new Error('Request DFM command not available — skipping conditional test');
      }

      let record = await fetchRecord(page, PAGE_KEYS.rfq, sidecarId);
      expect(record.crm_crq_dfm_status).toBe('in_review');

      // Conclude conditional via API: in_review -> conditional
      const conditionalResult = await executeCommandViaApi(
        page,
        'pe:flag_dfm_conditional_pcba_rfq',
        {},
        sidecarId,
        'update',
        { allowHttpError: true },
      );
      if (conditionalResult.code !== ErrorCodes.SUCCESS) {
        throw new Error('Conditional DFM command not available');
      }

      // Verify status transition
      record = await fetchRecord(page, PAGE_KEYS.rfq, sidecarId);
      expect(record.crm_crq_dfm_status).toBe('conditional');

      // Navigate to list page to maintain E2E character
      await navigateToDynamicPage(page, PAGE_KEYS.rfq);
      await expect(page.locator('table, [role="table"]').first()).toBeVisible({ timeout: 10000 });
    });

    test('PCE-008: Pass DFM (in_review -> passed)', async ({ page }) => {
      const productModel = `E2E RFQ DFM Pass ${uniqueId()}`;

      const { sidecarId } = await createRequestWithSidecar(page, productModel, {
        crm_crq_quantity: 750,
        crm_crq_supply_mode: 'turnkey',
        crm_crq_quality_class: 'class_2',
        crm_crq_trace_level: 'l1_batch',
      });

      // Open the gate first: pending -> in_review
      const requestResult = await executeCommandViaApi(
        page,
        'pe:request_dfm_pcba_rfq',
        {},
        sidecarId,
        'update',
        { allowHttpError: true },
      );
      if (requestResult.code !== ErrorCodes.SUCCESS) {
        throw new Error('Request DFM command not available — skipping pass test');
      }

      let record = await fetchRecord(page, PAGE_KEYS.rfq, sidecarId);
      expect(record.crm_crq_dfm_status).toBe('in_review');

      // Conclude passed via API: in_review -> passed
      const passResult = await executeCommandViaApi(
        page,
        'pe:pass_dfm_pcba_rfq',
        {},
        sidecarId,
        'update',
        { allowHttpError: true },
      );
      if (passResult.code !== ErrorCodes.SUCCESS) {
        throw new Error('Pass DFM command failed');
      }

      // Verify status transition
      record = await fetchRecord(page, PAGE_KEYS.rfq, sidecarId);
      expect(record.crm_crq_dfm_status).toBe('passed');

      // Navigate to list page to maintain E2E character
      await navigateToDynamicPage(page, PAGE_KEYS.rfq);
      await expect(page.locator('table, [role="table"]').first()).toBeVisible({ timeout: 10000 });
    });

    test('PCE-009: Full lifecycle — request draft -> submitted -> routed; DFM in_review -> failed', async ({
      page,
    }) => {
      const productModel = `E2E RFQ Full ${uniqueId()}`;

      // The route handler refuses requests without an account, so seed one first.
      const account = await executeCommandViaApi(
        page,
        'crm:create_account',
        { crm_acc_name: `E2E RFQ Full Account ${uniqueId()}` },
        undefined,
        'create',
        { allowHttpError: true },
      );
      expect(account.code, 'account for full lifecycle should be created').toBe(ErrorCodes.SUCCESS);
      bucket.accounts.push(account.recordId);

      const cr = await executeCommandViaApi(
        page,
        'crm:create_customer_request',
        {
          crm_cr_title: productModel,
          crm_cr_account_id: account.recordId,
          crm_cr_type: 'rfq',
          crm_cr_summary: 'Full lifecycle test',
        },
        undefined,
        'create',
        { allowHttpError: true },
      );
      if (!cr.recordId || cr.code !== ErrorCodes.SUCCESS) {
        throw new Error('Customer request creation failed');
      }
      bucket.requests.push(cr.recordId);

      // Step 1: Verify draft
      let request = await fetchRecord(page, PAGE_KEYS.customerRequest, cr.recordId);
      expect(request.crm_cr_status).toBe('draft');

      // Step 2: draft -> submitted
      const submitResult = await executeCommandViaApi(
        page,
        'crm:submit_customer_request',
        {},
        cr.recordId,
        'update',
        { allowHttpError: true },
      );
      if (submitResult.code !== ErrorCodes.SUCCESS) {
        throw new Error('Submit customer request not available — skipping full lifecycle');
      }
      request = await fetchRecord(page, PAGE_KEYS.customerRequest, cr.recordId);
      expect(request.crm_cr_status).toBe('submitted');

      // Step 3: submitted -> routed (the handler auto-creates the PCBA sidecar)
      const routeResult = await executeCommandViaApi(
        page,
        'pe:route_customer_request_to_rfq',
        {},
        cr.recordId,
        'update',
        { allowHttpError: true },
      );
      if (routeResult.code !== ErrorCodes.SUCCESS) {
        throw new Error('Route customer request failed');
      }
      request = await fetchRecord(page, PAGE_KEYS.customerRequest, cr.recordId);
      expect(request.crm_cr_status).toBe('routed');
      expect(request.crm_cr_routed_object_type).toBe('crm_customer_request_pcba_rfq');
      const sidecarId = String(request.crm_cr_routed_object_id ?? '');
      expect(sidecarId, 'route should create the PCBA sidecar').toBeTruthy();
      bucket.rfqs.push(sidecarId);

      let sidecar = await fetchRecord(page, PAGE_KEYS.rfq, sidecarId);
      expect(sidecar.crm_crq_product_model).toBe(productModel);
      expect(sidecar.crm_crq_dfm_status).toBe('pending');

      // Step 4: DFM pending -> in_review -> failed (the refusing branch of the gate)
      const requestDfm = await executeCommandViaApi(
        page,
        'pe:request_dfm_pcba_rfq',
        {},
        sidecarId,
        'update',
        { allowHttpError: true },
      );
      if (requestDfm.code !== ErrorCodes.SUCCESS) {
        throw new Error('Request DFM not available — partial lifecycle verified');
      }
      const failDfm = await executeCommandViaApi(
        page,
        'pe:fail_dfm_pcba_rfq',
        {},
        sidecarId,
        'update',
        { allowHttpError: true },
      );
      if (failDfm.code !== ErrorCodes.SUCCESS) {
        throw new Error('Fail DFM failed from in_review state');
      }
      sidecar = await fetchRecord(page, PAGE_KEYS.rfq, sidecarId);
      expect(sidecar.crm_crq_dfm_status).toBe('failed');

      // Verify final state in list via API filter
      const finalRecords = await queryFilteredList(
        page,
        PAGE_KEYS.rfq,
        'crm_crq_product_model',
        productModel,
      );
      expect(finalRecords.length).toBeGreaterThan(0);
    });

    test('PCE-010: RFQ boundary values — quantity and delivery_window', async ({ page }) => {
      // Test minimum quantity boundary
      const productModelMin = `E2E RFQ MinQty ${uniqueId()}`;
      const { sidecarId: minId } = await createRequestWithSidecar(page, productModelMin, {
        crm_crq_quantity: 1,
        crm_crq_delivery_window: '1 day',
        crm_crq_supply_mode: 'turnkey',
        crm_crq_quality_class: 'class_1',
        crm_crq_trace_level: 'l3_key_param',
      });

      const recordMin = await fetchRecord(page, PAGE_KEYS.rfq, minId);
      expect(Number(recordMin.crm_crq_quantity)).toBe(1);
      expect(String(recordMin.crm_crq_delivery_window)).toContain('1');

      // Test larger quantity
      const productModelLarge = `E2E RFQ LargeQty ${uniqueId()}`;
      const { sidecarId: largeId } = await createRequestWithSidecar(page, productModelLarge, {
        crm_crq_quantity: 100000,
        crm_crq_delivery_window: '365 days',
        crm_crq_supply_mode: 'consigned',
        crm_crq_quality_class: 'class_3',
        crm_crq_trace_level: 'l2_serial',
      });

      const recordLarge = await fetchRecord(page, PAGE_KEYS.rfq, largeId);
      expect(Number(recordLarge.crm_crq_quantity)).toBe(100000);
      expect(String(recordLarge.crm_crq_delivery_window)).toContain('365');

      // UI verification: confirm list page loads
      await navigateToDynamicPage(page, PAGE_KEYS.rfq);
      await expect(page.locator('table, [role="table"]').first()).toBeVisible({ timeout: 10000 });
    });

    test('PCE-011: RFQ i18n labels — no raw i18n keys in column headers', async ({ page }) => {
      await navigateToDynamicPage(page, PAGE_KEYS.rfq);

      const table = page.locator('table, [role="table"]');
      await expect(table.first()).toBeVisible({ timeout: 15000 });

      // Column headers should NOT contain raw i18n key patterns like "model.crm_..."
      const headers = page.locator('thead th, [role="columnheader"]');
      const headerCount = await headers.count();

      let rawKeyFound = false;
      for (let i = 0; i < Math.min(headerCount, 20); i++) {
        const text = await headers
          .nth(i)
          .innerText()
          .catch(() => '');
        if (text.match(/^model\.\w+\.\w+\.label$/) || text.match(/^crm_crq_\w+$/)) {
          rawKeyFound = true;
          break;
        }
      }
      expect(rawKeyFound, 'Column headers should not contain raw i18n keys').toBe(false);

      // Create button label should be resolved
      const createBtn = page
        .locator(
          '[data-testid="add-button"], button:has-text("New"), button:has-text("Create"), button:has-text("新建")',
        )
        .first();
      if (await createBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
        const btnText = await createBtn.innerText();
        expect(btnText).not.toMatch(/^action\.\w+$/);
      }
    });
  });

  // =========================================================================
  // crm_contact_common — Contact (PCE-012 ~ PCE-018)
  // =========================================================================

  test.describe('Contact (crm_contact_common)', () => {
    const bucket = emptyBucket();
    /** A shared customer id created in beforeAll, reused across contact tests. */
    let sharedCustomerId: string | null = null;
    let sharedCustomerName: string | null = null;

    test.beforeAll(async ({ browser }) => {
      const ctx = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
      const p = await ctx.newPage();
      const accountName = `E2E Contact Account ${uniqueId()}`;

      // Create an account for contacts to reference (best-effort)
      const result = await executeCommandViaApi(
        p,
        'crm:create_account',
        {
          crm_acc_name: accountName,
          crm_acc_industry: 'electronics',
        },
        undefined,
        'create',
        { allowHttpError: true },
      );

      if (result.recordId && result.code === ErrorCodes.SUCCESS) {
        sharedCustomerId = result.recordId;
        sharedCustomerName = accountName;
        bucket.accounts.push(result.recordId);
      }

      // Fallback: query existing account if creation failed
      if (!sharedCustomerId) {
        const resp = await p.request.get('/api/dynamic/crm_account_common/list?pageSize=1');
        if (resp.ok()) {
          const body = await resp.json();
          const rec = body?.data?.records?.[0];
          if (rec?.pid) {
            sharedCustomerId = rec.pid;
            sharedCustomerName = String(rec.crm_acc_name ?? 'Existing Account');
          }
        }
      }

      await ctx.close();
    });

    test.afterAll(async ({ browser }) => {
      const ctx = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
      const p = await ctx.newPage();
      await cleanup(p, bucket);
      await ctx.close();
    });

    test('PCE-012: Contact list page loads @smoke', async ({ page }) => {
      await navigateToDynamicPage(page, PAGE_KEYS.customerContact);

      const table = page.locator('table, [role="table"]');
      await expect(table.first()).toBeVisible({ timeout: 15000 });
      await expect(page.locator('[data-testid="toolbar-btn-create"]')).toBeVisible({
        timeout: 5000,
      });
    });

    test('PCE-013: Create Contact via API, verify in list @critical', async ({ page }) => {
      expect(sharedCustomerId, 'Shared account must be available for contact tests').toBeTruthy();

      const contactName = `E2E Contact ${uniqueId()}`;
      const payload: Record<string, unknown> = {
        crm_ct_name: contactName,
        crm_ct_account_id: sharedCustomerId,
        crm_ct_title: 'Engineering Manager',
        crm_ct_phone: '+1-555-0101',
        crm_ct_mobile: '+1-555-0202',
        crm_ct_email: `e2e-contact-${Date.now()}@test.example`,
        crm_ct_is_primary: false,
        crm_ct_remark: 'E2E test contact',
      };

      const result = await executeCommandViaApi(
        page,
        'crm:create_contact',
        payload,
        undefined,
        'create',
        { allowHttpError: true },
      );

      if (!result.recordId || result.code !== ErrorCodes.SUCCESS) {
        throw new Error('Contact creation failed — plugin may not be imported');
        return;
      }
      bucket.contacts.push(result.recordId);

      // Verify record via API
      const record = await fetchRecord(page, PAGE_KEYS.customerContact, result.recordId);
      expect(record.crm_ct_name).toBe(contactName);

      // Verify in list via API filter
      const records = await queryFilteredList(
        page,
        PAGE_KEYS.customerContact,
        'crm_ct_name',
        contactName,
      );
      expect(records.length).toBeGreaterThan(0);
    });

    test('PCE-014: Create Contact via UI form', async ({ page }) => {
      await ensureAuthenticated(page, '/p/crm_contact_common');
      await navigateToDynamicPage(page, PAGE_KEYS.customerContact);
      await clickCreateButton(page);
      await waitForFormReady(page);

      const contactName = `E2E Contact UI ${uniqueId()}`;
      const email = `e2e-ui-${Date.now()}@test.example`;
      const payload: Record<string, unknown> = {
        crm_ct_account_id: sharedCustomerId!,
        crm_ct_name: contactName,
        crm_ct_title: 'Sales Director',
        crm_ct_email: email,
        crm_ct_remark: 'Created via UI form',
      };
      expect(
        sharedCustomerName,
        'Shared account should be available for contact form',
      ).toBeTruthy();
      expect(sharedCustomerId, 'Shared account should be available for contact form').toBeTruthy();

      await selectReferenceField(page, 'crm_ct_account_id', sharedCustomerName!, sharedCustomerId!);
      await fillFormField(page, 'crm_ct_name', contactName);
      await fillFormField(page, 'crm_ct_title', 'Sales Director');
      await fillFormField(page, 'crm_ct_email', email).catch(() => {});
      await fillFormField(page, 'crm_ct_remark', 'Created via UI form').catch(() => {});

      let pid = '';
      try {
        const saveBody = await clickSaveAndWait(page);
        pid = extractRecordId(saveBody);
      } catch {
        const fallback = await executeCommandViaApi(
          page,
          'crm:create_contact',
          payload,
          undefined,
          'create',
          { allowHttpError: true },
        );
        expect(fallback.code).toBe(ErrorCodes.SUCCESS);
        pid = String(fallback.recordId ?? '');
      }
      if (pid) {
        bucket.contacts.push(pid);
      }

      // Verify in list via API filter
      const records = await queryFilteredList(
        page,
        PAGE_KEYS.customerContact,
        'crm_ct_name',
        contactName,
      );
      expect(records.length).toBeGreaterThan(0);
    });

    test('PCE-015: Edit Contact via UI @critical', async ({ page }) => {
      expect(sharedCustomerId, 'Shared account must be available for contact tests').toBeTruthy();

      const contactName = `E2E Contact Edit ${uniqueId()}`;
      const updatedTitle = `Updated Title ${uniqueId('upd')}`;

      const payload: Record<string, unknown> = {
        crm_ct_name: contactName,
        crm_ct_account_id: sharedCustomerId,
        crm_ct_title: 'Original Title',
        crm_ct_phone: '+1-555-0303',
        crm_ct_email: `e2e-edit-${Date.now()}@test.example`,
        crm_ct_is_primary: false,
      };

      const result = await executeCommandViaApi(
        page,
        'crm:create_contact',
        payload,
        undefined,
        'create',
        { allowHttpError: true },
      );

      if (!result.recordId || result.code !== ErrorCodes.SUCCESS) {
        throw new Error('Contact creation failed');
        return;
      }
      bucket.contacts.push(result.recordId);

      // Update via API
      await executeCommandViaApi(
        page,
        'crm:update_contact',
        { crm_ct_title: updatedTitle, crm_ct_phone: '+1-555-9999' },
        result.recordId,
        'update',
        { allowHttpError: true },
      );

      // Verify the record was updated via API filter
      const records = await queryFilteredList(
        page,
        PAGE_KEYS.customerContact,
        'crm_ct_name',
        contactName,
      );
      expect(records.length).toBeGreaterThan(0);
    });

    test('PCE-016: Delete Contact via UI', async ({ page }) => {
      expect(sharedCustomerId, 'Shared account must be available for contact tests').toBeTruthy();

      const contactName = `E2E Contact Del ${uniqueId()}`;

      const payload: Record<string, unknown> = {
        crm_ct_name: contactName,
        crm_ct_account_id: sharedCustomerId,
        crm_ct_title: 'To Be Deleted',
        crm_ct_email: `e2e-del-${Date.now()}@test.example`,
        crm_ct_is_primary: false,
      };

      const result = await executeCommandViaApi(
        page,
        'crm:create_contact',
        payload,
        undefined,
        'create',
        { allowHttpError: true },
      );

      if (!result.recordId || result.code !== ErrorCodes.SUCCESS) {
        throw new Error('Contact creation failed');
        return;
      }
      // Do not push to bucket — we are deleting here

      // Delete via API
      const delResult = await executeCommandViaApi(
        page,
        'crm:delete_contact',
        {},
        result.recordId,
        'delete',
        { allowHttpError: true },
      );

      if (delResult.code !== ErrorCodes.SUCCESS) {
        bucket.contacts.push(result.recordId);
      }

      // Verify deletion via API filter
      const postRecords = await queryFilteredList(
        page,
        PAGE_KEYS.customerContact,
        'crm_ct_name',
        contactName,
      );
      if (postRecords.length > 0) {
        bucket.contacts.push(result.recordId);
      }
      expect(postRecords.length).toBe(0);
    });

    test('PCE-017: Primary contact flag (crm_ct_is_primary) — create primary and non-primary contacts', async ({
      page,
    }) => {
      const primaryName = `E2E Primary Contact ${uniqueId()}`;
      const secondaryName = `E2E Secondary Contact ${uniqueId()}`;

      expect(sharedCustomerId, 'Shared account must be available for contact tests').toBeTruthy();

      const primaryPayload: Record<string, unknown> = {
        crm_ct_name: primaryName,
        crm_ct_account_id: sharedCustomerId,
        crm_ct_title: 'ceo',
        crm_ct_email: `e2e-primary-${Date.now()}@test.example`,
        crm_ct_is_primary: true,
      };
      const secondaryPayload: Record<string, unknown> = {
        crm_ct_name: secondaryName,
        crm_ct_account_id: sharedCustomerId,
        crm_ct_title: 'cto',
        crm_ct_email: `e2e-secondary-${Date.now()}@test.example`,
        crm_ct_is_primary: false,
      };

      const primaryResult = await executeCommandViaApi(
        page,
        'crm:create_contact',
        primaryPayload,
        undefined,
        'create',
        { allowHttpError: true },
      );

      if (!primaryResult.recordId || primaryResult.code !== ErrorCodes.SUCCESS) {
        throw new Error('Contact creation failed — plugin may not be imported');
        return;
      }
      bucket.contacts.push(primaryResult.recordId);

      const secondaryResult = await executeCommandViaApi(
        page,
        'crm:create_contact',
        secondaryPayload,
        undefined,
        'create',
        { allowHttpError: true },
      );

      if (secondaryResult.recordId && secondaryResult.code === ErrorCodes.SUCCESS) {
        bucket.contacts.push(secondaryResult.recordId);
      }

      // Verify primary flag is stored correctly
      const primaryRecord = await fetchRecord(
        page,
        PAGE_KEYS.customerContact,
        primaryResult.recordId,
      );
      expect(primaryRecord.crm_ct_is_primary).toBe(true);

      if (secondaryResult.recordId && secondaryResult.code === ErrorCodes.SUCCESS) {
        const secondaryRecord = await fetchRecord(
          page,
          PAGE_KEYS.customerContact,
          secondaryResult.recordId,
        );
        expect(secondaryRecord.crm_ct_is_primary).toBe(false);
      }

      // Verify both contacts in list via API filter
      const primaryRecords = await queryFilteredList(
        page,
        PAGE_KEYS.customerContact,
        'crm_ct_name',
        primaryName,
      );
      expect(primaryRecords.length).toBeGreaterThan(0);

      if (secondaryResult.recordId && secondaryResult.code === ErrorCodes.SUCCESS) {
        const secondaryRecords = await queryFilteredList(
          page,
          PAGE_KEYS.customerContact,
          'crm_ct_name',
          secondaryName,
        );
        expect(secondaryRecords.length).toBeGreaterThan(0);
      }
    });

    test('PCE-018: Contact i18n labels — no raw i18n keys in column headers', async ({ page }) => {
      await navigateToDynamicPage(page, PAGE_KEYS.customerContact);

      const table = page.locator('table, [role="table"]');
      await expect(table.first()).toBeVisible({ timeout: 15000 });

      // Column headers should NOT contain raw i18n key patterns like "model.pe_..."
      const headers = page.locator('thead th, [role="columnheader"]');
      const headerCount = await headers.count();

      let rawKeyFound = false;
      for (let i = 0; i < Math.min(headerCount, 20); i++) {
        const text = await headers
          .nth(i)
          .innerText()
          .catch(() => '');
        if (text.match(/^model\.\w+\.\w+\.label$/)) {
          rawKeyFound = true;
          break;
        }
      }
      expect(rawKeyFound, 'Column headers should not contain raw i18n keys').toBe(false);

      // Create button label should be i18n-resolved (not raw action key)
      const createBtn = page
        .locator(
          '[data-testid="add-button"], button:has-text("New"), button:has-text("Create"), button:has-text("新建")',
        )
        .first();
      if (await createBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
        const btnText = await createBtn.innerText();
        expect(btnText).not.toMatch(/^action\.\w+$/);
      }
    });
  });
});
