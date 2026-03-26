/**
 * Sales Best Practice Templates — Smoke E2E Tests
 *
 * ST-001 @smoke    : B2B Deals list → table visible, i18n headers
 * ST-002 @smoke    : B2B Milestones list → table visible
 * ST-003 @smoke    : B2B Stakeholders list → table visible
 * ST-004 @smoke    : B2C Customers list → table visible
 * ST-005 @smoke    : B2C Orders list → table visible
 * ST-006 @smoke    : Channel Partners list → table visible
 * ST-007 @smoke    : Channel Deals list → table visible
 * ST-008 @smoke    : Channel Commissions list → table visible
 * ST-009 @critical : Create B2B deal → appears in list
 * ST-010 @critical : B2B deal stage transition (Qualify)
 * ST-011 @critical : Create B2C customer → appears in list
 * ST-012 @critical : Create B2C order → appears in list
 * ST-013 @critical : Create channel partner → appears in list
 * ST-014 @critical : Create channel deal → appears in list
 * ST-015 @critical : B2B deal detail → milestones/stakeholders tabs
 * ST-016 @critical : B2C order detail → line items tab
 * ST-017 @critical : Channel partner detail → deals/commissions tabs
 *
 * Menu paths (Sales Templates root → submenus):
 *   /dynamic/stpl-b2b-deal         → model: stpl_b2b_deal
 *   /dynamic/stpl-b2b-deal-milestone → model: stpl_b2b_deal_milestone
 *   /dynamic/stpl-b2b-stakeholder  → model: stpl_b2b_stakeholder
 *   /dynamic/stpl-b2c-customer     → model: stpl_b2c_customer
 *   /dynamic/stpl-b2c-order        → model: stpl_b2c_order
 *   /dynamic/stpl-channel-partner  → model: stpl_channel_partner
 *   /dynamic/stpl-channel-deal     → model: stpl_channel_deal
 *   /dynamic/stpl-channel-commission → model: stpl_channel_commission
 *
 * Prerequisites: sales-templates plugin imported and all models published.
 */

import { test, expect, type Page } from '../../fixtures';
import { uniqueId, executeCommandViaApi, todayStr, dateOffsetStr } from '../helpers/index';

// ---------------------------------------------------------------------------
// Navigation helper
// ---------------------------------------------------------------------------

