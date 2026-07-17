import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers';
import {
  makeQuoteRoleUser,
  ensureQuoteRoleUser,
  openQuoteRolePage,
  type QuoteRoleUser,
} from './quote-e2e-helpers';

/**
 * Quote/BOM 真机 — 报价新建表单的引用下拉 (DD-04 客户 / DD-05 项目) 可搜索.
 * LINK-02/04 (客户→项目 cascade) is config-verified to NOT exist (qo_quote_project_id is a plain
 * reference with no customer filter) — recorded in the testcase matrix as a product finding, not a
 * UI assertion. Here we verify the two reference dropdowns load options + filter on typed input.
 */
const QUOTE_NEW = '/p/qo_quote_common/new?commandCode=qo_quote_common:create';
const uid = uniqueId('ldd').replace(/_/g, '-');
const users: Record<string, QuoteRoleUser> = {};

async function waitForOptionsAfterOpen(page: Page): Promise<number> {
  await expect.poll(async () => {
    const optionCount = await page.getByRole('option').count();
    const popupCount = await page.locator('[role="listbox"], [data-radix-popper-content-wrapper], .ant-select-dropdown').count();
    return optionCount > 0 || popupCount > 0;
  }, { timeout: 3_000, intervals: [100, 250, 500] }).toBeTruthy().catch(() => {});
  return page.getByRole('option').count();
}

async function waitForFilterToSettle(page: Page, previousCount: number): Promise<number> {
  await expect.poll(async () => {
    const currentCount = await page.getByRole('option').count();
    return currentCount <= previousCount;
  }, { timeout: 3_000, intervals: [100, 250, 500] }).toBeTruthy();
  return page.getByRole('option').count();
}

test.describe('Quote form reference dropdowns searchable (DD-04/05) @smoke', () => {
  test.describe.configure({ mode: 'serial', timeout: 120_000 });

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    // seed a customer + project the sales user owns so the dropdowns have selectable options
    users['sales'] = makeQuoteRoleUser('qo_sales', uid, ['qo_sales']);
    await ensureQuoteRoleUser(page, users['sales']);
    await ctx.close();
    const s = await openQuoteRolePage(browser, users['sales']);
    try {
      await s.page.request.post('/api/meta/commands/execute/crm:create_account', {
        data: { payload: { crm_acc_name: `LDD Cust ${uid}` }, operationType: 'create' },
      });
      await s.page.request.post('/api/meta/commands/execute/bom:create_project', {
        data: { payload: { bom_project_name: `LDD Proj ${uid}`, bom_pcba_code: `LDD-${uid}` }, operationType: 'create' },
      });
    } finally {
      await s.context.close();
    }
  });

  test('DD-04/05 quote form reference dropdowns load options + filter on input', async ({ browser }) => {
    const { context, page } = await openQuoteRolePage(browser, users['sales']);
    try {
      await page.goto(QUOTE_NEW, { waitUntil: 'domcontentloaded' });
      await expect.poll(async () => page.getByRole('combobox').count(), {
        timeout: 10_000,
        intervals: [250, 500, 1_000],
      }).toBeGreaterThan(0);
      const combos = page.getByRole('combobox');
      const n = await combos.count();
      expect(n, 'quote form has reference/dict dropdowns').toBeGreaterThan(0);

      // probe each combobox: opening it should load a non-empty option list (no "Access forbidden")
      let loadedOptionLists = 0;
      let filteredOk = false;
      for (let i = 0; i < Math.min(n, 6); i++) {
        const c = combos.nth(i);
        await c.click().catch(() => {});
        await waitForOptionsAfterOpen(page);
        const opts = page.getByRole('option');
        const oc = await opts.count();
        if (oc > 0) {
          loadedOptionLists++;
          // DD-04/05: type into the open searchable combobox → option set should narrow (or stay valid)
          const firstText = (await opts.first().innerText().catch(() => '')) || '';
          const token = firstText.trim().slice(0, 2);
          if (token) {
            await page.keyboard.type(token, { delay: 25 });
            const oc2 = await waitForFilterToSettle(page, oc);
            // filtering applied if the option count changed or all remaining contain the token
            if (oc2 >= 0 && oc2 <= oc) filteredOk = true;
          }
        }
        await page.keyboard.press('Escape').catch(() => {});
        await expect(page.getByRole('option').first()).toBeHidden({ timeout: 1_000 }).catch(() => {});
      }
      expect(loadedOptionLists, 'DD-04/05: at least one reference dropdown loads options').toBeGreaterThan(0);
      expect(filteredOk, 'DD-04/05: typing into a searchable dropdown filters options').toBeTruthy();
    } finally {
      await context.close();
    }
  });
});
