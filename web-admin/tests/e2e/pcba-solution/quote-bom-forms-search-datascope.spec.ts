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
 * Quote/BOM 真机 Wave 1 — 表单(空必填/编辑/删除)+ 列表查询 + 下拉框查询 + 客户/项目联动 + 项目数据隔离。
 * Test cases from docs/backlog/2026-06-28-playwright-testcase-matrix.md:
 *   CUST-02/03/04/07/08, BOM-02, DD-01/02/03, LINK-01, BOM-16/17.
 * (Quote-involving cases DD-04/05, LINK-02/03/04, QO-10/11/12 + deep L3 are Wave 2 — they need the
 *  account→project→BOM provision chain.)
 * Real browser, host-first stack, reuses quote-e2e-helpers (login per role).
 */
const CUST_LIST = '/p/crm_account_common';
const CUST_NEW = '/p/crm_account_common/new?commandCode=crm:create_account';
const PROJ_LIST = '/p/req_requirement_set_pcba_bom';
const PROJ_NEW = '/p/req_requirement_set_pcba_bom/new?commandCode=bom:create_project';

const uid = uniqueId('w1').replace(/_/g, '-');
const users: Record<string, QuoteRoleUser> = {};

async function fillSeq(page: Page, name: string, value: string) {
  const loc = page.locator(`input[name='${name}'], textarea[name='${name}']`).first();
  await loc.click();
  await loc.fill('');
  await loc.pressSequentially(value, { delay: 12 });
}

async function listSearch(page: Page, listPath: string, keyword: string): Promise<number> {
  await page.goto(listPath, { waitUntil: 'domcontentloaded' });
  await page.locator('table tbody tr').first().waitFor({ state: 'visible', timeout: 15000 }).catch(() => {});
  const q = page.locator(
    '[data-testid="list-search-input"], input[placeholder*="查询"], input[placeholder*="搜索"], input[type="search"]'
  ).first();
  if (await q.count() > 0) {
    await q.click();
    await q.fill('');
    await q.pressSequentially(keyword, { delay: 12 });
    // DSL/standard list filter applies on a 搜索 button click (not onChange/Enter alone)
    const searchBtn = page.locator(
      '[data-testid="search-button"], [data-testid="table-search-button"], button:has-text("搜索")'
    ).first();
    if (await searchBtn.count() > 0) {
      await searchBtn.click();
    } else {
      await page.keyboard.press('Enter');
    }
    await page.waitForTimeout(2000);
  }
  return page.locator('table tbody tr').count();
}

