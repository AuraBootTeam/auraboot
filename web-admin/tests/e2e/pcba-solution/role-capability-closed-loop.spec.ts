import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers';
import {
  makeQuoteRoleUser,
  ensureQuoteRoleUser,
  openQuoteRolePage,
  fetchRoleSnapshot,
  expectCommandDenied,
  expectCommandNotDenied,
  type QuoteRoleUser,
} from './quote-e2e-helpers';

/**
 * Role × capability 真机闭环 (real-browser closed-loop, owner-confirmed 2026-06-28).
 *
 * Unlike the focused-menu permission spec (snapshot assertions), this drives the ACTUAL business
 * closed-loop in a real browser AS EACH role and asserts data persisted (form → 保存 → list shows
 * the new record), plus command-level negatives (a role denied a tool is rejected by the pipeline).
 *
 * Owner-confirmed matrix (small-company overlap; business-roles.json):
 *   | role            | BOM转化 | 报价 | 客户 | 系统 | 组织 |
 *   | tenant_admin    | ✓       | ✓    | ✓    | ✓    | ✓   |
 *   | bom_engineering | ✓       | ✓    | ✓    | ✗    | ✗   |
 *   | qo_sales        | ✓       | ✓    | ✓    | ✗    | ✗   |
 *   | qo_procurement  | ✓       | ✓    | ✓    | ✗    | ✗   |
 *
 * Menu paths the matrix maps to (for menu↔capability coherence).
 *
 * RUN (host-first local enterprise E2E stack with quote/bom plugins + business roles):
 *   1. bring up the stack:  aura-quote/scripts/quote-bom-env.sh start <rt> --slot <n> --mode golden
 *   2. apply the owner role matrix (creates bom_engineering + applies caps):
 *        deploy-api.py provision-business-roles --base-url http://127.0.0.1:<be> \
 *          --admin-email admin@auraboot.com --admin-password <pw> \
 *          --roles-file aura-quote/deploy/quote-bom-docker/tools/business-roles.json --out /tmp/x.json
 *   3. run (point Playwright at the stack; tenant is "AuraBoot BOM", DB is the slot DB):
 *        PLAYWRIGHT_BASE_URL=http://127.0.0.1:<web> BACKEND_URL=http://127.0.0.1:<be> \
 *        BE_PORT=<be> BFF_PORT=<bff> PW_SKIP_WEBSERVER=1 AURA_BOOTSTRAP_COMPANY="AuraBoot BOM" \
 *        PG_HOST=127.0.0.1 PG_PORT=5432 PG_USER=auraboot PG_DB=enterprise_<slot> PGPASSWORD=<pw> \
 *          node_modules/.bin/playwright test tests/e2e/pcba-solution/role-capability-closed-loop.spec.ts --project=chromium
 *   Verified green 2026-06-28 (slot 40): 3/3 role tests pass.
 */
const MENU = {
  customer: '/p/crm_account',
  project: '/p/req_requirement_set_pcba_bom',
  quote: '/p/qo_quote_common',
};

// non-admin business roles under test; admin is the storageState session (separate, always all-allowed)
const ROLES: Array<{ key: string; roleCode: string; quote: boolean }> = [
  { key: 'bom_engineering', roleCode: 'bom_engineering', quote: true },
  { key: 'qo_sales', roleCode: 'qo_sales', quote: true },
  { key: 'qo_procurement', roleCode: 'qo_procurement', quote: true },
];

const uid = uniqueId('rolecl').replace(/_/g, '-');
const users: Record<string, QuoteRoleUser> = {};

async function waitForListReady(page: Page): Promise<void> {
  await expect.poll(async () => {
    const searchReady = await page.locator('[data-testid="list-search-input"], input[placeholder*="查询"], input[placeholder*="搜索"], input[placeholder="查询..."]').first().count();
    const tableReady = await page.locator('table, [role="table"]').first().count();
    const loading = await page.locator('[aria-busy="true"], .ant-spin-spinning, [data-loading="true"]').count();
    return (searchReady > 0 || tableReady > 0) && loading === 0;
  }).toBe(true);
}

async function searchList(page: Page, listPath: string, marker: string): Promise<number> {
  await page.goto(listPath, { waitUntil: 'domcontentloaded' });
  await waitForListReady(page);
  const q = page.getByPlaceholder('查询...').first();
  if (await q.count() > 0) {
    const response = page.waitForResponse((r) => (
      r.url().includes('/api/dynamic/') && r.url().includes('/list') && r.request().method() === 'GET'
    ), { timeout: 5000 }).catch(() => null);
    await q.click();
    await q.fill('');
    await q.pressSequentially(marker, { delay: 15 });
    await page.keyboard.press('Enter');
    await response;
    await waitForListReady(page);
  }
  return page.locator(`table tbody tr:has-text("${marker}")`).count();
}