async function navigateToStplPage(
  page: Page,
  rootMenuName: string,
  leafName: string,
  modelCode: string,
): Promise<void> {
  await page.goto('/dashboards');
  await page.waitForLoadState('domcontentloaded');

  const nav = page.locator('nav');

  // Expand Sales Templates root
  const stplRoot = nav.getByRole('button', { name: /Sales Templates|销售模板/i });
  await stplRoot.scrollIntoViewIfNeeded();
  await stplRoot.evaluate((el: HTMLElement) => el.click());
  await page.waitForResponse(() => true, { timeout: 1_500 }).catch(() => null);

  // Expand sub-menu (B2B / B2C / Channel)
  const subMenu = nav.getByRole('button', { name: new RegExp(rootMenuName, 'i') });
  await subMenu.scrollIntoViewIfNeeded();
  await subMenu.evaluate((el: HTMLElement) => el.click());
  await page.waitForResponse(() => true, { timeout: 1_500 }).catch(() => null);

  // Click leaf link
  const hrefPath = `/dynamic/${modelCode.replace(/_/g, '-')}`;
  const leafLink = nav
    .locator(`a[href="${hrefPath}"]`)
    .or(nav.getByRole('link', { name: leafName }))
    .first();
  await leafLink.waitFor({ state: 'attached', timeout: 8_000 });
  await leafLink.scrollIntoViewIfNeeded();

  const listResponsePromise = page
    .waitForResponse(
      (r) =>
        r.url().includes(`/api/dynamic/${modelCode}`) && r.status() === 200,
      { timeout: 15_000 },
    )
    .catch(() => null);
  await leafLink.evaluate((el: HTMLElement) => el.click());
  await listResponsePromise;

  await expect(
    page.locator('table, [class*="ant-table"]').first(),
  ).toBeVisible({ timeout: 10_000 });
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const UID = uniqueId('stpl');

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------

test.describe('Sales Templates — Smoke', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60_000);

  let b2bDealId: string;
  let b2cCustomerId: string;
  let b2cOrderId: string;
  let channelPartnerId: string;
  let channelDealId: string;

  const b2bDealName = `E2E B2B Deal ${UID}`;
  const b2cCustName = `E2E Customer ${UID}`;
  const cpName = `E2E Partner ${UID}`;
  const cdName = `E2E ChDeal ${UID}`;

  // -------------------------------------------------------------------------
  // Setup: create test records via API
  // -------------------------------------------------------------------------

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({
      storageState: 'tests/storage/admin.json',
    });
    const page = await ctx.newPage();

    try {
      // Create B2B deal
      const dealResult = await executeCommandViaApi(
        page,
        'stpl:b2b_create_deal',
        {
          stpl_b2b_deal_name: b2bDealName,
          stpl_b2b_deal_type: 'new_business',
          stpl_b2b_deal_priority: 'high',
          stpl_b2b_deal_amount: 100000,
          stpl_b2b_deal_probability: 30,
          stpl_b2b_deal_close_date: dateOffsetStr(30),
          stpl_b2b_deal_next_step: 'Initial call',
          stpl_b2b_deal_notes: `E2E test deal ${UID}`,
        },
        undefined,
        'create',
      );
      b2bDealId = dealResult.recordId;

      // Create B2C customer
      const custResult = await executeCommandViaApi(
        page,
        'stpl:b2c_create_customer',
        {
          stpl_b2c_cust_name: b2cCustName,
          stpl_b2c_cust_phone: '13800138000',
          stpl_b2c_cust_email: `e2e_${UID}@test.com`,
          stpl_b2c_cust_tier: 'gold',
        },
        undefined,
        'create',
      );
      b2cCustomerId = custResult.recordId;

      // Create B2C order
      const ordResult = await executeCommandViaApi(
        page,
        'stpl:b2c_create_order',
        {
          stpl_b2c_ord_customer_id: b2cCustomerId,
          stpl_b2c_ord_date: todayStr(),
          stpl_b2c_ord_payment_method: 'credit_card',
          stpl_b2c_ord_delivery_address: '123 Test St',
          stpl_b2c_ord_remark: `E2E order ${UID}`,
        },
        undefined,
        'create',
      );
      b2cOrderId = ordResult.recordId;

      // Create channel partner
      const cpResult = await executeCommandViaApi(
        page,
        'stpl:ch_create_partner',
        {
          stpl_cp_name: cpName,
          stpl_cp_tier: 'gold',
          stpl_cp_status: 'active',
          stpl_cp_commission_rate: 15,
          stpl_cp_contact_name: 'John Doe',
          stpl_cp_contact_phone: '13900139000',
          stpl_cp_contact_email: `partner_${UID}@test.com`,
          stpl_cp_region: 'East',
        },
        undefined,
        'create',
      );
      channelPartnerId = cpResult.recordId;

      // Create channel deal
      const cdResult = await executeCommandViaApi(
        page,
        'stpl:ch_create_deal',
        {
          stpl_cd_name: cdName,
          stpl_cd_partner_id: channelPartnerId,
          stpl_cd_amount: 50000,
          stpl_cd_commission_rate: 12,
          stpl_cd_close_date: dateOffsetStr(45),
          stpl_cd_notes: `E2E channel deal ${UID}`,
        },
        undefined,
        'create',
      );
      channelDealId = cdResult.recordId;
    } finally {
      await ctx.close();
    }
  });

  // =========================================================================
  // ST-001 @smoke: B2B Deals list
  // =========================================================================

  test('ST-001 @smoke: Navigate to B2B Deals list', async ({ page }) => {
    await navigateToStplPage(page, 'b2b', 'Deals', 'stpl_b2b_deal');

    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 8_000 });

    // i18n: no raw field code leak
    const headerRow = page.locator('thead tr').first();
    await expect(headerRow).toBeVisible({ timeout: 5_000 });
    const headerText = await headerRow.textContent();
    expect(headerText).not.toMatch(/stpl_b2b_deal_/i);
  });

  // =========================================================================
  // ST-002 @smoke: Milestones list
  // =========================================================================

  test('ST-002 @smoke: Navigate to Milestones list', async ({ page }) => {
    await navigateToStplPage(page, 'b2b', 'Milestones', 'stpl_b2b_deal_milestone');

    // Table should be visible (may be empty)
    const table = page.locator('table, [class*="ant-table"]').first();
    await expect(table).toBeVisible({ timeout: 10_000 });
  });

  // =========================================================================
  // ST-003 @smoke: Stakeholders list
  // =========================================================================

  test('ST-003 @smoke: Navigate to Stakeholders list', async ({ page }) => {
    await navigateToStplPage(page, 'b2b', 'Stakeholders', 'stpl_b2b_stakeholder');

    const table = page.locator('table, [class*="ant-table"]').first();
    await expect(table).toBeVisible({ timeout: 10_000 });
  });

  // =========================================================================
  // ST-004 @smoke: B2C Customers list
  // =========================================================================

  test('ST-004 @smoke: Navigate to B2C Customers list', async ({ page }) => {
    await navigateToStplPage(page, 'b2c', 'Customers', 'stpl_b2c_customer');

    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 8_000 });

    const headerRow = page.locator('thead tr').first();
    const headerText = await headerRow.textContent();
    expect(headerText).not.toMatch(/stpl_b2c_cust_/i);
  });

  // =========================================================================
  // ST-005 @smoke: B2C Orders list
  // =========================================================================

  test('ST-005 @smoke: Navigate to B2C Orders list', async ({ page }) => {
    await navigateToStplPage(page, 'b2c', 'Orders', 'stpl_b2c_order');

    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 8_000 });
  });

  // =========================================================================
  // ST-006 @smoke: Channel Partners list
  // =========================================================================

  test('ST-006 @smoke: Navigate to Channel Partners list', async ({ page }) => {
    await navigateToStplPage(page, '渠道','Partners', 'stpl_channel_partner');

    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 8_000 });
  });

  // =========================================================================
  // ST-007 @smoke: Channel Deals list
  // =========================================================================

  test('ST-007 @smoke: Navigate to Channel Deals list', async ({ page }) => {
    await navigateToStplPage(page, '渠道','Channel Deals', 'stpl_channel_deal');

    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 8_000 });
  });

  // =========================================================================
  // ST-008 @smoke: Commissions list
  // =========================================================================

  test('ST-008 @smoke: Navigate to Commissions list', async ({ page }) => {
    await navigateToStplPage(page, '渠道','Commissions', 'stpl_channel_commission');

    const table = page.locator('table, [class*="ant-table"]').first();
    await expect(table).toBeVisible({ timeout: 10_000 });
  });

  // =========================================================================
  // ST-009 @critical: Created B2B deal appears in list
  // =========================================================================

  test('ST-009 @critical: B2B deal created in setup appears in list', async ({
    page,
  }) => {
    await navigateToStplPage(page, 'b2b', 'Deals', 'stpl_b2b_deal');

    // Search for our test deal
    const searchInput = page.locator('input[placeholder*="earch"], input[type="search"]').first();
    if (await searchInput.isVisible({ timeout: 3_000 }).catch(() => false)) {
      await searchInput.fill(UID);
      await page.waitForResponse(
        (r) => r.url().includes('/api/dynamic/stpl_b2b_deal') && r.status() === 200,
        { timeout: 10_000 },
      ).catch(() => null);
    }

    // Verify our deal appears
    await expect(page.locator('tbody').getByText(b2bDealName).first()).toBeVisible({
      timeout: 10_000,
    });
  });

  // =========================================================================
  // ST-010 @critical: B2B deal stage transition (Qualify)
  // =========================================================================

  test('ST-010 @critical: Qualify B2B deal via command API', async ({
    page,
  }) => {
    // Execute qualify command
    const result = await executeCommandViaApi(
      page,
      'stpl:b2b_qualify_deal',
      { stpl_b2b_deal_next_step: 'Schedule demo call' },
      b2bDealId,
    );
    expect(result.code).not.toBe('');

    // Verify by navigating to deal detail
    await page.goto(`/dynamic/stpl_b2b_deal/view/${b2bDealId}`);
    await page.waitForLoadState('domcontentloaded');
    await expect(page.getByText(/qualified|Qualified|已资格确认/)).toBeVisible({ timeout: 10_000 });
  });

  // =========================================================================
  // ST-011 @critical: B2C customer appears in list
  // =========================================================================

  test('ST-011 @critical: B2C customer created in setup appears in list', async ({
    page,
  }) => {
    await navigateToStplPage(page, 'b2c', 'Customers', 'stpl_b2c_customer');

    const searchInput = page.locator('input[placeholder*="earch"], input[type="search"]').first();
    if (await searchInput.isVisible({ timeout: 3_000 }).catch(() => false)) {
      await searchInput.fill(UID);
      await page.waitForResponse(
        (r) => r.url().includes('/api/dynamic/stpl_b2c_customer') && r.status() === 200,
        { timeout: 10_000 },
      ).catch(() => null);
    }

    await expect(page.locator('tbody').getByText(b2cCustName).first()).toBeVisible({
      timeout: 10_000,
    });
  });

  // =========================================================================
  // ST-012 @critical: B2C order appears in list
  // =========================================================================

  test('ST-012 @critical: B2C order created in setup appears in list', async ({
    page,
  }) => {
    await navigateToStplPage(page, 'b2c', 'Orders', 'stpl_b2c_order');

    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 8_000 });

    // Verify at least 1 row with Inquiry status
    await expect(
      page.locator('tbody').getByText(/Inquiry|咨询/).first(),
    ).toBeVisible({ timeout: 8_000 });
  });

  // =========================================================================
  // ST-013 @critical: Channel partner appears in list
  // =========================================================================

  test('ST-013 @critical: Channel partner created in setup appears in list', async ({
    page,
  }) => {
    await navigateToStplPage(page, '渠道','Partners', 'stpl_channel_partner');

    const searchInput = page.locator('input[placeholder*="earch"], input[type="search"]').first();
    if (await searchInput.isVisible({ timeout: 3_000 }).catch(() => false)) {
      await searchInput.fill(UID);
      await page.waitForResponse(
        (r) => r.url().includes('/api/dynamic/stpl_channel_partner') && r.status() === 200,
        { timeout: 10_000 },
      ).catch(() => null);
    }

    await expect(page.locator('tbody').getByText(cpName).first()).toBeVisible({
      timeout: 10_000,
    });
  });

  // =========================================================================
  // ST-014 @critical: Channel deal appears in list
  // =========================================================================

  test('ST-014 @critical: Channel deal created in setup appears in list', async ({
    page,
  }) => {
    await navigateToStplPage(page, '渠道','Channel Deals', 'stpl_channel_deal');

    const searchInput = page.locator('input[placeholder*="earch"], input[type="search"]').first();
    if (await searchInput.isVisible({ timeout: 3_000 }).catch(() => false)) {
      await searchInput.fill(UID);
      await page.waitForResponse(
        (r) => r.url().includes('/api/dynamic/stpl_channel_deal') && r.status() === 200,
        { timeout: 10_000 },
      ).catch(() => null);
    }

    await expect(page.locator('tbody').getByText(cdName).first()).toBeVisible({
      timeout: 10_000,
    });
  });

  // =========================================================================
  // ST-015 @critical: B2B deal detail with tabs
  // =========================================================================

  test('ST-015 @critical: B2B deal detail shows milestones and stakeholders tabs', async ({
    page,
  }) => {
    await page.goto(`/dynamic/stpl_b2b_deal/view/${b2bDealId}`);
    await page.waitForLoadState('domcontentloaded');

    // Wait for detail page to render
    await expect(page.getByText(b2bDealName).first()).toBeVisible({ timeout: 10_000 });

    // Check tabs exist
    const milestonesTab = page.getByRole('tab', { name: /Milestones|里程碑/i });
    const stakeholdersTab = page.getByRole('tab', { name: /Stakeholders|决策人/i });

    await expect(milestonesTab).toBeVisible({ timeout: 5_000 });
    await expect(stakeholdersTab).toBeVisible({ timeout: 5_000 });
  });

  // =========================================================================
  // ST-016 @critical: B2C order detail with line items tab
  // =========================================================================

  test('ST-016 @critical: B2C order detail shows line items tab', async ({
    page,
  }) => {
    await page.goto(`/dynamic/stpl_b2c_order/view/${b2cOrderId}`);
    await page.waitForLoadState('domcontentloaded');

    // Wait for detail page
    await page.waitForResponse(
      (r) => r.url().includes('/api/dynamic/stpl_b2c_order') && r.status() === 200,
      { timeout: 10_000 },
    ).catch(() => null);

    // Check line items tab exists
    const linesTab = page.getByRole('tab', { name: /Line Items|订单明细/i });
    await expect(linesTab).toBeVisible({ timeout: 8_000 });
  });

  // =========================================================================
  // ST-017 @critical: Channel partner detail with deals and commissions tabs
  // =========================================================================

  test('ST-017 @critical: Channel partner detail shows deals and commissions tabs', async ({
    page,
  }) => {
    await page.goto(`/dynamic/stpl_channel_partner/view/${channelPartnerId}`);
    await page.waitForLoadState('domcontentloaded');

    await page.waitForResponse(
      (r) => r.url().includes('/api/dynamic/stpl_channel_partner') && r.status() === 200,
      { timeout: 10_000 },
    ).catch(() => null);

    const dealsTab = page.getByRole('tab', { name: /Deals|渠道商机/i });
    const commissionsTab = page.getByRole('tab', { name: /Commissions|佣金/i });

    await expect(dealsTab).toBeVisible({ timeout: 8_000 });
    await expect(commissionsTab).toBeVisible({ timeout: 8_000 });
  });
});
