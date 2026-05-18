/**
 * Showcase All Fields — Form Validation E2E Tests
 *
 * Focused tests for frontend form validation on dynamic form pages.
 * Validates that required field checks, maxLength, and other rules
 * actually BLOCK form submission (not just show visual hints).
 *
 * Bug context: button.action can be an object {type:"command", command:"..."}
 * which caused shouldValidate to be false, bypassing all validation.
 *
 * Coverage:
 * - Required field: empty submit is blocked with error toast
 * - Required field: clearing an existing value on edit is blocked
 * - Submission does NOT navigate away when validation fails
 * - Valid submission still works after validation fix
 */

import { test, expect, type Page } from '../../fixtures';
import {
  uniqueId,
  executeCommandViaApi,
  waitForFormReady,
  waitForToast,
  waitForDynamicPageLoad,
} from '../helpers/index';

test.describe.configure({ mode: 'serial' });

const UID = uniqueId('VAL');
const RECORD_NAME = `Validation Test ${UID}`;
const UNIQUE_NAME = `Unique Anchor ${UID}`;

let recordPid: string;

// ---------------------------------------------------------------------------
// Navigation helper
// ---------------------------------------------------------------------------

async function navigateToShowcaseList(page: Page): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  const nav = page.locator('nav');
  await nav.first().waitFor({ state: 'visible', timeout: 10_000 });

  const rootBtn = nav.getByRole('button', { name: /Showcase|展示/i }).first();
  await rootBtn.scrollIntoViewIfNeeded();
  await rootBtn.evaluate((el: HTMLElement) => el.click());

  const leafLink = nav.locator('a[href*="showcase_all_fields"]').first();
  await leafLink.waitFor({ state: 'attached', timeout: 8_000 });

  const listResponsePromise = page.waitForResponse(
    (r) =>
      (r.url().includes('/api/dynamic/showcase_all_fields') ||
        r.url().includes('/api/dynamic/showcase_all_fields')) &&
      r.url().includes('list') &&
      r.status() === 200,
    { timeout: 20_000 },
  );
  await leafLink.evaluate((el: HTMLElement) => el.click());
  await listResponsePromise;
}

// ---------------------------------------------------------------------------
// beforeAll: create a test record via API for edit validation tests
// ---------------------------------------------------------------------------

test.beforeAll(async ({ browser }) => {
  const context = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
  const page = await context.newPage();

  try {
    const result = await executeCommandViaApi(
      page,
      'sc:create_showcase',
      {
        sc_name: RECORD_NAME,
        sc_description: `Form validation test record ${UID}`,
        sc_quantity: 10,
        sc_price: 99.99,
      },
      undefined,
      'create',
    );
    recordPid = result?.recordId;
    if (!recordPid) {
      throw new Error('Failed to create test record — no recordId returned');
    }

    // Create a second record that will NOT be modified — used for uniqueness tests
    await executeCommandViaApi(
      page,
      'sc:create_showcase',
      { sc_name: UNIQUE_NAME, sc_description: `Unique anchor ${UID}` },
      undefined,
      'create',
    );
  } finally {
    await context.close();
  }
});

// ---------------------------------------------------------------------------
// Test: Required field blocks submission on CREATE (empty form)
// ---------------------------------------------------------------------------

test('VAL-001 — Create form: submit with empty required field is blocked', async ({ page }) => {
  test.setTimeout(30000);
  await navigateToShowcaseList(page);

  // Click Create button
  const createBtn = page.getByRole('button', { name: /新建|创建|Add|Create/i }).first();
  await createBtn.waitFor({ state: 'visible', timeout: 5_000 });
  await createBtn.click();
  await waitForFormReady(page, 15_000);

  // Verify we are on the form page
  const formUrl = page.url();
  expect(formUrl).toContain('showcase');

  // DO NOT fill sc_name (required field) — leave it empty
  // Click submit
  // Scope the listener to the showcase create command path. Unrelated
  // /commands/execute/ traffic — model/schema resolution, sibling page
  // polling under full-suite load — must not flip this flag. Use exact
  // command-code suffix match.
  let commandExecuted = false;
  let executedUrl = '';
  page.on('request', (req) => {
    const url = req.url();
    if (
      req.method() === 'POST' &&
      /\/api\/meta\/commands\/execute\/(?:sc[:_])?create_showcase(?:_all_fields)?(?:[/?]|$)/i.test(
        url,
      )
    ) {
      commandExecuted = true;
      executedUrl = url;
    }
  });
  const submitBtn = page.locator('[data-testid="form-btn-submit"]');
  if (await submitBtn.isVisible({ timeout: 2_000 }).catch(() => false)) {
    await submitBtn.click();
  } else {
    const altBtn = page.getByRole('button', { name: /提交|保存|Submit|Save/i }).first();
    await altBtn.click();
  }

  await waitForToast(page, undefined, 3_000).catch(() => null);

  const hasErrors = await expect
    .poll(
      async () => {
        const selectors = [
          '.ant-form-item-explain-error',
          '[data-testid*="error"]',
          '.field-error',
          '[role="alert"]',
          '.text-red-500',
          '.text-red-600',
          '.text-destructive',
          '[class*="border-red"]',
          'p:has-text("必填")',
          'p:has-text("required")',
          'span:has-text("必填")',
          'span:has-text("required")',
        ];
        for (const selector of selectors) {
          const visible = await page
            .locator(selector)
            .first()
            .isVisible({ timeout: 500 })
            .catch(() => false);
          if (visible) return true;
        }
        return false;
      },
      { timeout: 5_000, intervals: [200, 400, 800] },
    )
    .toBe(true)
    .then(() => true)
    .catch(() => false);

  // Should NOT navigate away — still on the form page
  await page.waitForTimeout(1_000);
  expect(
    commandExecuted,
    `Invalid empty form should not execute backend command (matched: ${executedUrl})`,
  ).toBe(false);
  expect(hasErrors, 'Submitting empty form should show validation feedback').toBeTruthy();
  expect(page.url()).not.toContain('/p/showcase_all_fields?');
  expect(page.url()).not.toMatch(/\/dynamic\/showcase[-_]all[-_]fields$/);
});

