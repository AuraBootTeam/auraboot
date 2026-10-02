import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

for (const external of [false, true]) test(`env reads ${external ? 'external CI' : 'default local'} runtime state`, t => {
  const workspace = fs.mkdtempSync(path.join(os.tmpdir(), 'golden-state-contract-'));
  t.after(() => fs.rmSync(workspace, { recursive: true, force: true }));
  fs.writeFileSync(path.join(workspace, 'dev.sh'), '#!/bin/bash\nexit 0\n', { mode: 0o755 });
  const state = path.join(workspace, external ? 'external-state' : '.workspace');
  fs.mkdirSync(path.join(state, 'golden', 'fixture'), { recursive: true });
  fs.mkdirSync(path.join(state, 'env'), { recursive: true });
  fs.writeFileSync(path.join(state, 'golden', 'fixture', 'ports'), '6430 5130 6130\n');
  fs.writeFileSync(path.join(state, 'env', 'fixture.env'), `AURA_EVIDENCE_ROOT=${workspace}/evidence\n`);
  const env = { ...process.env, AURA_WORKSPACE_ROOT: workspace };
  delete env.AURA_WORKSPACE_STATE_DIR;
  if (external) env.AURA_WORKSPACE_STATE_DIR = state;
  const result = spawnSync('bash', [path.join(import.meta.dirname, 'oss-golden-stack.sh'), 'env', 'fixture'], { env, encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /export BACKEND_URL=http:\/\/127\.0\.0\.1:6430/);
  assert.ok(result.stdout.includes(`export AURA_EVIDENCE_ROOT=${workspace}/evidence`));
});
