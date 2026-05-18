/**
 * Form Auto-Redirect Regression Test
 *
 * Regression test for the FetchExecutor.normalizeResult fix that converts
 * `result.code` from number to string, which broke form auto-redirect
 * after create/edit operations.
 *
 * When a form command succeeds, the built-in event handler calls `router.back`
 * (navigate(-1)) to return to the previous page. The fix ensures `result.code`
 * is always a string so the `success` flag is correctly derived, enabling
 * the redirect pipeline to fire.
 *
 * Models tested:
 * - showcase_all_fields (always available)
 * - crm_account (if CRM plugin is installed)
 *
 * Dimensions covered: D4 (create form), D8 (edit form), D14 (redirect feedback)
 *
 * @since 10.3.0
 */

import { test, expect, type Page } from '../../fixtures';
import {
  uniqueId,
  executeCommandViaApi,
  waitForFormReady,
  waitForDynamicPageLoad,
} from '../helpers/index';

test.describe.configure({ mode: 'serial' });

const UID = uniqueId('REDIR');

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Wait for the command execute API to respond with 200.
 */
function waitForCommandResponse(page: Page) {
  return page.waitForResponse(
    (r) =>
      r.url().includes('/api/meta/commands/execute/') &&
      r.request().method().toLowerCase() === 'post' &&
      r.status() === 200,
    { timeout: 20_000 },
  );
}

/**
 * Navigate to a list page and wait for it to fully load.
 * This establishes a browser history entry so router.back() has somewhere to go.
 */
async function gotoListAndWait(page: Page, modelCode: string): Promise<void> {
  const listApiPromise = page.waitForResponse(
    (r) => r.url().includes('/list') && r.status() === 200,
    { timeout: 20_000 },
  );
  await page.goto(`/p/${modelCode}`, { waitUntil: 'domcontentloaded', timeout: 30_000 });
  await listApiPromise.catch(() => null);
  await waitForDynamicPageLoad(page);
}

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------

