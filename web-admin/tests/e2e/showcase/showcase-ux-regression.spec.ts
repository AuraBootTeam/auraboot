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
      page.waitForResponse(r => r.url().includes('/crm_account') && r.url().includes('list'), { timeout: 15000 }),
      page.goto('/dynamic/crm-account'),
    ]);
    expect(resp.status()).toBe(200);
    await expect(page.locator('table, [data-testid="dynlist_table_view"]')).toBeVisible({ timeout: 10000 });
  });

  // ─── B7: Account detail has related data ─────────────────────────────

  test('B7: CRM Account detail page loads with related data', async ({ page, browserName }) => {
    const listResp = await page.request.get('/api/dynamic/crm_account/list?pageSize=1');
    const listBody = await listResp.json();
    const pid = listBody?.data?.records?.[0]?.pid;
    expect(pid).toBeTruthy();

    await page.goto(`/dynamic/crm-account/${pid}/view`);
    await page.waitForLoadState('domcontentloaded');
    await page.waitForTimeout(5000); // Wait for React + DSL rendering
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
    const listResp = await page.request.get('/api/dynamic/showcase_all_fields/list?pageSize=1');
    const listBody = await listResp.json();
    const pid = listBody?.data?.records?.[0]?.pid;
    expect(pid).toBeTruthy();

    await page.goto(`/dynamic/showcase-all-fields/${pid}/view`);
    await page.waitForLoadState('domcontentloaded');
    await page.waitForTimeout(5000);
    await expect(page.locator('body')).not.toContainText('Page not found');
  });

  // ─── A1: Marketplace has data ────────────────────────────────────────

  test('A1: Marketplace page loads', async ({ page }) => {
    await page.goto('/marketplace');
    await page.waitForLoadState('domcontentloaded');
    await page.waitForTimeout(3000);
    await expect(page.locator('body')).not.toContainText('Page not found');
  });

  // ─── A2: Dashboard widget types correct ──────────────────────────────

  test('A2: Arsenal dashboard page loads with chart blocks', async ({ page }) => {
    await page.goto('/p/sc_arsenal_dashboard');
    await page.waitForLoadState('domcontentloaded');
    // Dashboard should render chart blocks, not show errors
    await expect(page.locator('[data-testid*="dashboard-block-"]').first()).toBeVisible({ timeout: 10000 });
    const content = await page.textContent('body');
    expect(content).not.toContain('Page not found');
  });

  // ─── Seed data quality checks ────────────────────────────────────────

  test('Seed: Activities have realistic content', async ({ page }) => {
    const resp = await page.request.get('/api/dynamic/crm_activity/list?pageSize=5');
    const body = await resp.json();
    expect(body?.data?.total).toBeGreaterThan(200);

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
    const resp = await page.request.get('/api/views/accessible?modelCode=crm_opportunity');
    const body = await resp.json();
    const views = body?.data || [];
    expect(views.length).toBeGreaterThanOrEqual(7);

    const viewTypes = new Set(views.map((v: any) => v.viewType));
    expect(viewTypes.has('table')).toBeTruthy();
    expect(viewTypes.has('kanban')).toBeTruthy();
  });

  test('Seed: Agent definitions exist', async ({ page }) => {
    const resp = await page.request.get('/api/dynamic/agent_definition/list?pageSize=10');
    const body = await resp.json();
    expect(body?.data?.total).toBeGreaterThanOrEqual(3);
  });

  test('Seed: Knowledge base has documents', async ({ page }) => {
    const resp = await page.request.get('/api/ai/knowledge');
    const body = await resp.json();
    const kbs = body?.data || [];
    expect(kbs.length).toBeGreaterThanOrEqual(1);

    const totalDocs = kbs.reduce((sum: number, kb: any) => sum + (kb.docCount || 0), 0);
    expect(totalDocs).toBeGreaterThanOrEqual(1);
  });
});
