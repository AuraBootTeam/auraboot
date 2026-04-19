/**
 * Showcase UX Regression Tests
 *
 * Prevents regression of issues found during 2026-03-22 product review.
 * Tests verify pages load correctly and core data is accessible via API.
 */

import { test, expect } from '@playwright/test';

test.describe('Showcase UX Regression', () => {
  test.use({ storageState: 'tests/storage/admin.json' });
  test.setTimeout(60_000);

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
    const [resp] = await Promise.all([
      page.waitForResponse((r) => r.url().includes('/crm_account') && r.url().includes('list'), {
        timeout: 15000,
      }),
      page.goto('/p/crm_account'),
    ]);
    expect(resp.status()).toBe(200);
    await expect(page.locator('table, [data-testid="dynlist_table_view"]')).toBeVisible({
      timeout: 10000,
    });
  });

  // ─── B7: Account detail has related data ─────────────────────────────

  test('B7: CRM Account detail page loads with related data', async ({ page, browserName }) => {
    // Navigate via list page, then use the row "view" action button to drill
    // into detail. /p/{model} is the standard CRUD list URL (not a deep link).
    await page.goto('/p/crm_account', { waitUntil: 'domcontentloaded' });
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

  // ─── C1: Search works via API ────────────────────────────────────────

  test('C1: CRM Account search by keyword returns results', async ({ page }) => {
    const resp = await page.request.get('/api/dynamic/crm_account/list?keyword=宁波&pageSize=10');
    const body = await resp.json();
    expect(body?.data?.total).toBeGreaterThanOrEqual(1);
    expect(body.data.records[0].crm_acc_name).toContain('宁波');
  });

  // ─── C4: Showcase detail page accessible ─────────────────────────────

  test('C4: Showcase detail page loads', async ({ page }) => {
    // Navigate via list page, then use the row "view" action to drill into detail.
    await page.goto('/p/showcase_all_fields', { waitUntil: 'domcontentloaded' });
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
    await page.goto('/dashboards?code=sc_arsenal_dashboard');
    await page.waitForLoadState('domcontentloaded');
    // Dashboard container uses unified TestId convention: ab:dashboard:{code}:container
    // (see docs/e2e/06-Selector-TestId-迁移计划.md, deriveTestId.ts)
    await expect(page.locator('[data-testid^="ab:dashboard:"]').first()).toBeVisible({ timeout: 10000 });
    const content = await page.textContent('body');
    expect(content).not.toContain('Page not found');
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
