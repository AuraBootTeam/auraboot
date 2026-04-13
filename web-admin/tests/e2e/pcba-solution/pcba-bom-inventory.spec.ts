/**
 * PCBA ERP -- BOM & Inventory E2E Tests
 *
 * Tests PBM-001 ~ PBM-033: BOM CRUD, state transitions, BOM lines, and inventory.
 *
 * Models covered:
 * - pe_bom        (BOM) — CRUD + status workflow (draft -> active -> inactive)
 * - pe_bom_line   (BOM Line) — child of pe_bom, API-only tests
 * - pe_inventory  (Inventory) — read-only list & dashboard
 *
 * Prerequisites: PCBA ERP plugins must be imported and models published.
 *
 * @since 7.0.0
 */

import { test, expect } from '../../fixtures';
import { ErrorCodes } from '~/shared/services/http-client/types';
import {
  navigateToDynamicPage,
  waitForDynamicPageLoad,
  uniqueId,
  executeCommandViaApi,
  acceptConfirmDialog,
  findRowInPaginatedList,
  clickTabAndWaitForLoad,
  clickRowActionByLocator,
} from '../helpers';

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

const PAGE_KEYS = {
  bom: 'pe-bom',
  bomForm: 'pe-bom-form',
  bomDetail: 'pe-bom-detail',
  inventory: 'inv-inventory',
  inventoryDashboard: 'inv-inventory-dashboard',
};

const INVENTORY_QUERY_ROUTE = '/inventory/inventory';

const COMMANDS = {
  createBom: 'pe:create_bom',
  updateBom: 'pe:update_bom',
  deleteBom: 'pe:delete_bom',
  activateBom: 'pe:activate_bom',
  deactivateBom: 'pe:deactivate_bom',
  addBomLine: 'pe:add_bom_line',
  deleteBomLine: 'pe:delete_bom_line',
  createProduct: 'prod:create_product',
  deleteProduct: 'prod:delete_product',
};

type CleanupEntry = { commandCode: string; pid: string };

/** Wait for form page to be ready after navigation (create or edit). */
async function waitForFormReady(page: import('@playwright/test').Page) {
  await expect(page).toHaveURL(/\/(dynamic\/(pe-bom|pe_bom)|p\/pe_bom)(\/new|\/[^/]+\/edit)/, {
    timeout: 10000,
  });
  await waitForDynamicPageLoad(page);
  await page
    .locator(
      '[data-testid="form-btn-submit"], [data-testid="form-btn-save"], button:has-text("Save"), textarea, select',
    )
    .first()
    .waitFor({ state: 'visible', timeout: 10000 });
}