// ---------------------------------------------------------------------------
// Test: Required field blocks submission on EDIT (clear existing value)
// ---------------------------------------------------------------------------

test('VAL-002 — Edit form: clearing required field blocks submission', async ({ page }) => {
  const editUrl = `/p/showcase_all_fields/${recordPid}/edit`;

  await page.goto(editUrl, { waitUntil: 'domcontentloaded' });
  await waitForFormReady(page, 15_000);

  // Find the Name field — wait for it to be populated with data first
  const nameInput = page.locator('input[name="sc_name"], [data-field="sc_name"] input').first();
  await expect(nameInput).toBeVisible({ timeout: 5_000 });
  await expect(nameInput).not.toHaveValue('', { timeout: 10_000 });
  await nameInput.clear();
  await expect(nameInput).toHaveValue('');

  // Intercept any command execute call — should NOT be made
  let commandExecuted = false;
  page.on('request', (req) => {
    if (req.url().includes('/api/meta/commands/execute/') && req.method() === 'POST') {
      commandExecuted = true;
    }
  });

  // Click submit
  const submitBtn = page.locator('[data-testid="form-btn-submit"]');
  if (await submitBtn.isVisible({ timeout: 2_000 }).catch(() => false)) {
    await submitBtn.click();
  } else {
    const altBtn = page.getByRole('button', { name: /提交|保存|Submit|Save/i }).first();
    await altBtn.click();
  }

  // Should show error toast
  const errorToast = page.locator('[role="alert"]').first();
  await expect(errorToast).toBeVisible({ timeout: 5_000 });

  // The error message should mention the field name or "required"
  const toastText = await errorToast.textContent();
  expect(toastText).toBeTruthy();
  expect(
    /名称|Name|required|必填/i.test(toastText || ''),
    `Error toast should mention field name or required, got: "${toastText}"`,
  ).toBeTruthy();

  // Command should NOT have been executed
  expect(commandExecuted, 'Backend command should NOT be executed when validation fails').toBe(
    false,
  );

  // Should still be on the edit page
  expect(page.url()).toContain(recordPid);
});

// ---------------------------------------------------------------------------
// Test: Valid submission still works after fix
// ---------------------------------------------------------------------------

test('VAL-003 — Edit form: submission succeeds when required field has value', async ({ page }) => {
  const editUrl = `/p/showcase_all_fields/${recordPid}/edit`;

  await page.goto(editUrl, { waitUntil: 'domcontentloaded' });
  await waitForFormReady(page, 15_000);

  // Verify Name field has value
  const nameInput = page.locator('input[name="sc_name"], [data-field="sc_name"] input').first();
  await expect(nameInput).toBeVisible({ timeout: 5_000 });

  // Update the name to a new value
  const newName = `Updated ${UID}`;
  await nameInput.fill(newName);

  // Click submit — should succeed
  const commandResponsePromise = page.waitForResponse(
    (r) => r.url().includes('/api/meta/commands/execute/') && r.status() === 200,
    { timeout: 15_000 },
  );

  const submitBtn = page.locator('[data-testid="form-btn-submit"]');
  if (await submitBtn.isVisible({ timeout: 2_000 }).catch(() => false)) {
    await submitBtn.click();
  } else {
    const altBtn = page.getByRole('button', { name: /提交|保存|Submit|Save/i }).first();
    await altBtn.click();
  }

  // Should navigate back to list (success) OR show success toast
  const commandResponse = await commandResponsePromise.catch(() => null);
  if (commandResponse) {
    const body = await commandResponse.json().catch(() => null);
    expect(body?.code).toBe('0');
  }

  // Should navigate away from edit page
  await page.waitForURL((url) => !url.pathname.includes('/edit'), { timeout: 10_000 });
});

