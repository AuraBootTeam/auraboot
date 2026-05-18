/**
 * Query Builder E2E Tests
 *
 * Validates the query builder UI:
 * - Page accessible via menu
 * - Empty onboarding visible before model selected
 * - Full UI flow: select model → fields → filter → run → assert KPI + table
 * - Keyboard shortcuts (⌘+Enter)
 *
 * @since 8.0.0
 */

import { test, expect } from '../../fixtures';
import { Client as PgClient } from 'pg';
import { PG_CONN } from '../../helpers/environments';

// Seed at least one e2et_record row with status='failed' so QB-07 (which
// asserts data-rows >= 1 after filtering by status=failed) and QB-08 (which
// fires Cmd+Enter after model select) have data to return.
//
// Why DB-direct: POST /api/dynamic/e2et_record requires permission
// `model.e2et_record.create` which the default admin role doesn't grant in
// the OSS smoke stack (see test-fixtures plugin permissions). Bypass the API
// and write the row through the same data path the QB-execute SELECT reads.
async function seedE2etRecord(): Promise<void> {
  const client = new PgClient(PG_CONN);
  await client.connect();
  try {
    const tenantRow = await client.query<{ tenant_id: string }>(
      "SELECT tenant_id FROM ab_meta_model WHERE code = 'e2et_record' LIMIT 1",
    );
    if (tenantRow.rows.length === 0) return; // model missing — let test fail loudly elsewhere
    const tenantId = tenantRow.rows[0]!.tenant_id;
    await client.query(
      `INSERT INTO mt_e2et_record (pid, tenant_id, e2et_name, e2et_status, e2et_count, created_at, updated_at)
       VALUES ($1, $2, $3, 'failed', 1, NOW(), NOW())
       ON CONFLICT (pid) DO NOTHING`,
      ['qbseed_failed_01', tenantId, 'qb-seed-failed-01'],
    );
  } finally {
    await client.end();
  }
}

async function searchModel(page: import('@playwright/test').Page, modelCode: string): Promise<void> {
  const searchInput = page.locator('[data-testid="qb-model-search"]');
  await expect(searchInput).toBeVisible({ timeout: 10000 });
  await searchInput.fill('');
  await searchInput.fill(modelCode);
  await expect(searchInput).toHaveValue(modelCode);
  await expect(page.locator(`[data-testid="qb-model-${modelCode}"]`)).toBeVisible({
    timeout: 15000,
  });
}

// TODO(2026-05-08): QB-02..05 are API-only and should move to tests/api/
// per docs/standards/core/testing-e2e-web.md. Kept in this file to maintain
// the green baseline; new UI coverage lives in QB-07/08 below.

