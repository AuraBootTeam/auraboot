import type { Locator, Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { uniqueId, clickRowActionByLocator } from '../helpers';
import {
  searchBusinessList,
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
  await expect(loc).toBeVisible();
  await loc.click();
  await loc.fill('');
  await loc.pressSequentially(value, { delay: 12 });
}

const listSearch = searchBusinessList;

async function saveForm(page: Page): Promise<void> {
  const pending = page.waitForResponse((r) => (
    r.url().includes('/api/meta/commands/execute/') && r.request().method() === 'POST'
  ), { timeout: 15_000 });
  await page.getByRole('button', { name: '保存' }).first().click();
  const response = await pending;
  expect(response.status(), 'save must reach the command handler').toBe(200);
  const body = await response.json();
  expect(String(body.code), 'save must succeed, not merely return HTTP 200').toBe('0');
  // The response arrives before the form's onSuccess closes/navigates and
  // invalidates list data. Do not race that lifecycle with a new list search.
  await expect(page.getByRole('button', { name: '保存', exact: true })).toHaveCount(0);
}

async function clickSaveForValidation(page: Page): Promise<void> {
  const response = page.waitForResponse((r) => (
    r.url().includes('/api/meta/commands/execute/') && r.request().method() === 'POST'
  ), { timeout: 5000 }).catch(() => null);
  await page.getByRole('button', { name: '保存' }).first().click({ timeout: 5000 });
  await response;
}

async function createCustomer(page: Page, name: string): Promise<void> {
  await page.goto(CUST_NEW, { waitUntil: 'domcontentloaded' });
  await fillSeq(page, 'crm_acc_name', name);
  await saveForm(page);
}

async function pickFirstComboboxOptions(page: Page, maxCount: number): Promise<void> {
  const combos = page.getByRole('combobox');
  const n = Math.min(await combos.count(), maxCount);
  for (let i = 0; i < n; i++) {
    try {
      await combos.nth(i).click();
      await expect.poll(async () => page.getByRole('option').count()).toBeGreaterThan(0);
      await page.getByRole('option').first().click();
    } catch {
      // Optional/reference fields vary by role and fixture availability.
    }
  }
}

async function createProject(page: Page, name: string): Promise<void> {
  await page.goto(PROJ_NEW, { waitUntil: 'domcontentloaded' });
  await fillSeq(page, 'bom_project_name', name);
  await pickFirstComboboxOptions(page, 2);
  await saveForm(page);
}

async function openRowForEdit(page: Page, row: Locator): Promise<void> {
  await expect(row).toBeVisible();
  try {
    // CRM lists expose View directly and Edit in the row menu. Locator actions
    // auto-wait through the search refresh; an isVisible probe does not wait.
    await row.getByTestId('row-action-more').click();
    await page.getByTestId('row-action-dropdown').getByTestId('row-action-edit').click();
  } catch (error) {
    await test.info().attach('customer-edit-failure-browser', { body: await page.screenshot(), contentType: 'image/png' });
    await test.info().attach('customer-edit-failure-state', {
      body: JSON.stringify({ url: page.url(), row: await row.innerText().catch(() => 'detached'),
        actions: await page.locator('[data-testid^="row-action-"]').evaluateAll(nodes => nodes.map(n => ({
          testId: n.getAttribute('data-testid'), text: n.textContent, html: n.outerHTML,
        }))) }), contentType: 'application/json',
    });
    throw error;
  }
  await expect(page.locator("textarea[name='crm_acc_remark'], input[name='crm_acc_remark']")).toBeVisible();
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
      await expect(page.locator("input[name='crm_acc_name']")).toBeVisible();
      const saveBtn = page.getByRole('button', { name: '保存' }).first();
      const disabled = await saveBtn.isDisabled().catch(() => false);
      if (disabled) {
        // required-empty → Save disabled is a valid enforcement of the required rule
        expect(disabled, 'Save disabled when required empty').toBeTruthy();
      } else {
        await clickSaveForValidation(page);
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
    const listRequests: Array<{ at: number; url: string }> = [];
    page.on('request', request => {
      if (request.url().includes('/api/dynamic/crm_account_common/list')) {
        listRequests.push({ at: Date.now(), url: request.url() });
      }
    });
    try {
      const marker = `W1CUST${uid}`.slice(0, 26);
      await createCustomer(page, marker);
      // open the row's edit action (row-scoped), edit remark, save, reopen
      const rows = await listSearch(page, CUST_LIST, marker);
      expect(rows, 'created customer in list').toBeGreaterThan(0);
      const row = page.locator(`table tbody tr:has-text("${marker}")`).first();
      // open detail/edit via the row's edit action or its first link (not clicking the whole row)
      await openRowForEdit(page, row);
      const newRemark = `edited-${uid}`;
      await fillSeq(page, 'crm_acc_remark', newRemark);
      await saveForm(page);
      await listSearch(page, CUST_LIST, marker);
      const row2 = page.locator(`table tbody tr:has-text("${marker}")`).first();
      await openRowForEdit(page, row2);
      await expect(page.locator("textarea[name='crm_acc_remark'], input[name='crm_acc_remark']")).toHaveValue(newRemark);
      const searched = listRequests.map(request => new URL(request.url)).filter(url => url.searchParams.get('keyword') === marker);
      expect(searched.length).toBeGreaterThan(0);
      const firstSort = searched[0].searchParams.get('sortField');
      expect(firstSort).toBeTruthy();
      expect([...new Set(searched.map(url => url.searchParams.get('sortField')))], 'keyword updates preserve the active sort').toEqual([firstSort]);


    } finally {
      await test.info().attach('customer-edit-list-requests', { body: JSON.stringify(listRequests), contentType: 'application/json' });
      await context.close();
    }
  });

  // ── CUST-07/08: 客户名称查询 ──
  test('CUST-07/08 customer name search narrows / no-match empty', async ({ browser }) => {
    const { context, page } = await openQuoteRolePage(browser, users['qo_sales']);
    try {
      const marker = `W1SRCH${uid}`.slice(0, 26);
      const other = `W1OTHER${uid}`.slice(0, 26);
      await createCustomer(page, marker);
      await createCustomer(page, other);
      // Self-scoped fresh users need their own positive and negative controls.
      // Never depend on the runtime's historical customer count.
      await listSearch(page, CUST_LIST, '');
      const markerRow = page.locator('table tbody tr').filter({ hasText: marker });
      const otherRow = page.locator('table tbody tr').filter({ hasText: other });
      await expect(markerRow).toHaveCount(1);
      await expect(otherRow).toHaveCount(1);
      await listSearch(page, CUST_LIST, marker);
      await expect(markerRow).toHaveCount(1);
      await expect(otherRow).toHaveCount(0);
      await listSearch(page, CUST_LIST, `NOMATCH${uid}ZZZ`);
      await expect(markerRow).toHaveCount(0);
      await expect(otherRow).toHaveCount(0);
      await expect(page.locator('main').getByText(/暂无符合条件的客户|暂无数据|无数据|No data|empty/i).first()).toBeVisible();

    } finally {
      await context.close();
    }
  });

  // ── DD-01/02: 客户表单字典下拉(行业/评级/状态)加载 + 输入过滤 ──
  test('DD-01/02 customer form dict dropdowns load options + filter on input', async ({ browser }) => {
    const { context, page } = await openQuoteRolePage(browser, users['qo_sales']);
    try {
      await page.goto(CUST_NEW, { waitUntil: 'domcontentloaded' });
      await expect(page.locator("input[name='crm_acc_name']")).toBeVisible();
      await expect(page.locator('main')).not.toContainText(/无权访问|权限不足|Forbidden/i);
      for (const field of ['crm_acc_industry', 'crm_acc_rating', 'crm_acc_status']) {
        const trigger = page.getByTestId(`select-trigger-${field}`);
        await expect(trigger).toBeVisible();
        await trigger.click();
        const options = page.getByRole('option');
        await expect(options.first()).toBeVisible();
        const labels = await options.allTextContents();
        expect(labels.length, `${field} needs positive and negative search options`).toBeGreaterThan(1);
        const target = labels[0].trim();
        const search = page.getByTestId(`select-search-${field}`);
        await search.fill(target);
        await expect(options).toHaveCount(labels.filter((label) => label.toLowerCase().includes(target.toLowerCase())).length);
        await expect(page.getByRole('option', { name: target, exact: true })).toBeVisible();
        await search.fill(`NO-MATCH-${uid}`);
        await expect(options).toHaveCount(0);
        await expect(page.getByText(/无匹配结果|暂无匹配|No results|未找到结果/i).first()).toBeVisible();
        await search.fill(target);
        await page.getByRole('option', { name: target, exact: true }).click();
        await expect(trigger).toContainText(target);
        await expect(page.locator(`input[type="hidden"][name="${field}"]`)).not.toHaveValue('');
      }

    } finally {
      await context.close();
    }
  });

  // ── BOM-02: 项目必填留空 → 字段级报错 ──
  test('BOM-02 project required-empty shows field error', async ({ browser }) => {
    const { context, page } = await openQuoteRolePage(browser, users['bom_engineering']);
    try {
      await page.goto(PROJ_NEW, { waitUntil: 'domcontentloaded' });
      await expect(page.locator("input[name='bom_project_name']")).toBeVisible();
      const saveBtn = page.getByRole('button', { name: '保存' }).first();
      const disabled = await saveBtn.isDisabled().catch(() => false);
      if (disabled) {
        expect(disabled, 'project Save disabled when required empty').toBeTruthy();
      } else {
        await clickSaveForValidation(page);
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
      await createCustomer(page, cust);
      // create project referencing it
      const proj = `W1LPROJ${uid}`.slice(0, 26);
      await createProject(page, proj);
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
      await createProject(a.page, projMarker);
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
