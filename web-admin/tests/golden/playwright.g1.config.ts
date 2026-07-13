import { defineConfig, devices } from '@playwright/test';

/**
 * Golden for the two G1 gap fixes. Host stack must already be up (backend ← BFF ← vite).
 */
export default defineConfig({
  testDir: '.',
  testMatch: /g1-fixes-golden\.spec\.ts$/,
  timeout: 120_000,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: process.env.G1_GOLDEN_BASE ?? 'http://localhost:5173',
    headless: true,
    screenshot: 'on',
    trace: 'retain-on-failure',
    actionTimeout: 20_000,
    navigationTimeout: 40_000,
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