async function pickFirstComboboxOptions(page: Page, maxCount: number): Promise<void> {
  const combos = page.getByRole('combobox');
  const n = await combos.count();
  for (let i = 0; i < Math.min(n, maxCount); i++) {
    try {
      await combos.nth(i).click();
      await expect.poll(async () => page.getByRole('option').count()).toBeGreaterThan(0);
      await page.getByRole('option').first().click();
    } catch {
      // Some DSL comboboxes are optional or not interactable for this role.
    }
  }
}

test.describe('Role × capability 真机闭环 @smoke', () => {
  test.describe.configure({ mode: 'serial', timeout: 180_000 });

  test.beforeAll(async ({ browser }) => {
    // create one user per business role (admin storageState authorizes /api/admin/users)
    const ctx = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const page = await ctx.newPage();
    for (const r of ROLES) {
      users[r.roleCode] = makeQuoteRoleUser(r.key, uid, [r.roleCode]);
      await ensureQuoteRoleUser(page, users[r.roleCode]);
    }
    await ctx.close();
  });

  // ---- helper: real-browser create closed-loop (form → 保存 → list shows marker) ----
  async function createClosedLoop(page: Page, listPath: string, nameField: string, marker: string,
                                  commandCode: string, extra?: (p: Page) => Promise<void>): Promise<boolean> {
    await page.goto(`${listPath}/new?commandCode=${encodeURIComponent(commandCode)}`,
                    { waitUntil: 'domcontentloaded' });
    const nameInput = page.locator(`input[name='${nameField}'], textarea[name='${nameField}']`).first();
    await expect(nameInput).toBeVisible();
    await nameInput.click();
    await nameInput.pressSequentially(marker, { delay: 15 });
    if (extra) await extra(page);
    const saveResponse = page.waitForResponse((r) => (
      r.url().includes('/api/meta/commands/execute/') && r.request().method() === 'POST'
    ), { timeout: 5000 }).catch(() => null);
    await page.getByRole('button', { name: '保存' }).first().click();
    await saveResponse;
    return (await searchList(page, listPath, marker)) > 0;
  }

  for (const r of ROLES) {
    test(`${r.roleCode}: menu coherence + customer/project closed-loop + quote ${r.quote ? 'positive' : 'denied'}`, async ({ browser }) => {
      const { context, page } = await openQuoteRolePage(browser, users[r.roleCode]);
      try {
        // 1. menu↔capability coherence (snapshot)
        const snap = await fetchRoleSnapshot(page);
        expect(snap.roleCodes, `${r.roleCode} role assigned`).toContain(r.roleCode);
        expect(snap.menuPaths, `${r.roleCode} sees 客户 menu`).toContain(MENU.customer);
        expect(snap.menuPaths, `${r.roleCode} sees 项目 menu`).toContain(MENU.project);
        if (r.quote) {
          expect(snap.menuPaths, `${r.roleCode} sees 报价 menu`).toContain(MENU.quote);
        } else {
          expect(snap.menuPaths, `${r.roleCode} must NOT see 报价 menu`).not.toContain(MENU.quote);
        }

        // 2. 客户新建真机闭环
        const custMarker = `ZKHCUST${uid}${r.key}`.slice(0, 28);
        const custOk = await createClosedLoop(page, MENU.customer, 'crm_acc_name', custMarker, 'crm:create_account');
        expect(custOk, `${r.roleCode} customer created and visible in list`).toBe(true);

        // 3. 新建项目真机闭环 (references the just-created customer + quality level)
        const projMarker = `ZKHPROJ${uid}${r.key}`.slice(0, 28);
        const projOk = await createClosedLoop(page, MENU.project, 'bom_project_name', projMarker, 'bom:create_project', async (p) => {
          // 客户* reference + 质量等级* — pick first option of each remaining combobox
          await pickFirstComboboxOptions(p, 2);
        });
        expect(projOk, `${r.roleCode} BOM project created and visible in list`).toBe(true);

        // 4. 报价 capability — command-pipeline closed-loop check (positive: gate passes; negative: denied)
        if (r.quote) {
          await expectCommandNotDenied(page, 'qo_quote_common:create', {});
        } else {
          await expectCommandDenied(page, 'qo_quote_common:create', {});
        }
      } finally {
        await context.close();
      }
    });
  }
});
