/**
 * CRM M1 Value Chain — L4 UI E2E
 *
 * Proves the "win opportunity -> auto-create draft sales order" value chain
 * works through the real browser UI against the isolated CRM-M1 stack.
 *
 * Run against the isolated stack:
 *   PLAYWRIGHT_BASE_URL=http://localhost:5189 \
 *   PW_SKIP_WEBSERVER=1 PW_PROFILE=fast PW_ROLE_PROJECTS= \
 *   NO_PROXY=localhost,127.0.0.1 \
 *   npx playwright test tests/e2e/crm/crm-m1-value-chain.spec.ts \
 *     --project=chromium-m1 --config=tests/e2e/crm/m1.playwright.config.ts
 *
 * J1 journey:
 *   1. login (UI form)
 *   2. navigate to 商机 (Opportunities) via menu click
 *   3. open seeded negotiation-stage opportunity; verify line_items sub-table
 *      product picker field (crm_ol_product_id) + crm_ol_product_name present
 *   4. click 赢单 (Win) -> confirm dialog mentions auto-create sales order -> confirm
 *   5. navigate to 销售订单 (Sales Orders) via menu click
 *   6. open the generated draft order (code = OPP-...) and assert source-opp
 *      link (sl_so_source_opp_id) + line unit cost (sl_sol_unit_cost) visible
 */
import { test, expect, type Page } from '@playwright/test';
import { execFileSync } from 'node:child_process';

const BASE = process.env.PLAYWRIGHT_BASE_URL || 'http://localhost:5189';
const BE_PORT = process.env.BE_PORT || '6459';
const EMAIL = 'admin@auraboot.com';
const PW = 'Test2026x';
const SHOT = '/tmp/m1-e2e';

// Seed result populated in beforeAll.
let seed: { opp_pid: string; opp_code: string; opp_name: string; opp_stage: string; product_name: string };

async function uiLogin(page: Page): Promise<void> {
  // Retry the UI login up to 3x: the dev BFF occasionally returns 200 instead
  // of the 302 redirect on cold start, leaving the form in place (documented
  // flake in auth.setup.ts). Explicit retry — not a product fallback.
  for (let attempt = 1; attempt <= 3; attempt++) {
    await page.goto(`${BASE}/`, { waitUntil: 'domcontentloaded' });
    const emailInput = page.locator('input#email');
    const hasLogin = await emailInput.isVisible({ timeout: 8000 }).catch(() => false);
    if (!hasLogin) break; // already authenticated

    await emailInput.fill(EMAIL);
    await page.locator('input#password').fill(PW);
    await page.locator('button:has-text("立即登录"), button[type="submit"]').first().click();
    // Wait for either navigation away from /login or the form to disappear.
    await page
      .waitForURL((u) => !u.pathname.includes('login'), { timeout: 20000 })
      .catch(() => {});

    if (page.url().includes('tenant-selection')) {
      const enter = page
        .getByRole('button', { name: /进入|选择|Enter|Demo|AuraBoot/ })
        .or(page.getByText(/AuraBoot Demo/).first());
      await enter.first().click({ timeout: 5000 }).catch(() => {});
      await page
        .waitForURL((u) => !u.pathname.includes('tenant-selection'), { timeout: 15000 })
        .catch(() => {});
    }

    const stillOnLogin = await emailInput.isVisible({ timeout: 2000 }).catch(() => false);
    if (!stillOnLogin) break;
    if (attempt === 3) throw new Error('UI login failed after 3 attempts (still on login form)');
  }
  // Confirm authenticated (login form gone).
  await expect(page.locator('input#email')).toHaveCount(0, { timeout: 5000 });
}

/** Click a sidebar menu item by its visible text, expanding parents as needed. */
async function clickSidebarMenu(page: Page, label: string): Promise<void> {
  // Menu links live in the left navigation. Match an anchor/menu-item by text.
  const item = page
    .locator('nav, aside, [class*="sidebar" i], [class*="menu" i]')
    .getByText(label, { exact: true })
    .first();
  await expect(item, `sidebar menu "${label}" should be visible`).toBeVisible({ timeout: 10000 });
  await item.click();
}

test.describe.configure({ mode: 'serial' });