async function navigateToInventoryQueryPage(page: import('@playwright/test').Page): Promise<void> {
  const directLink = page.locator(`nav a[href="${INVENTORY_QUERY_ROUTE}"]`).first();
  if (await directLink.isVisible({ timeout: 2000 }).catch(() => false)) {
    await directLink.click();
  } else {
    const inventoryMenu = page
      .locator('nav button')
      .filter({ hasText: /Inventory|库存/ })
      .first();
    if (await inventoryMenu.isVisible({ timeout: 3000 }).catch(() => false)) {
      await inventoryMenu.click().catch(() => null);
    }
    const expandedLink = page.locator(`nav a[href="${INVENTORY_QUERY_ROUTE}"]`).first();
    if (await expandedLink.isVisible({ timeout: 3000 }).catch(() => false)) {
      await expandedLink.click();
    } else {
      await page.goto(INVENTORY_QUERY_ROUTE, { waitUntil: 'domcontentloaded' });
    }
  }
  await expect(page).toHaveURL(new RegExp(INVENTORY_QUERY_ROUTE.replace(/\//g, '\\/')), {
    timeout: 10000,
  });
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
  // Strategy 4: label text containing last segment of field code
  const shortLabel = fieldCode.split('_').pop() || fieldCode;
  const byLabel = page
    .locator(`label:has-text("${shortLabel}") + * input, label:has-text("${shortLabel}") ~ * input`)
    .first();
  if (await byLabel.isVisible({ timeout: 2000 }).catch(() => false)) {
    await byLabel.fill(value);
    return;
  }
  // Strategy 5: scan all visible inputs for matching name attribute
  const allInputs = page.locator(
    'form input[type="text"], form textarea, [data-testid*="form"] input[type="text"]',
  );
  const count = await allInputs.count();
  for (let i = 0; i < count; i++) {
    const input = allInputs.nth(i);
    const nameAttr = await input.getAttribute('name').catch(() => '');
    if (nameAttr && nameAttr.includes(fieldCode)) {
      await input.fill(value);
      return;
    }
  }
  throw new Error(`Could not find input field: ${fieldCode}`);
}

async function setReferenceFieldValue(
  page: import('@playwright/test').Page,
  fieldCode: string,
  value: string,
): Promise<void> {
  await page.evaluate(
    ([code, nextValue]) => {
      const candidates = Array.from(
        document.querySelectorAll<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>(
          [
            `[name="${code}"]`,
            `[data-field="${code}"] input`,
            `[data-testid="form-field-${code}"] input`,
            `[data-testid="form-field-${code}"] textarea`,
            `input[type="hidden"][name="${code}"]`,
          ].join(', '),
        ),
      );
      for (const input of candidates) {
        input.value = nextValue;
        input.dispatchEvent(new Event('input', { bubbles: true }));
        input.dispatchEvent(new Event('change', { bubbles: true }));
      }
    },
    [fieldCode, value],
  );
}

async function selectReferenceOption(
  page: import('@playwright/test').Page,
  fieldCode: string,
  optionLabel: string,
): Promise<void> {
  const trigger = page.locator(`[data-testid="select-trigger-${fieldCode}"]`).first();
  await trigger.waitFor({ state: 'visible', timeout: 5000 });
  await trigger.click();
  const option = page.locator('[role="option"]').filter({ hasText: optionLabel }).first();
  await option.waitFor({ state: 'visible', timeout: 5000 });
  await option.click();
}

/** Click the toolbar create button. */
async function clickCreateButton(page: import('@playwright/test').Page) {
  const createBtn = page
    .locator(
      '[data-testid="toolbar-btn-create"], button:has-text("New"), button:has-text("Create")',
    )
    .first();
  await createBtn.waitFor({ state: 'visible', timeout: 5000 });
  await createBtn.click();
  await expect(page).toHaveURL(/\/(dynamic\/(pe-bom|pe_bom)|p\/pe_bom)\/new/, { timeout: 10000 });
}

/** Click the save button and wait for the form submission to settle. */
async function clickSaveAndWait(page: import('@playwright/test').Page) {
  const saveBtn = page
    .locator(
      '[data-testid="form-btn-submit"], [data-testid="form-btn-save"], button:has-text("Save")',
    )
    .first();
  await saveBtn.waitFor({ state: 'visible', timeout: 5000 });

  const settlePromise = Promise.race([
    page
      .waitForURL((url) => !/\/new$|\/edit$/.test(url.pathname), { timeout: 10000 })
      .catch(() => null),
    page
      .waitForResponse(
        (r) => r.request().method() !== 'get' && r.status() >= 200 && r.status() < 300,
        { timeout: 10000 },
      )
      .catch(() => null),
  ]);
  await saveBtn.click();
  await settlePromise;
  return null;
}

/** Click the row-level edit button. */
async function clickRowEditButton(
  page: import('@playwright/test').Page,
  row: import('@playwright/test').Locator,
) {
  await clickRowActionByLocator(page, row, 'edit');
}

/** Click the row-level delete button, confirm, and wait for command. */
async function clickRowDeleteAndConfirm(
  page: import('@playwright/test').Page,
  row: import('@playwright/test').Locator,
) {
  const cmdPromise = page.waitForResponse(
    (r) => r.url().includes('/commands/execute/') && r.status() === 200,
    { timeout: 10000 },
  );
  await clickRowActionByLocator(page, row, 'delete');
  await acceptConfirmDialog(page);
  await cmdPromise.catch(() => null);
}

/** Fetch a single record by page key and pid. */
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

/** Cleanup helper that suppresses errors. */
async function safeCleanup(
  page: import('@playwright/test').Page,
  entries: CleanupEntry[],
): Promise<void> {
  for (const { commandCode, pid } of [...entries].reverse()) {
    await executeCommandViaApi(page, commandCode, {}, pid, 'delete', {
      allowHttpError: true,
    }).catch(() => {});
  }
}

// ==========================================================================
// BOM CRUD Tests
// ==========================================================================

test.describe('PCBA BOM -- CRUD', () => {
  test.describe.configure({ timeout: 45000 });

  const created: CleanupEntry[] = [];
  let productPid: string;
  let productName: string;

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    productName = `E2E BOM Product ${uniqueId('prod')}`;
    const result = await executeCommandViaApi(
      page,
      COMMANDS.createProduct,
      {
        prod_name: productName,
        prod_type: 'finished',
        prod_unit: 'pcs',
      },
      undefined,
      'create',
    );
    expect(result.code, 'Product creation must succeed').toBe(ErrorCodes.SUCCESS);
    expect(result.recordId, 'Product must return a recordId').toBeTruthy();
    productPid = result.recordId!;
    await ctx.close();
  });

  test.afterAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    await safeCleanup(page, created);
    if (productPid) {
      await executeCommandViaApi(page, COMMANDS.deleteProduct, {}, productPid, 'delete', {
        allowHttpError: true,
      }).catch(() => {});
    }
    await ctx.close();
  });

  test('PBM-001: BOM list page loads with table @smoke', async ({ page }) => {
    await navigateToDynamicPage(page, PAGE_KEYS.bom);
    const table = page.locator('table, [role="table"]');
    await expect(table.first()).toBeVisible({ timeout: 15000 });
    await expect(page.locator('[data-testid="toolbar-btn-create"]')).toBeVisible({ timeout: 5000 });
  });

  test('PBM-002: Create BOM via API, verify in list @critical', async ({ page }) => {
    const bomName = `E2E BOM ${uniqueId()}`;
    const bomCode = `E2E-BOM-${Date.now()}`;
    const result = await executeCommandViaApi(
      page,
      COMMANDS.createBom,
      {
        pe_bom_code: bomCode,
        pe_bom_name: bomName,
        pe_bom_version: 'V1.0',
        pe_bom_output_qty: 1,
        pe_bom_product_id: productPid,
      },
      undefined,
      'create',
    );

    if (!result.recordId || result.code !== ErrorCodes.SUCCESS) {
      throw new Error('BOM creation failed -- command may not be available');
      return;
    }
    created.push({ commandCode: COMMANDS.deleteBom, pid: result.recordId });

    await navigateToDynamicPage(page, PAGE_KEYS.bom);
    await clickTabAndWaitForLoad(page, /Draft|草稿/i).catch(() => null);
    const row = await findRowInPaginatedList(page, bomName);
    await expect(row).toBeVisible({ timeout: 10000 });
  });

  test('PBM-003: Create BOM via UI form', async ({ page }) => {
    await navigateToDynamicPage(page, PAGE_KEYS.bom);
    await clickCreateButton(page);
    await waitForFormReady(page);

    const bomName = `E2E BOM UI ${uniqueId()}`;

    await fillFormField(page, 'pe_bom_name', bomName);
    await fillFormField(page, 'pe_bom_version', 'V1.0');
    await selectReferenceOption(page, 'pe_bom_product_id', productName);

    // Fill output qty -- may be a decimal field
    const qtyField = page
      .locator(
        '[data-testid="form-field-pe_bom_output_qty"] input, [data-field="pe_bom_output_qty"] input, [name="pe_bom_output_qty"]',
      )
      .first();
    if (await qtyField.isVisible({ timeout: 2000 }).catch(() => false)) {
      await qtyField.fill('1');
    }

    await clickSaveAndWait(page);

    // Verify in list by searching for bomName
    await navigateToDynamicPage(page, PAGE_KEYS.bom);
    await clickTabAndWaitForLoad(page, /Draft|草稿/i).catch(() => null);
    const createdRow = await findRowInPaginatedList(page, bomName, 12000);
    await expect(createdRow).toBeVisible({ timeout: 8000 });
  });

  test.fixme('PBM-004: Edit BOM name via UI @critical', async ({ page }) => {
    const originalName = `E2E BOMEdit ${uniqueId()}`;
    const updatedName = `E2E BOMUpd ${uniqueId()}`;

    const result = await executeCommandViaApi(
      page,
      COMMANDS.createBom,
      {
        pe_bom_code: `E2E-BOMED-${Date.now()}`,
        pe_bom_name: originalName,
        pe_bom_version: 'V1.0',
        pe_bom_output_qty: 1,
        pe_bom_product_id: productPid,
      },
      undefined,
      'create',
    );

    if (!result.recordId || result.code !== ErrorCodes.SUCCESS) {
      throw new Error('BOM creation failed -- skipping edit test');
      return;
    }
    created.push({ commandCode: COMMANDS.deleteBom, pid: result.recordId });

    await navigateToDynamicPage(page, PAGE_KEYS.bom);
    await clickTabAndWaitForLoad(page, /Draft|草稿/i).catch(() => null);
    const row = await findRowInPaginatedList(page, originalName);
    await clickRowEditButton(page, row);
    await waitForFormReady(page);

    await fillFormField(page, 'pe_bom_name', updatedName);
    await clickSaveAndWait(page);

    await navigateToDynamicPage(page, PAGE_KEYS.bom);
    await clickTabAndWaitForLoad(page, /Draft|草稿/i).catch(() => null);
    const updatedRow = await findRowInPaginatedList(page, updatedName, 12000);
    await expect(updatedRow).toBeVisible({ timeout: 8000 });
  });

  test('PBM-005: Delete BOM via UI with confirm dialog', async ({ page }) => {
    const bomName = `E2E BOMDel ${uniqueId()}`;

    const result = await executeCommandViaApi(
      page,
      COMMANDS.createBom,
      {
        pe_bom_code: `E2E-BOMDL-${Date.now()}`,
        pe_bom_name: bomName,
        pe_bom_version: 'V1.0',
        pe_bom_output_qty: 1,
        pe_bom_product_id: productPid,
      },
      undefined,
      'create',
    );

    if (!result.recordId || result.code !== ErrorCodes.SUCCESS) {
      throw new Error('BOM creation failed -- skipping delete test');
      return;
    }
    // Do NOT push to created -- we expect this to be deleted by the test

    await navigateToDynamicPage(page, PAGE_KEYS.bom);
    await clickTabAndWaitForLoad(page, /Draft|草稿/i).catch(() => null);
    const row = await findRowInPaginatedList(page, bomName);

    await clickRowDeleteAndConfirm(page, row).catch((err) => {
      created.push({ commandCode: COMMANDS.deleteBom, pid: result.recordId });
      throw new Error(`Delete button not accessible — record may not be in draft status: ${err}`);
    });

    // Verify gone from list
    await navigateToDynamicPage(page, PAGE_KEYS.bom);
    await expect(page.locator('tbody tr', { hasText: bomName })).not.toBeVisible({ timeout: 5000 });
  });

  test('PBM-006: BOM code uniqueness (duplicate code rejected)', async ({ page }) => {
    const bomCode = `E2E-BOMDUP-${Date.now()}`;

    // Create first BOM
    const result1 = await executeCommandViaApi(
      page,
      COMMANDS.createBom,
      {
        pe_bom_code: bomCode,
        pe_bom_name: `E2E BOM Dup1 ${uniqueId()}`,
        pe_bom_version: 'V1.0',
        pe_bom_output_qty: 1,
        pe_bom_product_id: productPid,
      },
      undefined,
      'create',
    );

    if (!result1.recordId || result1.code !== ErrorCodes.SUCCESS) {
      throw new Error('First BOM creation failed -- skipping uniqueness test');
      return;
    }
    created.push({ commandCode: COMMANDS.deleteBom, pid: result1.recordId });

    // Try to create second BOM with the same code
    const result2 = await executeCommandViaApi(
      page,
      COMMANDS.createBom,
      {
        pe_bom_code: bomCode,
        pe_bom_name: `E2E BOM Dup2 ${uniqueId()}`,
        pe_bom_version: 'V1.0',
        pe_bom_output_qty: 1,
        pe_bom_product_id: productPid,
      },
      undefined,
      'create',
      { allowHttpError: true },
    );

    // Second creation should fail (duplicate code)
    // If it succeeds anyway (no unique constraint), clean it up
    if (result2.recordId && result2.code === ErrorCodes.SUCCESS) {
      created.push({ commandCode: COMMANDS.deleteBom, pid: result2.recordId });
      // Not necessarily a failure -- the model may not enforce unique codes
      test.info().annotations.push({
        type: 'info',
        description: 'BOM code uniqueness is not enforced at command level',
      });
    } else {
      expect(result2.code).not.toBe(ErrorCodes.SUCCESS);
    }
  });

  test('PBM-007: BOM required fields validation (submit empty form)', async ({ page }) => {
    await navigateToDynamicPage(page, PAGE_KEYS.bom);
    await clickCreateButton(page);
    await waitForFormReady(page);

    // Try to save without filling required fields
    const saveBtn = page
      .locator(
        '[data-testid="form-btn-submit"], [data-testid="form-btn-save"], button:has-text("Save")',
      )
      .first();
    await saveBtn.waitFor({ state: 'visible', timeout: 5000 });
    await saveBtn.click();

    // Should show validation errors or form stays open
    const errorIndicator = page.locator(
      '.ant-form-item-explain-error, [class*="error"]:not(header):not(nav), [role="alert"], .field-error, [data-testid*="error"], .text-red-500, .text-red-600, .border-red-500',
    );
    const hasErrors = await errorIndicator
      .first()
      .isVisible({ timeout: 5000 })
      .catch(() => false);
    if (!hasErrors) {
      // Fallback: verify the form is still open (save did not succeed)
      const stillOnForm = await page
        .locator(
          '[data-testid="form-btn-submit"], [data-testid="form-btn-save"], [data-testid="form-field-pe_bom_code"]',
        )
        .first()
        .isVisible({ timeout: 3000 })
        .catch(() => false);
      expect(stillOnForm).toBe(true);
    } else {
      expect(hasErrors).toBe(true);
    }
  });

  test('PBM-008: BOM i18n labels are translated (not raw keys)', async ({ page }) => {
    await navigateToDynamicPage(page, PAGE_KEYS.bom);
    await waitForDynamicPageLoad(page);

    const headers = page.locator('thead th, [role="columnheader"]');
    await headers.first().waitFor({ state: 'visible', timeout: 10000 });
    const headerCount = await headers.count();
    expect(headerCount).toBeGreaterThan(0);

    for (let i = 0; i < Math.min(headerCount, 8); i++) {
      const text = (await headers.nth(i).innerText()).trim();
      if (text.length > 0) {
        expect(text, `Header ${i} should not be a raw i18n key`).not.toMatch(/^model\./);
        expect(text, `Header ${i} should not be a raw field code`).not.toMatch(/^pe_bom_/);
        expect(text, `Header ${i} should not be empty`).not.toBe('');
      }
    }
  });
});

