/**
 * Dictionary Management — E2E Tests
 *
 * Tests the dictionary list page (/meta/dict):
 * - Page loads with data
 * - Filter by dict type (SIMPLE/TREE) returns results
 * - Filter by keyword returns results
 * - Filter by status returns results
 * - Reset clears filters and shows all data
 *
 * Uses real database, NO MOCKING.
 *
 * @since 7.0.0
 */

import { test, expect } from '../../fixtures';
import type { Page } from '@playwright/test';
import { ensureFilterFormOpen } from '../helpers';

test.describe('Dictionary Management @smoke', () => {
  async function runSearchAndWait(page: Page) {
    await page.getByTestId('filter-search').click();
    await page
      .locator('.animate-spin')
      .waitFor({ state: 'hidden', timeout: 10000 })
      .catch(() => {});
    const rows = page.locator('table tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 10000 });
    return rows;
  }

  async function getColumnTexts(page: Page, columnIndex: number) {
    return page.locator(`table tbody tr td:nth-child(${columnIndex})`).allTextContents();
  }

  test.beforeEach(async ({ page }) => {
    // Navigate to dict management page
    await page.goto('/meta/dict');
    await expect(page.getByTestId('dictionary-list')).toBeVisible({ timeout: 10000 });
    // Wait for initial data to load
    const rows = page.locator('table tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 10000 });
    await ensureFilterFormOpen(page);
  });

  test('should load dict list with data', async ({ page }) => {
    const rows = page.locator('table tbody tr');
    const count = await rows.count();
    expect(count).toBeGreaterThan(0);
  });

  test('should filter by dict type SIMPLE and return results', async ({ page }) => {
    await page.getByTestId('filter-type').selectOption('simple');
    const rows = await runSearchAndWait(page);
    expect(await rows.count()).toBeGreaterThan(0);
    const typeTexts = await getColumnTexts(page, 4);
    expect(typeTexts.every((text) => text.includes('简单'))).toBe(true);
  });

  test('should filter by dict type TREE', async ({ page }) => {
    await page.getByTestId('filter-type').selectOption('tree');
    const rows = await runSearchAndWait(page);
    if ((await rows.count()) === 1 && (await rows.first().textContent())?.includes('暂无数据')) {
      await expect(page.getByTestId('dictionary-list')).toBeVisible();
      return;
    }
    const typeTexts = await getColumnTexts(page, 4);
    expect(typeTexts.every((text) => text.includes('树形'))).toBe(true);
  });

  test('should filter by keyword and return matching results', async ({ page }) => {
    await page.getByTestId('filter-code').fill('status');
    const rows = await runSearchAndWait(page);
    expect(await rows.count()).toBeGreaterThan(0);
    const codeTexts = await page.locator('[data-testid^="dict-row-code-"]').allTextContents();
    expect(codeTexts.every((text) => text.toLowerCase().includes('status'))).toBe(true);
  });

  test('should filter by status published and return results', async ({ page }) => {
    await page.getByTestId('filter-status').selectOption('published');
    const rows = await runSearchAndWait(page);
    expect(await rows.count()).toBeGreaterThan(0);
    const statusTexts = await getColumnTexts(page, 5);
    expect(statusTexts.every((text) => text.includes('已发布'))).toBe(true);
  });

  test('should combine type + status filters', async ({ page }) => {
    await page.getByTestId('filter-type').selectOption('simple');
    await page.getByTestId('filter-status').selectOption('published');
    const rows = await runSearchAndWait(page);
    const count = await rows.count();
    expect(count).toBeGreaterThan(0);
    const typeTexts = await getColumnTexts(page, 4);
    const statusTexts = await getColumnTexts(page, 5);
    expect(typeTexts.every((text) => text.includes('简单'))).toBe(true);
    expect(statusTexts.every((text) => text.includes('已发布'))).toBe(true);
  });

  test('should reset filters and show all data', async ({ page }) => {
    // Apply a filter first
    await page.getByTestId('filter-type').selectOption('simple');
    await runSearchAndWait(page);

    // Reset filters
    await page.getByTestId('filter-reset').click();

    // Verify filters are cleared
    await expect(page.getByTestId('filter-type')).toHaveValue('');
    await expect(page.getByTestId('filter-status')).toHaveValue('');
    await expect(page.getByTestId('filter-code')).toHaveValue('');
    await expect(page.getByTestId('filter-name')).toHaveValue('');

    // Search with no filters
    const rows = await runSearchAndWait(page);
    expect(await rows.count()).toBeGreaterThan(0);
  });
});