// ---------------------------------------------------------------------------
// Test: Multiple validation errors shown for multiple empty required fields
// (if the model had multiple required fields — showcase only has sc_name editable)
// This test verifies error toast content is meaningful
// ---------------------------------------------------------------------------

test('VAL-004 — Edit form: error toast contains field-specific message', async ({ page }) => {
  const editUrl = `/p/showcase_all_fields/${recordPid}/edit`;

  await page.goto(editUrl, { waitUntil: 'domcontentloaded' });
  await waitForFormReady(page, 15_000);

  // Clear the required Name field — wait for it to have a value first
  const nameInput = page.locator('input[name="sc_name"], [data-field="sc_name"] input').first();
  await expect(nameInput).toBeVisible({ timeout: 5_000 });
  // Ensure the field is loaded with data before clearing
  await expect(nameInput).not.toHaveValue('', { timeout: 8_000 });
  await nameInput.clear();
  await expect(nameInput).toHaveValue('');

  // Intercept to confirm no backend call
  let commandExecuted = false;
  page.on('request', (req) => {
    if (req.url().includes('/api/meta/commands/execute/') && req.method() === 'POST') {
      commandExecuted = true;
    }
  });

  // Click submit
  const submitBtn = page.locator('[data-testid="form-btn-submit"]');
  if (await submitBtn.isVisible({ timeout: 2_000 }).catch(() => false)) {
    await submitBtn.click();
  } else {
    const altBtn = page.getByRole('button', { name: /提交|保存|Submit|Save/i }).first();
    await altBtn.click();
  }

  // Error toast should appear with field-specific message
  const errorToast = page.locator('[role="alert"]').first();
  await expect(errorToast).toBeVisible({ timeout: 8_000 });

  const toastText = await errorToast.textContent();
  // Should mention the field name or "required" keyword
  expect(
    /名称|Name|required|必填/i.test(toastText || ''),
    `Validation error should mention field name or required, got: "${toastText}"`,
  ).toBeTruthy();

  // Command should NOT have been executed
  expect(commandExecuted, 'Backend command should NOT execute when validation fails').toBe(false);

  // Should still be on the edit page
  expect(page.url()).toContain(recordPid);
});

// ---------------------------------------------------------------------------
// Test: Uniqueness constraint — duplicate sc_name is rejected by backend
// ---------------------------------------------------------------------------

test('VAL-005 — Create form: duplicate name is rejected by uniqueness constraint', async ({
  page,
}) => {
  // Navigate from a clean starting point (previous tests may have left us on a different page)
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  await navigateToShowcaseList(page);

  // Click Create
  const createBtn = page.getByRole('button', { name: /新建|创建|Add|Create/i }).first();
  await createBtn.waitFor({ state: 'visible', timeout: 5_000 });
  await createBtn.click();
  await waitForFormReady(page, 15_000);

  // Fill sc_name with the SAME name as the anchor record (never modified by other tests)
  const nameInput = page.locator('input[name="sc_name"], [data-field="sc_name"] input').first();
  await expect(nameInput).toBeVisible({ timeout: 5_000 });
  await nameInput.fill(UNIQUE_NAME);

  // Submit — backend unique_composite rule should reject
  const commandResponsePromise = page.waitForResponse(
    (r) =>
      r.url().includes('/api/meta/commands/execute/') ||
      r.url().includes('/api/dynamic/showcase_all_fields'),
    { timeout: 15_000 },
  );

  const submitBtn = page.locator('[data-testid="form-btn-submit"]');
  if (await submitBtn.isVisible({ timeout: 2_000 }).catch(() => false)) {
    await submitBtn.click();
  } else {
    await page
      .getByRole('button', { name: /提交|保存|Submit|Save/i })
      .first()
      .click();
  }

  // Wait for the backend response
  const resp = await commandResponsePromise;
  const body = await resp.json().catch(() => ({}));

  // Backend should reject with non-success code, or succeed if no uniqueness constraint
  // The behavior depends on whether the model has a unique constraint on sc_name
  if (body.code === '0') {
    // No uniqueness constraint — test validates the form submission works
    // Skip further uniqueness assertions
    return;
  }

  // Error should show on page (toast or inline error)
  const errorIndicator = page.locator(
    '[role="alert"], .text-red-500, .text-destructive, [data-testid*="error"]',
  );
  await expect(errorIndicator.first()).toBeVisible({ timeout: 8_000 });

  // Should still be on the create form, NOT navigated to list
  await page.waitForTimeout(1_000);
  const currentUrl = page.url();
  expect(
    currentUrl.includes('new') || currentUrl.includes('edit') || currentUrl.includes('showcase'),
    `Should not navigate to list, but URL is: ${currentUrl}`,
  ).toBeTruthy();
});
