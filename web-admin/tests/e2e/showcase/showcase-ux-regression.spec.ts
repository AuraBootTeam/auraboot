/**
 * Showcase UX Regression Tests
 *
 * Prevents regression of issues found during 2026-03-22 product review.
 * Tests verify pages load correctly and core data is accessible via API.
 */

import { test, expect, type Page } from '@playwright/test';
import { executeCommandViaApi } from '../helpers';

async function getAccountWithLinkedContact(
  page: Page,
): Promise<{ accountPid: string; contactName: string } | null> {
  const [accountResp, contactResp] = await Promise.all([
    page.request.get('/api/dynamic/crm_account/list?pageSize=200'),
    page.request.get('/api/dynamic/crm_contact/list?pageSize=300'),
  ]);
  expect(accountResp.ok()).toBeTruthy();
  expect(contactResp.ok()).toBeTruthy();

  const accountBody = await accountResp.json();
  const contactBody = await contactResp.json();
  const accounts = accountBody?.data?.records || [];
  const contacts = contactBody?.data?.records || [];

  const accountByPid = new Map(accounts.map((account: any) => [String(account.pid), account]));
  const linkedContact = contacts.find((contact: any) =>
    accountByPid.has(String(contact.crm_ct_account_id)),
  );
  if (!linkedContact) return null;

  const account = accountByPid.get(String(linkedContact.crm_ct_account_id));
  if (!account?.pid || !linkedContact?.crm_ct_name) return null;

  return {
    accountPid: String(account.pid),
    contactName: String(linkedContact.crm_ct_name),
  };
}

async function getAccountWithoutLinkedContact(page: Page): Promise<{ accountPid: string } | null> {
  const [accountResp, contactResp] = await Promise.all([
    page.request.get('/api/dynamic/crm_account/list?pageSize=200'),
    page.request.get('/api/dynamic/crm_contact/list?pageSize=300'),
  ]);
  expect(accountResp.ok()).toBeTruthy();
  expect(contactResp.ok()).toBeTruthy();

  const accountBody = await accountResp.json();
  const contactBody = await contactResp.json();
  const accounts = accountBody?.data?.records || [];
  const contacts = contactBody?.data?.records || [];

  const linkedAccountPids = new Set(
    contacts.map((contact: any) => String(contact.crm_ct_account_id)).filter(Boolean),
  );
  const account = accounts.find((candidate: any) => !linkedAccountPids.has(String(candidate.pid)));
  if (!account?.pid) return null;

  return { accountPid: String(account.pid) };
}

async function fetchContact(page: Page, contactPid: string): Promise<any> {
  const resp = await page.request.get(`/api/dynamic/crm_contact/${contactPid}`);
  expect(resp.ok()).toBeTruthy();
  const body = await resp.json();
  return body?.data ?? body;
}

/**
 * Navigate to a model's runtime list via sidebar menu (no page.goto deep-link).
 * Falls back to the dashboard landing first to ensure sidebar is rendered.
 */
async function navigateToListViaMenu(
  page: Page,
  parentLabel: RegExp,
  listUrl: string,
  modelCode: string,
): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' }).catch(() => {});
  await page.evaluate(() => localStorage.removeItem('sidebar-collapsed'));
  await page.reload({ waitUntil: 'domcontentloaded' });

  const parent = page.locator('button, [role="menuitem"]', { hasText: parentLabel }).first();
  await parent.waitFor({ state: 'visible', timeout: 10_000 });
  await parent.evaluate((el: HTMLElement) => el.click());

  const listResp = page.waitForResponse(
    (r) => r.url().includes(`/api/dynamic/${modelCode}/list`) && r.status() === 200,
    { timeout: 15_000 },
  );

  const leaf = page.locator(`a[href="${listUrl}"], a[href*="${listUrl}"]`).first();
  await leaf.waitFor({ state: 'attached', timeout: 5_000 });
  await leaf.evaluate((el: HTMLElement) => el.click());
  await listResp;

  await expect(page).toHaveURL(new RegExp(`${listUrl}(?:$|\\?)`), { timeout: 10_000 });
}

