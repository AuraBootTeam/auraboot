/**
 * Unified Playwright Fixtures
 *
 * Provides custom test fixtures for E2E tests:
 * - api: ApiClient instance with authentication
 *
 * Usage:
 *   import { test, expect } from '../fixtures';
 *
 *   test('example', async ({ page, api }) => {
 *     const model = await api.createModel(data);
 *     await page.goto(`/meta/models/${model.pid}`);
 *   });
 *
 * @since 4.0.0
 */

import { test as base, expect } from '@playwright/test';
import type { Page, APIRequestContext } from '@playwright/test';
import { ApiClient } from '../model-system/helpers/api-client';

/**
 * Custom fixture types
 */
export interface CustomFixtures {
  /**
   * API client for backend operations.
   * Uses page.request to inherit cookies from browser context.
   */
  api: ApiClient;
}

/**
 * Extended test with custom fixtures
 */
export const test = base.extend<CustomFixtures>({
  /**
   * ApiClient fixture
   * Automatically inherits auth cookies from the page context
   */
  api: async ({ page }, use) => {
    const client = new ApiClient(page);
    await use(client);
  },

  /**
   * Cap navigation-related waits (including `waitForLoadState('networkidle')`)
   * at 3 seconds. The admin app keeps a long-lived SSE connection
   * (`/api/notifications/stream`) open for the entire session, so
   * Chromium never reports the network as idle. Specs that call
   * `waitForLoadState('networkidle').catch(() => {})` rely on the wait
   * failing quickly so the catch handler can resume the test; the
   * default 15 s navigation timeout otherwise burns the whole per-test
   * budget before the real assertions run.
   */
  page: async ({ page }, use) => {
    page.setDefaultNavigationTimeout(3000);
    await use(page);
  },
});

/**
 * Re-export expect for convenience
 */
export { expect };

/**
 * Re-export types that tests commonly need
 */
export type { Page, APIRequestContext };
