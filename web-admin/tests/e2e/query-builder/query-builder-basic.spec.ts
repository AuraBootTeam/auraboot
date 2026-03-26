/**
 * Query Builder E2E Tests
 *
 * Validates the query builder UI:
 * - Page accessible via route
 * - Model list loads
 * - Fields load when model selected
 * - Query execution returns results
 *
 * @since 8.0.0
 */

import { test, expect } from '../../fixtures';

test.describe('Query Builder @smoke', () => {
  test.setTimeout(60000);

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
      data: {
        modelCode: 'e2et_record',
        limit: 10,
      },
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
        aggregations: [
          { fieldCode: 'pid', function: 'count', alias: 'total_count' },
        ],
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

    // Should have at least some model items rendered
    const modelItems = page.locator('[data-testid^="qb-model-"]');
    await expect(modelItems.first()).toBeVisible({ timeout: 10000 });
  });
});