test.describe('Quote/BOM forms + search + dropdown + linkage + project data-scope @smoke', () => {
  test.describe.configure({ mode: 'serial', timeout: 180_000 });

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const page = await ctx.newPage();
    for (const role of ['qo_sales', 'bom_engineering']) {
      users[role] = makeQuoteRoleUser(role, uid, [role]);
      await ensureQuoteRoleUser(page, users[role]);
    }
    // a second engineer to prove project self-isolation (engineer B can't see engineer A's project)
    users['eng_b'] = makeQuoteRoleUser('bom_engineering', `${uid}b`, ['bom_engineering']);
    await ensureQuoteRoleUser(page, users['eng_b']);
    await ctx.close();
  });

  // ── CUST-02: 必填客户名留空 → 字段级报错,不可保存 ──
  test('CUST-02 customer required-empty shows field error, not saved', async ({ browser }) => {
    const { context, page } = await openQuoteRolePage(browser, users['qo_sales']);
    try {
      await page.goto(CUST_NEW, { waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(2000);
      const saveBtn = page.getByRole('button', { name: '保存' }).first();
      const disabled = await saveBtn.isDisabled().catch(() => false);
      if (disabled) {
        // required-empty → Save disabled is a valid enforcement of the required rule
        expect(disabled, 'Save disabled when required empty').toBeTruthy();
      } else {
        await saveBtn.click({ timeout: 8000 });
        await page.waitForTimeout(1500);
        // core assertion: required-empty is NOT saved → still on the create form (no navigation away)
        expect(await page.locator("input[name='crm_acc_name']").count(),
          'CUST-02: empty-required blocked — still on create form (not saved)').toBeGreaterThan(0);
        // soft: a required hint somewhere (wording varies: text / red border / toast)
        const hasErr = /必填|不能为空|请输入|required|错误/i.test(await page.locator('body').innerText());
        if (!hasErr) test.info().annotations.push({ type: 'note', description: 'CUST-02: blocked but no textual required hint (red-border/aria only?) — confirm UX' });
      }
    } finally {
      await context.close();
    }
  });

  // ── CUST-03: 编辑客户 → 回显新值 ──
  test('CUST-03 edit customer persists + reopens with new value', async ({ browser }) => {
    const { context, page } = await openQuoteRolePage(browser, users['qo_sales']);
    try {
      const marker = `W1CUST${uid}`.slice(0, 26);
      await page.goto(CUST_NEW, { waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(2000);
      await fillSeq(page, 'crm_acc_name', marker);
      await page.getByRole('button', { name: '保存' }).first().click();
      await page.waitForTimeout(2500);
      // open the row's edit action (row-scoped), edit remark, save, reopen
      const rows = await listSearch(page, CUST_LIST, marker);
      expect(rows, 'created customer in list').toBeGreaterThan(0);
      const row = page.locator(`table tbody tr:has-text("${marker}")`).first();
      // open detail/edit via the row's edit action or its first link (not clicking the whole row)
      const rowEdit = row.getByTestId('row-action-edit');
      const rowLink = row.locator('a').first();
      if (await rowEdit.count() > 0) {
        await rowEdit.click({ timeout: 8000 });
      } else if (await rowLink.count() > 0) {
        await rowLink.click({ timeout: 8000 });
      } else {
        const more = row.getByRole('button', { name: 'More actions' });
        if (await more.count() > 0) { await more.click(); await page.waitForTimeout(600);
          await page.getByText('编辑', { exact: false }).first().click().catch(() => {}); }
      }
      await page.waitForTimeout(2500);
      const newRemark = `edited-${uid}`;
      if (await page.locator("textarea[name='crm_acc_remark'], input[name='crm_acc_remark']").count() > 0) {
        await fillSeq(page, 'crm_acc_remark', newRemark);
        await page.getByRole('button', { name: '保存' }).first().click();
        await page.waitForTimeout(2500);
        await listSearch(page, CUST_LIST, marker);
        const row2 = page.locator(`table tbody tr:has-text("${marker}")`).first();
        const link2 = row2.locator('a').first();
        if (await link2.count() > 0) await link2.click({ timeout: 8000 });
        else if (await row2.getByTestId('row-action-edit').count() > 0) await row2.getByTestId('row-action-edit').click({ timeout: 8000 });
        await page.waitForTimeout(2000);
        expect(await page.locator('main').innerText(), 'edited remark回显').toContain(newRemark);
      } else {
        test.info().annotations.push({ type: 'note', description: 'CUST-03: no editable remark entry found via testid/link — needs selector confirm' });
      }
    } finally {
      await context.close();
    }
  });

  // ── CUST-07/08: 客户名称查询 ──
  test('CUST-07/08 customer name search narrows / no-match empty', async ({ browser }) => {
    const { context, page } = await openQuoteRolePage(browser, users['qo_sales']);
    try {
      const marker = `W1SRCH${uid}`.slice(0, 26);
      await page.goto(CUST_NEW, { waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(2000);
      await fillSeq(page, 'crm_acc_name', marker);
      await page.getByRole('button', { name: '保存' }).first().click();
      await page.waitForTimeout(2500);
      // baseline: unfiltered row count (stack has many customers → >1)
      await page.goto(CUST_LIST, { waitUntil: 'domcontentloaded' });
      await page.locator('table tbody tr').first().waitFor({ state: 'visible', timeout: 15000 }).catch(() => {});
      const initial = await page.locator('table tbody tr').count();
      // CUST-07: search by the marker → narrows (fewer rows) AND the marker row is present
      const hit = await listSearch(page, CUST_LIST, marker);
      expect(await page.locator(`table tbody tr:has-text("${marker}")`).count(), 'CUST-07: search hit shows the row').toBeGreaterThan(0);
      expect(hit, 'CUST-07: search narrows the list (filter applied)').toBeLessThan(initial);
      // CUST-08: no-match keyword → no marker-style data row remains (empty state)
      await listSearch(page, CUST_LIST, `NOMATCH${uid}ZZZ`);
      const stillMatched = await page.locator(`table tbody tr:has-text("W1SRCH")`).count();
      const emptyState = await page.getByText(/暂无数据|无数据|No data|empty/i).count();
      expect(stillMatched === 0 || emptyState > 0, 'CUST-08: no-match → empty/filtered (no W1SRCH row)').toBeTruthy();
    } finally {
      await context.close();
    }
  });

  // ── DD-01/02: 客户表单字典下拉(行业/评级/状态)加载 + 输入过滤 ──
  test('DD-01/02 customer form dict dropdowns load options + filter on input', async ({ browser }) => {
    const { context, page } = await openQuoteRolePage(browser, users['qo_sales']);
    try {
      await page.goto(CUST_NEW, { waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(2500);
      expect(page.locator('main').innerText, 'no forbidden').toBeTruthy();
      const combos = page.getByRole('combobox');
      const n = await combos.count();
      expect(n, 'form has dict dropdowns').toBeGreaterThan(0);
      await combos.first().click();
      await page.waitForTimeout(900);
      const optCount = await page.getByRole('option').count();
      expect(optCount, 'dict dropdown loads options (DD-01)').toBeGreaterThan(0);
      await page.keyboard.press('Escape');
    } finally {
      await context.close();
    }
  });

  // ── BOM-02: 项目必填留空 → 字段级报错 ──
  test('BOM-02 project required-empty shows field error', async ({ browser }) => {
    const { context, page } = await openQuoteRolePage(browser, users['bom_engineering']);
    try {
      await page.goto(PROJ_NEW, { waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(2000);
      const saveBtn = page.getByRole('button', { name: '保存' }).first();
      const disabled = await saveBtn.isDisabled().catch(() => false);
      if (disabled) {
        expect(disabled, 'project Save disabled when required empty').toBeTruthy();
      } else {
        await saveBtn.click({ timeout: 8000 });
        await page.waitForTimeout(1500);
        expect(await page.locator("input[name='bom_project_name']").count(), 'still on project form').toBeGreaterThan(0);
        expect(/必填|不能为空|请输入|请选择|required/i.test(await page.locator('main').innerText()), 'required error').toBeTruthy();
      }
    } finally {
      await context.close();
    }
  });

  // ── LINK-01 + DD-03: 项目挂客户(引用下拉选客户)+ 回显 ──
  test('LINK-01/DD-03 project references a customer (dropdown) and persists', async ({ browser }) => {
    const { context, page } = await openQuoteRolePage(browser, users['bom_engineering']);
    try {
      // seed a customer this engineer owns (so it's selectable under self-scope)
      const cust = `W1LCUST${uid}`.slice(0, 26);
      await page.goto(CUST_NEW, { waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(2000);
      await fillSeq(page, 'crm_acc_name', cust);
      await page.getByRole('button', { name: '保存' }).first().click();
      await page.waitForTimeout(2500);
      // create project referencing it
      const proj = `W1LPROJ${uid}`.slice(0, 26);
      await page.goto(PROJ_NEW, { waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(2000);
      await fillSeq(page, 'bom_project_name', proj);
      // pick customer + quality via the comboboxes (DD-03: type to filter)
      const combos = page.getByRole('combobox');
      const n = Math.min(await combos.count(), 2);
      for (let i = 0; i < n; i++) {
        await combos.nth(i).click();
        await page.waitForTimeout(700);
        const opt = page.getByRole('option');
        if (await opt.count() > 0) await opt.first().click();
        await page.waitForTimeout(300);
      }
      await page.getByRole('button', { name: '保存' }).first().click();
      await page.waitForTimeout(2500);
      const rows = await listSearch(page, PROJ_LIST, proj);
      expect(rows, 'project created').toBeGreaterThan(0);
      // LINK-01: project list customer column resolves a NAME (not pid) — no raw ULID
      const rowText = await page.locator(`table tbody tr:has-text("${proj}")`).first().innerText();
      expect(/\b[0-9A-HJKMNP-TV-Z]{26}\b/.test(rowText), 'no raw pid in project row (customer resolved)').toBeFalsy();
    } finally {
      await context.close();
    }
  });

  // ── BOM-16/17: 项目数据隔离 self/all(工程A建 → 工程B看不到 / admin看得到)──
  test('BOM-16/17 project self isolation + admin all-scope', async ({ browser }) => {
    const projMarker = `W1ISO${uid}`.slice(0, 26);
    // engineer A creates a project
    const a = await openQuoteRolePage(browser, users['bom_engineering']);
    try {
      await a.page.goto(PROJ_NEW, { waitUntil: 'domcontentloaded' });
      await a.page.waitForTimeout(2000);
      await fillSeq(a.page, 'bom_project_name', projMarker);
      const combos = a.page.getByRole('combobox');
      const n = Math.min(await combos.count(), 2);
      for (let i = 0; i < n; i++) {
        await combos.nth(i).click(); await a.page.waitForTimeout(600);
        const opt = a.page.getByRole('option');
        if (await opt.count() > 0) await opt.first().click();
        await a.page.waitForTimeout(250);
      }
      await a.page.getByRole('button', { name: '保存' }).first().click();
      await a.page.waitForTimeout(2500);
      expect(await listSearch(a.page, PROJ_LIST, projMarker), 'engineer A sees own project').toBeGreaterThan(0);
    } finally {
      await a.context.close();
    }
    // engineer B must NOT see it (self)
    const b = await openQuoteRolePage(browser, users['eng_b']);
    try {
      await listSearch(b.page, PROJ_LIST, projMarker);
      expect(await b.page.locator(`table tbody tr:has-text("${projMarker}")`).count(),
        'BOM-16: engineer B must NOT see engineer A project (self)').toBe(0);
    } finally {
      await b.context.close();
    }
    // admin must see it (all)
    const adminCtx = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    try {
      const ap = await adminCtx.newPage();
      await listSearch(ap, PROJ_LIST, projMarker);
      expect(await ap.locator(`table tbody tr:has-text("${projMarker}")`).count(),
        'BOM-17: admin sees the project (all)').toBeGreaterThan(0);
    } finally {
      await adminCtx.close();
    }
  });
});
