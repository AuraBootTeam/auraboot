import { defineConfig } from '@playwright/test';
import path from 'node:path';
const baseURL = process.env.PLAYWRIGHT_BASE_URL;
const evidence = process.env.AURA_EVIDENCE_DIR;
if (!baseURL || !evidence || !['localhost', '127.0.0.1'].includes(new URL(baseURL).hostname)) {
  throw new Error('Bind the verified loopback runtime and its evidence directory.');
}
export default defineConfig({
  testDir: './tests/e2e',
  testMatch: ['decisionops/condition-expression-real.spec.ts'],
  workers: 1,
  retries: 0,
  forbidOnly: true,
  timeout: 240000,
  use: {
    extraHTTPHeaders: { Origin: new URL(baseURL).origin, Referer: new URL('/', baseURL).href },
    actionTimeout: 15000,
    navigationTimeout: 30000,
    baseURL,
    trace: 'on',
    screenshot: 'only-on-failure',
    storageState: { cookies: [], origins: [] },
    launchOptions: { args: ['--no-proxy-server'] },
  },
  reporter: [
    ['line'],
    ['json', { outputFile: path.join(evidence, 'condition-browser-results.json') }],
  ],
  outputDir: path.join(evidence, 'condition-browser-artifacts'),
});