test.describe('Form Auto-Redirect Regression (normalizeResult fix)', () => {
  test.setTimeout(90_000);

  let showcasePid: string;

  // Seed a showcase record for edit test
  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      const result = await executeCommandViaApi(
        page,
        'sc:create_showcase',
        {
          sc_name: `Redirect Edit Target ${UID}`,
          sc_description: `Record for edit redirect test ${UID}`,
          sc_quantity: 10,
          sc_price: 19.99,
          sc_priority: 'low',
          sc_category: 'electronics',
          sc_is_active: true,
        },
        undefined,
        'create',
      );
      showcasePid = result.recordId;
      expect(showcasePid, 'Seed record must be created').toBeTruthy();
    } finally {
      await ctx.close();
    }
  });

  // =========================================================================
  // Test 1: Showcase create -> auto-redirect back to list
  // =========================================================================
  test('Showcase create form submits and auto-redirects to list page', async ({ page }) => {
    // First navigate to list page to establish history entry
    await gotoListAndWait(page, 'showcase_all_fields');

    // Then navigate to create form — this pushes a new history entry
    await page.goto('/p/showcase_all_fields/new?commandCode=sc:create_showcase', {
      waitUntil: 'domcontentloaded',
      timeout: 30_000,
    });
    await waitForFormReady(page, 30_000);

    // Fill required field: sc_name
    const nameInput = page
      .locator('[data-testid="form-field-sc_name"] input, [data-field="sc_name"] input')
      .first();
    await nameInput.waitFor({ state: 'visible', timeout: 15_000 });
    await nameInput.fill(`Redirect Test Create ${UID}`);

    // Submit and wait for command response
    const submitBtn = page.locator('[data-testid="form-btn-submit"]').first();
    const submitBtnAlt = page.getByRole('button', { name: /提交|保存|Submit|Save/i }).first();
    const btn = (await submitBtn.isVisible({ timeout: 2_000 }).catch(() => false))
      ? submitBtn
      : submitBtnAlt;

    const cmdPromise = waitForCommandResponse(page);
    await btn.click();
    const cmdResp = await cmdPromise;
    const cmdBody = await cmdResp.json().catch(() => ({}));
    const cmdCode = String((cmdBody as any)?.code ?? '');
    expect(cmdCode, 'Command should succeed with code "0"').toBe('0');

    // KEY ASSERTION: URL should auto-redirect away from the form page
    // The fix ensures result.code is string '0' so the success flag is true
    // and the builtin event handler fires router.back (navigate(-1))
    await page.waitForURL(
      (url) => {
        const path = url.pathname;
        return !path.includes('/new') && !path.includes('/edit') && !path.includes('/create');
      },
      { timeout: 15_000 },
    );

    const currentUrl = page.url();
    expect(
      currentUrl,
      'Should redirect away from create form',
    ).not.toMatch(/\/(new|edit|create)/);
  });

  // =========================================================================
  // Test 2: Showcase edit -> auto-redirect back
  // =========================================================================
  test('Showcase edit form submits and auto-redirects away from edit page', async ({ page }) => {
    // First navigate to list page to establish history entry
    await gotoListAndWait(page, 'showcase_all_fields');

    // Then navigate to edit form
    await page.goto(
      `/p/showcase_all_fields/${showcasePid}/edit?commandCode=sc:update_showcase`,
      { waitUntil: 'domcontentloaded', timeout: 30_000 },
    );
    await waitForFormReady(page, 30_000);

    // Modify the name field — wait generously for Smart components to load
    const nameInput = page
      .locator('[data-testid="form-field-sc_name"] input, [data-field="sc_name"] input')
      .first();
    await nameInput.waitFor({ state: 'visible', timeout: 30_000 });
    await nameInput.clear();
    await nameInput.fill(`Edited Redirect Test ${UID}`);

    // Submit
    const submitBtn = page.locator('[data-testid="form-btn-submit"]').first();
    const submitBtnAlt = page.getByRole('button', { name: /提交|保存|Submit|Save/i }).first();
    const btn = (await submitBtn.isVisible({ timeout: 2_000 }).catch(() => false))
      ? submitBtn
      : submitBtnAlt;

    // Set up command response listener AFTER form is ready, before clicking
    const cmdPromise = page.waitForResponse(
      (r) =>
        r.url().includes('/api/meta/commands/execute/sc:update_showcase') &&
        r.request().method().toLowerCase() === 'post',
      { timeout: 20_000 },
    );
    await btn.click();
    const cmdResp = await cmdPromise;
    const cmdBody = await cmdResp.json().catch(() => ({}));
    const cmdCode = String((cmdBody as any)?.code ?? '');
    console.log(`[EDIT-REDIRECT] URL: ${cmdResp.url()}, status: ${cmdResp.status()}, code: ${cmdCode}`);
    expect(cmdCode, 'Update command should succeed with code "0"').toBe('0');

    // KEY ASSERTION: URL should auto-redirect away from edit page
    await page.waitForURL(
      (url) => !url.pathname.includes('/edit'),
      { timeout: 15_000 },
    );

    const currentUrl = page.url();
    expect(currentUrl, 'Should redirect away from edit form').not.toContain('/edit');
  });

  // =========================================================================
  // Test 3: CRM Account create -> auto-redirect (cross-model validation)
  // =========================================================================
  test('CRM Account create form auto-redirects after submit', async ({ page }) => {
    // Check if CRM Account model exists
    const listResp = await page.request
      .get('/api/dynamic/crm_account_list/list?pageNum=1&pageSize=1')
      .catch(() => null);

    if (!listResp || !listResp.ok()) {
      test.skip(true, 'CRM Account model not available (plugin not installed)');
      return;
    }

    // First navigate to CRM Account list to establish history entry
    await gotoListAndWait(page, 'crm_account');

    // Then navigate to create form
    await page.goto('/p/crm_account/new?commandCode=crm:create_account', {
      waitUntil: 'domcontentloaded',
      timeout: 30_000,
    });
    await waitForFormReady(page, 30_000);

    // Fill required field: crm_acc_name
    const nameInput = page
      .locator(
        '[data-testid="form-field-crm_acc_name"] input, [data-field="crm_acc_name"] input',
      )
      .first();
    await nameInput.waitFor({ state: 'visible', timeout: 15_000 });
    await nameInput.fill(`Redirect Test Account ${UID}`);

    // Submit
    const submitBtn = page.locator('[data-testid="form-btn-submit"]').first();
    const submitBtnAlt = page.getByRole('button', { name: /submit|save/i }).first();
    const btn = (await submitBtn.isVisible({ timeout: 2_000 }).catch(() => false))
      ? submitBtn
      : submitBtnAlt;

    const cmdPromise = waitForCommandResponse(page);
    await btn.click();
    const cmdResp = await cmdPromise;
    const cmdBody = await cmdResp.json().catch(() => ({}));
    const cmdCode = String((cmdBody as any)?.code ?? '');
    expect(cmdCode, 'CRM create command should succeed with code "0"').toBe('0');

    // KEY ASSERTION: auto-redirect away from form
    await page.waitForURL(
      (url) => {
        const path = url.pathname;
        return !path.includes('/new') && !path.includes('/edit') && !path.includes('/create');
      },
      { timeout: 15_000 },
    );

    const currentUrl = page.url();
    expect(
      currentUrl,
      'CRM Account should redirect away from create form',
    ).not.toMatch(/\/(new|edit|create)/);
  });
});
