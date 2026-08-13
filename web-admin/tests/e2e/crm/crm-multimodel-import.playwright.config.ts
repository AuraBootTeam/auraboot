import { defineConfig, devices } from '@playwright/test';
import dns from 'node:dns';

import { BASE_URL } from '../../helpers/playwright-env';

dns.setDefaultResultOrder('ipv4first');
delete process.env.http_proxy;
delete process.env.HTTP_PROXY;
delete process.env.https_proxy;
delete process.env.HTTPS_PROXY;
process.env.NO_PROXY = 'localhost,127.0.0.1';
process.env.no_proxy = process.env.NO_PROXY;

export default defineConfig({
  testDir: '.',
  testMatch: ['crm-multimodel-import-cordys-parity.spec.ts'],
  outputDir:
    process.env.PW_ARTIFACT_DIR ||
    '/Users/ghj/work/auraboot/.workspace/evidence/crm-multimodel-import-20260813-s143/artifacts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 90_000,
  reporter: [['list']],
  use: {
    baseURL: BASE_URL,
    headless: true,
    trace: 'on',
    screenshot: 'only-on-failure',
    video: 'off',
    actionTimeout: 10_000,
    navigationTimeout: 20_000,
    launchOptions: { args: ['--no-proxy-server'] },
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