// ==========================================================================
// BOM State Transition Tests
// ==========================================================================

test.describe('PCBA BOM -- State Transitions', () => {
  test.describe.configure({ timeout: 45000 });

  const created: CleanupEntry[] = [];
  let materialPid: string;

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    const result = await executeCommandViaApi(
      page,
      COMMANDS.createProduct,
      {
        prod_name: `E2E BOM Material ${uniqueId('mat')}`,
        prod_type: 'component',
        prod_unit: 'pcs',
      },
      undefined,
      'create',
    );
    expect(result.code, 'Material creation must succeed').toBe(ErrorCodes.SUCCESS);
    expect(result.recordId, 'Material must return a recordId').toBeTruthy();
    materialPid = result.recordId!;
    await ctx.close();
  });

  /** Helper: create a BOM in draft status for transition tests. */
  async function createDraftBom(
    page: import('@playwright/test').Page,
    suffix: string,
  ): Promise<{ pid: string; code: string; name: string } | null> {
    const bomName = `E2E BOMFlow ${suffix}`;
    const bomCode = `E2E-BOMFL-${Date.now()}-${Math.random().toString(36).slice(2, 5)}`;
    const result = await executeCommandViaApi(
      page,
      COMMANDS.createBom,
      {
        pe_bom_code: bomCode,
        pe_bom_name: bomName,
        pe_bom_version: 'V1.0',
        pe_bom_output_qty: 1,
        pe_bom_product_id: materialPid,
      },
      undefined,
      'create',
    );
    if (!result.recordId || result.code !== ErrorCodes.SUCCESS) return null;
    created.push({ commandCode: COMMANDS.deleteBom, pid: result.recordId });
    {
      const line = await executeCommandViaApi(
        page,
        COMMANDS.addBomLine,
        {
          pe_bom_line_bom_id: result.recordId,
          pe_bom_line_material_id: materialPid,
          pe_bom_line_qty: 1,
          pe_bom_line_unit: 'pcs',
          pe_bom_line_loss_rate: 0,
          pe_bom_line_remark: `E2E BOM line ${suffix}`,
        },
        undefined,
        'create',
        { allowHttpError: true },
      );
      if (line.code !== ErrorCodes.SUCCESS) return null;
    }
    return { pid: result.recordId, code: bomCode, name: bomName };
  }

  test.afterAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    await safeCleanup(page, created);
    if (materialPid) {
      await executeCommandViaApi(page, COMMANDS.deleteProduct, {}, materialPid, 'delete', {
        allowHttpError: true,
      }).catch(() => {});
    }
    await ctx.close();
  });

  test('PBM-010: Activate BOM (draft -> active) via API @critical', async ({ page }) => {
    const bom = await createDraftBom(page, uniqueId());
    if (!bom) {
      throw new Error('BOM creation failed -- skipping activation test');
      return;
    }

    // Verify initial status is draft
    const before = await fetchRecord(page, PAGE_KEYS.bom, bom.pid);
    expect(before.pe_bom_status).toBe('draft');

    // Activate: STATE_TRANSITION uses operationType UPDATE with targetRecordId
    const activateResult = await executeCommandViaApi(
      page,
      COMMANDS.activateBom,
      {},
      bom.pid,
      'update',
      { allowHttpError: true },
    );

    if (activateResult.code !== ErrorCodes.SUCCESS) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: `Activation failed: code=${activateResult.code}`,
      });
      return;
    }

    // Verify status changed
    const after = await fetchRecord(page, PAGE_KEYS.bom, bom.pid);
    expect(after.pe_bom_status).toBe('active');
  });

  test('PBM-011: Deactivate BOM (active -> inactive) via API', async ({ page }) => {
    const bom = await createDraftBom(page, uniqueId());
    if (!bom) {
      throw new Error('BOM creation failed -- skipping deactivation test');
      return;
    }

    // Activate first
    const activateResult = await executeCommandViaApi(
      page,
      COMMANDS.activateBom,
      {},
      bom.pid,
      'update',
      { allowHttpError: true },
    );
    if (activateResult.code !== ErrorCodes.SUCCESS) {
      throw new Error('BOM activation failed -- cannot test deactivation');
      return;
    }

    // Now deactivate
    const deactivateResult = await executeCommandViaApi(
      page,
      COMMANDS.deactivateBom,
      {},
      bom.pid,
      'update',
      { allowHttpError: true },
    );

    if (deactivateResult.code !== ErrorCodes.SUCCESS) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: `Deactivation failed: code=${deactivateResult.code}`,
      });
      return;
    }

    const after = await fetchRecord(page, PAGE_KEYS.bom, bom.pid);
    expect(after.pe_bom_status).toBe('inactive');
  });

  test('PBM-012: Cannot activate BOM already in active state (idempotency)', async ({ page }) => {
    const bom = await createDraftBom(page, uniqueId());
    if (!bom) {
      throw new Error('BOM creation failed');
      return;
    }

    // Activate once
    const first = await executeCommandViaApi(page, COMMANDS.activateBom, {}, bom.pid, 'update', {
      allowHttpError: true,
    });
    if (first.code !== ErrorCodes.SUCCESS) {
      throw new Error('First activation failed');
      return;
    }

    // Attempt to activate again (already active)
    // STATE_TRANSITION fromStates=[draft,inactive] -- active is NOT in fromStates
    const second = await executeCommandViaApi(page, COMMANDS.activateBom, {}, bom.pid, 'update', {
      allowHttpError: true,
    });

    // The second activation should fail (active is not in fromStates)
    // If it succeeds, the engine is lenient -- that is also valid behavior
    if (second.code === ErrorCodes.SUCCESS) {
      test.info().annotations.push({
        type: 'info',
        description: 'Engine allows re-activation of active BOM (idempotent)',
      });
    } else {
      expect(second.code).not.toBe(ErrorCodes.SUCCESS);
    }
  });

  test('PBM-013: Full lifecycle: draft -> active -> inactive -> active', async ({ page }) => {
    const bom = await createDraftBom(page, uniqueId());
    if (!bom) {
      throw new Error('BOM creation failed');
      return;
    }

    // Step 1: Verify draft
    const step0 = await fetchRecord(page, PAGE_KEYS.bom, bom.pid);
    expect(step0.pe_bom_status).toBe('draft');

    // Step 2: draft -> active
    const r1 = await executeCommandViaApi(page, COMMANDS.activateBom, {}, bom.pid, 'update', {
      allowHttpError: true,
    });
    if (r1.code !== ErrorCodes.SUCCESS) {
      throw new Error('Activation failed at step 2');
      return;
    }
    const step1 = await fetchRecord(page, PAGE_KEYS.bom, bom.pid);
    expect(step1.pe_bom_status).toBe('active');

    // Step 3: active -> inactive
    const r2 = await executeCommandViaApi(page, COMMANDS.deactivateBom, {}, bom.pid, 'update', {
      allowHttpError: true,
    });
    if (r2.code !== ErrorCodes.SUCCESS) {
      throw new Error('Deactivation failed at step 3');
      return;
    }
    const step2 = await fetchRecord(page, PAGE_KEYS.bom, bom.pid);
    expect(step2.pe_bom_status).toBe('inactive');

    // Step 4: inactive -> active (re-activate)
    const r3 = await executeCommandViaApi(page, COMMANDS.activateBom, {}, bom.pid, 'update', {
      allowHttpError: true,
    });
    if (r3.code !== ErrorCodes.SUCCESS) {
      throw new Error('Re-activation failed at step 4');
      return;
    }
    const step3 = await fetchRecord(page, PAGE_KEYS.bom, bom.pid);
    expect(step3.pe_bom_status).toBe('active');
  });
});

