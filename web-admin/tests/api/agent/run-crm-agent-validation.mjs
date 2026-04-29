#!/usr/bin/env node

import { spawn } from 'node:child_process';

const commonEnv = {
  ...process.env,
  NO_PROXY: process.env.NO_PROXY || 'localhost,127.0.0.1',
  PW_PROFILE: process.env.PW_PROFILE || 'full',
  PW_WORKERS: process.env.PW_WORKERS || '1',
};

const reporterArgs =
  process.env.CRM_AGENT_USE_CONFIG_REPORTER === '1'
    ? []
    : ['--reporter', process.env.CRM_AGENT_REPORTER || 'line'];

const stages = [
  {
    name: 'auth',
    args: [
      'test',
      '-c',
      'playwright.noweb.config.ts',
      'tests/auth.setup.ts',
      '--project=auth',
      ...reporterArgs,
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
      ...reporterArgs,
      '--no-deps',
    ],
  },
  {
    name: 'quality-unit',
    args: [
      'test',
      '-c',
      'playwright.noweb.config.ts',
      'tests/api/agent/crm-agent-quality-report.spec.ts',
      '--project=api',
      ...reporterArgs,
      '--no-deps',
    ],
  },
  {
    name: 'llm',
    requiresLlm: true,
    args: [
      'test',
      '-c',
      'playwright.noweb.config.ts',
      'tests/api/agent/crm-ai-scenarios.spec.ts',
      '--project=api',
      ...reporterArgs,
      '--no-deps',
    ],
  },
  {
    name: 'quality-report',
    requiresLlm: true,
    command: 'node',
    args: ['tests/api/agent/crm-agent-quality-report.mjs'],
  },
  {
    name: 'ui',
    requiresUi: true,
    args: [
      'test',
      '-c',
      'playwright.noweb.config.ts',
      'tests/e2e/crm/crm-agent-ui-smoke.spec.ts',
      '--project=chromium',
      ...reporterArgs,
      '--no-deps',
    ],
  },
];

function runStage(stage) {
  return new Promise((resolve, reject) => {
    console.log(`\n[crm-agent] stage=${stage.name}`);
    const child = spawn(stage.command || 'playwright', stage.args, {
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
  if (stage.requiresLlm && process.env.CRM_AGENT_SKIP_LLM === '1') {
    console.log(`\n[crm-agent] stage=${stage.name} skipped (CRM_AGENT_SKIP_LLM=1)`);
    continue;
  }
  if (stage.requiresUi && process.env.CRM_AGENT_SKIP_UI === '1') {
    console.log(`\n[crm-agent] stage=${stage.name} skipped (CRM_AGENT_SKIP_UI=1)`);
    continue;
  }
  await runStage(stage);
}

console.log('\n[crm-agent] all stages passed');
