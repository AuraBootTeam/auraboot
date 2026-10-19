import { defineConfig, devices } from '@playwright/test';
import baseConfig from './playwright.config';

const storageState =
  process.env.PW_ADMIN_STORAGE_STATE ||
  (process.env.PW_STORAGE_DIR ? `${process.env.PW_STORAGE_DIR}/admin.json` : undefined);
if (!storageState || !process.env.PLAYWRIGHT_BASE_URL) {
  throw new Error(
    'Workflow tests require an explicit owned PLAYWRIGHT_BASE_URL and private admin storageState',
  );
}

export default defineConfig({
  ...baseConfig,
  workers: 1,
  retries: 0,
  use: {
    ...baseConfig.use,
    ...devices['Desktop Chrome'],
    storageState,
    trace: 'retain-on-failure',
    extraHTTPHeaders: { Referer: `${process.env.PLAYWRIGHT_BASE_URL}/` },
  },
  projects: [
    {
      name: 'workflow-preflight',
      testDir: './tests/api/setup',
      testMatch: 'workflow-application.setup.ts',
    },
    {
      name: 'workflow',
      dependencies: ['workflow-preflight'],
      testMatch: [/tests\/e2e\/automation\/automation-designer-golden\.spec\.ts$/],
    },
    {
      name: 'workflow-deep',
      dependencies: ['workflow'],
      testMatch: /tests\/e2e\/automation\/automation-deep\.spec\.ts$/,
      timeout: 120_000,
      use: { actionTimeout: 30_000, navigationTimeout: 60_000 },
    },
  ],
});
