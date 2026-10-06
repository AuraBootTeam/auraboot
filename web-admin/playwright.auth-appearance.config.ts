import { defineConfig } from '@playwright/test';

// Dedicated deployment appearance gate: one isolated commercial deployment and
// one administrator session, no retries or unrelated project selection.
export default defineConfig({
  testDir: './tests/e2e/branding',
  testMatch: 'auth-appearance.golden.spec.ts',
  workers: 1,
  retries: 0,
  timeout: 180_000,
  expect: { timeout: 15_000 },
  use: {
    actionTimeout: 15_000,
    baseURL: process.env.PLAYWRIGHT_BASE_URL,
    storageState: process.env.PW_ADMIN_STORAGE_STATE,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  reporter: [['list'], ['json', { outputFile: process.env.PW_RESULTS_JSON ?? 'test-results/auth-appearance/results.json' }]],
});
