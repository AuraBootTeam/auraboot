import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { uniqueId, ensureSidebarExpanded, clickRowActionByLocator } from '../helpers';
import {
  makeQuoteRoleUser,
  ensureQuoteRoleUser,
  openQuoteRolePage,
  fetchRoleSnapshot,
  expectCommandDenied,
  expectCommandNotDenied,
  type QuoteRoleUser,
  queryDynamicRecords,
  probeCommand,
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
  customer: '/p/crm_account_common',
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
    const loadingTextVisible = await page
      .getByText(/^(?:加载中|Loading)(?:\.\.\.)?$/i)
      .first()
      .isVisible()
      .catch(() => false);
    return (searchReady > 0 || tableReady > 0) && loading === 0 && !loadingTextVisible;
  }, { timeout: 15_000 }).toBe(true);
}

async function searchList(page: Page, listPath: string, marker: string): Promise<number> {
  await page.goto(listPath, { waitUntil: 'domcontentloaded' });
  await waitForListReady(page);
  const q = page.getByPlaceholder('查询...').first();
  if (await q.count() > 0) {
    const response = page.waitForResponse((r) => (
      r.url().includes('/api/dynamic/') &&
      r.url().includes('/list') &&
      r.request().method() === 'GET' &&
      decodeURIComponent(r.url()).includes(marker)
    ), { timeout: 15_000 });
    await q.click();
    await q.fill(marker);
    await page.keyboard.press('Enter');
    const searchResponse = await response;
    expect(searchResponse.ok(), `list search failed: HTTP ${searchResponse.status()}`).toBe(true);
    await waitForListReady(page);
  }
  const rows = page.locator(`table tbody tr:has-text("${marker}")`);
  await expect.poll(() => rows.count(), { timeout: 15_000 }).toBeGreaterThan(0);
  return rows.count();
}