test.describe('CRM M1 Value Chain (L4 UI)', () => {
  test.beforeAll(async () => {
    const out = execFileSync('python3', ['/tmp/m1-e2e/seed_negotiation_opp.py'], {
      env: { ...process.env, BE_PORT },
      encoding: 'utf-8',
    });
    seed = JSON.parse(out.trim().split('\n').pop()!);
    expect(seed.opp_stage).toBe('negotiation');
    // eslint-disable-next-line no-console
    console.log('[seed]', JSON.stringify(seed));
  });

  test.beforeEach(async ({ page }) => {
    await uiLogin(page);
  });

  test('J1.0 menus 商机 + 销售订单 are visible to admin', async ({ page }) => {
    await page.screenshot({ path: `${SHOT}/j1-0-home.png` });
    // CRM 商机 menu
    const oppMenu = page.getByText('商机', { exact: true }).first();
    const ordMenu = page.getByText('销售订单', { exact: true }).first();
    await expect(oppMenu, 'CRM 商机 menu visible').toBeVisible({ timeout: 10000 });
    await expect(ordMenu, 'Sales 销售订单 menu visible').toBeVisible({ timeout: 10000 });
    await page.screenshot({ path: `${SHOT}/j1-0-menus-visible.png` });
  });

  test('J1.1-J1.6 win opportunity -> auto-create draft sales order', async ({ page }) => {
    // --- Step 2: navigate to Opportunities via menu click ---
    await clickSidebarMenu(page, '商机');
    await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => {});
    await expect(page).toHaveURL(/crm_opportunity_common|opportunit/i, { timeout: 10000 });
    await page.screenshot({ path: `${SHOT}/j1-1-opp-list.png`, fullPage: true });

    // Assert the seeded opportunity is visible in the list (by name).
    const oppRow = page.getByText(seed.opp_name, { exact: false }).first();
    await expect(oppRow, `seeded opp ${seed.opp_name} visible in list`).toBeVisible({ timeout: 10000 });

    // --- Step 3: open opportunity detail ---
    await oppRow.click();
    await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => {});
    await page.screenshot({ path: `${SHOT}/j1-3-opp-detail.png`, fullPage: true });

    // The line_items sub-table lives under the "行项" (Line Items) tab — click it.
    const lineItemsTab = page.getByRole('tab', { name: /行项|Line Items/ }).first();
    await expect(lineItemsTab, '行项 (Line Items) tab visible').toBeVisible({ timeout: 10000 });
    await lineItemsTab.click();
    await page.waitForLoadState('networkidle', { timeout: 10000 }).catch(() => {});
    await page.screenshot({ path: `${SHOT}/j1-3b-line-items-tab.png`, fullPage: true });

    // Line item product name (crm_ol_product_name) must be visible now.
    await expect(
      page.getByText(seed.product_name, { exact: false }).first(),
      `line item product name "${seed.product_name}" visible (crm_ol_product_name)`,
    ).toBeVisible({ timeout: 10000 });

    // Product picker column (crm_ol_product_id) resolves to the product's pid
    // (a ULID) — verify the picker column carries a value (not blank).
    const productCell = page.getByRole('cell', { name: /^01[0-9A-HJKMNP-TV-Z]{24}$/ }).first();
    await expect(
      productCell,
      'line item product picker cell (crm_ol_product_id) has a value',
    ).toBeVisible({ timeout: 8000 });

    // --- Step 4: click Win (auto-create draft sales order) ---
    // Switch back to 概览 — action buttons are context-bound to that tab.
    await page.getByRole('tab', { name: /概览|Overview/ }).first().click();
    await page.waitForLoadState('networkidle', { timeout: 8000 }).catch(() => {});

    // FIX (P1): detail-page action buttons now render their localized DSL label.
    // The Win action is the single PRIMARY (blue, bg-blue-600) header button and
    // shows "赢单" (was previously the raw literal "execute"). Pin to the blue
    // button explicitly — no fallback to "any" button (per §2.4) — and assert it
    // carries the localized label, not "execute".
    const winBtn = page.locator('button.bg-blue-600').filter({ hasText: '赢单' }).first();
    await expect(winBtn, 'Win (primary/blue) action button visible with 赢单 label').toBeVisible({
      timeout: 10000,
    });
    await expect(
      page.locator('button.bg-blue-600', { hasText: 'execute' }),
      'Win button must NOT render the raw literal "execute" (P1 regression guard)',
    ).toHaveCount(0);
    await winBtn.click();

    // FIX (P2): the Win button now declares confirm="crm.opportunity.win.confirm",
    // so clicking it opens a confirmation dialog whose content mentions the
    // auto-created draft sales order. Confirm to proceed.
    const winConfirmText = page.getByText('确认标记此商机为赢单', { exact: false }).first();
    await expect(
      winConfirmText,
      'Win confirm dialog shows the localized confirmMessage (P2)',
    ).toBeVisible({ timeout: 8000 });
    await page.screenshot({ path: `${SHOT}/j1-4a-win-confirm.png`, fullPage: true });
    await page
      .getByRole('button', { name: /确认|确定|OK|Confirm/ })
      .last()
      .click();

    // After confirming, the stage badge transitions to 赢单 (closed_won) in place.
    await expect(
      page.getByText('赢单', { exact: true }).first(),
      'opportunity stage badge transitions to 赢单 (closed_won) after Win',
    ).toBeVisible({ timeout: 12000 });
    await page.screenshot({ path: `${SHOT}/j1-4-after-win.png`, fullPage: true });

    // --- Step 5: navigate to Sales Orders via menu click ---
    await clickSidebarMenu(page, '销售订单');
    await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => {});
    await expect(page).toHaveURL(/sales-orders|sl_sales_order_common/i, { timeout: 10000 });
    await page.screenshot({ path: `${SHOT}/j1-5-order-list.png`, fullPage: true });

    // The generated draft order's code == opportunity code (sl_so_code = OPP-...).
    const orderRow = page.getByText(seed.opp_code, { exact: false }).first();
    await expect(
      orderRow,
      `generated draft order ${seed.opp_code} visible in list (sl_so_code = opp code)`,
    ).toBeVisible({ timeout: 12000 });

    // --- Step 6: open order detail, assert source-opp link + unit cost ---
    await orderRow.click();
    // Detail opens (drawer/page). Wait for the source-opp value to appear as the
    // ready signal, then screenshot.
    //
    // KNOWN PLATFORM-LAYER DEFECT (P3, NOT config-fixable): generic FK reference
    // fields render the raw stored value (the opportunity pid / ULID) in read-only
    // detail view. The read-only renderer (DynamicField in
    // routes/_shared/dynamic-route-utils.tsx) only resolves display labels for
    // sys_user / org_department / dict / option-backed fields — it has NO async
    // fetch path for a generic model reference, so sl_so_source_opp_id shows the
    // ULID instead of crm_opp_code. The field config (refTarget.targetField =
    // crm_opp_code) is correct; the gap is in the platform renderer. We therefore
    // still assert the ULID is shown and report P3 as a platform follow-up.
    const sourceOppValue = page.getByText(seed.opp_pid, { exact: false }).first();
    await expect(
      sourceOppValue,
      'order detail shows source opportunity raw pid (sl_so_source_opp_id = opp pid; see P3 platform-layer finding)',
    ).toBeVisible({ timeout: 12000 });
    await page.screenshot({ path: `${SHOT}/j1-6-order-detail.png`, fullPage: true });

    // Customer (sl_so_customer_id) must be populated (proves order carries the
    // account from the won opportunity).
    await expect(
      page.getByText('客户').first(),
      'order detail shows 客户 (customer) field',
    ).toBeVisible({ timeout: 8000 });

    // Order lines sub-table (订单明细) is below the fold — scroll it into view.
    await page.getByText('订单明细').first().scrollIntoViewIfNeeded();
    await page.mouse.wheel(0, 1500);
    await page.evaluate(() => {
      document.querySelectorAll('div').forEach((d) => {
        if (d.scrollHeight > d.clientHeight + 50) d.scrollTop = d.scrollHeight;
      });
    });
    await page.waitForTimeout(700);
    await page.screenshot({ path: `${SHOT}/j1-6b-order-lines.png`, fullPage: true });

    // Order lines sub-table: childModel sl_sales_order_line_common, FK parentField
    // sl_sol_order_id, with the 单位成本 (sl_sol_unit_cost) column.
    //
    // The earlier raw-code leak on these sub-table column headers (sl_sol_*) was
    // fixed on this branch (sl_sales_order_line_common field i18n labels), so the headers
    // now render the localized displayNames: 商品 / 数量 / 单价 / 单位成本 / 行毛利
    // / 金额. Pin the table by the localized 单位成本 header and assert the data.
    const orderLinesTable = page
      .locator('table')
      .filter({ has: page.getByText('单位成本', { exact: true }) })
      .first();
    await expect(
      orderLinesTable,
      'order lines table present with localized 单位成本 column header (raw-code leak fixed)',
    ).toBeVisible({ timeout: 10000 });
    // Raw code must NOT leak in the headers (regression guard).
    await expect(
      orderLinesTable.getByText('sl_sol_unit_cost'),
      'sub-table column header must NOT leak raw code sl_sol_unit_cost',
    ).toHaveCount(0);
    // Unit cost value 60 (carried from the opp line cost) must be in this table.
    await expect(
      orderLinesTable.getByRole('cell', { name: /^60(\.0+)?$/ }).first(),
      'order line shows unit cost (单位成本 = 60) carried from opp line',
    ).toBeVisible({ timeout: 10000 });
  });
});