test.describe('Query Builder @smoke', () => {
  test.setTimeout(60000);

  test.beforeAll(async () => {
    await seedE2etRecord();
  });

  test('QB-01: Query Builder page loads', async ({ page }) => {
    await page.goto('/query-builder', { waitUntil: 'domcontentloaded' });
    const builder = page.locator('[data-testid="query-builder"]');
    await expect(builder).toBeVisible({ timeout: 10000 });
    await expect(page.locator('h1:has-text("Query Builder")')).toBeVisible();
  });

  test('QB-02: Models list loads from API', async ({ page }) => {
    const resp = await page.request.get('/api/query-builder/models');
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    expect(body?.code).toBe('0');
    expect(Array.isArray(body?.data)).toBe(true);
    expect(body.data.length).toBeGreaterThan(0);
  });

  test('QB-03: Fields load for e2et_record model', async ({ page }) => {
    const resp = await page.request.get('/api/query-builder/models/e2et_record/fields');
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    expect(body?.code).toBe('0');
    expect(Array.isArray(body?.data)).toBe(true);
    expect(body.data.length).toBeGreaterThan(0);
  });

  test('QB-04: Query execution returns data', async ({ page }) => {
    const resp = await page.request.post('/api/query-builder/execute', {
      data: { modelCode: 'e2et_record', limit: 10 },
    });
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    expect(body?.code).toBe('0');
    expect(Array.isArray(body?.data)).toBe(true);
  });

  test('QB-05: Query execution with aggregation', async ({ page }) => {
    const resp = await page.request.post('/api/query-builder/execute', {
      data: {
        modelCode: 'e2et_record',
        aggregations: [{ fieldCode: 'pid', function: 'count', alias: 'total_count' }],
        limit: 10,
      },
    });
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    expect(body?.code).toBe('0');
    expect(Array.isArray(body?.data)).toBe(true);
    if (body.data.length > 0) {
      expect(body.data[0]).toHaveProperty('total_count');
    }
  });

  test('QB-06: Model selector shows models on page', async ({ page }) => {
    await page.goto('/query-builder', { waitUntil: 'domcontentloaded' });
    const searchInput = page.locator('[data-testid="qb-model-search"]');
    await expect(searchInput).toBeVisible({ timeout: 10000 });
    const modelItems = page.locator('[data-testid^="qb-model-"]');
    await expect(modelItems.first()).toBeVisible({ timeout: 10000 });
  });

  test('QB-07: full UI flow — select model, fields, filter, run, verify result', async ({
    page,
  }) => {
    await page.goto('/');
    await page
      .getByRole('link', { name: /query builder|查询构建|menu\.query_builder/i })
      .or(page.locator('nav a[href="/query-builder"]'))
      .first()
      .click();
    await expect(page.locator('[data-testid="query-builder"]')).toBeVisible({ timeout: 10000 });

    await expect(page.locator('[data-testid="qb-empty-onboarding"]')).toBeVisible();

    // The QB models endpoint caps results at 20; in stacks with many seeded
    // models, e2et_record may not appear in the default list. Use the search
    // input (which re-fetches with keyword) to surface it deterministically.
    await searchModel(page, 'e2et_record');
    await page.locator('[data-testid="qb-model-e2et_record"]').click();
    await expect(page.locator('[data-testid="qb-empty-onboarding"]')).toBeHidden();
    await expect(page.locator('[data-testid="qb-step-fields"]')).toBeVisible();

    // e2et_record fields are namespaced (e2et_*); the spec selects three
    // representative columns to satisfy the "3 fields" summary assertion.
    await page.locator('[data-testid="qb-field-pid"]').click();
    await page.locator('[data-testid="qb-field-e2et_name"]').click();
    await page.locator('[data-testid="qb-field-e2et_status"]').click();

    await page.locator('[data-testid="qb-add-filter"]').click();
    const row = page.locator('[data-testid="qb-filter-row-0"]');
    await row.locator('[data-role="field"]').selectOption('e2et_status');
    await row.locator('[data-role="op"]').selectOption('EQ');
    await row.locator('[data-role="value"]').fill('failed');

    await page.locator('[data-testid="qb-run"]').click();

    const status = page.locator('[data-testid="qb-result-status"]');
    await expect(status).toBeVisible({ timeout: 15000 });
    await expect(status).toHaveAttribute('data-rows', /^[1-9][0-9]*$/);

    const table = page.locator('[data-testid="qb-result-table"]');
    await expect(table).toBeVisible();
    await expect(table.locator('tbody tr').first()).toContainText('failed');

    await expect(page.locator('[data-testid="qb-summary"]')).toContainText('e2et_record');
    await expect(page.locator('[data-testid="qb-summary"]')).toContainText('3 fields');
    await expect(page.locator('[data-testid="qb-summary"]')).toContainText('1 filters');
  });

  test('QB-08: ⌘+Enter triggers run after model selection', async ({ page }) => {
    await page.goto('/');
    await page
      .getByRole('link', { name: /query builder|查询构建|menu\.query_builder/i })
      .or(page.locator('nav a[href="/query-builder"]'))
      .first()
      .click();
    await expect(page.locator('[data-testid="qb-empty-onboarding"]')).toBeVisible({
      timeout: 10000,
    });

    await searchModel(page, 'e2et_record');
    await page.locator('[data-testid="qb-model-e2et_record"]').click();
    await expect(page.locator('[data-testid="qb-empty-onboarding"]')).toBeHidden();

    await page.keyboard.press('Meta+Enter');

    const status = page.locator('[data-testid="qb-result-status"]');
    await expect(status).toBeVisible({ timeout: 15000 });
    // qb-result-status is rendered with data-latency-ms="" before any run; poll
    // until handleRun's finally{} populates it with a positive integer.
    await expect(status).toHaveAttribute('data-latency-ms', /^[1-9][0-9]*$/, { timeout: 15000 });
    const latency = await status.getAttribute('data-latency-ms');
    expect(Number(latency)).toBeGreaterThan(0);
  });
});
