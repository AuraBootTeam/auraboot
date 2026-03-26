/**
 * Authentication Fixture for E2E Tests
 *
 * NOTE: This fixture is deprecated in favor of storageState-based authentication.
 *
 * The new approach:
 * 1. global-setup.ts performs real login and saves auth state
 * 2. playwright.config.ts configures storageState for all tests
 * 3. Tests automatically start authenticated
 *
 * This file is kept for backward compatibility and will be removed in a future version.
 * Please migrate to using fixtures/index.ts which provides the api fixture.
 *
 * @deprecated Use storageState configuration instead
 * @since 4.0.0
 */

import { test as base, type Page } from '@playwright/test';

export interface AuthFixture {
  /** Pre-authenticated page (now provided by storageState) */
  authedPage: Page;
}

/**
 * @deprecated Use storageState in playwright.config.ts instead
 */
export const test = base.extend<AuthFixture>({
  authedPage: async ({ page }, use) => {
    // With storageState configured in playwright.config.ts,
    // the page is already authenticated - no additional setup needed
    await use(page);
  },
});

export { expect } from '@playwright/test';
