/**
 * CRM — Quote & Complaint Lifecycle E2E Tests
 *
 * QT-001 @smoke    : Navigate to 报价单 list → table visible
 * QT-002 @critical : Quote draft → REVIEWED → SENT → ACCEPTED lifecycle
 * QT-003 @critical : Quote reject branch: SENT → rejected → revise → draft
 * CP-001 @smoke    : Navigate to 投诉 list → table visible
 * CP-002 @critical : Complaint open → INVESTIGATING → resolved → closed lifecycle
 *
 * Menu paths (CRM root → direct leaf links):
 *   /p/crm-quote       → model: crm_quote
 *   /p/crm-complaint   → model: crm_complaint
 *
 * Prerequisites: crm plugin imported and all models published.
 *
 * @since 10.0.0
 */

import { test, expect, type Page } from '../../fixtures';
import { uniqueId, executeCommandViaApi } from '../helpers/index';

// ---------------------------------------------------------------------------
// Navigation helper
// ---------------------------------------------------------------------------

async function navigateToCrmPage(page: Page, leafName: string, modelCode: string): Promise<void> {
  await page.goto('/dashboards');
  await page.waitForLoadState('domcontentloaded');

  const nav = page.locator('nav');

  // Expand CRM root
  const crmBtn = nav.getByRole('button', { name: 'crm' }).first();
  await crmBtn.scrollIntoViewIfNeeded();
  await crmBtn.evaluate((el: HTMLElement) => el.click());

  // Brief pause for sidebar animation
  await page.waitForResponse(() => true, { timeout: 1_500 }).catch(() => null);

  // Use href-based selector for reliability (menu path uses hyphens)
  const hrefPath = `/p/${modelCode}`;
  const leafLink = nav
    .locator(`a[href="${hrefPath}"]`)
    .or(nav.getByRole('link', { name: leafName }))
    .first();
  await leafLink.waitFor({ state: 'attached', timeout: 8_000 });
  await leafLink.scrollIntoViewIfNeeded();

  // Set up waitForResponse BEFORE click
  const listResponsePromise = page
    .waitForResponse((r) => r.url().includes(`/api/dynamic/${modelCode}`) && r.status() === 200, {
      timeout: 15_000,
    })
    .catch(() => null);
  await leafLink.evaluate((el: HTMLElement) => el.click());
  await listResponsePromise;

  await expect(page.locator('table, [class*="ant-table"]').first()).toBeVisible({
    timeout: 10_000,
  });
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const UID = uniqueId('crmqc');

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------

test.describe('CRM — Quote & Complaint', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60_000);

  let mainQuoteId: string;
  let rejectQuoteId: string;
  let complaintId: string;

  // -------------------------------------------------------------------------
  // Setup: create test records via API
  // -------------------------------------------------------------------------

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({
      storageState: 'tests/storage/admin.json',
    });
    const page = await ctx.newPage();

    try {
      // Create main lifecycle quote
      const q1 = await executeCommandViaApi(
        page,
        'crm:create_quote',
        {
          crm_qt_name: `E2E Quote ${UID}`,
          crm_qt_currency: 'cny',
          crm_qt_notes: `E2E lifecycle ${UID}`,
        },
        undefined,
        'create',
      );
      mainQuoteId = q1.recordId;

      // Create reject-flow quote
      const q2 = await executeCommandViaApi(
        page,
        'crm:create_quote',
        {
          crm_qt_name: `E2E QuoteReject ${UID}`,
          crm_qt_currency: 'cny',
          crm_qt_notes: `E2E reject flow ${UID}`,
        },
        undefined,
        'create',
      );
      rejectQuoteId = q2.recordId;

      // Create an account first — complaint REFERENCE field needs a real PID
      const acct = await executeCommandViaApi(
        page,
        'crm:create_account',
        {
          crm_acc_name: `E2E Account ${UID}`,
          crm_acc_type: 'customer',
        },
        undefined,
        'create',
      );

      // Create complaint with real account PID and DATE format (YYYY-MM-DD)
      const c1 = await executeCommandViaApi(
        page,
        'crm:create_complaint',
        {
          crm_cmp_account_id: acct.recordId,
          crm_cmp_type: 'quality',
          crm_cmp_date: new Date().toISOString(),
          crm_cmp_description: `E2E complaint ${UID}`,
        },
        undefined,
        'create',
      );
      complaintId = c1.recordId;
    } finally {
      await ctx.close();
    }
  });

  // =========================================================================
  // Quote — 报价单
  // =========================================================================

  test('QT-001 @smoke: Navigate to 报价单 list via CRM menu', async ({ page }) => {
    await navigateToCrmPage(page, '报价单', 'crm_quote');

    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 8_000 });

    // i18n: headers must not show raw field codes
    const headerRow = page.locator('thead tr').first();
    await expect(headerRow).toBeVisible({ timeout: 5_000 });
    const headerText = await headerRow.textContent();
    expect(headerText).not.toMatch(/crm_qt_/i);
  });

  test('QT-002 @critical: Quote draft → REVIEWED → SENT → ACCEPTED', async ({ page }) => {
    expect(mainQuoteId).toBeTruthy();

    // Verify starts as draft
    let resp = await page.request.get(`/api/dynamic/crm_quote/${mainQuoteId}`);
    expect(resp.ok()).toBe(true);
    expect((await resp.json())?.data?.crm_qt_status ?? (await resp.json()).crm_qt_status).toBe(
      'draft',
    );

    // draft → REVIEWED
    await executeCommandViaApi(page, 'crm:review_quote', {}, mainQuoteId, 'state_transition');

    resp = await page.request.get(`/api/dynamic/crm_quote/${mainQuoteId}`);
    const reviewBody = await resp.json();
    expect((reviewBody?.data ?? reviewBody).crm_qt_status).toBe('reviewed');

    // REVIEWED → SENT
    await executeCommandViaApi(page, 'crm:send_quote', {}, mainQuoteId, 'state_transition');

    resp = await page.request.get(`/api/dynamic/crm_quote/${mainQuoteId}`);
    const sentBody = await resp.json();
    expect((sentBody?.data ?? sentBody).crm_qt_status).toBe('sent');

    // SENT → ACCEPTED
    await executeCommandViaApi(page, 'crm:accept_quote', {}, mainQuoteId, 'state_transition');

    resp = await page.request.get(`/api/dynamic/crm_quote/${mainQuoteId}`);
    const acceptedBody = await resp.json();
    expect((acceptedBody?.data ?? acceptedBody).crm_qt_status).toBe('accepted');

    // Verify on list UI
    await navigateToCrmPage(page, '报价单', 'crm_quote');
    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 8_000 });
  });

  test('QT-003 @critical: Quote reject branch — SENT → rejected → revise → draft', async ({
    page,
  }) => {
    expect(rejectQuoteId).toBeTruthy();

    // Advance to REVIEWED then SENT
    await executeCommandViaApi(page, 'crm:review_quote', {}, rejectQuoteId, 'state_transition');
    await executeCommandViaApi(page, 'crm:send_quote', {}, rejectQuoteId, 'state_transition');

    // SENT → rejected
    await executeCommandViaApi(page, 'crm:reject_quote', {}, rejectQuoteId, 'state_transition');

    let resp = await page.request.get(`/api/dynamic/crm_quote/${rejectQuoteId}`);
    const rejectedBody = await resp.json();
    expect((rejectedBody?.data ?? rejectedBody).crm_qt_status).toBe('rejected');

    // rejected → revise → draft
    await executeCommandViaApi(page, 'crm:revise_quote', {}, rejectQuoteId, 'state_transition');

    resp = await page.request.get(`/api/dynamic/crm_quote/${rejectQuoteId}`);
    const revisedBody = await resp.json();
    expect((revisedBody?.data ?? revisedBody).crm_qt_status).toBe('draft');
  });

  // =========================================================================
  // Complaint — 投诉
  // =========================================================================

  test('CP-001 @smoke: Navigate to 投诉 list via CRM menu', async ({ page }) => {
    await navigateToCrmPage(page, '投诉', 'crm_complaint');

    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 8_000 });

    // i18n check
    const headerRow = page.locator('thead tr').first();
    await expect(headerRow).toBeVisible({ timeout: 5_000 });
    const headerText = await headerRow.textContent();
    expect(headerText).not.toMatch(/crm_cmp_/i);
  });

  test('CP-002 @critical: Complaint open → INVESTIGATING → resolved → closed', async ({ page }) => {
    expect(complaintId).toBeTruthy();

    // Verify starts as open
    let resp = await page.request.get(`/api/dynamic/crm_complaint/${complaintId}`);
    expect(resp.ok()).toBe(true);
    const openBody = await resp.json();
    expect((openBody?.data ?? openBody).crm_cmp_status).toBe('open');

    // open → INVESTIGATING
    await executeCommandViaApi(
      page,
      'crm:investigate_complaint',
      {},
      complaintId,
      'state_transition',
    );

    resp = await page.request.get(`/api/dynamic/crm_complaint/${complaintId}`);
    const investigatingBody = await resp.json();
    expect((investigatingBody?.data ?? investigatingBody).crm_cmp_status).toBe('investigating');

    // INVESTIGATING → resolved
    await executeCommandViaApi(page, 'crm:resolve_complaint', {}, complaintId, 'state_transition');

    resp = await page.request.get(`/api/dynamic/crm_complaint/${complaintId}`);
    const resolvedBody = await resp.json();
    expect((resolvedBody?.data ?? resolvedBody).crm_cmp_status).toBe('resolved');

    // resolved → closed
    await executeCommandViaApi(page, 'crm:close_complaint', {}, complaintId, 'state_transition');

    resp = await page.request.get(`/api/dynamic/crm_complaint/${complaintId}`);
    const closedBody = await resp.json();
    expect((closedBody?.data ?? closedBody).crm_cmp_status).toBe('closed');

    // Verify on list UI
    await navigateToCrmPage(page, '投诉', 'crm_complaint');
    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 8_000 });
  });
});
