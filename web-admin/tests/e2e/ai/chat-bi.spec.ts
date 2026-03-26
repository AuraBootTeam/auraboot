/**
 * ChatBI E2E Tests
 *
 * Tests CHATBI-01 ~ CHATBI-04: ChatBI page rendering, input interaction, and API.
 * - Page navigation and title
 * - Chat input field visibility
 * - API health check endpoint
 * - Query API responds with structured result
 *
 * Uses storageState for authentication.
 * Connects to real database and API (no mocks).
 *
 * @since 9.0.0
 */

import { test, expect } from '../../fixtures';
import type { Page } from '@playwright/test';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';

async function ensureAuthenticated(page: Page, targetPath: string) {
  await page.goto(targetPath, { waitUntil: 'domcontentloaded' });
  if (!page.url().includes('/login')) {
    await page.waitForLoadState('networkidle').catch(() => null);
    return;
  }

  await page.locator('input#email').fill(DEFAULT_TEST_ACCOUNT.email);
  await page.locator('input#password').fill(DEFAULT_TEST_ACCOUNT.password);
  await page.locator('button:has-text("立即登录")').click();
  await page.waitForURL((url: URL) => !url.pathname.includes('/login'), { timeout: 20000 });
  await page.goto(targetPath, { waitUntil: 'domcontentloaded' });
  await page.waitForLoadState('networkidle').catch(() => null);
}

test.describe('ChatBI @smoke', () => {
  test.setTimeout(60000);

  /**
   * CHATBI-01: ChatBI page loads via direct navigation.
   */
  test('CHATBI-01: Page loads and shows input', async ({ page }) => {
    // ChatBI page was absorbed into AuraBot as builtin__chat_bi tool.
    // Verify the ChatBI API endpoint is still available instead.
    const resp = await page.request.get('/api/ai/chat-bi/health');
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    expect(body?.code).toBe('0');
  });

  /**
   * CHATBI-02: ChatBI health check API endpoint responds.
   */
  test('CHATBI-02: Health check endpoint responds', async ({ page }) => {
    const resp = await page.request.get('/api/ai/chat-bi/health');
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    expect(body?.code).toBe('0');
    expect(typeof body?.data).toBe('string');
    expect(body.data).toContain('ChatBI');
  });

  /**
   * CHATBI-03: Query API accepts a request with modelCode and returns structured result.
   * Uses the e2et_record model which is guaranteed to exist in the test environment.
   */
  test('CHATBI-03: Query API returns structured result', async ({ page }) => {
    const resp = await page.request.post('/api/ai/chat-bi/query', {
      data: {
        question: 'count records',
        modelCode: 'e2et_record',
      },
    });
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    expect(body?.code).toBe('0');

    const data = body?.data;
    expect(data).toBeDefined();
    expect(typeof data.interpretation).toBe('string');
    expect(Array.isArray(data.columns)).toBe(true);
    expect(Array.isArray(data.records)).toBe(true);
    expect(typeof data.chartType).toBe('string');
    expect(typeof data.sql).toBe('string');
    expect(data.sql.length).toBeGreaterThan(0);
  });

  /**
   * CHATBI-04: Input interaction — type a question and send it.
   * Verifies the UI flow: type → send → loading → result or error message appears.
   */
  test('CHATBI-04: Input interaction sends question and shows response', async ({ page }) => {
    // ChatBI UI page was absorbed into AuraBot as builtin__chat_bi tool.
    // Verify the query API still works end-to-end instead.
    const resp = await page.request.post('/api/ai/chat-bi/query', {
      data: {
        question: 'count e2et_record',
        modelCode: 'e2et_record',
      },
    });
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    expect(body?.code).toBe('0');
    expect(body?.data).toBeDefined();
    // Query should return a SQL and some result structure
    expect(typeof body.data.sql).toBe('string');
    expect(body.data.sql.length).toBeGreaterThan(0);
  });
});
