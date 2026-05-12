import { defineConfig, devices } from '@playwright/test';
import { BASE_URL } from './tests/helpers/playwright-env';

const artifactDir = process.env.PW_SEED_ARTIFACT_DIR || process.env.PW_ARTIFACT_DIR || './test-results/seed';

/**
 * Playwright config for running seed scripts.
 * Usage: npx playwright test --config=playwright.seed.config.ts
 */
export default defineConfig({
  testDir: './tests',
  testMatch: ['**/seed-showcase-*.spec.ts', '**/e2e/showcase/**/*.spec.ts'],
  outputDir: artifactDir,
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['line']],
  timeout: 600_000, // 10 min per test
  use: {
    baseURL: BASE_URL,
    storageState: process.env.PW_ADMIN_STORAGE_STATE || './tests/storage/admin.json',
    ...devices['Desktop Chrome'],
  },
});
