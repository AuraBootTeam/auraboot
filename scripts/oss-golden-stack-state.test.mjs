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
  // Hermetic registered Workspace controller; no real runtime allocation.
  assert.equal(spawnSync('git', ['init', workspace], { encoding: 'utf8' }).status, 0);
  fs.writeFileSync(path.join(workspace, 'aura'), 'if (process.argv.slice(2).join(" ") !== "control require 1") process.exit(2);\n', { mode: 0o755 });
  fs.writeFileSync(path.join(workspace, 'runtime.yaml'), 'version: 1\n');
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


test('warm reads the same selected admin state as Playwright without falling back to tracked fixtures', () => {
  const source = fs.readFileSync(path.join(import.meta.dirname, 'oss-golden-stack.sh'), 'utf8');
  const warm = source.slice(source.indexOf('cmd_warm() {'));
  const selection = warm.match(/  local admin_json[\s\S]*?(?=  local env_exports;)/)?.[0];
  assert.ok(selection, 'Warm admin-state selection must exist');
  const cases = [
    [{}, '/fixture/web-admin/tests/storage/admin.json'],
    [{ PW_STORAGE_DIR: '/private/task-auth' }, '/private/task-auth/admin.json'],
    [{ PW_STORAGE_DIR: 'task-auth' }, '/fixture/web-admin/task-auth/admin.json'],
    [{ PW_STORAGE_DIR: '/private/task-auth', PW_ADMIN_STORAGE_STATE: '/private/explicit.json' }, '/private/explicit.json'],
  ];
  for (const [overrides, expected] of cases) {
    const env = { ...process.env };
    delete env.PW_STORAGE_DIR; delete env.PW_ADMIN_STORAGE_STATE;
    Object.assign(env, overrides);
    const script = `read_state() { local fe=/fixture/web-admin;\n${selection}\nprintf '%s' "$admin_json";\n}\nread_state`;
    const result = spawnSync('bash', ['-c', script], { env, encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr);
    assert.equal(result.stdout, expected);
  }
});
