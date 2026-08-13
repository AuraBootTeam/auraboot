import { defineConfig, devices } from '@playwright/test';
import dns from 'node:dns';
import path from 'node:path';

dns.setDefaultResultOrder('ipv4first');
for (const key of ['http_proxy', 'HTTP_PROXY', 'https_proxy', 'HTTPS_PROXY']) {
  delete process.env[key];
}
process.env.NO_PROXY = 'localhost,127.0.0.1';
process.env.no_proxy = process.env.NO_PROXY;

export default defineConfig({
  testDir: '.',
  testMatch: 'forecast-variance.golden.spec.ts',
  outputDir:
    process.env.FORECAST_VARIANCE_ARTIFACT_DIR ||
    path.resolve(process.cwd(), '.workspace', 'artifacts', 'crm-forecast-variance'),
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 180_000,
  reporter: [['list']],
  use: {
    baseURL: process.env.PLAYWRIGHT_BASE_URL || 'http://127.0.0.1:5198',
    headless: true,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
    launchOptions: { args: ['--no-proxy-server'] },
  },
  projects: [
    {
      name: 'crm-forecast-variance-chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});
