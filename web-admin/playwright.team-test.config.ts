import { defineConfig, devices } from '@playwright/test';

const baseURL = process.env.PLAYWRIGHT_BASE_URL || 'http://localhost:5173';

/**
 * Minimal Playwright config for running team management tests.
 * Uses existing auth storage, skips fixture setup.
 *
 * Usage: NO_PROXY=localhost npx playwright test --config=playwright.team-test.config.ts
 */
export default defineConfig({
  testDir: './tests/e2e',
  testMatch: ['**/organization/team-management.spec.ts'],
  outputDir: './test-results/artifacts',

  globalTeardown: './tests/global-teardown.ts',

  fullyParallel: false,
  retries: 0,
  workers: 1,
  timeout: 30000,

  reporter: [['list']],

  use: {
    baseURL,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    actionTimeout: 10000,
    navigationTimeout: 10000,
    launchOptions: {
      args: ['--no-proxy-server'],
    },
  },

  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        storageState: './tests/storage/admin.json',
      },
    },
  ],
});
