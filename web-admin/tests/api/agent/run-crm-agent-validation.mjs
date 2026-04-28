#!/usr/bin/env node

import { spawn } from 'node:child_process';

const commonEnv = {
  ...process.env,
  NO_PROXY: process.env.NO_PROXY || 'localhost,127.0.0.1',
  PW_PROFILE: process.env.PW_PROFILE || 'full',
  PW_WORKERS: process.env.PW_WORKERS || '1',
};

const stages = [
  {
    name: 'auth',
    args: [
      'test',
      '-c',
      'playwright.noweb.config.ts',
      'tests/auth.setup.ts',
      '--project=auth',
      '--reporter=line',
    ],
  },
  {
    name: 'substrate',
    args: [
      'test',
      '-c',
      'playwright.noweb.config.ts',
      'tests/api/agent/crm-agent-validation.spec.ts',
      '--project=api',
      '--reporter=line',
      '--no-deps',
    ],
  },
  {
    name: 'llm',
    args: [
      'test',
      '-c',
      'playwright.noweb.config.ts',
      'tests/api/agent/crm-ai-scenarios.spec.ts',
      '--project=api',
      '--reporter=line',
      '--no-deps',
    ],
  },
  {
    name: 'ui',
    args: [
      'test',
      '-c',
      'playwright.noweb.config.ts',
      'tests/e2e/crm/crm-agent-ui-smoke.spec.ts',
      '--project=chromium',
      '--reporter=line',
      '--no-deps',
    ],
  },
];

function runStage(stage) {
  return new Promise((resolve, reject) => {
    console.log(`\n[crm-agent] stage=${stage.name}`);
    const child = spawn('playwright', stage.args, {
      stdio: 'inherit',
      env: commonEnv,
      shell: false,
    });

    child.on('error', reject);
    child.on('exit', (code, signal) => {
      if (code === 0) {
        resolve();
        return;
      }
      reject(
        new Error(
          `CRM agent validation stage "${stage.name}" failed with ${signal || `exit code ${code}`}`,
        ),
      );
    });
  });
}

for (const stage of stages) {
  await runStage(stage);
}

console.log('\n[crm-agent] all stages passed');
