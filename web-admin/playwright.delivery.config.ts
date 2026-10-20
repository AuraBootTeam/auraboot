import { defineConfig } from '@playwright/test';

const baseURL = process.env.DELIVERY_WEB_URL;
if (!baseURL || !['localhost', '127.0.0.1'].includes(new URL(baseURL).hostname)) {
  throw new Error('DELIVERY_WEB_URL must identify the owned local runtime.');
}
export default defineConfig({
  testDir: './tests/e2e/project-management',
  testMatch: 'delivery-smoke.spec.ts',
  workers: 1,
  retries: 0,
  timeout: 90_000,
  use: { baseURL, channel: 'chrome', locale: 'zh-CN', viewport: { width: 1440, height: 1000 }, trace: 'on', video: 'on' },
  reporter: [['list'], ['json', { outputFile: process.env.DELIVERY_EVIDENCE_ROOT + '/browser-results.json' }]],
  outputDir: process.env.DELIVERY_EVIDENCE_ROOT + '/browser-artifacts',
});
