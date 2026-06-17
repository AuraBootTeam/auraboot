import { defineConfig, devices } from '@playwright/test';

const baseURL = process.env.PLAYWRIGHT_BASE_URL || 'http://localhost:5173';
const storageState =
  process.env.PW_ADMIN_STORAGE_STATE ||
  (process.env.PW_STORAGE_DIR ? `${process.env.PW_STORAGE_DIR}/admin.json` : './tests/storage/admin.json');

/**
 * Quick test config — no setup/auth projects, reuses existing server.
 * Use when setup has already been completed and you want fast test iteration.
 */
export default defineConfig({
  testDir: './tests/e2e',
  testMatch: ['**/*.spec.ts'],
  outputDir: './test-results/artifacts',

  globalTeardown: './tests/global-teardown.ts',

  fullyParallel: true,
  forbidOnly: false,
  retries: 0,
  workers: Number(process.env.PW_QUICK_WORKERS || 4),
  timeout: 60000,

  reporter: [['line']],

  use: {
    baseURL,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    actionTimeout: 10000,
    navigationTimeout: 15000,
    storageState,
    launchOptions: {
      args: ['--no-proxy-server'],
    },
  },

  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],

  // No webServer — assume it's already running
});
