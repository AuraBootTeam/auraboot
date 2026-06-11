import { defineConfig, devices } from '@playwright/test';

// Minimal standalone config for the G3-T3 andon workbench visual golden.
// Reuses the already-running host Vite (:5137 -> BFF :3537 -> backend :6437);
// the spec self-authenticates, so no setup/auth project dependencies.
export default defineConfig({
  testDir: './tests/e2e/workbench',
  testMatch: /pe-andon-workbench\.golden\.spec\.ts/,
  timeout: 120_000,
  retries: 0,
  workers: 1,
  reporter: [['line']],
  use: {
    baseURL: process.env.PLAYWRIGHT_BASE_URL || 'http://localhost:5137',
    headless: true,
    trace: 'off',
    video: 'off',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