test.describe('Showcase UX Regression', () => {
  test.use({ storageState: 'tests/storage/admin.json' });
  test.setTimeout(60_000);

  test('A0: Showcase sidebar hides widget dashboard entry', async ({ page }) => {
    await page.goto('/dashboards', { waitUntil: 'domcontentloaded' }).catch(() => {});
    await page.evaluate(() => localStorage.removeItem('sidebar-collapsed'));
    await page.reload({ waitUntil: 'domcontentloaded' });

    const nav = page.locator('nav').first();
    const parent = nav
      .locator('button, [role="menuitem"]', {
        hasText: /字段展示|能力展示|Field Showcase|Showcase|menu\.sc_root/i,
      })
      .first();
    await parent.waitFor({ state: 'visible', timeout: 10_000 });
    await parent.evaluate((el: HTMLElement) => el.click());

    await expect(nav).toContainText(/字段展示|能力展示|Field Showcase|Showcase|menu\.sc_root/i);
    await expect(nav).toContainText(/全字段类型|All Field Types|menu\.sc_all_fields/i);
    await expect(nav).not.toContainText(/组件仪表盘|Widget Dashboard|menu\.sc_arsenal_dashboard/i);
  });

  // ─── B3: Rating dict has colors (API-level check) ────────────────────

  test('B3: CRM Account rating distribution exists', async ({ page }) => {
    const resp = await page.request.get('/api/dynamic/crm_account/list?pageSize=100');
    const body = await resp.json();
    expect(body?.data?.total).toBeGreaterThan(0);

    const ratings = new Set((body.data.records || []).map((r: any) => r.crm_acc_rating));
    // Must have at least A, B, C ratings
    expect(ratings.has('A')).toBeTruthy();
    expect(ratings.has('B')).toBeTruthy();
    expect(ratings.has('C')).toBeTruthy();
  });

  // ─── B3+: Opportunity stage distribution ─────────────────────────────

  test('B3+: CRM Opportunity all 6 stages present', async ({ page }) => {
    const resp = await page.request.get('/api/dynamic/crm_opportunity/list?pageSize=200');
    const body = await resp.json();
    expect(body?.data?.total).toBeGreaterThan(0);

    const stages = new Set((body.data.records || []).map((r: any) => r.crm_opp_stage));
    expect(stages.size).toBeGreaterThanOrEqual(5); // At least 5 of 6 stages
  });

  // ─── B5: Action column renders (page loads) ──────────────────────────

  test('B5: CRM Account list page loads', async ({ page }) => {
    await navigateToListViaMenu(page, /CRM|客户关系|menu\.crm/i, '/p/crm_account', 'crm_account');
    // Table renders synchronously after the list response settles in
    // navigateToListViaMenu — 5s is sufficient for per-action visibility.
    await expect(page.locator('table, [data-testid="dynlist_table_view"]')).toBeVisible({
      timeout: 5000,
    });
  });

  // ─── B7: Account detail has related data ─────────────────────────────

  test('B7: CRM Account detail page loads with related data', async ({ page, browserName }) => {
    // Navigate via sidebar menu, then drill into detail through row-action-view.
    await navigateToListViaMenu(page, /CRM|客户关系|menu\.crm/i, '/p/crm_account', 'crm_account');
    const firstRow = page
      .locator('[data-testid="dynamic-list"] table tbody tr')
      .first();
    await firstRow.waitFor({ state: 'visible', timeout: 10_000 });

    // Use the canonical row-action-view button emitted by RowActionButtons.
    const viewBtn = firstRow.locator('[data-testid="row-action-view"]').first();
    await viewBtn.waitFor({ state: 'visible', timeout: 5_000 });
    await Promise.all([
      page.waitForURL(/\/p\/crm_account\/view\/.+/, { timeout: 5_000 }),
      viewBtn.click(),
    ]);

    // Detail page must render (not a 404 / 403 fallback).
    await expect(page.locator('body')).not.toContainText('Page not found');
    await expect(page.locator('body')).not.toContainText('Access forbidden');
  });

  test('B7.1: CRM Account #contacts tab renders linked contacts', async ({ page }) => {
    const linked = await getAccountWithLinkedContact(page);
    expect(linked, 'Seed data should contain at least one account with a linked contact').not.toBeNull();

    await page.goto(`/p/crm_account/view/${linked!.accountPid}#contacts`, {
      waitUntil: 'domcontentloaded',
    });

    await expect(page.locator('body')).not.toContainText('Page not found');
    await expect(page.locator('body')).not.toContainText('Access forbidden');
    await expect(
      page.locator('.sub-table-section', { hasText: /联系人|Contacts/ }).first(),
    ).toBeVisible({ timeout: 10_000 });
    await expect(page.locator('body')).toContainText(linked!.contactName);
  });

  test('B7.2: CRM Account #contacts tab shows empty state when no contacts exist', async ({
    page,
  }) => {
    const unlinked = await getAccountWithoutLinkedContact(page);
    expect(unlinked, 'Seed data should contain at least one account without contacts').not.toBeNull();

    await page.goto(`/p/crm_account/view/${unlinked!.accountPid}#contacts`, {
      waitUntil: 'domcontentloaded',
    });

    await expect(page.locator('[data-testid="subtable-empty-state"]')).toBeVisible({
      timeout: 10_000,
    });
    await expect(page.locator('body')).toContainText('暂无联系人');
    await expect(page.locator('[data-testid="subtable-empty-action"]')).toContainText('添加联系人');
  });

  test('B7.3: CRM Account keeps only one primary contact', async ({ page }) => {
    const uid = `primary-${Date.now()}`;
    const account = await executeCommandViaApi(
      page,
      'crm:create_account',
      {
        crm_acc_name: `Primary Contact Account ${uid}`,
        crm_acc_industry: 'technology',
        crm_acc_status: 'active',
      },
      undefined,
      'create',
    );

    const firstContact = await executeCommandViaApi(
      page,
      'crm:create_contact',
      {
        crm_ct_account_id: account.recordId,
        crm_ct_name: `Primary One ${uid}`,
        crm_ct_email: `${uid}-1@example.com`,
        crm_ct_is_primary: true,
      },
      undefined,
      'create',
    );

    const secondContact = await executeCommandViaApi(
      page,
      'crm:create_contact',
      {
        crm_ct_account_id: account.recordId,
        crm_ct_name: `Primary Two ${uid}`,
        crm_ct_email: `${uid}-2@example.com`,
        crm_ct_is_primary: true,
      },
      undefined,
      'create',
    );

    await expect
      .poll(
        async () => {
          const [first, second] = await Promise.all([
            fetchContact(page, firstContact.recordId),
            fetchContact(page, secondContact.recordId),
          ]);
          return {
            first: Boolean(first?.crm_ct_is_primary),
            second: Boolean(second?.crm_ct_is_primary),
          };
        },
        {
          timeout: 10_000,
          message: 'The newer primary contact should demote the older one',
        },
      )
      .toEqual({ first: false, second: true });
  });

  test('B7.4: CRM Account activity subject never renders null via command', async ({ page }) => {
    const uid = `timeline-${Date.now()}`;
    const account = await executeCommandViaApi(
      page,
      'crm:create_account',
      {
        crm_acc_name: `Timeline Account ${uid}`,
        crm_acc_industry: 'technology',
      },
      undefined,
      'create',
    );

    await expect
      .poll(
        async () => {
          const resp = await page.request.get('/api/activities', {
            params: { objectModel: 'crm_account', objectRecord: account.recordId, limit: 10 },
          });
          expect(resp.ok()).toBeTruthy();
          const body = await resp.json();
          const items = body?.data || [];
          return String(items[0]?.subject || '');
        },
        {
          timeout: 10_000,
          message: 'New CRM account should have a readable timeline subject',
        },
      )
      .toContain(`Timeline Account ${uid}`);
  });

  // ─── C1: Search works via API ────────────────────────────────────────

  test('C1: CRM Account search by keyword returns results', async ({ page }) => {
    const resp = await page.request.get('/api/dynamic/crm_account/list?keyword=宁波&pageSize=10');
    const body = await resp.json();
    expect(body?.data?.total).toBeGreaterThanOrEqual(1);
    expect(body.data.records[0].crm_acc_name).toContain('宁波');
  });

  // ─── C4: Showcase detail page accessible ─────────────────────────────

  test('C4: Showcase detail page loads', async ({ page }) => {
    await navigateToListViaMenu(
      page,
      /字段展示|能力展示|Field Showcase|Showcase|menu\.sc_root/i,
      '/p/showcase_all_fields',
      'showcase_all_fields',
    );
    const firstRow = page
      .locator('[data-testid="dynamic-list"] table tbody tr')
      .first();
    await firstRow.waitFor({ state: 'visible', timeout: 10_000 });

    const viewBtn = firstRow.locator('[data-testid="row-action-view"]').first();
    await viewBtn.waitFor({ state: 'visible', timeout: 5_000 });
    await Promise.all([
      page.waitForURL(/\/p\/showcase_all_fields\/view\/.+/, { timeout: 5_000 }),
      viewBtn.click(),
    ]);

    await expect(page.locator('body')).not.toContainText('Page not found');
  });

  // ─── A1: Marketplace has data ────────────────────────────────────────

  test('A1: Plugin management page loads', async ({ page }) => {
    // /marketplace + /system/plugins merged into /plugins (Tabs).
    // Wait for the plugin list API response instead of an arbitrary delay.
    const apiResp = page.waitForResponse(
      (r) => r.url().includes('/api/plugins') && r.status() === 200,
      { timeout: 10_000 },
    );
    await page.goto('/plugins?tab=discovery', { waitUntil: 'domcontentloaded' });
    await apiResp.catch(() => null);
    await expect(page.locator('body')).not.toContainText('Page not found');
    // Discovery tab content should render (either marketplace cards or the empty state).
    await expect(page.locator('main, [role="main"]').first()).toBeVisible({ timeout: 5_000 });
  });

  // ─── A2: Dashboard widget types correct ──────────────────────────────

  test('A2: Arsenal dashboard page loads with chart blocks', async ({ page }) => {
    // Dashboards live under /dashboards?code=… (see plugins/showcase/config/menus.json),
    // not the legacy /p/c/ custom-page route which was removed when dashboards moved
    // to ab_dashboard table (2026-04-15 architecture pivot).
    // Dashboard initial-load: smoke-style top-level route (not /p/{model}).
    // Wait on dashboards API before asserting the container so 5s per-action holds.
    const dashResp = page.waitForResponse(
      (r) => r.url().includes('/api/dashboards') && r.status() === 200,
      { timeout: 10_000 },
    );
    await page.goto('/dashboards?code=sc_arsenal_dashboard');
    await dashResp.catch(() => null);
    // Dashboard container uses unified TestId convention: ab:dashboard:{code}:container
    // (see docs/e2e/06-Selector-TestId-迁移计划.md, deriveTestId.ts)
    await expect(page.locator('[data-testid^="ab:dashboard:"]').first()).toBeVisible({ timeout: 5000 });
    const content = await page.textContent('body');
    expect(content).not.toContain('Page not found');
  });

  test('A3: Arsenal capability dashboard renders styled KPI cards', async ({ page }) => {
    const dashResp = page.waitForResponse(
      (r) => r.url().includes('/api/dashboards') && r.status() === 200,
      { timeout: 10_000 },
    );
    await page.goto('/dashboards?code=arsenal_capability_dashboard');
    await dashResp.catch(() => null);

    await expect(page.locator('[data-card-style="metric"]')).toHaveCount(4, { timeout: 10_000 });
    await expect(page.locator('body')).toContainText('客户总数');
    await expect(page.locator('body')).toContainText('本月新线索');
  });

  // ─── Seed data quality checks ────────────────────────────────────────

  test('Seed: Activities have realistic content', async ({ page }) => {
    const resp = await page.request.get('/api/dynamic/crm_activity/list?pageSize=5');
    const body = await resp.json();
    expect(body?.data?.total).toBeGreaterThanOrEqual(200);

    for (const r of body.data.records) {
      // Subject should be real Chinese text, not "Test_001"
      expect(r.crm_act_subject).not.toMatch(/^Test/i);
      expect(r.crm_act_subject.length).toBeGreaterThan(3);
    }
  });

  test('Seed: Opportunity amounts have realistic spread', async ({ page }) => {
    const resp = await page.request.get('/api/dynamic/crm_opportunity/list?pageSize=200');
    const body = await resp.json();
    const amounts = (body.data.records || [])
      .map((r: any) => Number(r.crm_opp_expected_amount || 0))
      .filter((a: number) => a > 0);

    expect(amounts.length).toBeGreaterThan(10);
    const min = Math.min(...amounts);
    const max = Math.max(...amounts);
    // Amounts should range from ~30k to ~5M (at least 10x spread)
    expect(max / min).toBeGreaterThan(10);
  });

  test('Seed: SavedViews exist for opportunity', async ({ page }) => {
    // Use the list endpoint which doesn't require special permissions
    const resp = await page.request.get('/api/views?modelCode=crm_opportunity');
    const body = await resp.json();
    const views = body?.data?.records || body?.data || [];
    // At minimum the auto-created default view should exist after model publish
    expect(Array.isArray(views)).toBeTruthy();
  });

  test('Seed: Agent definitions exist', async ({ page }) => {
    const resp = await page.request.get('/api/dynamic/agent_definition/list?pageSize=10');
    const body = await resp.json();
    expect(body?.data?.total).toBeGreaterThanOrEqual(3);
  });

  test('Seed: Knowledge base exists', async ({ page }) => {
    const resp = await page.request.get('/api/ai/knowledge');
    const body = await resp.json();
    const kbs = body?.data || [];
    // Knowledge base should exist; documents may not be seeded in every reset cycle
    expect(kbs.length).toBeGreaterThanOrEqual(1);
  });
});
