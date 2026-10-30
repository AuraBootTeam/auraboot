import { defineConfig } from '@playwright/test';
import baseConfig from './playwright.config';

const baseURL = process.env.PLAYWRIGHT_BASE_URL;
const storageDir = process.env.PW_STORAGE_DIR;
const evidence = process.env.PW_ARTIFACT_DIR;
if (!baseURL || !/^http:\/\/(localhost|127\.0\.0\.1):\d+$/.test(baseURL)
    || !storageDir || !evidence) {
  throw new Error('CRUD regression requires explicit owned localhost PLAYWRIGHT_BASE_URL, private PW_STORAGE_DIR and PW_ARTIFACT_DIR');
}

// Reuse the original workbench journey and real BFF authentication. No private fixture
// PIDs, test-only permissions, retries, web-server replacement or skipped prerequisites.
export default defineConfig({
  ...baseConfig,
  webServer: undefined,
  workers: 1,
  retries: 0,
  use: { ...baseConfig.use, baseURL, viewport: { width: 1440, height: 1000 },
    extraHTTPHeaders: { Referer: `${baseURL}/` }, trace: 'retain-on-failure' },
  projects: [
    { name: 'auth-setup', testDir: './tests', testMatch: 'auth.setup.ts',
      grep: /authenticate as admin/ },
    { name: 'crud-default', dependencies: ['auth-setup'],
      testMatch: /tests\/e2e\/showcase\/(model-page-workbench-e2e|crud-default-case)\.spec\.ts$/,
      use: { storageState: `${storageDir}/admin.json` } },
  ],
});
