import { defineConfig, devices } from '@playwright/test';
import { existsSync, readFileSync } from 'node:fs';
import { isAbsolute } from 'node:path';
import baseConfig from './playwright.config';

// Paid models and private role fixtures belong to this explicit profile only.
const fixture = process.env.AGENT_PERMISSION_FIXTURE;
const evidence = process.env.AGENT_PERMISSION_EVIDENCE;
const scope = process.env.AGENT_ACCEPTANCE_SCOPE ?? 'permissions';
const agentApprovalSpecNames = [
  'permissions',
  'memory',
  'steady',
  'forms',
  'governance',
  'recovery',
];
if (!agentApprovalSpecNames.includes(scope)) throw new Error('Unknown agent acceptance scope');
if (
  !fixture ||
  !isAbsolute(fixture) ||
  !existsSync(fixture) ||
  !evidence ||
  !isAbsolute(evidence) ||
  !process.env.PLAYWRIGHT_BASE_URL ||
  !process.env.BACKEND_URL ||
  process.env.AGENT_ACCEPTANCE_REAL_MODEL !== 'deepseek'
) {
  throw new Error(
    'Agent permission UI requires explicit owned URLs, private AGENT_PERMISSION_FIXTURE, AGENT_PERMISSION_EVIDENCE and AGENT_ACCEPTANCE_REAL_MODEL=deepseek',
  );
}
const inputs = JSON.parse(readFileSync(fixture, 'utf8'));
if (
  inputs.baseURL !== process.env.PLAYWRIGHT_BASE_URL ||
  inputs.backendURL !== process.env.BACKEND_URL ||
  !inputs.tenantId ||
  !inputs.database?.database ||
  !inputs.productIdentity ||
  !/^[a-f0-9]{64}$/.test(inputs.bundleSha256 || '')
) {
  throw new Error('Agent UI fixture must bind explicit URLs, storage and product identity');
}
if (
  scope === 'permissions' &&
  (!inputs.admin?.email ||
    !inputs.admin?.password ||
    !inputs.reader?.email ||
    !inputs.reader?.password ||
    !inputs.modelCode ||
    !inputs.modelName ||
    !inputs.adminConversationId ||
    !inputs.privateHistoryValue)
) {
  throw new Error('Agent permission fixture requires explicit roles and controlled data');
}
if (
  scope === 'memory' &&
  (!inputs.owner?.email ||
    !inputs.owner?.password ||
    !inputs.other?.email ||
    !inputs.other?.password ||
    !inputs.memory?.pid ||
    !inputs.memory?.actualTitle ||
    !inputs.memory?.value ||
    !inputs.memory?.question ||
    !inputs.memory?.missingQuestion ||
    !inputs.memory?.missingMarker)
) {
  throw new Error(
    'Agent memory fixture requires distinct accounts and actual persisted source data',
  );
}
if (
  scope === 'steady' &&
  (!inputs.owner?.email ||
    !inputs.owner?.password ||
    !inputs.owner?.userId ||
    !inputs.modelCode ||
    !inputs.businessPid ||
    !inputs.expectedBusiness ||
    !inputs.memory?.pid ||
    !inputs.memory?.value ||
    !inputs.memory?.foreignValue ||
    !inputs.memory?.expectedSpace ||
    !inputs.memory?.question ||
    !inputs.runtime?.workspace ||
    !inputs.runtime?.processDirectory ||
    !inputs.runtime?.name)
) {
  throw new Error(
    'Steady product qualification requires the frozen business/memory truth and exact owned process identities',
  );
}
if (
  ['forms', 'governance', 'recovery'].includes(scope) &&
  (!inputs.admin?.email ||
    !inputs.admin?.password ||
    !inputs.form?.code ||
    !inputs.form?.manualName ||
    !inputs.form?.contact ||
    !inputs.form?.manualContact ||
    !inputs.runtime?.workspace ||
    !inputs.runtime?.name ||
    !inputs.runtime?.processDirectory ||
    !inputs.runtime?.bundle)
) {
  throw new Error('Product qualification requires frozen form truth and exact owned runtime paths');
}
if (
  ['governance', 'recovery'].includes(scope) &&
  (!inputs.codes ||
    !inputs.adminBearer ||
    !inputs.readerBearer ||
    !inputs.foreignBearer ||
    !inputs.writer?.memberPid ||
    !inputs.memory?.value ||
    !inputs.admin?.userId ||
    !inputs.reader?.userId ||
    !inputs.approvalPolicyPid ||
    !inputs.readerRolePid ||
    !Array.isArray(inputs.writerInitialRolePids) ||
    inputs.writerInitialRolePids.length !== 2)
) {
  throw new Error(
    'Governed qualification requires distinct private identities, pending codes and independent source truth',
  );
}
if (scope === 'recovery' && process.env.AGENT_PROCESS_FAULTS !== 'owned-jvm') {
  throw new Error('Process interruption requires explicit AGENT_PROCESS_FAULTS=owned-jvm');
}

export default defineConfig({
  ...baseConfig,
  testDir: './tests/acceptance/agent-approval',
  testMatch: agentApprovalSpecNames
    .filter((name) => name === scope)
    .map((name) => '**/' + name + '.spec.ts'),
  globalSetup: undefined,
  globalTeardown: undefined,
  outputDir: evidence + '/artifacts',
  workers: 1,
  retries: 0,
  timeout: scope === 'recovery' ? 600_000 : 180_000,
  reporter: [['list'], ['json', { outputFile: evidence + '/report.json' }]],
  use: {
    ...baseConfig.use,
    locale: 'zh-CN',
    trace: 'retain-on-failure',
    storageState: { cookies: [], origins: [] },
  },
  projects: [
    {
      name: 'agent-approval-ui',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 1000 } },
    },
  ],
});
