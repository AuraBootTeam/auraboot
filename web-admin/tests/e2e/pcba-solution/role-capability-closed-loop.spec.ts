import type { Page } from '@playwright/test';
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
    const row = actor.locator('table tbody tr').filter({ hasText: stamp });
    await expect(row).toHaveCount(1);
    await expect(row).toBeVisible();
    if (readOnly) {
      await expect(actor.getByRole('button', { name: '新建团队', exact: true })).toHaveCount(0);
      await expect(row.getByRole('button', { name: '编辑', exact: true })).toHaveCount(0);
      await expect(row.getByRole('button', { name: '删除', exact: true })).toHaveCount(0);
    }
    await row.click();
    await expect(actor).toHaveURL(new RegExp(`/organization/teams/${team.pid}$`));
  }
  async function selectRead(grant: boolean, unrelatedCommandCapability = false) {
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
    await page.getByTestId('capability-checkbox-org.cap.team_view').setChecked(grant);
    await page.getByTestId('capability-checkbox-org.cap.member_offboarding').setChecked(
      unrelatedCommandCapability,
    );
    if (!grant) {
      await page.getByTestId('capability-checkbox-org.cap.member_view').setChecked(false);
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
      Number(grant) + Number(unrelatedCommandCapability),
    );
    if (unrelatedCommandCapability) {
      expect(response.request().postDataJSON()).toContain('org.cap.member_offboarding');
    }
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
  const department = await executeCommandViaApi(page, 'org:create_department', {
    org_dept_name: stamp, org_dept_code: `STAFF-${Date.now()}`,
  });
  expect(String(department.code)).toBe('0');
  expect(department.recordId).toBeTruthy();
  const position = await executeCommandViaApi(page, 'org:create_position', {
    org_pos_name: stamp, org_pos_code: `STAFF-P-${Date.now()}`,
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
    await reader.screenshot({ path: info.outputPath('standalone-staff-read-revoked.png'), fullPage: true });

    await selectCapability('org.cap.member');
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
    await reader.screenshot({ path: info.outputPath('member-provision-revoked.png'), fullPage: true });
  } finally { await opened.context.close(); }
});
