import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright config for running seed scripts.
 * Usage: npx playwright test --config=playwright.seed.config.ts
 */
export default defineConfig({
  testDir: './tests',
  testMatch: ['**/seed-showcase-*.spec.ts', '**/e2e/showcase/**/*.spec.ts'],
  outputDir: './test-results/seed',
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['line']],
  timeout: 600_000, // 10 min per test
  use: {
    baseURL: process.env.PLAYWRIGHT_BASE_URL || 'http://127.0.0.1:5173',
    storageState: process.env.PW_ADMIN_STORAGE_STATE || './tests/storage/admin.json',
    ...devices['Desktop Chrome'],
  },
});