async function pickRequiredSelectOption(page: Page, fieldCode: string): Promise<void> {
  const trigger = page.getByTestId(`select-trigger-${fieldCode}`);
  await expect(trigger, `${fieldCode} select trigger`).toBeVisible({ timeout: 10_000 });
  await trigger.click();
  const option = page.getByRole('option').first();
  await expect(option, `${fieldCode} must expose at least one option`).toBeVisible({
    timeout: 10_000,
  });
  await option.click();
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
      r.url().includes('/api/meta/commands/execute/') &&
      r.request().method() === 'POST' &&
      decodeURIComponent(r.url()).includes(commandCode)
    ), { timeout: 20_000 });
    await page.getByRole('button', { name: '保存' }).first().click();
    const commandResponse = await saveResponse;
    expect(commandResponse.ok(), `${commandCode} failed: HTTP ${commandResponse.status()}`).toBe(true);
    const commandBody = await commandResponse.json().catch(() => ({}));
    expect(String((commandBody as { code?: unknown }).code), JSON.stringify(commandBody)).toBe('0');
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

        // Delete a separate, unreferenced account through the visible business bulk action.
        const deleteMarker = `ZKHDELCUST${uid}${r.key}`.slice(0, 28);
        expect(await createClosedLoop(page, MENU.customer, 'crm_acc_name', deleteMarker, 'crm:create_account')).toBe(true);
        const deletionRows = await queryDynamicRecords(page, 'crm_account_common', [
          { fieldName: 'crm_acc_name', operator: 'EQ', value: deleteMarker },
        ]);
        expect(deletionRows).toHaveLength(1);
        await page.locator('table tbody tr').filter({ hasText: deleteMarker }).getByRole('checkbox').check();
        await page.getByTestId('bulk-more-actions-btn').click();
        await expect(page.getByTestId('bulk-delete-btn')).toHaveCount(0);
        await page.getByTestId('bulk-action-bulk_delete_accounts').click();
        const deletionResponse = page.waitForResponse(response =>
          new URL(response.url()).origin === new URL(page.url()).origin &&
          decodeURIComponent(response.url()).endsWith('/api/meta/commands/execute/crm:delete_account') &&
          response.request().method() === 'POST');
        await page.getByTestId('confirm-dialog').getByTestId('confirm-ok').click();
        const deleted = await deletionResponse;
        expect(deleted.status()).toBe(200);
        expect(String((await deleted.json()).code)).toBe('0');
        expect(deleted.request().postDataJSON().targetRecordPid).toBe(deletionRows[0].pid);
        await expect(page.locator('table tbody tr').filter({ hasText: deleteMarker })).toHaveCount(0);
        await page.reload();
        await waitForListReady(page);
        expect(await queryDynamicRecords(page, 'crm_account_common', [
          { fieldName: 'crm_acc_name', operator: 'EQ', value: deleteMarker },
        ])).toHaveLength(0);

        // 3. 新建项目真机闭环 (references the just-created customer + quality level)
        const projMarker = `ZKHPROJ${uid}${r.key}`.slice(0, 28);
        const projOk = await createClosedLoop(page, MENU.project, 'bom_project_name', projMarker, 'bom:create_project', async (p) => {
          // Select both required fields explicitly. Positional combobox guessing can
          // silently choose only quality level and leave customer validation red.
          await pickRequiredSelectOption(p, 'bom_project_customer_id');
          await pickRequiredSelectOption(p, 'bom_project_quality_level');
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


test('a standalone BOM rules capability grants UI authoring and revokes to read-only without RFQ intake', async ({ page, browser }, info) => {
  test.setTimeout(180_000);
  const code = `e2e_rules_only_${Date.now()}`;
  const created = await page.request.post('/api/roles', { data: { code, name: code, type: 'custom' } });
  expect(created.ok()).toBe(true);
  const role = (await created.json()).data;
  const user = makeQuoteRoleUser('rules-only', code, [code]);
  await ensureQuoteRoleUser(page, user);
  async function selectCapabilities(manage: boolean) {
    await page.goto('/home');
    await ensureSidebarExpanded(page);
    await page.getByTestId('sidebar').locator('a[href="/enterprise/permissions"]').click();
    await page.getByTestId('role-search-input').fill(code);
    await page.getByTestId(`role-item-${code}`).click();
    await expect(page.getByTestId('capability-role-editor')).toHaveAttribute('data-role-pid', role.pid);
    await page.getByTestId('capability-checkbox-qo.cap.platform_read').check();
    await page.getByTestId('capability-checkbox-bom.cap.rules_manage').setChecked(manage);
    await page.getByTestId('capability-checkbox-bom.cap.rules_view').setChecked(!manage);
    await expect(page.getByTestId('capability-checkbox-qo.cap.intake')).not.toBeChecked();
    await page.getByTestId('capability-save').click();
    await expect(page.getByTestId('confirm-dialog')).toBeVisible();
    const saved = page.waitForResponse(r => r.url().includes('/api/permission/capabilities?') && r.request().method() === 'PUT');
    await page.getByTestId('confirm-ok').click();
    const response = await saved;
    expect(response.status()).toBe(200);
    expect(String((await response.json()).code)).toBe('0');
    await expect(page.getByTestId('capability-save')).toBeDisabled();
  }
  await selectCapabilities(true);
  const writer = await openQuoteRolePage(browser, user);
  try {
    await ensureSidebarExpanded(writer.page);
    const sidebar = writer.page.getByTestId('sidebar');
    const alias = sidebar.locator('a[href="/p/bom_header_alias"]');
    if (!(await alias.isVisible())) {
      const center = sidebar.getByRole('button', { name: '规则中心', exact: true });
      if (!(await center.isVisible())) await sidebar.getByRole('button', { name: 'BOM转化工具', exact: true }).click();
      await center.click();
    }
    await alias.click();
    await writer.page.locator('main').getByRole('button', { name: '新建', exact: true }).click();
    await writer.page.getByTestId('form-field-bom_ha_alias').getByRole('textbox').fill(code);
    await writer.page.getByTestId('form-field-bom_ha_system_field').getByRole('combobox').first().click();
    await writer.page.getByRole('option', { name: '品牌/厂家', exact: true }).click();
    const pending = writer.page.waitForResponse(r => decodeURIComponent(r.url()).includes('/api/meta/commands/execute/bom:create_header_alias') && r.request().method() === 'POST');
    await writer.page.getByRole('button', { name: '保存', exact: true }).click();
    const response = await pending;
    expect(new URL(response.url()).origin).toBe(new URL(writer.page.url()).origin);
    expect(response.status()).toBe(200);
    expect(String((await response.json()).code)).toBe('0');
    const payload = response.request().postDataJSON();
    expect(payload.payload?.bom_ha_alias ?? payload.params?.payload?.bom_ha_alias).toBe(code);
    const rows = await queryDynamicRecords(page, 'bom_header_alias', [{ fieldName: 'bom_ha_alias', operator: 'EQ', value: code }]);
    expect(rows).toHaveLength(1);
    await writer.page.screenshot({ path: info.outputPath('standalone-rules-created.png'), fullPage: true });
    await selectCapabilities(false);
    // A fresh login proves persisted authorization and avoids a cached browser permission map.
    await writer.context.close();
    const reader = await openQuoteRolePage(browser, user);
    try {
      await ensureSidebarExpanded(reader.page);
      const sidebar = reader.page.getByTestId('sidebar');
      const alias = sidebar.locator('a[href="/p/bom_header_alias"]');
      if (!(await alias.isVisible())) {
        const center = sidebar.getByRole('button', { name: '规则中心', exact: true });
        if (!(await center.isVisible())) await sidebar.getByRole('button', { name: 'BOM转化工具', exact: true }).click();
        await expect(center).toBeVisible();
        await center.click();
      }
      await expect(alias).toBeVisible();
      await alias.click();
      await expect(reader.page.locator('main').getByRole('button', { name: '新建', exact: true })).toHaveCount(0);
      const row = reader.page.getByRole('row').filter({ hasText: code });
      await expect(row).toBeVisible();
      await clickRowActionByLocator(reader.page, row, 'view', '查看');
      await expect(reader.page).toHaveURL(new RegExp(`/p/bom_header_alias/view/${rows[0].pid}$`));
      await expect(reader.page.locator('main')).toContainText(code);
      await expect(reader.page.getByTestId('toolbar-btn-edit')).toHaveCount(0);
      await expect(reader.page.getByTestId('toolbar-btn-delete')).toHaveCount(0);
      const denied = await probeCommand(reader.page, 'bom:create_header_alias', { bom_ha_alias: `${code}-denied`, bom_ha_system_field: 'brand' });
      expect(denied.status).toBe(403);
      expect(await queryDynamicRecords(page, 'bom_header_alias', [{ fieldName: 'bom_ha_alias', operator: 'EQ', value: `${code}-denied` }])).toHaveLength(0);
      await reader.page.screenshot({ path: info.outputPath('standalone-rules-read-only.png'), fullPage: true });
    } finally { await reader.context.close(); }
  } finally { await writer.context.close(); }
});
