import { defineConfig, devices } from '@playwright/test';

const baseURL = process.env.PLAYWRIGHT_BASE_URL || 'http://localhost:5173';

/**
 * Playwright Configuration for Environment Initialization
 *
 * This config is used specifically for init-env.spec.ts
 * It skips global setup/teardown since the user doesn't exist yet.
 */
export default defineConfig({
  testDir: './tests',
  testMatch: ['**/api/setup/init-env.spec.ts'],
  outputDir: './test-results/artifacts',

  // No global setup - we're creating the user in the test
  // globalSetup: undefined,
  // globalTeardown: undefined,

  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  workers: 1,

  reporter: [
    ['list'],
    ['html', { open: 'never', outputFolder: './test-results/html-report' }],
  ],

  use: {
    baseURL,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    actionTimeout: 15000,
    navigationTimeout: 20000,
    // Bypass system HTTP proxy for localhost connections
    launchOptions: {
      args: ['--no-proxy-server'],
    },
  },

  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        // No storageState - fresh session
      },
    },
  ],

  // No webServer - assume services are already running
});
