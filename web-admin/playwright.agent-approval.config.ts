import { defineConfig, devices } from '@playwright/test';
import { existsSync, readFileSync } from 'node:fs';
import { isAbsolute } from 'node:path';
import baseConfig from './playwright.config';

// Paid models and private role fixtures belong to this explicit profile only.
const fixture = process.env.AGENT_PERMISSION_FIXTURE;
const evidence = process.env.AGENT_PERMISSION_EVIDENCE;
const agentApprovalSpecNames = ['permissions'];
if (!fixture || !isAbsolute(fixture) || !existsSync(fixture)
    || !evidence || !isAbsolute(evidence)
    || !process.env.PLAYWRIGHT_BASE_URL || !process.env.BACKEND_URL
    || process.env.AGENT_ACCEPTANCE_REAL_MODEL !== 'deepseek') {
  throw new Error('Agent permission UI requires explicit owned URLs, private AGENT_PERMISSION_FIXTURE, AGENT_PERMISSION_EVIDENCE and AGENT_ACCEPTANCE_REAL_MODEL=deepseek');
}
const inputs = JSON.parse(readFileSync(fixture, 'utf8'));
if (inputs.baseURL !== process.env.PLAYWRIGHT_BASE_URL
    || inputs.backendURL !== process.env.BACKEND_URL
    || !inputs.admin?.email || !inputs.admin?.password
    || !inputs.reader?.email || !inputs.reader?.password
    || !inputs.modelCode || !inputs.modelName || !inputs.tenantId
    || !inputs.adminConversationId || !inputs.privateHistoryValue
    || !inputs.database?.database || !inputs.productIdentity
    || !/^[a-f0-9]{64}$/.test(inputs.bundleSha256 || '')) {
  throw new Error('Agent permission UI fixture must bind explicit roles, controlled data and product identity');
}

export default defineConfig({
  ...baseConfig,
  testDir: './tests/acceptance/agent-approval',
  testMatch: agentApprovalSpecNames.map(name => '**/' + name + '.spec.ts'),
  globalSetup: undefined,
  globalTeardown: undefined,
  outputDir: evidence + '/artifacts',
  workers: 1,
  retries: 0,
  timeout: 180_000,
  reporter: [['list'], ['json', { outputFile: evidence + '/report.json' }]],
  use: { ...baseConfig.use, locale: 'zh-CN', trace: 'retain-on-failure',
    storageState: { cookies: [], origins: [] } },
  projects: [{ name: 'agent-approval-ui', use: { ...devices['Desktop Chrome'] } }],
});