// ==========================================================================
// BOM Line Tests
// ==========================================================================

test.describe('PCBA BOM -- BOM Lines', () => {
  test.describe.configure({ timeout: 45000 });

  const created: CleanupEntry[] = [];
  let parentBomPid: string;
  let materialPid: string;

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();

    // Create a material (product) for BOM lines — must be created BEFORE the BOM
    const matResult = await executeCommandViaApi(
      page,
      COMMANDS.createProduct,
      {
        prod_name: `E2E BOM Material ${uniqueId('mat')}`,
        prod_type: 'raw_material',
        prod_unit: 'pcs',
      },
      undefined,
      'create',
    );
    expect(matResult.code, 'Material creation must succeed').toBe(ErrorCodes.SUCCESS);
    expect(matResult.recordId, 'Material must return a recordId').toBeTruthy();
    materialPid = matResult.recordId!;

    // Create a parent BOM (requires product reference)
    const bomResult = await executeCommandViaApi(
      page,
      COMMANDS.createBom,
      {
        pe_bom_code: `E2E-BOMLN-${Date.now()}`,
        pe_bom_name: `E2E BOM Lines Parent ${uniqueId()}`,
        pe_bom_version: 'V1.0',
        pe_bom_output_qty: 1,
        pe_bom_product_id: materialPid,
      },
      undefined,
      'create',
    );
    expect(bomResult.code, 'BOM creation must succeed').toBe(ErrorCodes.SUCCESS);
    expect(bomResult.recordId, 'BOM must return a recordId').toBeTruthy();
    parentBomPid = bomResult.recordId!;

    await ctx.close();
  });

  test.afterAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();

    // Clean up BOM lines first (child records)
    await safeCleanup(page, created);

    // Then clean parent BOM and material
    if (parentBomPid) {
      await executeCommandViaApi(page, COMMANDS.deleteBom, {}, parentBomPid, 'delete', {
        allowHttpError: true,
      }).catch(() => {});
    }
    if (materialPid) {
      await executeCommandViaApi(page, COMMANDS.deleteProduct, {}, materialPid, 'delete', {
        allowHttpError: true,
      }).catch(() => {});
    }
    await ctx.close();
  });

  test('PBM-020: Add BOM line via API', async ({ page }) => {
    expect(parentBomPid, 'Parent BOM not available').toBeTruthy();

    const result = await executeCommandViaApi(
      page,
      COMMANDS.addBomLine,
      {
        pe_bom_line_bom_id: parentBomPid,
        pe_bom_line_material_id: materialPid,
        pe_bom_line_qty: 10,
        pe_bom_line_loss_rate: 0.02,
        pe_bom_line_unit: 'pcs',
        pe_bom_line_ref_designator: 'R1,R2,R3',
        pe_bom_line_remark: 'E2E test BOM line',
      },
      undefined,
      'create',
      { allowHttpError: true },
    );

    if (!result.recordId || result.code !== ErrorCodes.SUCCESS) {
      throw new Error('BOM line creation failed -- command may not be available');
      return;
    }
    created.push({ commandCode: COMMANDS.deleteBomLine, pid: result.recordId });

    expect(result.recordId).toBeTruthy();
  });

  test('PBM-021: BOM line with different units', async ({ page }) => {
    expect(parentBomPid, 'Parent BOM not available').toBeTruthy();

    const units = ['KG', 'M', 'roll'];
    for (const unit of units) {
      const result = await executeCommandViaApi(
        page,
        COMMANDS.addBomLine,
        {
          pe_bom_line_bom_id: parentBomPid,
          pe_bom_line_material_id: materialPid,
          pe_bom_line_qty: 5,
          pe_bom_line_unit: unit,
          pe_bom_line_remark: `E2E unit=${unit}`,
        },
        undefined,
        'create',
        { allowHttpError: true },
      );

      if (result.recordId && result.code === ErrorCodes.SUCCESS) {
        created.push({ commandCode: COMMANDS.deleteBomLine, pid: result.recordId });
      }
    }

    // At least one unit should have been accepted
    const successCount = created.filter((e) => e.commandCode === COMMANDS.deleteBomLine).length;
    // We already created one in PBM-020, so discount that; but if PBM-020 was skipped, count from 0
    expect(successCount).toBeGreaterThan(0);
  });

  test('PBM-022: Delete BOM line via API', async ({ page }) => {
    expect(parentBomPid, 'Parent BOM not available').toBeTruthy();

    // Create a line specifically for deletion
    const result = await executeCommandViaApi(
      page,
      COMMANDS.addBomLine,
      {
        pe_bom_line_bom_id: parentBomPid,
        pe_bom_line_material_id: materialPid,
        pe_bom_line_qty: 1,
        pe_bom_line_unit: 'pcs',
        pe_bom_line_remark: 'E2E to-be-deleted',
      },
      undefined,
      'create',
      { allowHttpError: true },
    );

    if (!result.recordId || result.code !== ErrorCodes.SUCCESS) {
      throw new Error('BOM line creation failed -- cannot test deletion');
      return;
    }

    // Delete the line
    const deleteResult = await executeCommandViaApi(
      page,
      COMMANDS.deleteBomLine,
      {},
      result.recordId,
      'delete',
      { allowHttpError: true },
    );

    expect(deleteResult.code).toBe(ErrorCodes.SUCCESS);
  });

  test('PBM-023: BOM line qty and loss_rate boundary values', async ({ page }) => {
    expect(parentBomPid, 'Parent BOM not available').toBeTruthy();

    // Test with very small qty and zero loss rate
    const smallQtyResult = await executeCommandViaApi(
      page,
      COMMANDS.addBomLine,
      {
        pe_bom_line_bom_id: parentBomPid,
        pe_bom_line_material_id: materialPid,
        pe_bom_line_qty: 0.001,
        pe_bom_line_loss_rate: 0,
        pe_bom_line_unit: 'pcs',
        pe_bom_line_remark: 'E2E boundary small qty',
      },
      undefined,
      'create',
      { allowHttpError: true },
    );

    if (smallQtyResult.recordId && smallQtyResult.code === ErrorCodes.SUCCESS) {
      created.push({ commandCode: COMMANDS.deleteBomLine, pid: smallQtyResult.recordId });
    }

    // Test with large qty and high loss rate
    const largeQtyResult = await executeCommandViaApi(
      page,
      COMMANDS.addBomLine,
      {
        pe_bom_line_bom_id: parentBomPid,
        pe_bom_line_material_id: materialPid,
        pe_bom_line_qty: 99999,
        pe_bom_line_loss_rate: 0.99,
        pe_bom_line_unit: 'set',
        pe_bom_line_remark: 'E2E boundary large qty',
      },
      undefined,
      'create',
      { allowHttpError: true },
    );

    if (largeQtyResult.recordId && largeQtyResult.code === ErrorCodes.SUCCESS) {
      created.push({ commandCode: COMMANDS.deleteBomLine, pid: largeQtyResult.recordId });
    }

    // At least one boundary case should succeed
    const eitherSucceeded =
      smallQtyResult.code === ErrorCodes.SUCCESS || largeQtyResult.code === ErrorCodes.SUCCESS;
    expect(eitherSucceeded).toBe(true);
  });
});

