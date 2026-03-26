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
});

/**
 * Re-export expect for convenience
 */
export { expect };

/**
 * Re-export types that tests commonly need
 */
export type { Page, APIRequestContext };
