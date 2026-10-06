import type { Page } from '@playwright/test';
import { utils as XLSXUtils, write as xlsxWrite } from 'xlsx';
import { test, expect } from '../../fixtures';
import { uniqueId, ensureSidebarExpanded, clickRowActionByLocator, executeCommandViaApi } from '../helpers';
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
  seedDownloadableQuote,
  searchBusinessList,
} from './quote-e2e-helpers';

test.use({ locale: 'zh-CN' });

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
      locale: 'zh-CN',
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
    expect(response.request().postDataJSON().slice().sort()).toEqual([
      manage ? 'bom.cap.rules_manage' : 'bom.cap.rules_view',
      'qo.cap.platform_read',
    ].sort());
    expect(String((await response.json()).code)).toBe('0');
    await expect(page.getByTestId('capability-save')).toBeDisabled();
  }
  async function assertRuleOnlyReads(rulePage: Page) {
    const snapshot = await fetchRoleSnapshot(rulePage);
    expect(snapshot.menuPaths).not.toContain(MENU.project);
    expect(snapshot.menuPaths).not.toContain('/p/bom_conversion_task_pcba_workbench');
    for (const pageKey of ['req_requirement_set_pcba_bom', 'bom_conversion_task_pcba_workbench']) {
      const response = await rulePage.request.get(`/api/dynamic/${pageKey}/list`, {
        params: { pageNum: 1, pageSize: 1 },
      });
      expect(response.status(), `${pageKey} must not be admitted by a rule navigation dependency`).toBe(403);
      expect(String((await response.json()).code)).toBe('403');
    }
  }
  await selectCapabilities(true);
  const writer = await openQuoteRolePage(browser, user);
  try {
    await assertRuleOnlyReads(writer.page);
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
      await assertRuleOnlyReads(reader.page);
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


test('a standalone model-service capability manages tenant LLM settings without cloud or console access', async ({ page, browser }, info) => {
  test.setTimeout(180_000);
  const code = `e2e_model_only_${Date.now()}`;
  const provider = `e2ellm${Date.now()}`;
  const created = await page.request.post('/api/roles', { data: { code, name: code, type: 'custom' } });
  expect(created.status()).toBe(200);
  const role = (await created.json()).data;
  const user = makeQuoteRoleUser('model-only', code, [code]);
  await ensureQuoteRoleUser(page, user);

  async function selectModelCapability(selected: boolean) {
    await page.goto('/home');
    await ensureSidebarExpanded(page);
    await page.getByTestId('sidebar').locator('a[href="/enterprise/permissions"]').click();
    await page.getByTestId('role-search-input').fill(code);
    await page.getByTestId(`role-item-${code}`).click();
    await expect(page.getByTestId('capability-role-editor')).toHaveAttribute('data-role-pid', role.pid);
    await page.getByTestId('capability-checkbox-sys.cap.model_service').setChecked(selected);
    await expect(page.getByTestId('capability-checkbox-sys.cap.console')).not.toBeChecked();
    await expect(page.getByTestId('capability-checkbox-sys.cap.cloud_config')).not.toBeChecked();
    await page.getByTestId('capability-save').click();
    await expect(page.getByTestId('confirm-dialog')).toBeVisible();
    const saved = page.waitForResponse(response => response.url().includes('/api/permission/capabilities?') && response.request().method() === 'PUT');
    await page.getByTestId('confirm-ok').click();
    const response = await saved;
    expect(response.status()).toBe(200);
    expect(String((await response.json()).code)).toBe('0');
    await expect(page.getByTestId('capability-save')).toBeDisabled();
  }

  await selectModelCapability(true);
  const member = await openQuoteRolePage(browser, user);
  try {
    const snapshot = await fetchRoleSnapshot(member.page);
    expect(snapshot.permissionCodes).toContain('ai_center');
    expect(snapshot.permissionCodes).not.toContain('sys.cloud_config.update');
    expect(snapshot.permissionCodes).not.toContain('system_management');
    await ensureSidebarExpanded(member.page);
    const sidebar = member.page.getByTestId('sidebar');
    const entry = sidebar.locator('a[href="/aurabot/providers"]');
    if (!(await entry.isVisible())) await sidebar.getByRole('button', { name: '系统管理', exact: true }).click();
    const listed = member.page.waitForResponse(response => new URL(response.url()).pathname === '/api/llm-config' && response.request().method() === 'GET');
    await entry.click();
    const initial = await listed;
    expect(initial.status()).toBe(200);
    expect(String((await initial.json()).code)).toBe('0');
    await expect(member.page.getByTestId('level-toggle-platform')).toHaveCount(0);
    await expect(sidebar.locator('a[href="/p/c/account_security_policy_detail"]')).toHaveCount(0);
    await expect(sidebar.locator('a[href="/p/c/system_preferences_form"]')).toHaveCount(0);
    await member.page.getByTestId('add-provider-btn').click();
    await member.page.getByTestId('picker-preset-custom').click();
    await member.page.getByTestId('custom-display-name').fill(provider);
    await member.page.getByTestId('field-apiKey').fill('e2e-local-fixture-key');
    await member.page.getByTestId('field-defaultModel').fill('e2e-fixture-model');
    const saving = member.page.waitForResponse(response => new URL(response.url()).pathname === '/api/llm-config' && response.request().method() === 'POST');
    await member.page.getByTestId('panel-save-btn').click();
    const saved = await saving;
    expect(new URL(saved.url()).origin).toBe(new URL(member.page.url()).origin);
    expect(saved.status()).toBe(200);
    expect(String((await saved.json()).code)).toBe('0');
    expect(saved.request().postDataJSON()).toMatchObject({ serviceType: 'llm', configLevel: 'tenant', providerCode: provider });
    const card = member.page.getByTestId(`provider-card-${provider}`);
    await expect(card).toBeVisible();
    await member.page.reload();
    await expect(card).toBeVisible();
    const list = await member.page.request.get('/api/llm-config');
    expect(list.status()).toBe(200);
    const rows = (await list.json()).data.filter((row: { providerCode: string }) => row.providerCode === provider);
    expect(rows).toHaveLength(1);
    const pid = rows[0].pid;
    await member.page.getByTestId(`provider-edit-${provider}`).click();
    await member.page.getByTestId('field-priority').fill('7');
    let editPosts = 0;
    const countEditPosts = (request: import('@playwright/test').Request) => {
      if (new URL(request.url()).pathname === '/api/llm-config' && request.method() === 'POST') editPosts++;
    };
    member.page.on('request', countEditPosts);
    await member.page.route('**/api/llm-config*', async route => {
      if (route.request().method() === 'GET') {
        await route.fulfill({ status: 503, contentType: 'application/json',
          body: JSON.stringify({ code: '503', message: 'Injected configuration readback failure' }) });
      } else await route.continue();
    });
    const editing = member.page.waitForResponse(response => new URL(response.url()).pathname === '/api/llm-config' && response.request().method() === 'POST');
    await member.page.getByTestId('panel-save-btn').click();
    const edited = await editing;
    expect(edited.status()).toBe(200);
    expect(String((await edited.json()).code)).toBe('0');
    expect(edited.request().postDataJSON()).toMatchObject({ pid, priority: 7, serviceType: 'llm' });
    await expect(member.page.getByTestId('config-save-readback-notice')).toBeVisible();
    await expect(member.page.getByTestId('field-priority')).toBeDisabled();
    await expect(member.page.getByTestId('panel-save-btn')).toBeDisabled();
    await member.page.screenshot({ path: info.outputPath('standalone-model-service-readback-error.png'), fullPage: true });
    const afterEdit = await member.page.request.get('/api/llm-config');
    expect(afterEdit.status()).toBe(200);
    const updated = (await afterEdit.json()).data.filter((row: { providerCode: string }) => row.providerCode === provider);
    expect(updated).toHaveLength(1);
    expect(updated[0]).toMatchObject({ pid, priority: 7 });
    await member.page.unroute('**/api/llm-config*');
    const retriedRead = member.page.waitForResponse(response =>
      new URL(response.url()).pathname === '/api/llm-config' && response.request().method() === 'GET');
    await member.page.getByTestId('config-save-readback-retry').click();
    const readResponse = await retriedRead;
    expect(readResponse.status()).toBe(200);
    expect(String((await readResponse.json()).code)).toBe('0');
    await expect(member.page.getByTestId('provider-edit-panel')).toHaveCount(0);
    expect(editPosts).toBe(1);
    member.page.off('request', countEditPosts);
    const toggling = member.page.waitForResponse(response => new URL(response.url()).pathname === '/api/llm-config' && response.request().method() === 'POST');
    await member.page.getByTestId(`provider-toggle-${provider}`).click();
    const toggled = await toggling;
    expect(toggled.status()).toBe(200);
    expect(String((await toggled.json()).code)).toBe('0');
    expect(toggled.request().postDataJSON()).toMatchObject({ pid, serviceType: 'llm', enabled: true });
    await expect(member.page.getByTestId(`provider-toggle-${provider}`)).toHaveAttribute('aria-checked', 'true');
    await member.page.reload();
    await expect(member.page.getByTestId(`provider-toggle-${provider}`)).toHaveAttribute('aria-checked', 'true');
    await member.page.screenshot({ path: info.outputPath('standalone-model-service-saved.png'), fullPage: true });
    const nonLlm = await member.page.request.post('/api/llm-config', { data: { serviceType: 'sms', configLevel: 'tenant', providerCode: `${provider}bad`, config: '{}', enabled: false } });
    expect(nonLlm.status()).toBe(403);
    const deleting = member.page.waitForResponse(response => new URL(response.url()).pathname === `/api/llm-config/${pid}` && response.request().method() === 'DELETE');
    member.page.once('dialog', async dialog => {
      expect(dialog.type()).toBe('confirm');
      expect(dialog.message()).toContain(provider);
      await dialog.accept();
    });
    await member.page.getByTestId(`provider-delete-${provider}`).click();
    const deleted = await deleting;
    expect(deleted.status()).toBe(200);
    expect(String((await deleted.json()).code)).toBe('0');
    await expect(card).toHaveCount(0);
    await member.page.reload();
    await expect(card).toHaveCount(0);
    const afterDelete = await member.page.request.get('/api/llm-config');
    expect(afterDelete.status()).toBe(200);
    expect((await afterDelete.json()).data.filter((row: { providerCode: string }) => row.providerCode === provider)).toHaveLength(0);
  } finally { await member.context.close(); }
  await selectModelCapability(false);
  const revoked = await openQuoteRolePage(browser, user);
  try {
    expect((await fetchRoleSnapshot(revoked.page)).permissionCodes).not.toContain('ai_center');
    await ensureSidebarExpanded(revoked.page);
    await expect(revoked.page.getByTestId('sidebar').locator('a[href="/aurabot/providers"]')).toHaveCount(0);
    expect((await revoked.page.request.get('/api/llm-config')).status()).toBe(403);
    await revoked.page.screenshot({ path: info.outputPath('standalone-model-service-revoked.png'), fullPage: true });
  } finally { await revoked.context.close(); }
});

test('a standalone team-view capability reads DSL team membership, denies writes and revokes access', async ({
  page,
  browser,
}, info) => {
  test.setTimeout(180_000);
  const stamp = uniqueId('team-view');
  const roleCode = `e2e_team_view_${stamp}`;
  const createdRole = await page.request.post('/api/roles', {
    data: { code: roleCode, name: stamp, type: 'custom' },
  });
  expect(createdRole.status()).toBe(200);
  const roleBody = await createdRole.json();
  expect(String(roleBody.code)).toBe('0');
  const role = roleBody.data;
  const viewer = makeQuoteRoleUser('team-view', stamp, [roleCode]);
  const target = makeQuoteRoleUser('team-member', stamp, []);
  await ensureQuoteRoleUser(page, viewer);
  await ensureQuoteRoleUser(page, target);
  const search = await page.request.post('/api/tenant/members/search', {
    data: { keyword: target.email, pageNum: 1, pageSize: 50 },
  });
  expect(search.status()).toBe(200);
  const searchBody = await search.json();
  expect(String(searchBody.code)).toBe('0');
  const member = searchBody.data.records.find(
    (record: { user?: { email: string } }) => record.user?.email === target.email,
  );
  expect(member).toBeDefined();
  const createdTeam = await page.request.post('/api/org/teams', {
    data: { code: stamp, name: stamp, description: 'Independent team read boundary' },
  });
  expect(createdTeam.status()).toBe(200);
  const teamBody = await createdTeam.json();
  expect(String(teamBody.code)).toBe('0');
  const team = teamBody.data;

  async function openTeam(actor: Page, readOnly = false) {
    await actor.goto('/home');
    await ensureSidebarExpanded(actor);
    const link = actor.locator('nav a[href="/organization/teams"]');
    if (!(await link.isVisible())) {
      await actor.locator('nav button').filter({ hasText: '组织管理' }).first().click();
    }
    await expect(link).toBeVisible();
    await link.click();
    const searchInput = actor.getByTestId('list-search-input');
    await expect(searchInput).toBeVisible();
    const searched = actor.waitForResponse(response => {
      const url = new URL(response.url());
      return url.pathname === '/api/dynamic/ab_team/list' &&
        url.searchParams.get('keyword') === stamp;
    });
    await searchInput.fill(stamp);
    await searchInput.press('Enter');
    const searchResult = await searched;
    expect(searchResult.status()).toBe(200);
    const searchBody = await searchResult.json();
    expect(String(searchBody.code)).toBe('0');
    expect(searchBody.data.records.map((record: { pid: string }) => record.pid)).toEqual([team.pid]);
    const row = actor.locator('table tbody tr').filter({ hasText: stamp });
    await expect(row).toHaveCount(1);
    await expect(row).toBeVisible();
    if (readOnly) {
      await expect(actor.getByRole('button', { name: '新建团队', exact: true })).toHaveCount(0);
      await expect(row.getByRole('button', { name: '编辑', exact: true })).toHaveCount(0);
      await expect(row.getByRole('button', { name: '删除', exact: true })).toHaveCount(0);
    }
    await row.click();
    await expect(actor).toHaveURL(url => url.pathname === `/organization/teams/${team.pid}`);
  }
  async function selectRead(grant: boolean, unrelatedCommandCapability = false, integration = false, governance: 'data_permission' | 'scheduled_task' | false = false) {
    await page.goto('/home');
    await ensureSidebarExpanded(page);
    await page.getByTestId('sidebar').locator('a[href="/enterprise/permissions"]').click();
    await page.getByTestId('role-search-input').fill(roleCode);
    await page.getByTestId(`role-item-${roleCode}`).click();
    await expect(page.getByTestId('capability-role-editor')).toHaveAttribute(
      'data-role-pid',
      role.pid,
    );
    if (grant) {
      await page.getByTestId('data-scope-modify-btn').click();
      await page.getByTestId('data-scope-option-all').click();
      await page.getByTestId('data-scope-apply').click();
      await expect(page.getByTestId('data-scope-drawer')).toHaveCount(0);
    }
    // Coverage can infer additional fully covered capabilities on reload. Select the fixture explicitly.
    async function checkedCapabilityCodes() {
      return page.getByTestId(/^capability-checkbox-/).evaluateAll(inputs => inputs
        .filter(input => (input as HTMLInputElement).checked)
        .map(input => input.getAttribute('data-testid')!.replace('capability-checkbox-', '')));
    }
    for (const code of await checkedCapabilityCodes()) {
      await page.getByTestId(`capability-checkbox-${code}`).setChecked(false);
    }
    await page.getByTestId('capability-checkbox-org.cap.team_view').setChecked(grant);
    await page.getByTestId('capability-checkbox-sys.cap.integration').setChecked(integration);
    await page.getByTestId('capability-checkbox-sys.cap.data_permission').setChecked(governance === 'data_permission');
    await page.getByTestId('capability-checkbox-sys.cap.scheduled_task').setChecked(governance === 'scheduled_task');
    await page.getByTestId('capability-checkbox-org.cap.member_offboarding').setChecked(
      unrelatedCommandCapability,
    );
    // The read capability is also fully covered by offboarding; keep the fixture selection exact.
    await page.getByTestId('capability-checkbox-org.cap.member_view').setChecked(false);
    const expectedSelection = [
      ...(grant ? ['org.cap.team_view'] : []),
      ...(unrelatedCommandCapability ? ['org.cap.member_offboarding'] : []),
      ...(integration ? ['sys.cap.integration'] : []),
      ...(governance ? [`sys.cap.${governance}`] : []),
    ].sort();
    const selectedCodes = await checkedCapabilityCodes();
    expect(selectedCodes.sort()).toEqual(expectedSelection);
    if (await page.getByTestId('capability-save').isDisabled()) {
      await info.attach('team-capability-selection-already-current', {
        body: JSON.stringify({ selected: expectedSelection, writePerformed: false }),
        contentType: 'application/json',
      });
      return;
    }
    await page.getByTestId('capability-save').click();
    await expect(page.getByTestId('confirm-dialog')).toBeVisible();
    const saved = page.waitForResponse(
      (response) =>
        response.request().method() === 'PUT' &&
        response.url().includes('/api/permission/capabilities?'),
    );
    await page.getByTestId('confirm-ok').click();
    const response = await saved;
    expect(response.status()).toBe(200);
    expect(String((await response.json()).code)).toBe('0');
    expect(response.request().postDataJSON()).toEqual(
      expect.arrayContaining(grant ? ['org.cap.team_view'] : []),
    );
    expect(response.request().postDataJSON()).toHaveLength(
      Number(grant) + Number(unrelatedCommandCapability) + Number(integration) + Number(Boolean(governance)),
    );
    if (unrelatedCommandCapability) {
      expect(response.request().postDataJSON()).toContain('org.cap.member_offboarding');
    }
    expect(response.request().postDataJSON().includes('sys.cap.integration')).toBe(integration);
    expect(response.request().postDataJSON().includes('sys.cap.data_permission')).toBe(governance === 'data_permission');
    expect(response.request().postDataJSON().includes('sys.cap.scheduled_task')).toBe(governance === 'scheduled_task');
    await expect(page.getByTestId('capability-save')).toBeDisabled();
  }
  async function membership() {
    const response = await page.request.get(`/api/org/teams/${team.pid}/members`);
    expect(response.status()).toBe(200);
    const body = await response.json();
    expect(String(body.code)).toBe('0');
    return body.data as Array<{ memberPid: string }>;
  }

  // Management positive path uses the exact active DSL custom block, with persisted payload proof.
  await openTeam(page);
  await page.getByTestId('team-members-add').click();
  await page.getByTestId('team-members-select').selectOption(member.pid);
  const added = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === `/api/org/teams/${team.pid}/members`,
  );
  await page.getByTestId('team-members-confirm').click();
  const addResponse = await added;
  expect(addResponse.status()).toBe(200);
  expect(addResponse.request().postDataJSON()).toEqual({ memberPid: member.pid, role: 'member' });
  const addedBody = await addResponse.json();
  expect(String(addedBody.code)).toBe('0');
  const membershipPid = addedBody.data.pid as string;
  expect(membershipPid).not.toBe(member.pid);
  await expect(page.getByTestId(`team-members-remove-${membershipPid}`)).toBeVisible();
  expect((await membership()).map((record) => record.memberPid)).toEqual([member.pid]);
  await expect(page.getByRole('button', { name: 'Close notification', exact: true })).toHaveCount(0);
  await page.screenshot({ path: info.outputPath('team-manager-member-added.png'), fullPage: true });

  await selectRead(true);
  const opened = await openQuoteRolePage(browser, viewer);
  const reader = opened.page;
  try {
    const snapshot = await fetchRoleSnapshot(reader);
    expect(snapshot.permissionCodes).toEqual(
      expect.arrayContaining([
        'org.team.read',
        'org_management',
        'org_teams',
        'model.ab_team.read',
      ]),
    );
    for (const code of [
      'org.team.manage',
      'model.ab_team.create',
      'model.ab_team.update',
      'model.ab_team.delete',
      'meta.command.execute',
    ]) {
      expect(snapshot.permissionCodes).not.toContain(code);
    }
    await info.attach('team-read-role-snapshot', {
      body: JSON.stringify(snapshot),
      contentType: 'application/json',
    });
    await openTeam(reader, true);
    await expect(reader.getByText('查看团队成员与团队角色', { exact: true })).toBeVisible();
    await expect(reader.getByText(target.displayName, { exact: true })).toBeVisible();
    await expect(reader.getByTestId('team-members-add')).toHaveCount(0);
    await expect(reader.getByTestId(`team-members-remove-${membershipPid}`)).toHaveCount(0);
    await expect(reader.getByRole('button', { name: '编辑', exact: true })).toHaveCount(0);
    const nativeTeam = await reader.request.get(`/api/org/teams/${team.pid}`);
    expect(nativeTeam.status()).toBe(200);
    const nativeTeamBody = await nativeTeam.json();
    expect(String(nativeTeamBody.code)).toBe('0');
    expect(nativeTeamBody.data.pid).toBe(team.pid);
    expect(nativeTeamBody.data.name).toBe(stamp);
    await reader.screenshot({ path: info.outputPath('team-independent-read.png'), fullPage: true });
    const deniedAdd = await reader.request.post(`/api/org/teams/${team.pid}/members`, {
      data: { memberPid: member.pid, role: 'leader' },
    });
    const deniedRemove = await reader.request.delete(
      `/api/org/teams/${team.pid}/members/${membershipPid}`,
    );
    expect(deniedAdd.status()).toBe(403);
    expect(deniedRemove.status()).toBe(403);
    expect((await membership()).map((record) => record.memberPid)).toEqual([member.pid]);
    await info.attach('team-write-denials', {
      body: JSON.stringify({
        addStatus: deniedAdd.status(),
        removeStatus: deniedRemove.status(),
        preservedMemberPid: member.pid,
      }),
      contentType: 'application/json',
    });
    // A command grant from another business capability must not authorize team CRUD.
    await selectRead(true, true);
    await reader.reload();
    const crossCapability = await fetchRoleSnapshot(reader);
    expect(crossCapability.permissionCodes).toContain('meta.command.execute');
    for (const operation of ['create', 'update', 'delete']) {
      expect(crossCapability.permissionCodes).not.toContain(`model.ab_team.${operation}`);
      const denied = await reader.request.post(`/api/meta/commands/execute/org:${operation}_team`, {
        data: {
          payload: operation === 'create'
            ? { code: `${stamp}-forbidden`, name: `${stamp}-forbidden` }
            : operation === 'update' ? { name: `${stamp}-forbidden-update` } : {},
          ...(operation === 'create' ? {} : { targetRecordId: team.pid }),
          operationType: operation,
        },
      });
      expect(denied.status(), `Unrelated command capability must deny team ${operation}`).toBe(403);
      expect(['403', '10403']).toContain(String((await denied.json()).code));
    }
    // A licensed platform command must enforce its own action despite unrelated command access.
    const entitlementResponse = await reader.request.get('/api/entitlements');
    expect(entitlementResponse.status()).toBe(200);
    const entitlementBody = await entitlementResponse.json();
    expect(String(entitlementBody.code)).toBe('0');
    expect(entitlementBody.data.enabled).toBe(true);
    expect(entitlementBody.data.entitlements).toEqual(expect.arrayContaining([
      expect.objectContaining({ pluginId: 'com.auraboot.platform-admin', status: 'active' }),
    ]));
    expect(crossCapability.permissionCodes).not.toContain('model.api_connector.create');
    const connectorMarker = `${stamp}-forbidden-connector`;
    const connectorBefore = await queryDynamicRecords(page, 'api_connector', [{ fieldName: 'name', operator: 'EQ', value: connectorMarker }]);
    expect(connectorBefore.filter(record => record.name === connectorMarker)).toEqual([]);
    const deniedConnector = await reader.request.post('/api/meta/commands/execute/admin:create_api_connector', {
      data: { operationType: 'create', payload: {
        name: connectorMarker, base_url: 'http://127.0.0.1:9', auth_type: 'none', enabled: false,
      } },
    });
    const deniedConnectorBody = await deniedConnector.json();
    await info.attach('licensed-platform-command-denial', {
      body: JSON.stringify({ status: deniedConnector.status(), body: deniedConnectorBody }),
      contentType: 'application/json',
    });
    expect(deniedConnector.status()).toBe(403);
    expect(String(deniedConnectorBody.code)).toBe('403');
    expect(deniedConnectorBody.context?.detail ?? deniedConnectorBody.context?.messageKey)
      .toContain('Command permission denied: required one of model.api_connector.create');
    const connectorAfter = await queryDynamicRecords(page, 'api_connector', [{ fieldName: 'name', operator: 'EQ', value: connectorMarker }]);
    expect(connectorAfter).toEqual(connectorBefore);
    // Grant the owning upper capability through the UI and prove real command persistence.
    await selectRead(true, false, true);
    const permittedSnapshot = await fetchRoleSnapshot(reader);
    expect(permittedSnapshot.permissionCodes).toEqual(expect.arrayContaining([
      'model.api_connector.read', 'model.api_connector.create',
      'model.api_connector.update', 'model.api_connector.delete', 'meta.command.execute',
    ]));
    const permittedName = `${stamp}-permitted-connector`;
    const connectorCreate = await reader.request.post('/api/meta/commands/execute/admin:create_api_connector', {
      data: { operationType: 'create', payload: {
        name: permittedName, base_url: 'http://127.0.0.1:9', auth_type: 'none', enabled: false,
      } },
    });
    expect(connectorCreate.status()).toBe(200);
    const connectorCreateBody = await connectorCreate.json();
    expect(String(connectorCreateBody.code)).toBe('0');
    expect(connectorCreateBody.data.data.api_connector_inserted).toBe(1);
    const connectorPid = String(connectorCreateBody.data.data.recordPid);
    expect(connectorPid).toMatch(/^[0-9A-Z]{26}$/);
    async function connectorRecord() {
      const response = await page.request.get(`/api/dynamic/api_connector/${connectorPid}`);
      expect(response.status()).toBe(200);
      const body = await response.json();
      expect(String(body.code)).toBe('0');
      expect(body.data.pid).toBe(connectorPid);
      return body.data;
    }
    expect(await connectorRecord()).toEqual(expect.objectContaining({ name: permittedName, enabled: false }));
    const updatedName = `${permittedName}-updated`;
    const connectorUpdate = await reader.request.post('/api/meta/commands/execute/admin:update_api_connector', {
      data: { operationType: 'update', targetRecordPid: connectorPid, payload: { name: updatedName } },
    });
    expect(connectorUpdate.status()).toBe(200);
    const connectorUpdateBody = await connectorUpdate.json();
    expect(String(connectorUpdateBody.code)).toBe('0');
    expect(connectorUpdateBody.data.data.api_connector_updated).toBe(1);
    expect(await connectorRecord()).toEqual(expect.objectContaining({ name: updatedName, enabled: false }));
    await selectRead(true, true, false);
    const revokedPlatform = await fetchRoleSnapshot(reader);
    expect(revokedPlatform.permissionCodes).toContain('meta.command.execute');
    const recordBeforeRevocationAttempts = await connectorRecord();
    const commandEvidence = [];
    for (const operation of ['create', 'update', 'delete']) {
      expect(revokedPlatform.permissionCodes).not.toContain(`model.api_connector.${operation}`);
      const response = await reader.request.post(`/api/meta/commands/execute/admin:${operation}_api_connector`, {
        data: {
          operationType: operation,
          ...(operation === 'create' ? {} : { targetRecordPid: connectorPid }),
          payload: operation === 'create'
            ? { name: connectorMarker, base_url: 'http://127.0.0.1:9', enabled: false }
            : operation === 'update' ? { name: connectorMarker } : {},
        },
      });
      const body = await response.json();
      commandEvidence.push({ operation, status: response.status(), body });
      expect(response.status()).toBe(403);
      expect(String(body.code)).toBe('403');
      expect(body.context?.detail ?? body.context?.messageKey)
        .toContain(`Command permission denied: required one of model.api_connector.${operation}`);
    }
    expect(await connectorRecord()).toEqual(recordBeforeRevocationAttempts);
    expect(await queryDynamicRecords(page, 'api_connector', [
      { fieldName: 'name', operator: 'EQ', value: connectorMarker },
    ])).toEqual([]);
    await info.attach('licensed-platform-command-revocation', {
      body: JSON.stringify({ connectorPid, commandEvidence, recordBeforeRevocationAttempts }),
      contentType: 'application/json',
    });
    await selectRead(true, false, true);
    const connectorDelete = await reader.request.post('/api/meta/commands/execute/admin:delete_api_connector', {
      data: { operationType: 'delete', targetRecordPid: connectorPid, payload: {} },
    });
    expect(connectorDelete.status()).toBe(200);
    const connectorDeleteBody = await connectorDelete.json();
    expect(String(connectorDeleteBody.code)).toBe('0');
    expect(connectorDeleteBody.data.data.api_connector_deleted).toBe(1);
    expect(await queryDynamicRecords(page, 'api_connector', [
      { fieldName: 'pid', operator: 'EQ', value: connectorPid },
    ])).toEqual([]);
    await info.attach('licensed-platform-command-positive', {
      body: JSON.stringify({ connectorPid, create: connectorCreateBody, update: connectorUpdateBody,
        delete: connectorDeleteBody, deletedRecordAbsent: true }),
      contentType: 'application/json',
    });

    // Exercise the other licensed model commands without enabling any runtime side effects.
    const platformFixtures = [
      { model: 'webhook', capability: 'integration', payload: { target_url: 'http://127.0.0.1:9', event_type: 'record_created', model_code: 'ab_team' } },
      { model: 'scheduled_task', capability: 'scheduled_task', payload: { task_type: 'interval', interval_ms: 60000, handler_bean: 'e2eDisabledHandler' } },
      { model: 'data_permission', capability: 'data_permission', payload: { model_code: 'ab_team', policy_type: 'row', scope_type: 'self' } },
    ] as const;
    const platformRecords: Array<{
      model: string; pid: string; name: string; payload: Record<string, unknown>;
      create: unknown; update: unknown; before: Record<string, unknown>;
    }> = [];
    const platformCommandEvidence: Array<Record<string, unknown>> = [];
    async function platformRecord(model: string, pid: string) {
      const response = await page.request.get(`/api/dynamic/${model}/${pid}`);
      expect(response.status()).toBe(200);
      const body = await response.json();
      expect(String(body.code)).toBe('0');
      expect(body.data.pid).toBe(pid);
      return body.data as Record<string, unknown>;
    }
    for (const fixture of platformFixtures) {
      await selectRead(true, false, fixture.capability === 'integration',
        fixture.capability === 'integration' ? false : fixture.capability);
      const platformGranted = await fetchRoleSnapshot(reader);
      expect(platformGranted.permissionCodes).toContain('meta.command.execute');
      await info.attach(`licensed-${fixture.model}-independent-capability`, {
        body: JSON.stringify({ capability: `sys.cap.${fixture.capability}`, snapshot: platformGranted }),
        contentType: 'application/json',
      });
      const name = `${stamp}-${fixture.model}`;
      expect(await queryDynamicRecords(page, fixture.model, [
        { fieldName: 'name', operator: 'EQ', value: name },
      ])).toEqual([]);
      for (const action of ['read', 'create', 'update', 'delete']) {
        expect(platformGranted.permissionCodes).toContain(`model.${fixture.model}.${action}`);
      }
      const payload = { name, ...fixture.payload, enabled: false };
      const created = await reader.request.post(`/api/meta/commands/execute/admin:create_${fixture.model}`, {
        data: { operationType: 'create', payload },
      });
      const createBody = await created.json();
      await info.attach(`licensed-${fixture.model}-create`, {
        body: JSON.stringify({ status: created.status(), body: createBody }), contentType: 'application/json',
      });
      expect(created.status()).toBe(200);
      expect(String(createBody.code)).toBe('0');
      expect(createBody.data.phaseReached).toBe('completed');
      expect(createBody.data.idempotentReplay).toBe(false);
      expect(createBody.data.data[`${fixture.model}_inserted`]).toBe(1);
      const pid = String(createBody.data.data.recordPid);
      expect(pid).toMatch(/^[0-9A-Z]{26}$/);
      expect(await platformRecord(fixture.model, pid)).toEqual(expect.objectContaining(payload));
      const updatedName = `${name}-updated`;
      const updated = await reader.request.post(`/api/meta/commands/execute/admin:update_${fixture.model}`, {
        data: { operationType: 'update', targetRecordPid: pid, payload: { name: updatedName } },
      });
      const updateBody = await updated.json();
      expect(updated.status()).toBe(200);
      expect(String(updateBody.code)).toBe('0');
      expect(updateBody.data.phaseReached).toBe('completed');
      expect(updateBody.data.idempotentReplay).toBe(false);
      expect(updateBody.data.data[`${fixture.model}_updated`]).toBe(1);
      const before = await platformRecord(fixture.model, pid);
      expect(before).toEqual(expect.objectContaining({ ...payload, name: updatedName }));
      platformRecords.push({ model: fixture.model, pid, name, payload, create: createBody, update: updateBody, before });
    }
    await selectRead(true, true);
    const platformRevoked = await fetchRoleSnapshot(reader);
    expect(platformRevoked.permissionCodes).toContain('meta.command.execute');
    for (const record of platformRecords) {
      const forbiddenName = `${record.name}-forbidden`;
      expect(await queryDynamicRecords(page, record.model, [
        { fieldName: 'name', operator: 'EQ', value: forbiddenName },
      ])).toEqual([]);
      for (const operation of ['create', 'update', 'delete']) {
        expect(platformRevoked.permissionCodes).not.toContain(`model.${record.model}.${operation}`);
        const denied = await reader.request.post(`/api/meta/commands/execute/admin:${operation}_${record.model}`, {
          data: { operationType: operation,
            ...(operation === 'create' ? {} : { targetRecordPid: record.pid }),
            payload: operation === 'create' ? { ...record.payload, name: forbiddenName }
              : operation === 'update' ? { name: forbiddenName } : {},
          },
        });
        const body = await denied.json();
        platformCommandEvidence.push({ model: record.model, operation, status: denied.status(), body });
        expect(denied.status()).toBe(403);
        expect(String(body.code)).toBe('403');
        expect(body.context?.detail ?? body.context?.messageKey)
          .toContain(`Command permission denied: required one of model.${record.model}.${operation}`);
      }
      expect(await platformRecord(record.model, record.pid)).toEqual(record.before);
      expect(await queryDynamicRecords(page, record.model, [
        { fieldName: 'name', operator: 'EQ', value: forbiddenName },
      ])).toEqual([]);
    }
    await info.attach('licensed-platform-other-command-revocations', {
      body: JSON.stringify({ records: platformRecords, denials: platformCommandEvidence }), contentType: 'application/json',
    });
    for (const record of platformRecords) {
      const fixture = platformFixtures.find(candidate => candidate.model === record.model)!;
      await selectRead(true, false, fixture.capability === 'integration',
        fixture.capability === 'integration' ? false : fixture.capability);
      const deleted = await reader.request.post(`/api/meta/commands/execute/admin:delete_${record.model}`, {
        data: { operationType: 'delete', targetRecordPid: record.pid, payload: {} },
      });
      const deleteBody = await deleted.json();
      expect(deleted.status()).toBe(200);
      expect(String(deleteBody.code)).toBe('0');
      expect(deleteBody.data.phaseReached).toBe('completed');
      expect(deleteBody.data.idempotentReplay).toBe(false);
      expect(deleteBody.data.data[`${record.model}_deleted`]).toBe(1);
      expect(await queryDynamicRecords(page, record.model, [
        { fieldName: 'pid', operator: 'EQ', value: record.pid },
      ])).toEqual([]);
      await info.attach(`licensed-${record.model}-positive`, {
        body: JSON.stringify({ ...record, delete: deleteBody, deletedRecordAbsent: true }), contentType: 'application/json',
      });
    }

    const unchanged = await page.request.get(`/api/dynamic/ab_team/${team.pid}`);
    expect(unchanged.status()).toBe(200);
    const unchangedBody = await unchanged.json();
    expect(String(unchangedBody.code)).toBe('0');
    expect(unchangedBody.data.name).toBe(stamp);
    expect((await membership()).map((record) => record.memberPid)).toEqual([member.pid]);
    await selectRead(false);
    const revokedRead = reader.waitForResponse(response =>
      new URL(response.url()).pathname === `/api/dynamic/ab_team/${team.pid}` && response.request().method() === 'GET');
    await reader.reload();
    expect((await revokedRead).status()).toBe(403);
    await expect(reader.getByRole('heading', { name: '无法访问此记录', exact: true })).toBeVisible();
    await expect(reader.getByText('当前账号没有访问权限，请联系记录负责人。', { exact: true })).toBeVisible();
    await expect(reader.getByText(target.displayName, { exact: true })).toHaveCount(0);
    await expect(reader.getByTestId('team-members-add')).toHaveCount(0);
    await reader.screenshot({ path: info.outputPath('team-read-revoked.png'), fullPage: true });
    const revoked = await fetchRoleSnapshot(reader);
    expect(revoked.permissionCodes).not.toContain('model.ab_team.read');
    expect(revoked.permissionCodes).not.toContain('org_teams');
    for (const endpoint of [`/api/org/teams/${team.pid}`, `/api/org/teams/${team.pid}/members`]) {
      const nativeRead = await reader.request.get(endpoint);
      expect(nativeRead.status(), `Team read revocation must protect ${endpoint}`).toBe(403);
    }
    await reader.goto('/home');
    await ensureSidebarExpanded(reader);
    await expect(reader.locator('nav a[href="/organization/teams"]')).toHaveCount(0);
  } finally {
    await opened.context.close();
  }

  await openTeam(page);
  page.once('dialog', (dialog) => dialog.accept());
  const removed = page.waitForResponse(
    (response) =>
      response.request().method() === 'DELETE' &&
      new URL(response.url()).pathname === `/api/org/teams/${team.pid}/members/${membershipPid}`,
  );
  await page.getByTestId(`team-members-remove-${membershipPid}`).click();
  const removeResponse = await removed;
  expect(removeResponse.status()).toBe(200);
  expect(String((await removeResponse.json()).code)).toBe('0');
  expect(await membership()).toEqual([]);
  await expect(page.getByText('暂无团队成员', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Close notification', exact: true })).toHaveCount(0);
  await page.screenshot({
    path: info.outputPath('team-manager-member-removed.png'),
    fullPage: true,
  });
});

test('independent organization read and member provisioning preserve separate native employee boundaries', async ({
  page, browser,
}, info) => {
  test.setTimeout(180_000);
  const stamp = uniqueId('staff');
  const roleCode = `e2e_staff_${stamp}`;
  const roleResponse = await page.request.post('/api/roles', {
    data: { code: roleCode, name: stamp, type: 'custom' },
  });
  expect(roleResponse.status()).toBe(200);
  const roleBody = await roleResponse.json();
  expect(String(roleBody.code)).toBe('0');
  const rolePid = roleBody.data.pid as string;
  const user = makeQuoteRoleUser('staff', stamp, [roleCode]);
  await ensureQuoteRoleUser(page, user);
  const directoryStamp = uniqueId('directory');
  const directoryResponse = await page.request.post('/api/admin/users/employee-accounts', {
    data: { employees: [{
      name: directoryStamp, userName: directoryStamp, email: `${directoryStamp}@e2e.local`,
      mobile: '13912345679', roles: [],
    }] },
  });
  expect(directoryResponse.status()).toBe(200);
  const directoryBody = await directoryResponse.json();
  expect(String(directoryBody.code)).toBe('0');
  const directoryMemberPid = directoryBody.data.accounts[0].memberPid as string;
  expect(directoryMemberPid).toBeTruthy();
  const departmentName = `Department ${stamp}`;
  const positionName = `Position ${stamp}`;
  const department = await executeCommandViaApi(page, 'org:create_department', {
    org_dept_name: departmentName, org_dept_code: `STAFF-${Date.now()}`,
  });
  expect(String(department.code)).toBe('0');
  expect(department.recordId).toBeTruthy();
  const position = await executeCommandViaApi(page, 'org:create_position', {
    org_pos_name: positionName, org_pos_code: `STAFF-P-${Date.now()}`,
    org_pos_level: '1', org_pos_dept_id: department.recordId,
  });
  expect(String(position.code)).toBe('0');
  expect(position.recordId).toBeTruthy();
  const employee = await executeCommandViaApi(page, 'org:create_employee', {
    org_emp_name: stamp, org_emp_phone: '13912345678',
    org_emp_dept_id: department.recordId, org_emp_position_id: position.recordId,
  });
  expect(String(employee.code)).toBe('0');
  const employeePid = employee.recordId as string;
  expect(employeePid).toBeTruthy();

  async function selectCapability(capability: 'org.cap.hr_view' | 'org.cap.hr' | 'org.cap.member' | null) {
    await page.goto('/home');
    await ensureSidebarExpanded(page);
    await page.getByTestId('sidebar').locator('a[href="/enterprise/permissions"]').click();
    await page.getByTestId('role-search-input').fill(roleCode);
    await page.getByTestId(`role-item-${roleCode}`).click();
    await expect(page.getByTestId('capability-role-editor')).toHaveAttribute('data-role-pid', rolePid);
    if (capability === 'org.cap.hr_view') {
      await page.getByTestId('data-scope-modify-btn').click();
      await page.getByTestId('data-scope-option-all').click();
      await page.getByTestId('data-scope-apply').click();
      await expect(page.getByTestId('data-scope-drawer')).toHaveCount(0);
      const notification = page.getByRole('button', { name: 'Close notification', exact: true });
      if (await notification.isVisible()) await notification.click();
    }
    for (const code of ['org.cap.hr_view', 'org.cap.hr', 'org.cap.member', 'org.cap.member_view']) {
      await page.getByTestId(`capability-checkbox-${code}`).setChecked(code === capability);
    }
    await page.getByTestId('capability-save').click();
    await expect(page.getByTestId('confirm-dialog')).toBeVisible();
    const saved = page.waitForResponse(response => response.request().method() === 'PUT'
      && new URL(response.url()).pathname === '/api/permission/capabilities'
      && new URL(response.url()).searchParams.get('rolePid') === rolePid);
    await page.getByTestId('confirm-ok').click();
    const response = await saved;
    expect(response.status()).toBe(200);
    expect(response.request().postDataJSON()).toEqual(capability ? [capability] : []);
    expect(String((await response.json()).code)).toBe('0');
    await expect(page.getByTestId('capability-save')).toBeDisabled();
  }
  const endpoints = [
    `/api/org/employees?pageNum=1&pageSize=20&keyword=${encodeURIComponent(stamp)}`,
    `/api/org/departments/${department.recordId}/employees?pageNum=1&pageSize=20&keyword=${encodeURIComponent(stamp)}`,
    `/api/dynamic/org_employee/list?pageNum=1&pageSize=20&keyword=${encodeURIComponent(stamp)}`,
  ];
  const optionsEndpoint = `/api/org/employees/provision-options?pageNum=1&pageSize=20&keyword=${encodeURIComponent(stamp)}`;
  const unlinkedEndpoint = `/api/org/members/unlinked?keyword=${encodeURIComponent(user.email)}`;
  const opened = await openQuoteRolePage(browser, user);
  const reader = opened.page;
  try {
    async function expectFullReads(status: 200 | 403) {
      for (const endpoint of endpoints) {
        const response = await reader.request.get(endpoint);
        expect(response.status(), endpoint).toBe(status);
        if (status === 200) {
          const body = await response.json();
          expect(String(body.code)).toBe('0');
          expect(body.data.records.some((record: { pid: string }) => record.pid === employeePid)).toBe(true);
        }
      }
    }
    const directorySearch = await reader.request.post('/api/tenant/members/search', {
      data: { keyword: directoryStamp, status: 'active', pageNum: 1, pageSize: 10 },
    });
    expect(directorySearch.status()).toBe(200);
    const directoryResults = await directorySearch.json();
    expect(String(directoryResults.code)).toBe('0');
    expect(directoryResults.data.records).toHaveLength(1);
    const directoryOption = directoryResults.data.records[0];
    expect(Object.keys(directoryOption).sort()).toEqual(['pid', 'status', 'user']);
    expect(Object.keys(directoryOption.user).sort()).toEqual(['avatar', 'email', 'pid', 'realName', 'username']);
    expect(directoryOption).toMatchObject({
      pid: directoryMemberPid, status: 'active',
      user: { username: directoryStamp, realName: directoryStamp, email: `${directoryStamp}@e2e.local` },
    });
    expect(directoryOption.user.pid).toBeTruthy();
    const directoryDetailEndpoint = `/api/tenant/members/${directoryMemberPid}`;
    expect((await reader.request.get(directoryDetailEndpoint)).status()).toBe(403);
    await expectFullReads(403);
    expect((await reader.request.get(optionsEndpoint)).status()).toBe(403);
    expect((await reader.request.get(unlinkedEndpoint)).status()).toBe(403);
    await selectCapability('org.cap.hr_view');
    await reader.goto('/home');
    await ensureSidebarExpanded(reader);
    const staffLink = reader.getByTestId('sidebar').locator('a[href="/p/org_employee"]');
    if (!(await staffLink.isVisible())) {
      await reader.locator('nav button').filter({ hasText: '组织管理' }).first().click();
    }
    await expect(staffLink).toBeVisible();
    await staffLink.click();
    const search = reader.getByTestId('list-search-input');
    await expect(search).toBeVisible();
    await expect(search).toHaveAttribute('placeholder', '搜索人员编号、姓名或状态');
    await search.fill(stamp);
    await search.press('Enter');
    const row = reader.locator('tbody tr').filter({ hasText: stamp });
    await expect(row).toHaveCount(1);
    await row.click();
    await expect(reader).toHaveURL(url => url.pathname === `/p/org_employee/view/${employeePid}`
      && url.searchParams.get('keyword') === stamp);
    await expect(reader.getByText(stamp, { exact: true }).first()).toBeVisible();
    await expect(reader.getByTestId('toolbar-btn-edit')).toHaveCount(0);
    await expectFullReads(200);
    expect((await reader.request.get(optionsEndpoint)).status()).toBe(403);
    expect((await reader.request.get(unlinkedEndpoint)).status()).toBe(403);
    await expect(reader.getByRole('heading', { name: '员工详情', exact: true })).toBeVisible();
    await expect(reader.getByText(stamp, { exact: true }).first()).toBeVisible();
    await expect(reader.getByText(departmentName, { exact: true })).toBeVisible();
    await expect(reader.getByText(positionName, { exact: true })).toBeVisible();
    await expect(reader.getByText('在职', { exact: true })).toBeVisible();
    await expect(reader.getByText('加载中...', { exact: true })).toHaveCount(0);
    await reader.screenshot({ path: info.outputPath('standalone-staff-read.png'), fullPage: true });
    await selectCapability('org.cap.hr');
    const unlinked = await reader.request.get(unlinkedEndpoint);
    expect(unlinked.status()).toBe(200);
    const unlinkedBody = await unlinked.json();
    expect(String(unlinkedBody.code)).toBe('0');
    expect(unlinkedBody.data).toEqual([expect.objectContaining({ email: user.email, name: user.displayName })]);
    expect((await reader.request.get(optionsEndpoint)).status()).toBe(403);
    await selectCapability(null);
    await expectFullReads(403);
    expect((await reader.request.get(unlinkedEndpoint)).status()).toBe(403);
    await reader.goto('/home');
    await ensureSidebarExpanded(reader);
    await expect(reader.locator('nav a[href="/p/org_employee"]')).toHaveCount(0);
    await reader.getByTestId('sidebar').screenshot({ path: info.outputPath('standalone-staff-read-revoked.png') });

    await selectCapability('org.cap.member');
    const directoryDetail = await reader.request.get(directoryDetailEndpoint);
    expect(directoryDetail.status()).toBe(200);
    const directoryDetailBody = await directoryDetail.json();
    expect(String(directoryDetailBody.code)).toBe('0');
    expect(directoryDetailBody.data.pid).toBe(directoryMemberPid);
    expect(directoryDetailBody.data.user.phone).toBe('13912345679');
    await expectFullReads(403);
    expect((await reader.request.get(unlinkedEndpoint)).status()).toBe(403);
    const options = await reader.request.get(optionsEndpoint);
    expect(options.status()).toBe(200);
    const optionsBody = await options.json();
    expect(String(optionsBody.code)).toBe('0');
    expect(optionsBody.data.records).toContainEqual({ pid: employeePid, name: stamp });
    for (const option of optionsBody.data.records) expect(Object.keys(option).sort()).toEqual(['name', 'pid']);
    await reader.goto('/home');
    await ensureSidebarExpanded(reader);
    const accounts = reader.getByTestId('sidebar').locator('a[href="/p/tenant_member"]');
    if (!(await accounts.isVisible())) {
      await reader.locator('nav button').filter({ hasText: '组织管理' }).first().click();
    }
    await expect(accounts).toBeVisible();
    await accounts.click();
    await reader.getByTestId('toolbar-btn-provision_from_employee').click();
    await expect(reader.getByTestId('form-dialog')).toBeVisible();
    await reader.getByTestId('form-dialog-field-employeePid').selectOption(employeePid);
    await reader.screenshot({ path: info.outputPath('member-provision-identity-selector.png'), fullPage: true });
    const provisioned = reader.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/meta/commands/execute/admin:provision_member_from_employee');
    await reader.getByTestId('form-dialog-submit').click();
    const response = await provisioned;
    expect(response.status()).toBe(200);
    expect(response.request().postDataJSON().payload).toMatchObject({ employeePid });
    const body = await response.json();
    expect(String(body.code)).toBe('0');
    const result = body.data?.data ?? body.data;
    expect(result.employeePid).toBe(employeePid);
    expect(result.createdMember).toBe(true);
    expect(result.memberPid).toBeTruthy();
    expect(result.userPid).toBeTruthy();
    await expect(reader.getByRole('heading', { name: '登录凭据已生成' })).toBeVisible();
    const persisted = await page.request.get(`/api/dynamic/org_employee/${employeePid}`);
    expect(persisted.status()).toBe(200);
    const record = (await persisted.json()).data;
    expect(record.org_emp_member_id).toBe(result.memberPid);
    expect(record.org_emp_user_id).toBe(result.userPid);
    await expectFullReads(403);
    await selectCapability(null);
    expect((await reader.request.get(optionsEndpoint)).status()).toBe(403);
    expect((await reader.request.get(unlinkedEndpoint)).status()).toBe(403);
    await reader.goto('/home');
    await ensureSidebarExpanded(reader);
    await expect(reader.locator('nav a[href="/p/tenant_member"]')).toHaveCount(0);
    expect((await reader.request.get(directoryDetailEndpoint)).status()).toBe(403);
    await reader.getByTestId('sidebar').screenshot({ path: info.outputPath('member-provision-revoked.png') });
  } finally { await opened.context.close(); }
});

test('team authoring capability protects direct forms and revokes without losing read access', async ({ page, browser }, info) => {
  test.setTimeout(180_000);
  const marker = uniqueId('team');
  const roleCode = `e2e_team_${Date.now()}`;
  const created = await page.request.post('/api/roles', { data: { code: roleCode, name: marker, type: 'custom' } });
  expect(created.status()).toBe(200);
  const roleBody = await created.json();
  expect(String(roleBody.code)).toBe('0');
  const rolePid = roleBody.data.pid as string;
  const user = makeQuoteRoleUser('team', marker, [roleCode]);
  await ensureQuoteRoleUser(page, user);
  const team = await executeCommandViaApi(page, 'org:create_team', { code: marker, name: marker });
  expect(String(team.code)).toBe('0');
  expect(team.recordId).toBeTruthy();
  const opened = await openQuoteRolePage(browser, user);
  const member = opened.page;
  const origin = new URL(member.url()).origin;
  try {
    async function selectTeamAuthoring(manage: boolean, configureScope = false) {
      await page.goto('/home');
      await ensureSidebarExpanded(page);
      await page.getByTestId('sidebar').locator('a[href="/enterprise/permissions"]').click();
      await page.getByTestId('role-search-input').fill(roleCode);
      await page.getByTestId(`role-item-${roleCode}`).click();
      await expect(page.getByTestId('capability-role-editor')).toHaveAttribute('data-role-pid', rolePid);
      if (configureScope) {
        await page.getByTestId('data-scope-modify-btn').click();
        await page.getByTestId('data-scope-option-all').click();
        await page.getByTestId('data-scope-apply').click();
        await expect(page.getByTestId('data-scope-drawer')).toHaveCount(0);
      }
      await page.getByTestId('capability-checkbox-qo.cap.platform_read').check();
      await page.getByTestId('capability-checkbox-org.cap.team_view').check();
      await page.getByTestId('capability-checkbox-org.cap.team').setChecked(manage);
      await page.getByTestId('capability-save').click();
      await expect(page.getByTestId('confirm-dialog')).toBeVisible();
      const saving = page.waitForResponse(response => new URL(response.url()).origin === origin
        && new URL(response.url()).pathname === '/api/permission/capabilities'
        && new URL(response.url()).searchParams.get('rolePid') === rolePid
        && response.request().method() === 'PUT');
      await page.getByTestId('confirm-ok').click();
      const saved = await saving;
      expect(saved.status()).toBe(200);
      expect(String((await saved.json()).code)).toBe('0');
      const expected = ['qo.cap.platform_read', 'org.cap.team_view', ...(manage ? ['org.cap.team'] : [])];
      expect([...saved.request().postDataJSON()].sort()).toEqual(expected.sort());
      await expect(page.getByTestId('capability-save')).toBeDisabled();
    }
    async function openTeamList() {
      await member.goto('/home');
      await ensureSidebarExpanded(member);
      await member.getByTestId('sidebar').locator('a[href="/organization/teams"]').click();
      await member.getByTestId('list-search-input').fill(marker);
      await member.getByTestId('list-search-input').press('Enter');
      const row = member.locator('tbody tr').filter({ hasText: marker });
      await expect(row).toHaveCount(1);
      return row;
    }
    await selectTeamAuthoring(false, true);
    await openTeamList();
    for (const path of ['/organization/teams/new', `/organization/teams/${team.recordId}/edit`]) {
      await member.goto(path);
      await expect(member.getByText('无权限访问此页面，请联系管理员。', { exact: true })).toBeVisible();
      await expect(member.getByTestId('form-btn-save')).toHaveCount(0);
    }
    await member.screenshot({ path: info.outputPath('team-form-readonly.png'), fullPage: true });
    await selectTeamAuthoring(true);
    const row = await openTeamList();
    await clickRowActionByLocator(member, row, 'edit');
    await expect(member).toHaveURL(url => url.pathname === `/organization/teams/${team.recordId}/edit`);
    const description = `${marker}-edited`;
    await member.getByTestId('form-field-description').locator('textarea, input').fill(description);
    const updating = member.waitForResponse(response => new URL(response.url()).origin === origin
      && decodeURIComponent(new URL(response.url()).pathname).endsWith('/api/meta/commands/execute/org:update_team')
      && response.request().method() === 'POST');
    await member.getByTestId('form-btn-save').click();
    const updated = await updating;
    expect(updated.status()).toBe(200);
    expect(String((await updated.json()).code)).toBe('0');
    expect(updated.request().postDataJSON().payload).toMatchObject({ description });
    await member.goto(`/organization/teams/${team.recordId}/edit`);
    await expect(member.getByTestId('form-field-description').locator('textarea, input')).toHaveValue(description);
    await member.screenshot({ path: info.outputPath('team-form-writer.png'), fullPage: true });
    await selectTeamAuthoring(false);
    await member.reload();
    await expect(member.getByText('无权限访问此页面，请联系管理员。', { exact: true })).toBeVisible();
    await expect(member.getByTestId('form-btn-save')).toHaveCount(0);
    await member.screenshot({ path: info.outputPath('team-form-revoked.png'), fullPage: true });
    const denied = await member.request.post('/api/meta/commands/execute/org:update_team', {
      data: { payload: { description: `${marker}-denied` }, targetRecordPid: team.recordId, operationType: 'update' },
    });
    expect(denied.status()).toBe(403);
    expect(String((await denied.json()).code)).toBe('403');
    const readback = await member.request.get(`/api/dynamic/ab_team/${team.recordId}`);
    expect(readback.status()).toBe(200);
    expect((await readback.json()).data.description).toBe(description);
    await openTeamList();
  } finally { await opened.context.close(); }
});

test('dashboard management capability governs customer 360 editing and revocation without widening record access', async ({ page, browser }, info) => {
  test.setTimeout(180_000);
  const marker = uniqueId('dash');
  const roleCode = `e2e_dashboard_${Date.now()}`;
  const created = await page.request.post('/api/roles', { data: { code: roleCode, name: marker, type: 'custom' } });
  expect(created.status()).toBe(200);
  const roleBody = await created.json();
  expect(String(roleBody.code)).toBe('0');
  const rolePid = roleBody.data.pid as string;
  const user = makeQuoteRoleUser('dash', marker, ['qo_sales', roleCode]);
  await ensureQuoteRoleUser(page, user);
  const opened = await openQuoteRolePage(browser, user);
  const member = opened.page;
  const origin = new URL(member.url()).origin;
  try {
    // Customer creation is fixture setup. The dashboard path below uses the actual customer menu and toolbar.
    const customer = await executeCommandViaApi(member, 'crm:create_account', { crm_acc_name: marker });
    expect(String(customer.code)).toBe('0');
    expect(customer.recordId).toBeTruthy();
    async function openCustomerDashboard() {
      await member.goto('/home');
      await ensureSidebarExpanded(member);
      await member.getByTestId('sidebar').locator('a[href="/p/crm_account_common"]').click();
      const search = member.getByTestId('list-search-input');
      await search.fill(marker);
      await search.press('Enter');
      const row = member.locator('tbody tr').filter({ hasText: marker });
      await expect(row).toHaveCount(1);
      await row.click();
      await expect(member).toHaveURL(url => url.pathname === `/p/crm_account_common/view/${customer.recordId}`);
      await member.getByTestId('toolbar-btn-view_360').click();
      await expect(member).toHaveURL(url => url.pathname === '/dashboards/view/crm_account_360'
        && url.searchParams.get('recordPid') === customer.recordId);
      await expect(member.getByRole('heading', { name: '客户360°视图', exact: true })).toBeVisible();
    }
    async function selectDashboardCapability(selected: boolean) {
      await page.goto('/home');
      await ensureSidebarExpanded(page);
      await page.getByTestId('sidebar').locator('a[href="/enterprise/permissions"]').click();
      await page.getByTestId('role-search-input').fill(roleCode);
      await page.getByTestId(`role-item-${roleCode}`).click();
      await expect(page.getByTestId('capability-role-editor')).toHaveAttribute('data-role-pid', rolePid);
      await page.getByTestId('capability-checkbox-sys.cap.dashboard').setChecked(selected);
      await page.getByTestId('capability-save').click();
      await expect(page.getByTestId('confirm-dialog')).toBeVisible();
      const saving = page.waitForResponse(response => new URL(response.url()).origin === origin
        && new URL(response.url()).pathname === '/api/permission/capabilities'
        && new URL(response.url()).searchParams.get('rolePid') === rolePid
        && response.request().method() === 'PUT');
      await page.getByTestId('confirm-ok').click();
      const saved = await saving;
      expect(saved.status()).toBe(200);
      expect(saved.request().postDataJSON()).toEqual(selected ? ['sys.cap.dashboard'] : []);
      expect(String((await saved.json()).code)).toBe('0');
      await expect(page.getByTestId('capability-save')).toBeDisabled();
    }
    await openCustomerDashboard();
    await expect(member.getByRole('link', { name: '编辑', exact: true })).toHaveCount(0);
    const opportunityEndpoint = '/api/dynamic/crm_opportunity_common/list';
    expect((await member.request.get(opportunityEndpoint)).status()).toBe(403);
    const baselineResponse = await member.request.get('/api/dashboards/code/crm_account_360');
    expect(baselineResponse.status()).toBe(200);
    const baselineBody = await baselineResponse.json();
    expect(String(baselineBody.code)).toBe('0');
    const baseline = baselineBody.data;
    expect(baseline.pid).toBeTruthy();
    const endpoint = `/api/dashboards/${baseline.pid}`;
    const versionsEndpoint = `${endpoint}/versions`;
    async function readDashboardVersions() {
      const response = await member.request.get(versionsEndpoint);
      expect(response.status()).toBe(200);
      const body = await response.json();
      expect(String(body.code)).toBe('0');
      expect(Array.isArray(body.data)).toBe(true);
      return body.data as Array<{ pid: string; version: string; operation: string; parentVersionId: string | null }>;
    }

    expect((await member.request.put(endpoint, { data: { description: marker } })).status()).toBe(403);
    await member.screenshot({ path: info.outputPath('dashboard-edit-readonly.png'), fullPage: true });
    await member.goto(`/dashboard-designer/${baseline.pid}`);
    await expect(member.getByRole('heading', { name: '无权编辑仪表盘', exact: true })).toBeVisible();
    await expect(member.getByTestId('designer-toolbar')).toHaveCount(0);
    await member.screenshot({ path: info.outputPath('dashboard-edit-direct-route.png'), fullPage: true });

    await selectDashboardCapability(true);
    await openCustomerDashboard();
    const snapshot = await fetchRoleSnapshot(member);
    expect(snapshot.permissionCodes).toContain('dashboard.update');
    expect(snapshot.permissionCodes).not.toContain('dashboard.manage');
    expect(snapshot.roleCodes).not.toContain('tenant_admin');
    expect(snapshot.permissionCodes).not.toContain('model.crm_opportunity_common.read');
    expect((await member.request.get(opportunityEndpoint)).status()).toBe(403);
    await member.getByRole('link', { name: '编辑', exact: true }).click();
    await expect(member).toHaveURL(url => url.pathname === `/dashboard-designer/${baseline.pid}`);
    await expect(member.getByTestId('designer-toolbar')).toBeVisible();
    async function saveDescription(value: string) {
      await member.getByTestId('toolbar-btn-settings').click();
      const settings = member.getByRole('dialog', { name: 'Dashboard Settings', exact: true });
      await settings.locator('textarea').fill(value);
      await settings.getByRole('button', { name: '保存', exact: true }).click();
      await expect(settings).toHaveCount(0);
      const saving = member.waitForResponse(response => new URL(response.url()).origin === origin
        && new URL(response.url()).pathname === endpoint && response.request().method() === 'PUT');
      await member.getByTestId('designer-toolbar-btn-save').click();
      const saved = await saving;
      expect(saved.status()).toBe(200);
      expect(saved.request().postDataJSON().description).toBe(value);
      expect(String((await saved.json()).code)).toBe('0');
      const readback = await member.request.get(endpoint);
      expect(readback.status()).toBe(200);
      const body = await readback.json();
      expect(String(body.code)).toBe('0');
      expect(body.data.description).toBe(value);
    }
    await saveDescription(marker);
    const firstHistory = await readDashboardVersions();
    expect(firstHistory.length).toBeGreaterThan(0);
    const firstVersion = firstHistory[0];
    expect(firstVersion.pid).toBeTruthy();
    expect(firstVersion.operation).toBe('update');
    await saveDescription(`${marker}-v2`);
    const beforeRollback = await readDashboardVersions();
    expect(beforeRollback.length).toBe(firstHistory.length + 1);
    expect(beforeRollback[0].pid).not.toBe(firstVersion.pid);
    const rollbackEndpoint = `${versionsEndpoint}/${firstVersion.pid}/rollback`;
    const historyLoading = member.waitForResponse(response => new URL(response.url()).origin === origin
      && new URL(response.url()).pathname === versionsEndpoint && response.request().method() === 'GET');
    await member.getByTestId('designer-toolbar').getByRole('button', { name: /^History/ }).click();
    const historyResponse = await historyLoading;
    expect(historyResponse.status()).toBe(200);
    expect(String((await historyResponse.json()).code)).toBe('0');
    const panel = member.getByTestId('version-history-panel');
    await expect(panel.getByRole('heading', { name: '版本历史', exact: true })).toBeVisible();
    const versionButton = panel.getByRole('button').filter({ has: member.getByText(`v${firstVersion.version}`, { exact: true }) });
    await expect(versionButton).toHaveCount(1);
    const detailLoading = member.waitForResponse(response => new URL(response.url()).origin === origin
      && new URL(response.url()).pathname === `${versionsEndpoint}/${firstVersion.pid}`
      && response.request().method() === 'GET');
    await versionButton.click();
    const detailResponse = await detailLoading;
    expect(detailResponse.status()).toBe(200);
    const detailBody = await detailResponse.json();
    expect(String(detailBody.code)).toBe('0');
    expect(detailBody.data.resourceType).toBe('dashboard');
    expect(detailBody.data.resourceId).toBe(baseline.pid);
    expect(detailBody.data.schemaSnapshot.description).toBe(marker);
    await expect(panel.getByText('正在查看历史版本（只读）', { exact: true })).toBeVisible();
    await member.screenshot({ path: info.outputPath('dashboard-version-preview.png'), fullPage: true });
    await panel.getByRole('button', { name: '回滚', exact: true }).click();
    await expect(member.getByRole('heading', { name: '确认回滚', exact: true })).toBeVisible();
    await member.screenshot({ path: info.outputPath('dashboard-version-confirm.png'), fullPage: true });
    await member.getByRole('button', { name: '取消', exact: true }).click();
    await expect(member.getByRole('heading', { name: '确认回滚', exact: true })).toHaveCount(0);
    expect(await readDashboardVersions()).toEqual(beforeRollback);
    const canceledResponse = await member.request.get(endpoint);
    expect(canceledResponse.status()).toBe(200);
    const canceledBody = await canceledResponse.json();
    expect(String(canceledBody.code)).toBe('0');
    expect(canceledBody.data.description).toBe(`${marker}-v2`);
    await panel.getByRole('button', { name: '回滚', exact: true }).click();
    const rollingBack = member.waitForResponse(response => new URL(response.url()).origin === origin
      && new URL(response.url()).pathname === rollbackEndpoint && response.request().method() === 'POST');
    await member.getByRole('button', { name: '确认回滚', exact: true }).click();
    const rolledBack = await rollingBack;
    expect(rolledBack.status()).toBe(200);
    const rollbackBody = await rolledBack.json();
    expect(String(rollbackBody.code)).toBe('0');
    expect(rollbackBody.data.operation).toBe('rollback');
    expect(rollbackBody.data.resourceId).toBe(baseline.pid);
    await expect(member.getByRole('heading', { name: '确认回滚', exact: true })).toHaveCount(0);
    const afterRollback = await readDashboardVersions();
    expect(afterRollback.length).toBe(beforeRollback.length + 2);
    expect(afterRollback.map(version => version.pid)).toEqual(expect.arrayContaining([
      firstVersion.pid, beforeRollback[0].pid, rollbackBody.data.pid,
    ]));
    const backup = afterRollback.find(version => version.operation === 'backup_before_rollback'
      && version.parentVersionId === beforeRollback[0].pid);
    expect(backup).toBeTruthy();
    const backupResponse = await member.request.get(`${versionsEndpoint}/${backup!.pid}`);
    expect(backupResponse.status()).toBe(200);
    const backupBody = await backupResponse.json();
    expect(String(backupBody.code)).toBe('0');
    expect(backupBody.data.schemaSnapshot.description).toBe(`${marker}-v2`);
    await panel.getByRole('button', { name: '关闭版本面板', exact: true }).click();
    await member.reload();
    await expect(member.getByTestId('designer-toolbar')).toBeVisible();
    await member.getByTestId('toolbar-btn-settings').click();
    const restoredSettings = member.getByRole('dialog', { name: 'Dashboard Settings', exact: true });
    await expect(restoredSettings.locator('textarea')).toHaveValue(marker);
    await member.screenshot({ path: info.outputPath('dashboard-version-restored.png'), fullPage: true });
    await restoredSettings.getByRole('button', { name: '取消', exact: true }).click();
    await expect(member.getByTestId('designer-toolbar')).toBeVisible();
    await member.screenshot({ path: info.outputPath('dashboard-edit-writer.png'), fullPage: true });
    await saveDescription(baseline.description ?? '');
    // Authorized saving normalizes legacy widgets. Compare denied writes against the persisted configuration.
    const restoredResponse = await member.request.get(endpoint);
    expect(restoredResponse.status()).toBe(200);
    const restoredBody = await restoredResponse.json();
    expect(String(restoredBody.code)).toBe('0');
    const restored = restoredBody.data;
    expect(restored.description).toBe(baseline.description ?? '');
    // Editing a contextual CRM dashboard must not change the general product default.
    const defaultLoading = member.waitForResponse(response => new URL(response.url()).origin === origin
      && new URL(response.url()).pathname === '/api/dashboards/default' && response.request().method() === 'GET');
    await member.goto('/dashboards');
    const defaultResponse = await defaultLoading;
    expect(defaultResponse.status()).toBe(200);
    const defaultBody = await defaultResponse.json();
    expect(String(defaultBody.code)).toBe('0');
    expect(defaultBody.data.code).toBe('qo_tool_admin_dashboard');
    for (const widgetId of ['qo_tool_quote_weekly_trend', 'qo_tool_bom_weekly_trend']) {
      expect(defaultBody.data.widgets.some((widget: { id: string }) => widget.id === widgetId)).toBe(true);
      const widget = member.locator(`[data-widget-id="${widgetId}"]`);
      await expect(widget).toBeVisible();
      await expect(widget.locator('canvas')).toHaveCount(1);
      await expect(widget.locator('canvas')).toBeVisible();
    }
    await member.screenshot({ path: info.outputPath('dashboard-default-after-context-edit.png'), fullPage: true });
    await member.goto(`/dashboard-designer/${baseline.pid}`);
    await expect(member.getByTestId('designer-toolbar')).toBeVisible();
    await selectDashboardCapability(false);
    await member.reload();
    await expect(member.getByRole('heading', { name: '无权编辑仪表盘', exact: true })).toBeVisible();
    await expect(member.getByTestId('designer-toolbar')).toHaveCount(0);
    expect((await member.request.put(endpoint, { data: { description: `${marker}-denied` } })).status()).toBe(403);
    const versionsAfterRevoke = await readDashboardVersions();
    const deniedRollback = await member.request.post(rollbackEndpoint);
    expect(deniedRollback.status()).toBe(403);
    expect(String((await deniedRollback.json()).code)).toBe('403');
    expect(await readDashboardVersions()).toEqual(versionsAfterRevoke);

    const finalResponse = await member.request.get(endpoint);
    expect(finalResponse.status()).toBe(200);
    const finalBody = await finalResponse.json();
    expect(String(finalBody.code)).toBe('0');
    expect(finalBody.data.description).toBe(baseline.description ?? '');
    expect(finalBody.data.widgets).toEqual(restored.widgets);
    expect(finalBody.data.layoutConfig).toEqual(restored.layoutConfig);
    await member.screenshot({ path: info.outputPath('dashboard-edit-revoked.png'), fullPage: true });
    await openCustomerDashboard();
    await expect(member.getByRole('link', { name: '编辑', exact: true })).toHaveCount(0);
    expect((await member.request.get(opportunityEndpoint)).status()).toBe(403);
  } finally {
    await opened.context.close();
  }
});


for (const domain of ['catalog', 'board', 'rfq', 'dfm', 'margin', 'model'] as const) {
  test(`declared ${domain} capabilities govern direct form writes and revoke without enabling hidden menus`, async ({ page, browser }, info) => {
    test.setTimeout(240_000);
    const marker = uniqueId(domain);
    const roleCode = `e2e_${domain}_${Date.now()}`;
    const created = await page.request.post('/api/roles', { data: { code: roleCode, name: marker, type: 'custom' } });
    expect(created.status()).toBe(200);
    const roleBody = await created.json();
    expect(String(roleBody.code)).toBe('0');
    const rolePid = roleBody.data.pid as string;
    const user = makeQuoteRoleUser(domain, marker, [roleCode]);
    await ensureQuoteRoleUser(page, user);
    const viewCapability = domain === 'catalog' ? 'prod.cap.catalog_view'
      : domain === 'margin' ? 'qo.cap.margin_policy_view'
      : domain === 'model' ? 'sys.cap.model_view' : `pe.cap.${domain}_view`;
    const manageCapability = domain === 'catalog' ? 'prod.cap.catalog'
      : domain === 'margin' ? 'qo.cap.margin_manage'
      : domain === 'model' ? 'sys.cap.model_manage' : `pe.cap.${domain}`;
    type FormFixture = { model: string; pid: string; command: string; field: string; value: string; referenceField?: string };
    const forms: FormFixture[] = [];
    const modelCode = `e2e_model_${Date.now()}`;
    let createdModelPid: string | undefined;
    let bindingFieldPid: string | undefined;
    const bindingFieldCode = `${modelCode}_binding`;
    let marginQuote: { pid: string; code: string; margin: number } | undefined;
    async function seed(command: string, payload: Record<string, unknown>): Promise<string> {
      const result = await executeCommandViaApi(page, command, payload, undefined, 'create');
      expect(String(result.code), command).toBe('0');
      expect(result.recordId, command).toBeTruthy();
      return String(result.recordId);
    }
    if (domain === 'catalog') {
      for (const config of [
        { model: 'prod_brand', command: 'brand', field: 'prod_brand_name', payload: { prod_brand_name: marker, prod_brand_code: marker.slice(0, 32) } },
        { model: 'prod_category', command: 'category', field: 'prod_cat_name', payload: { prod_cat_name: marker, prod_cat_code: marker.slice(0, 32) } },
        { model: 'prod_product', command: 'product', field: 'prod_name', payload: { prod_name: marker, prod_type: 'finished', prod_unit: 'pcs', prod_currency: 'cny' } },
      ]) {
        forms.push({ model: config.model, pid: await seed(`prod:create_${config.command}`, config.payload),
          command: `prod:update_${config.command}`, field: config.field, value: `${marker}-edited` });
      }
    } else if (domain === 'margin') {
      const quote = await seedDownloadableQuote(page);
      const quoteResponse = await page.request.get(`/api/dynamic/qo_quote_common/${quote.quoteId}`);
      expect(quoteResponse.status()).toBe(200);
      const quoteBody = await quoteResponse.json();
      expect(String(quoteBody.code)).toBe('0');
      expect(quoteBody.data.qo_quote_customer).toBeTruthy();
      marginQuote = { pid: quote.quoteId, code: quote.quoteCode, margin: Number(quoteBody.data.qo_quote_margin_pct ?? 0) };
      // The existing evaluator matches the quote customer text, not the CRM record identifier.
      forms.push({ model: 'qo_margin_policy_common', pid: await seed('qo_margin_policy_common:create', {
        qo_mp_name: marker, qo_mp_scope: 'customer', qo_mp_scope_ref: quoteBody.data.qo_quote_customer,
        qo_mp_target_margin: 30, qo_mp_min_margin: 20,
      }), command: 'qo_margin_policy_common:update', field: 'qo_mp_name', value: `${marker}-edited` });
    } else if (domain === 'model') {
      // Model metadata creation uses its native form, not business command forms.
      const fixture = await page.request.post('/api/meta/fields', { data: {
        code: bindingFieldCode, dataType: 'string', uiSchema: { label: marker },
      } });
      expect(fixture.status()).toBe(200);
      const field = await fixture.json();
      expect(String(field.code)).toBe('0');
      bindingFieldPid = field.data.pid;
      expect(bindingFieldPid).toBeTruthy();
    } else if (domain === 'board') {
      const account = await seed('crm:create_account', { crm_acc_name: marker });
      forms.push({ model: 'req_product_pcba_board', pid: await seed('pe:create_board', { pe_board_account_id: account, pe_board_name: marker }),
        command: 'pe:update_board', field: 'pe_board_name', value: `${marker}-edited`, referenceField: 'pe_board_account_id' });
    } else if (domain === 'rfq') {
      const request = await seed('crm:create_customer_request', { crm_cr_title: marker });
      forms.push({ model: 'crm_customer_request_pcba_rfq', pid: await seed('pe:create_customer_request_pcba_rfq', { crm_customer_request_id: request, crm_crq_product_model: marker }),
        command: 'pe:update_customer_request_pcba_rfq', field: 'crm_crq_product_model', value: `${marker}-edited`, referenceField: 'crm_customer_request_id' });
      forms.push({ model: 'crm_customer_request_pcba_price_tier', pid: await seed('pe:create_price_tier_pcba', {
        crm_cpt_customer_request_id: request, crm_cpt_min_qty: 10, crm_cpt_unit_price: 12.5, crm_cpt_currency: 'CNY',
      }), command: 'pe:update_price_tier_pcba', field: 'crm_cpt_min_qty', value: '20', referenceField: 'crm_cpt_customer_request_id' });
    } else {
      for (const config of [
        { common: 'review', title: 'crm_rv_title', model: 'crm_review_pcba_dfm', reference: 'crm_review_common_id', command: 'review', field: 'crm_rvd_min_trace_space', value: '5mil' },
        { common: 'risk', title: 'crm_rk_title', model: 'crm_risk_pcba_dfm', reference: 'crm_risk_common_id', command: 'risk', field: 'crm_rkd_affected_refdes', value: 'R1,R2' },
        { common: 'clarification', title: 'crm_cl_title', model: 'crm_clarification_pcba_dfm', reference: 'crm_clarification_common_id', command: 'clarification', field: 'crm_cld_drawing_ref', value: `${marker}-drawing` },
      ]) {
        const common = await seed(`crm:create_${config.common}`, { [config.title]: marker });
        forms.push({ model: config.model, pid: await seed(`pe:create_${config.command}_pcba_dfm`, { [config.reference]: common }),
          command: `pe:update_${config.command}_pcba_dfm`, field: config.field, value: config.value, referenceField: config.reference });
      }
    }
    const opened = await openQuoteRolePage(browser, user);
    const member = opened.page;
    const origin = new URL(member.url()).origin;
    try {
      async function select(manage: boolean, configureScope = false, importing = false) {
        await page.goto('/home');
        await ensureSidebarExpanded(page);
        await page.getByTestId('sidebar').locator('a[href="/enterprise/permissions"]').click();
        await page.getByTestId('role-search-input').fill(roleCode);
        await page.getByTestId(`role-item-${roleCode}`).click();
        await expect(page.getByTestId('capability-role-editor')).toHaveAttribute('data-role-pid', rolePid);
        if (configureScope) {
          await page.getByTestId('data-scope-modify-btn').click();
          await page.getByTestId('data-scope-option-all').click();
          await page.getByTestId('data-scope-apply').click();
          await expect(page.getByTestId('data-scope-drawer')).toHaveCount(0);
        }
        await page.getByTestId('capability-checkbox-qo.cap.platform_read').check();
        await page.getByTestId(`capability-checkbox-${viewCapability}`).check();
        if (domain === 'margin') await page.getByTestId('capability-checkbox-qo.cap.quote_view').check();
        await page.getByTestId(`capability-checkbox-${manageCapability}`).setChecked(manage);
        if (domain === 'catalog') {
          await page.getByTestId('capability-checkbox-prod.cap.product_import').setChecked(importing);
        }
        await page.getByTestId('capability-save').click();
        await expect(page.getByTestId('confirm-dialog')).toBeVisible();
        const saving = page.waitForResponse(response => new URL(response.url()).origin === origin
          && new URL(response.url()).pathname === '/api/permission/capabilities'
          && new URL(response.url()).searchParams.get('rolePid') === rolePid
          && response.request().method() === 'PUT');
        await page.getByTestId('confirm-ok').click();
        const saved = await saving;
        expect(saved.status()).toBe(200);
        expect(String((await saved.json()).code)).toBe('0');
        const payload = saved.request().postDataJSON() as string[];
        expect(payload).toContain(viewCapability);
        expect(payload.includes(manageCapability)).toBe(manage);
        if (domain === 'catalog') expect(payload.includes('prod.cap.product_import')).toBe(importing);
        if (domain === 'margin') expect([...payload].sort()).toEqual([
          'qo.cap.platform_read', 'qo.cap.quote_view', viewCapability,
          ...(manage ? [manageCapability] : []),
        ].sort());
        await expect(page.getByTestId('capability-save')).toBeDisabled();
      }
      async function expectDeniedForm(form: FormFixture, stage: string) {
        await member.goto(`/p/${form.model}/edit/${form.pid}?commandCode=${encodeURIComponent(form.command)}`);
        await expect(member.getByText('无权限访问此页面，请联系管理员。', { exact: true })).toBeVisible();
        await expect(member.getByTestId('form-btn-save')).toHaveCount(0);
        await member.screenshot({ path: info.outputPath(`${form.model}-${stage}.png`), fullPage: true });
      }
      await select(false, true);
      await member.goto('/home');
      await ensureSidebarExpanded(member);
      const initialMenuPaths = await member.getByTestId('sidebar').locator('a[href]').evaluateAll(links => links.map(link => link.getAttribute('href')).sort());
      for (const form of forms) await expectDeniedForm(form, 'readonly');
      async function expectModelCreationDenied(stage: string) {
        await member.goto('/meta/models');
        await expect(member.getByRole('columnheader', { name: /模型编码$/ })).toBeVisible();
        await expect(member.getByTestId('toolbar-btn-create')).toHaveCount(0);
        for (const path of ['/meta/models/new', '/meta/models/new/virtual']) {
          await member.goto(path);
          await expect(member.getByText('无权限访问此页面，请联系管理员。', { exact: true })).toBeVisible();
          await expect(member.getByTestId('model-code-input')).toHaveCount(0);
          await expect(member.getByTestId('model-create-submit')).toHaveCount(0);
        }
        const denied = await member.request.post('/api/meta/models', {
          data: { code: `${modelCode}_${stage}`, displayName: marker, modelType: 'entity', namespace: 'default', env: 'dev' },
        });
        expect(denied.status()).toBe(403);
        const listing = await member.request.get('/api/meta/models', { params: { code: `${modelCode}_${stage}` } });
        expect(listing.status()).toBe(200);
        const listed = await listing.json();
        expect(String(listed.code)).toBe('0');
        expect(listed.data.records).toEqual([]);
        await member.screenshot({ path: info.outputPath(`model-create-${stage}.png`), fullPage: true });
      }
      async function marginRows(name: string): Promise<Record<string, unknown>[]> {
        const response = await page.request.get('/api/dynamic/qo_margin_policy_common/list', {
          params: { pageNum: '1', pageSize: '10', filters: JSON.stringify([
            { fieldName: 'qo_mp_name', operator: 'EQ', value: name },
          ]) },
        });
        expect(response.status()).toBe(200);
        const body = await response.json();
        expect(String(body.code)).toBe('0');
        expect(Array.isArray(body.data.records)).toBe(true);
        return body.data.records;
      }
      async function expectMarginCreateDeleteDenied(stage: string) {
        const rejectedName = `${marker}-${stage}-create-denied`;
        const retained = forms[0];
        const before = await marginRows(stage === 'revoked' ? retained.value : marker);
        expect(before).toHaveLength(1);
        await searchBusinessList(member, '/p/c/qo_margin_policy_common_list', stage === 'revoked' ? retained.value : marker, 'qo_margin_policy_common');
        const row = member.getByRole('row').filter({ hasText: stage === 'revoked' ? retained.value : marker });
        await expect(row).toHaveCount(1);
        await expect(member.getByTestId('toolbar-btn-create')).toHaveCount(0);
        await expect(row.getByTestId('row-action-delete')).toHaveCount(0);
        await member.screenshot({ path: info.outputPath(`margin-list-${stage}.png`), fullPage: true });
        await member.goto('/p/qo_margin_policy_common/new?commandCode=qo_margin_policy_common%3Acreate');
        await expect(member.getByText('无权限访问此页面，请联系管理员。', { exact: true })).toBeVisible();
        await expect(member.getByTestId('form-btn-save')).toHaveCount(0);
        await member.screenshot({ path: info.outputPath(`margin-create-${stage}.png`), fullPage: true });
        const deniedCreate = await member.request.post('/api/meta/commands/execute/qo_margin_policy_common:create', {
          data: { payload: { qo_mp_name: rejectedName, qo_mp_scope: 'customer', qo_mp_scope_ref: rejectedName }, operationType: 'create' },
        });
        expect(deniedCreate.status()).toBe(403);
        expect(String((await deniedCreate.json()).code)).toBe('403');
        expect(await marginRows(rejectedName)).toEqual([]);
        const deniedDelete = await member.request.post('/api/meta/commands/execute/qo_margin_policy_common:delete', {
          data: { payload: {}, targetRecordPid: retained.pid, operationType: 'delete' },
        });
        expect(deniedDelete.status()).toBe(403);
        expect(String((await deniedDelete.json()).code)).toBe('403');
        expect(await marginRows(stage === 'revoked' ? retained.value : marker)).toEqual(before);
      }
      if (domain === 'model') await expectModelCreationDenied('readonly');
      if (domain === 'margin') await expectMarginCreateDeleteDenied('readonly');
      await select(true);
      if (domain === 'margin') {
        const authoredName = `${marker}-member-created`;
        await searchBusinessList(member, '/p/c/qo_margin_policy_common_list', marker, 'qo_margin_policy_common');
        await member.getByTestId('toolbar-btn-create').click();
        await expect(member.getByTestId('form-field-qo_mp_name')).toBeVisible();
        await member.getByTestId('form-field-qo_mp_name').locator('input, textarea').fill(authoredName);
        await member.getByTestId('select-trigger-qo_mp_scope').click();
        await member.getByRole('option', { name: '客户', exact: true }).click();
        await member.getByTestId('form-field-qo_mp_scope_ref').locator('input, textarea').fill(authoredName);
        await member.getByTestId('form-field-qo_mp_target_margin').locator('input').fill('32');
        await member.getByTestId('form-field-qo_mp_min_margin').locator('input').fill('18');
        const creating = member.waitForResponse(response => new URL(response.url()).origin === origin
          && decodeURIComponent(new URL(response.url()).pathname).endsWith('/api/meta/commands/execute/qo_margin_policy_common:create')
          && response.request().method() === 'POST');
        await member.getByTestId('form-btn-save').click();
        const created = await creating;
        expect(created.status()).toBe(200);
        const result = await created.json();
        expect(String(result.code)).toBe('0');
        expect(result.data.commandCode).toBe('qo_margin_policy_common:create');
        expect(result.data.data.qo_margin_policy_common_inserted).toBe(1);
        expect(result.data.data.recordPid).toBeTruthy();
        expect(created.request().postDataJSON().payload).toMatchObject({
          qo_mp_name: authoredName, qo_mp_scope: 'customer', qo_mp_scope_ref: authoredName,
        });
        const rows = await marginRows(authoredName);
        expect(rows).toHaveLength(1);
        expect(rows[0].pid).toBe(result.data.data.recordPid);
        expect(Number(rows[0].qo_mp_target_margin)).toBe(32);
        expect(Number(rows[0].qo_mp_min_margin)).toBe(18);
        await searchBusinessList(member, '/p/c/qo_margin_policy_common_list', authoredName, 'qo_margin_policy_common');
        const row = member.getByRole('row').filter({ hasText: authoredName });
        await expect(row).toHaveCount(1);
        await member.screenshot({ path: info.outputPath('margin-member-created.png'), fullPage: true });
        await clickRowActionByLocator(member, row, 'delete', '删除');
        await expect(member.getByTestId('confirm-dialog')).toBeVisible();
        await member.screenshot({ path: info.outputPath('margin-member-delete-confirm.png'), fullPage: true });
        await member.getByTestId('confirm-cancel').click();
        expect(await marginRows(authoredName)).toEqual(rows);
        await clickRowActionByLocator(member, row, 'delete', '删除');
        await expect(member.getByTestId('confirm-dialog')).toBeVisible();
        const deleting = member.waitForResponse(response => new URL(response.url()).origin === origin
          && decodeURIComponent(new URL(response.url()).pathname).endsWith('/api/meta/commands/execute/qo_margin_policy_common:delete')
          && response.request().method() === 'POST');
        await member.getByTestId('confirm-ok').click();
        const deleted = await deleting;
        expect(deleted.status()).toBe(200);
        expect(String((await deleted.json()).code)).toBe('0');
        expect(deleted.request().postDataJSON().targetRecordPid).toBe(result.data.data.recordPid);
        expect(await marginRows(authoredName)).toEqual([]);
        await member.reload();
        await expect(member.getByTestId('list-search-input')).toHaveValue(authoredName);
        await expect(member.getByRole('cell', { name: '暂无数据', exact: true })).toBeVisible();
        await expect(member.getByRole('row').filter({ hasText: authoredName })).toHaveCount(0);
        await member.screenshot({ path: info.outputPath('margin-member-deleted.png'), fullPage: true });
      }
      if (domain === 'model') {
        await member.goto('/meta/models');
        await expect(member.getByRole('columnheader', { name: /模型编码$/ })).toBeVisible();
        await member.getByTestId('toolbar-btn-create').click();
        await expect(member).toHaveURL(/\/meta\/models\/new(?:[?#]|$)/);
        await member.getByTestId('model-type-physical').click();
        await member.getByTestId('model-code-input').fill(modelCode);
        await member.getByTestId('model-display-name-input').fill(marker);
        const creating = member.waitForResponse(response => new URL(response.url()).origin === origin
          && new URL(response.url()).pathname === '/api/meta/models' && response.request().method() === 'POST');
        await member.getByTestId('model-create-submit').click();
        const created = await creating;
        expect(created.status()).toBe(200);
        const result = await created.json();
        expect(String(result.code)).toBe('0');
        expect(created.request().postDataJSON().code).toBe(modelCode);
        createdModelPid = result.data.pid;
        expect(createdModelPid).toBeTruthy();
        await expect(member).toHaveURL(new RegExp(`/meta/models/${createdModelPid}(?:[?#]|$)`));
        const persisted = await member.request.get(`/api/meta/models/${createdModelPid}`);
        expect(persisted.status()).toBe(200);
        const body = await persisted.json();
        expect(body.data.code).toBe(modelCode);
        expect(body.data.displayName).toBe(marker);
        await expect(member.getByRole('heading', { name: marker, exact: true })).toBeVisible();
        await expect(member.getByTestId('model-edit-action')).toBeVisible();
        await expect(member.getByTestId('model-more-actions')).toBeVisible();
        const modelPermissions = await member.request.get(`/api/permissions/model/${modelCode}`);
        expect(modelPermissions.status()).toBe(200);
        expect(String((await modelPermissions.json()).code)).toBe('0');
        const globalPermissions = await member.request.get('/api/permissions/tree');
        expect(globalPermissions.status()).toBe(403);
        await expect(member.getByTestId('model-primary-page-action')).toHaveCount(0);
        await member.screenshot({ path: info.outputPath('model-created-writer.png'), fullPage: true });
        await member.getByRole('button', { name: /^版本 \(\d+\)$/ }).click();
        const version = Number(body.data.version);
        expect(version).toBeGreaterThan(0);
        const readingVersion = member.waitForResponse(response => new URL(response.url()).origin === origin
          && new URL(response.url()).pathname === `/api/meta/models/code/${modelCode}/versions/${version}`
          && response.request().method() === 'GET');
        await member.getByTestId(`model-version-view-${version}`).click();
        const versionResponse = await readingVersion;
        expect(versionResponse.status()).toBe(200);
        const versionBody = await versionResponse.json();
        expect(String(versionBody.code)).toBe('0');
        expect(versionBody.data).toMatchObject({ code: modelCode, version, displayName: marker });
        await expect(member.getByTestId('model-version-detail')).toHaveAttribute('data-version', String(version));
        await expect(member.getByTestId('model-version-detail')).toContainText(marker);
        await member.screenshot({ path: info.outputPath('model-version-detail-writer.png'), fullPage: true });
        await member.getByRole('button', { name: /^字段 \(\d+\)$/ }).click();
        await expect(member.getByRole('columnheader', { name: '字段编码', exact: true })).toBeVisible();
        await expect(member.getByTestId('model-field-configure')).toHaveCount(2);
        await expect(member.getByTestId('model-field-unbind')).toHaveCount(2);
        await expect(member.getByTestId('model-field-reorder')).toHaveCount(2);
        await member.getByTestId('model-fields-add-button').click();
        await expect(member.getByTestId('field-selection-dialog')).toBeVisible();
        await expect(member.getByTestId('field-selection-tab-create')).toHaveCount(0);
        await member.getByTestId('field-selection-search').fill(bindingFieldCode);
        await member.getByTestId(`field-selection-field-${bindingFieldPid}`).click();
        const binding = member.waitForResponse(response => new URL(response.url()).origin === origin
          && new URL(response.url()).pathname === `/api/meta/models/${createdModelPid}/fields/bind`
          && response.request().method() === 'POST');
        await member.getByTestId('field-selection-bind').click();
        const bound = await binding;
        expect(bound.status()).toBe(200);
        expect(String((await bound.json()).code)).toBe('0');
        expect(bound.request().postDataJSON().fieldPid).toBe(bindingFieldPid);
        await expect(member.getByTestId('field-selection-dialog')).toHaveCount(0);
        const fields = await member.request.get(`/api/meta/models/${createdModelPid}/fields`);
        expect(fields.status()).toBe(200);
        const fieldBody = await fields.json();
        expect(String(fieldBody.code)).toBe('0');
        expect(fieldBody.data.some((field: { code: string; pid: string }) => field.code === bindingFieldCode && field.pid === bindingFieldPid)).toBe(true);
        await expect(member.getByRole('row').filter({ hasText: bindingFieldCode })).toHaveCount(1);
        await member.getByRole('row').filter({ hasText: bindingFieldCode }).getByTestId('model-field-unbind').click();
        await expect(member.getByTestId('confirm-dialog')).toBeVisible();
        await expect(member.getByTestId('confirm-dialog')).toContainText(bindingFieldCode);
        await member.screenshot({ path: info.outputPath('model-field-unbind-confirm.png'), fullPage: true });
        const unbinding = member.waitForResponse(response => new URL(response.url()).origin === origin
          && new URL(response.url()).pathname === `/api/meta/models/${createdModelPid}/fields/${bindingFieldPid}`
          && response.request().method() === 'DELETE');
        await member.getByTestId('confirm-ok').click();
        const unbound = await unbinding;
        expect(unbound.status()).toBe(200);
        const unboundBody = await unbound.json();
        expect(String(unboundBody.code)).toBe('0');
        expect(unboundBody.data).toBe(true);
        await member.reload();
        await expect(member.getByRole('row').filter({ hasText: bindingFieldCode })).toHaveCount(0);
        const removedFields = await member.request.get(`/api/meta/models/${createdModelPid}/fields`);
        expect(removedFields.status()).toBe(200);
        expect((await removedFields.json()).data.some((field: { pid: string }) => field.pid === bindingFieldPid)).toBe(false);
        await member.getByTestId('model-fields-add-button').click();
        await member.getByTestId('field-selection-search').fill(bindingFieldCode);
        await member.getByTestId(`field-selection-field-${bindingFieldPid}`).click();
        const rebinding = member.waitForResponse(response => new URL(response.url()).origin === origin
          && new URL(response.url()).pathname === `/api/meta/models/${createdModelPid}/fields/bind`
          && response.request().method() === 'POST');
        await member.getByTestId('field-selection-bind').click();
        const rebound = await rebinding;
        expect(rebound.status()).toBe(200);
        expect(String((await rebound.json()).code)).toBe('0');
        expect(rebound.request().postDataJSON().fieldPid).toBe(bindingFieldPid);
        await expect(member.getByTestId('field-selection-dialog')).toHaveCount(0);
        await expect(member.getByTestId('model-fields-guidance')).toHaveText('拖动字段可调整显示顺序');
        await member.getByRole('row').filter({ hasText: bindingFieldCode }).getByTestId('model-field-configure').click();
        await expect(member.getByTestId('model-field-config-dialog')).toBeVisible();
        await member.getByTestId('schema-config-field-readonly').getByRole('switch').check();
        await member.screenshot({ path: info.outputPath('model-field-config-open.png'), fullPage: true });
        const configuring = member.waitForResponse(response => new URL(response.url()).origin === origin
          && new URL(response.url()).pathname === `/api/meta/models/${createdModelPid}/field-bindings/${bindingFieldPid}/configure`
          && response.request().method() === 'POST');
        await member.getByTestId('model-field-config-save').click();
        const configured = await configuring;
        expect(configured.status()).toBe(200);
        const configuredBody = await configured.json();
        expect(String(configuredBody.code)).toBe('0');
        expect(configuredBody.data.modelPid).toBe(createdModelPid);
        expect(configuredBody.data.fieldPid).toBe(bindingFieldPid);
        expect(configured.request().postDataJSON().editable).toBe(false);
        await expect(member.getByTestId('model-field-config-dialog')).toHaveCount(0);
        await member.reload();
        await expect(member.getByRole('row').filter({ hasText: bindingFieldCode })).toContainText('只读');
        const configuredFields = await member.request.get(`/api/meta/models/${createdModelPid}/fields`);
        expect(configuredFields.status()).toBe(200);
        const savedField = (await configuredFields.json()).data.find((field: { pid: string }) => field.pid === bindingFieldPid);
        expect(savedField.editable).toBe(false);
        const reorderHandle = member.getByRole('row').filter({ hasText: bindingFieldCode }).getByTestId('model-field-reorder');
        const initialPosition = await reorderHandle.boundingBox();
        expect(initialPosition).not.toBeNull();
        await reorderHandle.focus();
        await reorderHandle.press('Space');
        await expect(reorderHandle).toHaveAttribute('aria-pressed', 'true');
        await reorderHandle.press('ArrowUp');
        await expect.poll(async () => (await reorderHandle.boundingBox())?.y).toBeLessThan(initialPosition!.y);
        const [reordered] = await Promise.all([
          member.waitForResponse(response => new URL(response.url()).origin === origin
            && new URL(response.url()).pathname === `/api/meta/models/${createdModelPid}/fields/reorder`
            && response.request().method() === 'PUT'),
          reorderHandle.press('Space'),
        ]);
        expect(reordered.status()).toBe(200);
        expect(String((await reordered.json()).code)).toBe('0');
        expect(reordered.request().postDataJSON()[bindingFieldPid!]).toBe(2);
        await member.reload();
        const reorderedFields = await member.request.get(`/api/meta/models/${createdModelPid}/fields`);
        expect(reorderedFields.status()).toBe(200);
        expect((await reorderedFields.json()).data.find((field: { pid: string }) => field.pid === bindingFieldPid).fieldOrder).toBe(2);
        await expect(member.getByRole('row').filter({ hasText: bindingFieldCode }).getByRole('cell').nth(7)).toHaveText('2');
        await member.screenshot({ path: info.outputPath('model-fields-writer.png'), fullPage: true });
        await member.getByRole('button', { name: '概览', exact: true }).click();
      }
      for (const form of forms) {
        await member.goto(`/p/${form.model}/edit/${form.pid}?commandCode=${encodeURIComponent(form.command)}`);
        const input = member.getByTestId(`form-field-${form.field}`).locator('input, textarea');
        await expect(input).toBeVisible();
        if (form.referenceField) {
          await expect(member.getByTestId(`select-trigger-${form.referenceField}`)).toBeVisible();
          await expect(member.getByTestId(`select-trigger-${form.referenceField}`)).toHaveText(marker);
        }
        await input.fill(form.value);
        const saving = member.waitForResponse(response => new URL(response.url()).origin === origin
          && decodeURIComponent(new URL(response.url()).pathname).endsWith(`/api/meta/commands/execute/${form.command}`)
          && response.request().method() === 'POST');
        await member.getByTestId('form-btn-save').click();
        const saved = await saving;
        expect(saved.status()).toBe(200);
        expect(String((await saved.json()).code)).toBe('0');
        expect(String(saved.request().postDataJSON().payload[form.field])).toBe(form.value);
        await member.goto(`/p/${form.model}/edit/${form.pid}?commandCode=${encodeURIComponent(form.command)}`);
        await expect(member.getByTestId(`form-field-${form.field}`).locator('input, textarea')).toHaveValue(form.value);
        if (form.referenceField) {
          await expect(member.getByTestId(`select-trigger-${form.referenceField}`)).toBeVisible();
          await expect(member.getByTestId(`select-trigger-${form.referenceField}`)).toHaveText(marker);
        }
        await member.getByTestId('form-btn-save').scrollIntoViewIfNeeded();
        await member.screenshot({ path: info.outputPath(`${form.model}-writer-actions.png`), fullPage: true });
        await member.getByTestId(`form-field-${form.field}`).scrollIntoViewIfNeeded();
        await member.screenshot({ path: info.outputPath(`${form.model}-writer.png`), fullPage: true });
      }
      if (marginQuote) {
        await searchBusinessList(member, '/p/c/qo_pricing_workbench_list', marginQuote.code, 'qo_quote_common');
        const row = member.getByRole('row').filter({ hasText: marginQuote.code });
        await expect(row).toHaveCount(1);
        const evaluating = member.waitForResponse(response => new URL(response.url()).origin === origin
          && decodeURIComponent(new URL(response.url()).pathname).endsWith('/api/meta/commands/execute/qo_quote_common:evaluate_margin')
          && response.request().method() === 'POST');
        await expect(row.getByTestId('row-action-more')).toBeVisible();
        await clickRowActionByLocator(member, row, 'evaluate_margin', '评估毛利');
        const evaluated = await evaluating;
        expect(evaluated.status()).toBe(200);
        expect(String((await evaluated.json()).code)).toBe('0');
        expect(evaluated.request().postDataJSON().targetRecordPid).toBe(marginQuote.pid);
        const readback = await member.request.get(`/api/dynamic/qo_quote_common/${marginQuote.pid}`);
        expect(readback.status()).toBe(200);
        const body = await readback.json();
        expect(String(body.code)).toBe('0');
        expect(Number(body.data.qo_quote_applied_min_margin)).toBe(20);
        expect(body.data.qo_quote_margin_alert).toBe(marginQuote.margin < 20);
        await member.screenshot({ path: info.outputPath('margin-evaluated-workbench.png'), fullPage: true });
      }
      await select(false);
      if (domain === 'model') {
        await expectModelCreationDenied('revoked');
        const retained = await member.request.get(`/api/meta/models/${createdModelPid}`);
        expect(retained.status()).toBe(200);
        const body = await retained.json();
        expect(body.data.code).toBe(modelCode);
        expect(body.data.displayName).toBe(marker);
        await member.goto(`/meta/models/${createdModelPid}`);
        await expect(member.getByRole('heading', { name: marker, exact: true })).toBeVisible();
        await expect(member.getByTestId('model-edit-action')).toHaveCount(0);
        await expect(member.getByTestId('model-more-actions')).toHaveCount(0);
        await expect(member.getByTestId('model-primary-page-action')).toHaveCount(0);
        await member.screenshot({ path: info.outputPath('model-detail-revoked-reader.png'), fullPage: true });
        await member.getByRole('button', { name: /^字段 \(\d+\)$/ }).click();
        await expect(member.getByRole('columnheader', { name: '字段编码', exact: true })).toBeVisible();
        await expect(member.getByTestId('model-field-configure')).toHaveCount(0);
        await expect(member.getByTestId('model-field-unbind')).toHaveCount(0);
        await expect(member.getByTestId('model-field-reorder')).toHaveCount(0);
        await expect(member.getByTestId('model-fields-add-button')).toHaveCount(0);
        await expect(member.getByRole('row').filter({ hasText: bindingFieldCode })).toHaveCount(1);
        await expect(member.getByTestId('model-fields-guidance')).toHaveText('字段配置仅供查看');
        const forbiddenUnbind = await member.request.delete(`/api/meta/models/${createdModelPid}/fields/${bindingFieldPid}`);
        expect(forbiddenUnbind.status()).toBe(403);
        const forbiddenReorder = await member.request.put(`/api/meta/models/${createdModelPid}/fields/reorder`, {
          data: { [bindingFieldPid!]: 99 },
        });
        expect(forbiddenReorder.status()).toBe(403);
        const deniedConfig = await member.request.post(`/api/meta/models/${createdModelPid}/field-bindings/${bindingFieldPid}/configure`, {
          data: { editable: true },
        });
        expect(deniedConfig.status()).toBe(403);
        const retainedFields = await member.request.get(`/api/meta/models/${createdModelPid}/fields`);
        expect(retainedFields.status()).toBe(200);
        const retainedField = (await retainedFields.json()).data.find((field: { pid: string }) => field.pid === bindingFieldPid);
        expect(retainedField.editable).toBe(false);
        expect(retainedField.fieldOrder).toBe(2);
        await member.screenshot({ path: info.outputPath('model-fields-revoked-reader.png'), fullPage: true });
      }
      if (marginQuote) {
        await searchBusinessList(member, '/p/c/qo_pricing_workbench_list', marginQuote.code, 'qo_quote_common');
        const row = member.getByRole('row').filter({ hasText: marginQuote.code });
        await expect(row).toHaveCount(1);
        // A closed portal menu alone cannot prove the action was removed.
        await expect(row.getByTestId('row-action-more')).toHaveCount(0);
        await expect(member.getByTestId('row-action-evaluate_margin')).toHaveCount(0);
        const denied = await member.request.post('/api/meta/commands/execute/qo_quote_common:evaluate_margin', {
          data: { payload: {}, targetRecordPid: marginQuote.pid, operationType: 'update' },
        });
        expect(denied.status()).toBe(403);
        const readback = await member.request.get(`/api/dynamic/qo_quote_common/${marginQuote.pid}`);
        expect(readback.status()).toBe(200);
        const body = await readback.json();
        expect(Number(body.data.qo_quote_applied_min_margin)).toBe(20);
        expect(body.data.qo_quote_margin_alert).toBe(marginQuote.margin < 20);
        await member.screenshot({ path: info.outputPath('margin-revoked-workbench.png'), fullPage: true });
      }
      if (domain === 'margin') await expectMarginCreateDeleteDenied('revoked');
      for (const form of forms) {
        await expectDeniedForm(form, 'revoked');
        const denied = await member.request.post(`/api/meta/commands/execute/${form.command}`, {
          data: { payload: { [form.field]: `${marker}-denied` }, targetRecordPid: form.pid, operationType: 'update' },
        });
        expect(denied.status()).toBe(403);
        expect(String((await denied.json()).code)).toBe('403');
        const readback = await member.request.get(`/api/dynamic/${form.model}/${form.pid}`);
        expect(readback.status()).toBe(200);
        const body = await readback.json();
        expect(String(body.code)).toBe('0');
        expect(String(body.data[form.field])).toBe(form.value);
      }
      if (domain === 'catalog') {
        // The focused composition keeps catalog menus hidden. Verify the real API contract
        // after capability-UI save without claiming a business upload-button journey.
        await select(false, false, true);
        const importedSnapshot = await fetchRoleSnapshot(member);
        expect(importedSnapshot.roleCodes).toEqual([roleCode]);
        expect(importedSnapshot.permissionCodes).not.toContain('prod.product.manage');
        for (const action of ['read', 'create', 'update', 'import']) {
          expect(importedSnapshot.permissionCodes).toContain(`model.prod_product.${action}`);
        }
        const importedName = `${marker}-imported`;
        const updatedName = `${marker}-import-updated`;
        const deniedName = `${marker}-import-revoked`;
        const workbookFor = (payload: Record<string, unknown>): Buffer => {
          const workbook = XLSXUtils.book_new();
          XLSXUtils.book_append_sheet(workbook, XLSXUtils.json_to_sheet([payload]), 'Products');
          return Buffer.from(xlsxWrite(workbook, { bookType: 'xlsx', type: 'buffer' }));
        };
        const productValues = { prod_type: 'finished', prod_unit: 'pcs', prod_currency: 'cny' };
        const readExactProducts = async (name: string): Promise<Record<string, unknown>[]> => {
          const response = await member.request.get('/api/dynamic/prod_product/list', { params: {
            pageNum: '1', pageSize: '10', filters: JSON.stringify([
              { fieldName: 'prod_name', operator: 'EQ', value: name },
            ]),
          } });
          expect(response.status()).toBe(200);
          const body = await response.json();
          expect(String(body.code)).toBe('0');
          expect(Array.isArray(body.data.records)).toBe(true);
          return body.data.records;
        };
        const postImport = async (buffer: Buffer, mode: 'insert' | 'update') => {
          const params = new URLSearchParams({ mode, ...(mode === 'update' ? { matchKey: 'prod_code' } : {}) });
          return member.request.post(`/api/meta/excel/import/prod_product?${params}`, { multipart: {
            file: { name: `${marker}-${mode}.xlsx`, mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', buffer },
          } });
        };
        expect(await readExactProducts(importedName)).toHaveLength(0);
        const inserted = await postImport(workbookFor({ prod_name: importedName, ...productValues }), 'insert');
        expect(inserted.status()).toBe(200);
        const insertBody = await inserted.json();
        expect(String(insertBody.code)).toBe('0');
        expect(insertBody.data).toMatchObject({ totalRows: 1, successCount: 1, errorCount: 0,
          createdCount: 1, updatedCount: 0, asyncTask: false });
        const insertedRows = await readExactProducts(importedName);
        expect(insertedRows).toHaveLength(1);
        const importedPid = String(insertedRows[0].pid);
        const importedCode = String(insertedRows[0].prod_code ?? '');
        expect(importedPid).not.toBe('');
        expect(importedCode).not.toBe('');
        expect(insertedRows[0].prod_status).toBe('planned');
        const updated = await postImport(workbookFor({ prod_code: importedCode,
          prod_name: updatedName, ...productValues }), 'update');
        expect(updated.status()).toBe(200);
        const updateBody = await updated.json();
        expect(String(updateBody.code)).toBe('0');
        expect(updateBody.data).toMatchObject({ totalRows: 1, successCount: 1, errorCount: 0,
          createdCount: 0, updatedCount: 1, asyncTask: false });
        expect(await readExactProducts(importedName)).toHaveLength(0);
        const updatedRows = await readExactProducts(updatedName);
        expect(updatedRows).toHaveLength(1);
        expect(String(updatedRows[0].pid)).toBe(importedPid);
        expect(updatedRows[0].prod_code).toBe(importedCode);
        expect(updatedRows[0].prod_status).toBe('planned');
        const restrictedCommands = [
          ...['delete', 'activate', 'discontinue', 'obsolete'].map(action => ({
            code: `prod:${action}_product`, pid: importedPid, payload: {}, operation: action === 'delete' ? 'delete' : 'update',
          })),
          ...forms.filter(form => form.model !== 'prod_product').flatMap(form => {
            const entity = form.model === 'prod_brand' ? 'brand' : 'category';
            return [
              { code: `prod:create_${entity}`, pid: undefined,
                payload: { [form.field]: deniedName }, operation: 'create' },
              { code: form.command, pid: form.pid,
                payload: { [form.field]: deniedName }, operation: 'update' },
              { code: `prod:delete_${entity}`, pid: form.pid, payload: {}, operation: 'delete' },
            ];
          }),
        ];
        for (const command of restrictedCommands) {
          const denied = await member.request.post(`/api/meta/commands/execute/${command.code}`, { data: {
            payload: command.payload, targetRecordPid: command.pid, operationType: command.operation,
          } });
          expect(denied.status(), command.code).toBe(403);
          expect(String((await denied.json()).code), command.code).toBe('403');
        }
        for (const form of forms.filter(form => form.model !== 'prod_product')) {
          const response = await member.request.get(`/api/dynamic/${form.model}/${form.pid}`);
          expect(response.status()).toBe(200);
          const body = await response.json();
          expect(String(body.code)).toBe('0');
          expect(body.data[form.field]).toBe(form.value);
          const added = await member.request.get(`/api/dynamic/${form.model}/list`, { params: {
            filters: JSON.stringify([{ fieldName: form.field, operator: 'EQ', value: deniedName }]),
            pageSize: '10', pageNum: '1',
          } });
          expect(added.status()).toBe(200);
          const addedBody = await added.json();
          expect(String(addedBody.code)).toBe('0');
          expect(addedBody.data.records).toEqual([]);
        }
        expect(await readExactProducts(updatedName)).toEqual(updatedRows);
        await select(false);
        const revoked = await fetchRoleSnapshot(member);
        for (const action of ['create', 'update', 'import']) {
          expect(revoked.permissionCodes).not.toContain(`model.prod_product.${action}`);
        }
        const deniedInsert = await postImport(workbookFor({ prod_name: deniedName, ...productValues }), 'insert');
        expect(deniedInsert.status()).toBe(403);
        expect(String((await deniedInsert.json()).code)).toBe('403');
        const deniedUpdate = await postImport(workbookFor({ prod_code: importedCode,
          prod_name: deniedName, ...productValues }), 'update');
        expect(deniedUpdate.status()).toBe(403);
        expect(String((await deniedUpdate.json()).code)).toBe('403');
        expect(await readExactProducts(deniedName)).toHaveLength(0);
        expect(await readExactProducts(updatedName)).toEqual(updatedRows);
      }
      await member.goto('/home');
      await ensureSidebarExpanded(member);
      expect(await member.getByTestId('sidebar').locator('a[href]').evaluateAll(links => links.map(link => link.getAttribute('href')).sort())).toEqual(initialMenuPaths);
    } finally { await opened.context.close(); }
  });
}


test('model field capability binds, unbinds, configures and revokes in the existing session without changing visible menus', async ({ page, browser }, info) => {
  test.setTimeout(180_000);
  const marker = uniqueId('field_cap');
  const roleCode = `e2e_field_cap_${Date.now()}`;
  const modelCode = `e2e_field_model_${Date.now()}`;
  const fieldCode = `${modelCode}_bound`;
  const roleResponse = await page.request.post('/api/roles', { data: {
    code: roleCode, name: marker, type: 'custom', defaultDataScopeType: 'all',
  } });
  expect(roleResponse.status()).toBe(200);
  const roleBody = await roleResponse.json();
  expect(String(roleBody.code)).toBe('0');
  const rolePid = String(roleBody.data.pid);
  const user = makeQuoteRoleUser('field-capability', marker, [roleCode]);
  await ensureQuoteRoleUser(page, user);
  const modelResponse = await page.request.post('/api/meta/models', { data: {
    code: modelCode, displayName: marker, modelType: 'entity', namespace: 'default', env: 'dev',
  } });
  expect(modelResponse.status()).toBe(200);
  const modelBody = await modelResponse.json();
  expect(String(modelBody.code)).toBe('0');
  const modelPid = String(modelBody.data.pid);
  const fieldResponse = await page.request.post('/api/meta/fields', { data: {
    code: fieldCode, dataType: 'string', uiSchema: { label: marker },
  } });
  expect(fieldResponse.status()).toBe(200);
  const fieldBody = await fieldResponse.json();
  expect(String(fieldBody.code)).toBe('0');
  const fieldPid = String(fieldBody.data.pid);
  const opened = await openQuoteRolePage(browser, user);
  const member = opened.page;
  const origin = new URL(member.url()).origin;
  const fieldsPath = `/api/meta/models/${modelPid}/fields`;
  const configurePath = `/api/meta/models/${modelPid}/field-bindings/${fieldPid}/configure`;
  async function fields() {
    const response = await member.request.get(fieldsPath);
    expect(response.status()).toBe(200);
    const body = await response.json();
    expect(String(body.code)).toBe('0');
    return body.data as Array<{ pid: string; code: string; editable: boolean; fieldOrder: number }>;
  }
  async function menus() {
    const response = await member.request.get('/api/menu/user');
    expect(response.status()).toBe(200);
    const body = await response.json();
    expect(String(body.code)).toBe('0');
    return body.data;
  }
  async function select(manage: boolean) {
    await page.goto('/home');
    await ensureSidebarExpanded(page);
    await page.getByTestId('sidebar').locator('a[href="/enterprise/permissions"]').click();
    await page.getByTestId('role-search-input').fill(roleCode);
    await page.getByTestId(`role-item-${roleCode}`).click();
    await expect(page.getByTestId('capability-role-editor')).toHaveAttribute('data-role-pid', rolePid);
    for (const code of ['qo.cap.platform_read', 'sys.cap.model_view'])
      await page.getByTestId(`capability-checkbox-${code}`).check();
    await page.getByTestId('capability-checkbox-sys.cap.model_manage').setChecked(manage);
    await page.getByTestId('capability-save').click();
    await expect(page.getByTestId('confirm-dialog')).toBeVisible();
    const saving = page.waitForResponse(response => {
      const url = new URL(response.url());
      return url.origin === origin && url.pathname === '/api/permission/capabilities'
        && url.searchParams.get('rolePid') === rolePid && response.request().method() === 'PUT';
    });
    await page.getByTestId('confirm-ok').click();
    const saved = await saving;
    expect(saved.status()).toBe(200);
    expect(String((await saved.json()).code)).toBe('0');
    const payload = saved.request().postDataJSON() as string[];
    expect(payload).toContain('sys.cap.model_view');
    expect(payload.includes('sys.cap.model_manage')).toBe(manage);
    await expect(page.getByTestId('capability-save')).toBeDisabled();
  }
  async function openFields() {
    await member.goto(`/meta/models/${modelPid}`);
    await expect(member.getByRole('heading', { name: marker, exact: true })).toBeVisible();
    await member.getByRole('button', { name: /^字段 \(\d+\)$/ }).click();
    await expect(member.getByRole('columnheader', { name: '字段编码', exact: true })).toBeVisible();
  }
  async function bind() {
    await member.getByTestId('model-fields-add-button').click();
    await expect(member.getByTestId('field-selection-dialog')).toBeVisible();
    await expect(member.getByTestId('field-selection-tab-create')).toHaveCount(0);
    await member.getByTestId('field-selection-search').fill(fieldCode);
    await member.getByTestId(`field-selection-field-${fieldPid}`).click();
    const pending = member.waitForResponse(response => new URL(response.url()).origin === origin
      && new URL(response.url()).pathname === `${fieldsPath}/bind` && response.request().method() === 'POST');
    await member.getByTestId('field-selection-bind').click();
    const response = await pending;
    expect(response.status()).toBe(200);
    expect(String((await response.json()).code)).toBe('0');
    expect(response.request().postDataJSON().fieldPid).toBe(fieldPid);
    await expect(member.getByTestId('field-selection-dialog')).toHaveCount(0);
    await expect(member.getByRole('row').filter({ hasText: fieldCode })).toHaveCount(1);
    expect((await fields()).find(field => field.pid === fieldPid)?.code).toBe(fieldCode);
  }
  try {
    await select(false);
    await openFields();
    for (const id of ['model-field-configure', 'model-field-unbind', 'model-field-reorder', 'model-fields-add-button'])
      await expect(member.getByTestId(id)).toHaveCount(0);
    await expect(member.getByTestId('model-fields-guidance')).toHaveText('字段配置仅供查看');
    const originalFields = await fields();
    const beforeMenus = await menus();
    const deniedBind = await member.request.post(`${fieldsPath}/bind`, { data: { fieldPid } });
    expect(deniedBind.status()).toBe(403);
    expect(await fields()).toEqual(originalFields);
    await member.screenshot({ path: info.outputPath('model-field-permission-readonly.png'), fullPage: true });
    await select(true);
    await openFields();
    await bind();
    await member.getByRole('row').filter({ hasText: fieldCode }).getByTestId('model-field-unbind').click();
    await expect(member.getByTestId('confirm-dialog')).toContainText(fieldCode);
    await member.screenshot({ path: info.outputPath('model-field-permission-unbind-confirm.png'), fullPage: true });
    const unbinding = member.waitForResponse(response => new URL(response.url()).origin === origin
      && new URL(response.url()).pathname === `${fieldsPath}/${fieldPid}` && response.request().method() === 'DELETE');
    await member.getByTestId('confirm-ok').click();
    const unbound = await unbinding;
    expect(unbound.status()).toBe(200);
    const unboundBody = await unbound.json();
    expect(String(unboundBody.code)).toBe('0');
    expect(unboundBody.data).toBe(true);
    await openFields();
    await expect(member.getByRole('row').filter({ hasText: fieldCode })).toHaveCount(0);
    expect((await fields()).some(field => field.pid === fieldPid)).toBe(false);
    await bind();
    await member.getByRole('row').filter({ hasText: fieldCode }).getByTestId('model-field-configure').click();
    await expect(member.getByTestId('model-field-config-dialog')).toBeVisible();
    const readonlySwitch = member.getByTestId('schema-config-field-readonly').getByRole('switch');
    await readonlySwitch.check();
    await expect(readonlySwitch).toHaveAttribute('data-state', 'checked');
    await member.screenshot({ path: info.outputPath('model-field-permission-config-open.png'), fullPage: true, animations: 'disabled' });
    const configuring = member.waitForResponse(response => new URL(response.url()).origin === origin
      && new URL(response.url()).pathname === configurePath && response.request().method() === 'POST');
    await member.getByTestId('model-field-config-save').click();
    const configured = await configuring;
    expect(configured.status()).toBe(200);
    const configuredBody = await configured.json();
    expect(String(configuredBody.code)).toBe('0');
    expect(configuredBody.data.modelPid).toBe(modelPid);
    expect(configuredBody.data.fieldPid).toBe(fieldPid);
    expect(configured.request().postDataJSON().editable).toBe(false);
    await expect(member.getByTestId('model-field-config-dialog')).toHaveCount(0);
    await openFields();
    await expect(member.getByRole('row').filter({ hasText: fieldCode })).toContainText('只读');
    const configuredFields = await fields();
    expect(configuredFields.find(field => field.pid === fieldPid)?.editable).toBe(false);
    expect(await menus()).toEqual(beforeMenus);
    await expect.poll(() => member.evaluate(() =>
      document.documentElement.scrollWidth <= document.documentElement.clientWidth),
    { message: 'long model and field codes must not expand the document beyond the viewport' }).toBe(true);
    const configureAction = member.getByRole('row').filter({ hasText: fieldCode }).getByTestId('model-field-configure');
    await configureAction.scrollIntoViewIfNeeded();
    expect(await configureAction.evaluate(element => {
      const rect = element.getBoundingClientRect();
      return rect.left >= 0 && rect.right <= document.documentElement.clientWidth;
    }), 'field configure action remains reachable within the viewport').toBe(true);
    await member.screenshot({ path: info.outputPath('model-field-permission-configured.png'), fullPage: true, animations: 'disabled' });
    await select(false);
    await openFields();
    for (const id of ['model-field-configure', 'model-field-unbind', 'model-field-reorder', 'model-fields-add-button'])
      await expect(member.getByTestId(id)).toHaveCount(0);
    await expect(member.getByTestId('model-fields-guidance')).toHaveText('字段配置仅供查看');
    await expect(member.getByRole('row').filter({ hasText: fieldCode })).toContainText('只读');
    for (const response of [
      await member.request.post(`${fieldsPath}/bind`, { data: { fieldPid } }),
      await member.request.delete(`${fieldsPath}/${fieldPid}`),
      await member.request.put(`${fieldsPath}/reorder`, { data: { [fieldPid]: 99 } }),
      await member.request.post(configurePath, { data: { editable: true } }),
    ]) {
      expect(response.status()).toBe(403);
      expect(String((await response.json()).code)).toBe('403');
    }
    expect(await fields()).toEqual(configuredFields);
    expect(await menus()).toEqual(beforeMenus);
    await member.screenshot({ path: info.outputPath('model-field-permission-revoked.png'), fullPage: true });
  } finally {
    await opened.context.close();
  }
});

test('declared page capabilities generate linked CRUD pages and revoke without changing visible menus or current role grants', async ({ page, browser }, info) => {
  test.setTimeout(120_000);
  const marker = uniqueId('page_cap');
  const modelCode = `e2e_page_cap_${Date.now()}`;
  const roleCode = `e2e_page_cap_role_${Date.now()}`;
  const roleResponse = await page.request.post('/api/roles', { data: {
    code: roleCode, name: marker, type: 'custom', defaultDataScopeType: 'all',
  } });
  expect(roleResponse.status()).toBe(200);
  const roleBody = await roleResponse.json();
  expect(String(roleBody.code)).toBe('0');
  const rolePid = String(roleBody.data.pid);
  const user = makeQuoteRoleUser('page-capability', marker, [roleCode]);
  await ensureQuoteRoleUser(page, user);
  const modelResponse = await page.request.post('/api/meta/models', { data: {
    code: modelCode, displayName: marker, modelType: 'entity', namespace: 'default', env: 'dev',
  } });
  expect(modelResponse.status()).toBe(200);
  const modelBody = await modelResponse.json();
  expect(String(modelBody.code)).toBe('0');
  const modelPid = String(modelBody.data.pid);
  expect(modelPid).not.toBe('undefined');
  const opened = await openQuoteRolePage(browser, user);
  const member = opened.page;
  const origin = new URL(member.url()).origin;
  const menus = async () => {
    const response = await member.request.get('/api/menu/user');
    expect(response.status()).toBe(200);
    const body = await response.json();
    expect(String(body.code)).toBe('0');
    return body.data;
  };
  async function select(manage: boolean) {
    await page.goto('/home');
    await ensureSidebarExpanded(page);
    await page.getByTestId('sidebar').locator('a[href="/enterprise/permissions"]').click();
    await page.getByTestId('role-search-input').fill(roleCode);
    await page.getByTestId(`role-item-${roleCode}`).click();
    await expect(page.getByTestId('capability-role-editor')).toHaveAttribute('data-role-pid', rolePid);
    // Preserve an unrelated business command grant across page-management revocation.
    for (const code of ['qo.cap.platform_read', 'sys.cap.page_view', 'org.cap.member_offboarding'])
      await page.getByTestId(`capability-checkbox-${code}`).check();
    await page.getByTestId('capability-checkbox-sys.cap.page_manage').setChecked(manage);
    await page.getByTestId('capability-save').click();
    await expect(page.getByTestId('confirm-dialog')).toBeVisible();
    const saving = page.waitForResponse(response => {
      const url = new URL(response.url());
      return url.origin === origin && url.pathname === '/api/permission/capabilities'
        && url.searchParams.get('rolePid') === rolePid && response.request().method() === 'PUT';
    });
    await page.getByTestId('confirm-ok').click();
    const saved = await saving;
    expect(saved.status()).toBe(200);
    expect(String((await saved.json()).code)).toBe('0');
    expect(saved.request().postDataJSON().includes('sys.cap.page_manage')).toBe(manage);
    await expect(page.getByTestId('capability-save')).toBeDisabled();
  }
  async function openModel() {
    await member.goto(`/meta/models/${modelPid}`);
    await expect(member.getByRole('heading', { name: marker, exact: true })).toBeVisible();
  }
  async function expectGenerationDenied() {
    const denied = await member.request.post('/api/templates/crud/generate', { data: {
      modelCode, config: { generateList: true, generateForm: true, generateDetail: true,
        createMenu: false, createPermissions: false, assignRoles: false },
    } });
    expect(denied.status()).toBe(403);
    expect(String((await denied.json()).code)).toBe('403');
  }
  try {
    await select(false);
    await openModel();
    await expect(member.getByTestId('model-primary-page-action')).toHaveCount(0);
    await expectGenerationDenied();
    const beforeMenus = await menus();
    await member.screenshot({ path: info.outputPath('page-capability-readonly.png'), fullPage: true });
    await select(true);
    const grantsBeforeResponse = await page.request.get(`/api/roles/${rolePid}/permissions`);
    expect(grantsBeforeResponse.status()).toBe(200);
    const grantsBefore = await grantsBeforeResponse.json();
    expect(String(grantsBefore.code)).toBe('0');
    // A page manager must not use generation flags to acquire other administrative powers.
    for (const flag of ['createMenu', 'createPermissions', 'assignRoles']) {
      const denied = await member.request.post('/api/templates/crud/generate', { data: {
        modelCode, config: { menuName: marker, generateList: true, generateForm: true, generateDetail: true,
          createMenu: false, createPermissions: false, assignRoles: false, [flag]: true },
      } });
      expect(denied.status(), flag).toBe(403);
      expect(String((await denied.json()).code), flag).toBe('403');
      const linked = await member.request.get(`/api/meta/models/${modelPid}/pages`);
      expect(linked.status()).toBe(200);
      const linkedBody = await linked.json();
      expect(String(linkedBody.code)).toBe('0');
      expect(linkedBody.data, flag).toEqual([]);
      const unchanged = await page.request.get(`/api/roles/${rolePid}/permissions`);
      expect(unchanged.status()).toBe(200);
      const unchangedBody = await unchanged.json();
      expect(String(unchangedBody.code)).toBe('0');
      expect(unchangedBody.data, flag).toEqual(grantsBefore.data);
      expect(await menus(), flag).toEqual(beforeMenus);
    }
    await openModel();
    await member.getByTestId('model-primary-page-action').click();
    await expect(member.getByRole('heading', { name: '生成基础 CRUD 页面', exact: true })).toBeVisible();
    await member.getByTestId('crud-open-designer').uncheck();
    const generation = member.waitForResponse(response =>
      new URL(response.url()).origin === origin && new URL(response.url()).pathname === '/api/templates/crud/generate'
      && response.request().method() === 'POST');
    await member.getByTestId('crud-generate-submit').click();
    const generated = await generation;
    expect(generated.status()).toBe(200);
    const payload = generated.request().postDataJSON();
    expect(payload.modelCode).toBe(modelCode);
    for (const key of ['createMenu', 'createPermissions', 'assignRoles']) expect(payload.config[key]).toBe(false);
    const generatedBody = await generated.json();
    expect(String(generatedBody.code)).toBe('0');
    expect(generatedBody.data.modelCode).toBe(modelCode);
    const resources = generatedBody.data.generatedResources;
    expect(resources.pages.map((item: { kind: string }) => item.kind).sort()).toEqual(['detail', 'form', 'list']);
    expect(resources.menus).toEqual([]);
    expect(resources.permissions).toEqual([]);
    const grantsAfterResponse = await page.request.get(`/api/roles/${rolePid}/permissions`);
    expect(grantsAfterResponse.status()).toBe(200);
    const grantsAfter = await grantsAfterResponse.json();
    expect(String(grantsAfter.code)).toBe('0');
    expect(grantsAfter.data).toEqual(grantsBefore.data);
    await expect(member.getByText('页面生成成功', { exact: true })).toBeVisible();
    await member.screenshot({ path: info.outputPath('page-capability-generated.png'), fullPage: true });
    const snapshots: Array<{ pid: string; body: unknown }> = [];
    for (const item of resources.pages as Array<{ pid: string; kind: string }>) {
      expect(item.pid).toBeTruthy();
      const response = await member.request.get(`/api/pages/${item.pid}`);
      expect(response.status()).toBe(200);
      const body = await response.json();
      expect(String(body.code)).toBe('0');
      expect(body.data.modelCode).toBe(modelCode);
      expect(body.data.kind).toBe(item.kind);
      snapshots.push({ pid: item.pid, body: body.data });
    }
    expect(await menus()).toEqual(beforeMenus);
    await select(false);
    // The existing session must lose the grant without obtaining a new token.
    await openModel();
    await expect(member.getByTestId('model-primary-page-action')).toHaveCount(0);
    await expectGenerationDenied();
    // Command execution must not bypass the revoked native page-management grant.
    const revokedPermissions = await fetchRoleSnapshot(member);
    expect(revokedPermissions.permissionCodes).toContain('meta.command.execute');
    for (const action of ['create', 'update', 'delete', 'publish', 'archive', 'duplicate'])
      expect(revokedPermissions.permissionCodes).not.toContain(`model.page_schema.${action}`);
    const duplicateDenied = await member.request.post('/api/meta/commands/execute/pgm:duplicate_page_schema', {
      data: { targetRecordPid: snapshots[0].pid, operationType: 'custom', payload: {} },
    });
    const duplicateDeniedBody = await duplicateDenied.json();
    await info.attach('page-command-revocation-response', {
      body: JSON.stringify({ status: duplicateDenied.status(), body: duplicateDeniedBody }),
      contentType: 'application/json',
    });
    expect(duplicateDenied.status()).toBe(403);
    expect(String(duplicateDeniedBody.code)).toBe('403');
    for (const snapshot of snapshots) {
      const denied = await member.request.delete(`/api/pages/${snapshot.pid}`);
      expect(denied.status()).toBe(403);
      const response = await member.request.get(`/api/pages/${snapshot.pid}`);
      expect(response.status()).toBe(200);
      const body = await response.json();
      expect(String(body.code)).toBe('0');
      expect(body.data).toEqual(snapshot.body);
    }
    expect(await menus()).toEqual(beforeMenus);
    await member.screenshot({ path: info.outputPath('page-capability-revoked.png'), fullPage: true });
  } finally {
    await opened.context.close();
  }
});
