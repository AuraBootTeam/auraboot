#!/usr/bin/env node

import { mkdirSync } from 'node:fs';
import { dirname } from 'node:path';
import { spawnSync } from 'node:child_process';

const targetSpecs = [
  'tests/e2e/auth/login.spec.ts',
  'tests/e2e/auth/auth-complete.spec.ts',
  'tests/e2e/auth/auth-recovery-and-signup.spec.ts',
  'tests/e2e/auth/login-multichannel.spec.ts',
  'tests/e2e/auth/logout.spec.ts',
  'tests/e2e/auth/employee-account-login-policy.spec.ts',
  'tests/e2e/auth/space-selection.spec.ts',
];

const runId =
  process.env.PW_E2E_RUN_ID ||
  `auth-regression-${new Date().toISOString().replace(/[-:.TZ]/g, '').slice(0, 14)}-${process.pid}`;

const env = { ...process.env };
env.NO_PROXY = appendNoProxy(env.NO_PROXY);
env.no_proxy = env.NO_PROXY;
env.PW_PROFILE ||= 'fast';
env.PW_ROLE_PROJECTS ||= '1';
env.PW_WORKERS ||= '1';
env.PW_STORAGE_DIR ||= `tests/storage/${runId}`;
env.PW_ADMIN_STORAGE_STATE ||= `${env.PW_STORAGE_DIR}/admin.json`;
env.PW_OPERATOR_STORAGE_STATE ||= `${env.PW_STORAGE_DIR}/operator.json`;
env.PW_VIEWER_STORAGE_STATE ||= `${env.PW_STORAGE_DIR}/viewer.json`;
env.PW_ARTIFACT_DIR ||= `test-results/runs/${runId}/artifacts`;
env.PW_REPORT_DIR ||= `test-results/runs/${runId}/html-report`;
env.PW_RESULTS_JSON ||= `test-results/runs/${runId}/results.json`;

for (const dir of [
  env.PW_STORAGE_DIR,
  env.PW_ARTIFACT_DIR,
  env.PW_REPORT_DIR,
  dirname(env.PW_RESULTS_JSON),
]) {
  mkdirSync(dir, { recursive: true });
}

runPlaywright([
  'test',
  'tests/api/setup/00-bootstrap.spec.ts',
  'tests/api/setup/01-multi-role-users.spec.ts',
  '--project=setup',
]);
runPlaywright(['test', 'tests/auth.setup.ts', '--project=auth', '--no-deps']);
runPlaywright(['test', ...targetSpecs, '--project=chromium', '--no-deps']);

function runPlaywright(args) {
  const result = spawnSync('playwright', args, {
    stdio: 'inherit',
    env,
    shell: process.platform === 'win32',
  });
  if (result.error) {
    console.error(result.error);
    process.exit(1);
  }
  if (result.status !== 0) {
    process.exit(result.status ?? 1);
  }
}

function appendNoProxy(value) {
  const entries = new Set(
    String(value || '')
      .split(',')
      .map((entry) => entry.trim())
      .filter(Boolean),
  );
  for (const entry of ['localhost', '127.0.0.1']) {
    entries.add(entry);
  }
  return Array.from(entries).join(',');
}
