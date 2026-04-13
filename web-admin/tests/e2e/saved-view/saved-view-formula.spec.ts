/**
 * E2E Test: Formula Field Enhancement (GAP-125)
 *
 * Tests formula function availability via the FormulaController API.
 */

import { test, expect } from '@playwright/test';

test.describe('Formula Field Enhancement (GAP-125)', () => {
  test('FF-001: formula functions API returns all categories', async ({ page }) => {
    await page.goto('/');
    await page.locator('nav, [data-testid="sidebar"]').first().waitFor({ timeout: 15000 });

    const resp = await page.request.get('/api/meta/formula/functions');
    expect(resp.ok()).toBeTruthy();
    const body = await resp.json();
    const functions = body.data ?? body;
    expect(Array.isArray(functions)).toBe(true);
    expect(functions.length).toBeGreaterThan(20);

    // Verify categories
    const categories = new Set(functions.map((f: any) => f.category));
    expect(categories.has('text')).toBe(true);
    expect(categories.has('math')).toBe(true);
    expect(categories.has('date')).toBe(true);
    expect(categories.has('logical')).toBe(true);
  });

  test('FF-002: IF function registered', async ({ page }) => {
    await page.goto('/');
    await page.locator('nav, [data-testid="sidebar"]').first().waitFor({ timeout: 15000 });

    const resp = await page.request.get('/api/meta/formula/functions');
    const body = await resp.json();
    const functions = body.data ?? body;
    const ifFunc = (Array.isArray(functions) ? functions : []).find(
      (f: any) => f.name?.toLowerCase() === 'if',
    );
    expect(ifFunc).toBeTruthy();
    expect(ifFunc.category).toBe('logical');
  });

  test('FF-003: DATEADD function registered', async ({ page }) => {
    await page.goto('/');
    await page.locator('nav, [data-testid="sidebar"]').first().waitFor({ timeout: 15000 });

    const resp = await page.request.get('/api/meta/formula/functions');
    const functions = (await resp.json()).data ?? [];
    const dateadd = (Array.isArray(functions) ? functions : []).find(
      (f: any) => f.name === 'dateadd',
    );
    expect(dateadd).toBeTruthy();
    expect(dateadd.category).toBe('date');
  });

  test('FF-004: CONCATENATE function registered', async ({ page }) => {
    await page.goto('/');
    await page.locator('nav, [data-testid="sidebar"]').first().waitFor({ timeout: 15000 });

    const resp = await page.request.get('/api/meta/formula/functions');
    const functions = (await resp.json()).data ?? [];
    const concat = (Array.isArray(functions) ? functions : []).find(
      (f: any) => f.name === 'concatenate',
    );
    expect(concat).toBeTruthy();
    expect(concat.category).toBe('text');
  });

  test('FF-005: SWITCH function registered', async ({ page }) => {
    await page.goto('/');
    await page.locator('nav, [data-testid="sidebar"]').first().waitFor({ timeout: 15000 });

    const resp = await page.request.get('/api/meta/formula/functions');
    const functions = (await resp.json()).data ?? [];
    const switchFunc = (Array.isArray(functions) ? functions : []).find(
      (f: any) => f.name === 'switch',
    );
    expect(switchFunc).toBeTruthy();
    expect(switchFunc.category).toBe('logical');
  });

  test('FF-006: enhanced functions count ≥ 30', async ({ page }) => {
    await page.goto('/');
    await page.locator('nav, [data-testid="sidebar"]').first().waitFor({ timeout: 15000 });

    const resp = await page.request.get('/api/meta/formula/functions');
    const functions = (await resp.json()).data ?? [];
    // Original: ~25 functions + GAP-125 additions: SWITCH, CONCATENATE, CONTAINS, SUBSTITUTE, MID, DATEADD, WEEKDAY, EOMONTH, MOD, INT
    expect(Array.isArray(functions) ? functions.length : 0).toBeGreaterThanOrEqual(30);
  });
});
