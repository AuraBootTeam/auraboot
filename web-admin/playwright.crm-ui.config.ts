import { defineConfig, devices } from '@playwright/test';
import { existsSync, readFileSync } from 'node:fs';
import { isAbsolute, resolve } from 'node:path';
import baseConfig from './playwright.config';
const scope = JSON.parse(readFileSync(new URL('../oss-scope.json', import.meta.url), 'utf8')) as {
  application_test_profiles: Array<{ id: string; tags: string[] }>;
};

const profile = scope.application_test_profiles.find(entry => entry.id === 'crm-ui-seams');
const origin = process.env.PLAYWRIGHT_BASE_URL;
const source = process.env.AURA_CRM_SOURCE_ROOT;
const manifest = process.env.AURA_WEB_CONTRIBUTION_MANIFEST;
const storageState = process.env.PW_ADMIN_STORAGE_STATE;
if (!profile || !origin || !storageState || !source || !isAbsolute(source)
  || !manifest || resolve(manifest) !== resolve(source, 'packages/aura-crm-web/src/contribution-module.ts')
  || !existsSync(manifest) || process.env.PW_SKIP_WEBSERVER !== '1') {
  throw new Error('CRM UI seams require an explicit owned runtime, private auth and the frozen real CRM contribution');
}

// The real dashboard storage/browser and real contributed widgets remain in the path.
// Existing case-controlled CRM responses prove UI states, not live CRM business data.
export default defineConfig({
  ...baseConfig,
  workers: 1,
  retries: 0,
  use: {
    ...baseConfig.use,
    ...devices['Desktop Chrome'],
    storageState,
    extraHTTPHeaders: { Referer: `${origin}/` },
  },
  projects: [{
    name: 'chromium',
    testDir: './tests/e2e',
    testMatch: /dashboard\/dashboard-widget-runtime\.spec\.ts$/,
    grep: new RegExp(profile.tags.join('|')),
  }],
});
