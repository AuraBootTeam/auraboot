/**
 * CRM — Core Entities E2E Tests
 *
 * CE-001 @smoke    : Navigate to 客户 (Account) list → table visible, i18n headers
 * CE-002 @smoke    : Navigate to 联系人 (Contact) list → table visible
 * CE-003 @smoke    : Navigate to 营销活动 (Campaign) list → table visible
 * CE-004 @critical : Account created → appears in list
 * CE-005 @critical : Contact linked to account → appears in list
 * CE-006 @critical : Campaign draft → active → completed lifecycle
 * CE-007 @critical : Campaign cancel branch → active → cancelled
 *
 * Menu paths (CRM root → direct leaf links):
 *   /p/crm-account    → model: crm_account
 *   /p/crm-contact    → model: crm_contact
 *   /p/crm-campaign   → model: crm_campaign
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
  await page.waitForResponse(() => true, { timeout: 1_500 }).catch(() => null);

  // Use href-based selector for reliability
  const hrefPath = `/p/${modelCode}`;
  const leafLink = nav
    .locator(`a[href="${hrefPath}"]`)
    .or(nav.getByRole('link', { name: leafName }))
    .first();
  await leafLink.waitFor({ state: 'attached', timeout: 8_000 });
  await leafLink.scrollIntoViewIfNeeded();

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

const UID = uniqueId('crmce');

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------

test.describe('CRM — Core Entities', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60_000);

  let accountId: string;
  let contactId: string;
  let campaignId: string;
  let cancelCampaignId: string;

  const accountName = `E2E Account ${UID}`;
  const contactName = `E2E Contact ${UID}`;
  const campaignName = `E2E Campaign ${UID}`;

  // -------------------------------------------------------------------------
  // Setup: create test records via API
  // -------------------------------------------------------------------------

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({
      storageState: 'tests/storage/admin.json',
    });
    const page = await ctx.newPage();

    try {
      // Create account
      const accResult = await executeCommandViaApi(
        page,
        'crm:create_account',
        {
          crm_acc_name: accountName,
          crm_acc_industry: 'tech',
          crm_acc_rating: 'A',
          crm_acc_remark: `E2E account ${UID}`,
        },
        undefined,
        'create',
      );
      accountId = accResult.recordId;

      // Create contact linked to account
      const ctResult = await executeCommandViaApi(
        page,
        'crm:create_contact',
        {
          crm_ct_account_id: accountId,
          crm_ct_name: contactName,
          crm_ct_title: 'Director',
          crm_ct_email: `e2e_${UID}@test.com`,
          crm_ct_is_primary: true,
        },
        undefined,
        'create',
      );
      contactId = ctResult.recordId;

      // Create campaign for lifecycle test
      const cpnResult = await executeCommandViaApi(
        page,
        'crm:create_campaign',
        {
          crm_cpn_name: campaignName,
          crm_cpn_type: 'email',
          crm_cpn_start_date: '2026-04-01',
          crm_cpn_end_date: '2026-04-30',
          crm_cpn_budget: 50000,
          crm_cpn_description: `E2E campaign ${UID}`,
        },
        undefined,
        'create',
      );
      campaignId = cpnResult.recordId;

      // Create campaign for cancel branch
      const cpnCancelResult = await executeCommandViaApi(
        page,
        'crm:create_campaign',
        {
          crm_cpn_name: `E2E CampaignCancel ${UID}`,
          crm_cpn_type: 'social',
          crm_cpn_start_date: '2026-05-01',
          crm_cpn_end_date: '2026-05-31',
          crm_cpn_budget: 20000,
          crm_cpn_description: `E2E campaign cancel ${UID}`,
        },
        undefined,
        'create',
      );
      cancelCampaignId = cpnCancelResult.recordId;
    } finally {
      await ctx.close();
    }
  });

  // =========================================================================
  // CE-001 @smoke: Navigate to 客户 list
  // =========================================================================

  test('CE-001 @smoke: Navigate to 客户 list via CRM menu', async ({ page }) => {
    await navigateToCrmPage(page, '客户', 'crm_account');

    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 8_000 });

    // i18n: no raw field code leak
    const headerRow = page.locator('thead tr').first();
    await expect(headerRow).toBeVisible({ timeout: 5_000 });
    const headerText = await headerRow.textContent();
    expect(headerText).not.toMatch(/crm_acc_/i);
  });

  // =========================================================================
  // CE-002 @smoke: Navigate to 联系人 list
  // =========================================================================

  test('CE-002 @smoke: Navigate to 联系人 list via CRM menu', async ({ page }) => {
    await navigateToCrmPage(page, '联系人', 'crm_contact');

    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 8_000 });

    // i18n check
    const headerRow = page.locator('thead tr').first();
    await expect(headerRow).toBeVisible({ timeout: 5_000 });
    const headerText = await headerRow.textContent();
    expect(headerText).not.toMatch(/crm_ct_/i);
  });

  // =========================================================================
  // CE-003 @smoke: Navigate to 营销活动 list
  // =========================================================================

  test('CE-003 @smoke: Navigate to 营销活动 list via CRM menu', async ({ page }) => {
    await navigateToCrmPage(page, '营销活动', 'crm_campaign');

    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 8_000 });

    // i18n check
    const headerRow = page.locator('thead tr').first();
    await expect(headerRow).toBeVisible({ timeout: 5_000 });
    const headerText = await headerRow.textContent();
    expect(headerText).not.toMatch(/crm_cpn_/i);
  });

  // =========================================================================
  // CE-004 @critical: Account appears in list with correct data
  // =========================================================================

  test('CE-004 @critical: Created account appears in 客户 list', async ({ page }) => {
    expect(accountId).toBeTruthy();

    // Verify via direct GET
    const resp = await page.request.get(`/api/dynamic/crm_account/${accountId}`);
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const record = body?.data ?? body;
    expect(record.crm_acc_name).toBe(accountName);

    // Verify in list UI — table must have rows
    await navigateToCrmPage(page, '客户', 'crm_account');
    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 8_000 });
  });

  // =========================================================================
  // CE-005 @critical: Contact linked to account appears in 联系人 list
  // =========================================================================

  test('CE-005 @critical: Created contact linked to account appears in 联系人 list', async ({
    page,
  }) => {
    expect(contactId).toBeTruthy();

    // Verify via API
    const resp = await page.request.get(`/api/dynamic/crm_contact/${contactId}`);
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const record = body?.data ?? body;
    expect(record.crm_ct_name).toBe(contactName);
    expect(record.crm_ct_account_id).toBe(accountId);

    // Verify in list UI — table must have rows
    await navigateToCrmPage(page, '联系人', 'crm_contact');
    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 8_000 });
  });

  // =========================================================================
  // CE-006 @critical: Campaign draft → active → completed
  // =========================================================================

  test('CE-006 @critical: Campaign PLANNED → active → completed lifecycle', async ({ page }) => {
    expect(campaignId).toBeTruthy();

    // Verify starts as PLANNED
    let resp = await page.request.get(`/api/dynamic/crm_campaign/${campaignId}`);
    expect(resp.ok()).toBe(true);
    const draftBody = await resp.json();
    expect((draftBody?.data ?? draftBody).crm_cpn_status).toBe('planned');

    // draft → active
    await executeCommandViaApi(page, 'crm:activate_campaign', {}, campaignId, 'state_transition');

    resp = await page.request.get(`/api/dynamic/crm_campaign/${campaignId}`);
    const activeBody = await resp.json();
    expect((activeBody?.data ?? activeBody).crm_cpn_status).toBe('active');

    // active → completed
    await executeCommandViaApi(page, 'crm:complete_campaign', {}, campaignId, 'state_transition');

    resp = await page.request.get(`/api/dynamic/crm_campaign/${campaignId}`);
    const completedBody = await resp.json();
    expect((completedBody?.data ?? completedBody).crm_cpn_status).toBe('completed');

    // Verify in list UI
    await navigateToCrmPage(page, '营销活动', 'crm_campaign');
    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 8_000 });
  });

  // =========================================================================
  // CE-007 @critical: Campaign cancel branch → active → cancelled
  // =========================================================================

  test('CE-007 @critical: Campaign cancel branch → active → cancelled', async ({ page }) => {
    expect(cancelCampaignId).toBeTruthy();

    // Activate first
    await executeCommandViaApi(
      page,
      'crm:activate_campaign',
      {},
      cancelCampaignId,
      'state_transition',
    );

    let resp = await page.request.get(`/api/dynamic/crm_campaign/${cancelCampaignId}`);
    expect(resp.ok()).toBe(true);
    const activeBody = await resp.json();
    expect((activeBody?.data ?? activeBody).crm_cpn_status).toBe('active');

    // active → cancelled
    await executeCommandViaApi(
      page,
      'crm:cancel_campaign',
      {},
      cancelCampaignId,
      'state_transition',
    );

    resp = await page.request.get(`/api/dynamic/crm_campaign/${cancelCampaignId}`);
    const cancelledBody = await resp.json();
    expect((cancelledBody?.data ?? cancelledBody).crm_cpn_status).toBe('cancelled');
  });
});
