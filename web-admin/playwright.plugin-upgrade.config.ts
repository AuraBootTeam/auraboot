import { defineConfig, devices } from '@playwright/test';
import base from './playwright.quick.config';

/** Owns new customer fixtures only; retains them for review instead of global cleanup. */
export default defineConfig({
  ...base,
  testMatch: ['**/two-customer-upgrade.spec.ts'],
  globalTeardown: undefined,
  workers: 1,
  retries: 0,
  timeout: 120000,
  outputDir: process.env.PW_ARTIFACT_DIR || './test-results/plugin-upgrade',
  reporter: [['list'], ['json', { outputFile: process.env.PW_RESULTS_JSON || './test-results/plugin-upgrade.json' }]],
  use: { ...base.use, ...devices['Desktop Chrome'], viewport: { width: 1440, height: 1000 }, locale: 'zh-CN', storageState: { cookies: [], origins: [] }, extraHTTPHeaders: { Referer: `${process.env.PLAYWRIGHT_BASE_URL || 'http://localhost:5173'}/` } },
  projects: [{ name: 'plugin-upgrade' }],
});