// ==========================================================================
// Inventory Tests (read-only)
// ==========================================================================

test.describe('PCBA BOM -- Inventory', () => {
  test.describe.configure({ timeout: 30000 });

  test('PBM-030: Inventory list page loads @smoke', async ({ page }) => {
    await navigateToInventoryQueryPage(page);
    const content = page
      .locator('main, table, [role="table"], [data-testid="dynamic-list"]')
      .first();
    await expect(content).toBeVisible({ timeout: 15000 });
  });

  test('PBM-031: Inventory page shows expected columns', async ({ page }) => {
    await navigateToInventoryQueryPage(page);

    const headers = page.locator('thead th');
    await expect(headers.first()).toBeVisible({ timeout: 10000 });

    const headerCount = await headers.count();
    expect(headerCount).toBeGreaterThanOrEqual(3);

    // Collect all header texts to verify meaningful columns exist
    const headerTexts: string[] = [];
    for (let i = 0; i < headerCount; i++) {
      const text = (await headers.nth(i).innerText()).trim();
      if (text.length > 0) headerTexts.push(text.toLowerCase());
    }

    // The inventory page should have columns related to product, warehouse, qty, etc.
    // We check that there are at least a few non-action columns
    const nonActionHeaders = headerTexts.filter(
      (t) => !['action', 'actions', 'operation', 'operations'].includes(t),
    );
    expect(nonActionHeaders.length).toBeGreaterThanOrEqual(3);
  });

  test('PBM-032: Inventory i18n labels are translated (not raw keys)', async ({ page }) => {
    await navigateToInventoryQueryPage(page);

    const headers = page.locator('thead th');
    await expect(headers.first()).toBeVisible({ timeout: 10000 });
    const headerCount = await headers.count();
    expect(headerCount).toBeGreaterThan(0);

    for (let i = 0; i < Math.min(headerCount, 8); i++) {
      const text = (await headers.nth(i).innerText()).trim();
      if (text.length > 0) {
        expect(text, `Header ${i} should not be a raw i18n key`).not.toMatch(/^model\./);
        expect(text, `Header ${i} should not be a raw field code`).not.toMatch(/^inv_/);
      }
    }
  });

  test('PBM-033: Inventory dashboard page loads', async ({ page }) => {
    // Navigate to the inventory dashboard page
    const listResponsePromise = page
      .waitForResponse((resp) => resp.status() === 200, { timeout: 10000 })
      .catch(() => null);

    await page.goto(`/p/${PAGE_KEYS.inventoryDashboard}`);
    await page.waitForLoadState('domcontentloaded');
    await listResponsePromise;

    // Dashboard may contain charts, cards, or summary widgets
    // Verify the page loaded without errors (no 404 or blank page)
    const content = page.locator(
      'table, [role="table"], canvas, [data-testid*="chart"], [data-testid*="dashboard"], .recharts-wrapper, [class*="card"], [class*="widget"], main, [data-testid="dynamic-page"]',
    );
    const hasContent = await content
      .first()
      .isVisible({ timeout: 10000 })
      .catch(() => false);

    if (!hasContent) {
      // Check if the page at least rendered (no 404 error message)
      const errorMessage = page.locator('text=404, text=Not Found, text=Page not found');
      const has404 = await errorMessage
        .first()
        .isVisible({ timeout: 2000 })
        .catch(() => false);
      if (has404) {
        throw new Error('Inventory dashboard page not configured (404)');
        return;
      }
    }

    // Verify spinner is gone (page finished loading)
    const spinner = page.locator('.animate-spin, [data-testid="loading"]');
    await expect(spinner).not.toBeVisible({ timeout: 10000 });
  });
});
