import { defineConfig, devices } from '@playwright/test';
import dns from 'node:dns';

dns.setDefaultResultOrder('ipv4first');
for (const key of ['http_proxy', 'HTTP_PROXY', 'https_proxy', 'HTTPS_PROXY']) {
  delete process.env[key];
}
process.env.NO_PROXY = 'localhost,127.0.0.1';
process.env.no_proxy = process.env.NO_PROXY;

const customerRequestIntakeSpecNames = [
  'customer-request-intake.golden',
];

export default defineConfig({
  testDir: '.',
  testMatch: customerRequestIntakeSpecNames.map((name) => `${name}.spec.ts`),
  outputDir:
    process.env.CUSTOMER_REQUEST_INTAKE_BROWSER_ARTIFACT_DIR
    || '/tmp/crm-customer-request-intake-browser-artifacts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 90_000,
  reporter: [['list']],
  use: {
    baseURL: process.env.PLAYWRIGHT_BASE_URL || 'http://127.0.0.1:5231',
    headless: true,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
    launchOptions: { args: ['--no-proxy-server'] },
  },
  projects: [{
    name: 'customer-request-intake-chromium',
    use: { ...devices['Desktop Chrome'] },
  }],
});
