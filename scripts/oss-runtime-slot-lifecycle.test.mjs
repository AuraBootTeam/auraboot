import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const gatePath = fileURLToPath(new URL('./oss-e2e-gate-run.sh', import.meta.url));
const stackPath = fileURLToPath(new URL('./oss-golden-stack.sh', import.meta.url));

test('default Bash handles empty source arrays and preserves incomplete-operation failure', () => {
  const source = readFileSync(stackPath, 'utf8');
  const lockFunctions = source.slice(source.indexOf('GOLDEN_STACK_LOCK_DIR=""'), source.indexOf('state_dir()'));
  const fixture = mkdtempSync(join(tmpdir(), 'oss-bash-contract-'));
  try {
    const prefix = `set -euo pipefail\nREPO_ROOT="$1"\nTMPDIR="$1"\n${lockFunctions}\nacquire_stack_lock\n`;
    const arrayLoop = source.slice(source.indexOf('  local source_args='), source.indexOf('  "$DEV" runtime migrate'));
    const normal = spawnSync('/bin/bash', ['-c', `${prefix}\ndeclare -a extra_plugin_roots=() product_migration_roots=()\nWORKSPACE="$1"\nfreeze_sources() {\n${arrayLoop}\n}\nfreeze_sources\nprintf 'freeze-reached'\nSTACK_OPERATION_COMPLETE=1`, 'fixture', fixture], { encoding: 'utf8' });
    assert.equal(normal.status, 0, normal.stderr);
    assert.equal(normal.stdout, 'freeze-reached');
    const red = spawnSync('/bin/bash', ['-c', `${prefix}\nprintf '%s' "$MISSING_REQUIRED_VALUE"`, 'fixture', fixture], { encoding: 'utf8' });
    assert.notEqual(red.status, 0, 'an interrupted operation must never report success');
    const lockPath = spawnSync('/bin/bash', ['-c', `REPO_ROOT="$1"\nTMPDIR="$1"\n${lockFunctions}\ngolden_stack_lock_dir`, 'fixture', fixture], { encoding: 'utf8' }).stdout.trim();
    assert.equal(existsSync(lockPath), false, 'owned lock is released on both exits');
  } finally { rmSync(fixture, { recursive: true, force: true }); }
});

test('fresh OSS gate preserves existing allocations and retains exit evidence', () => {
  const source = readFileSync(gatePath, 'utf8');
  assert.match(source, /already exists; choose a new verification name/u);
  assert.doesNotMatch(source, /"\$GS" destroy|--fresh-db/u);
  assert.match(source, /"\$GS" down "\$NAME"/u);
  assert.match(source, /"\$DEV" runtime close "\$NAME"/u);
  assert.match(source, /--require-empty-db/u);
});

test('OSS gate resolves the workspace in local and sibling-repository CI layouts', () => {
  const source = readFileSync(gatePath, 'utf8');
  const stack = readFileSync(stackPath, 'utf8');
  assert.match(source, /AURA_WORKSPACE_ROOT/u);
  assert.match(source, /AURA_CI_WORKSPACE_ROOT/u);
  assert.match(source, /auraboot-workspace\/dev\.sh/u);
  assert.match(stack, /AURA_CI_WORKSPACE_ROOT/u);
  assert.match(stack, /auraboot-workspace\/dev\.sh/u);
  assert.match(source, /ENVIRONMENT-INVALID:[^]*exit 2/u);
});

test('golden stack uses idempotent runtime identity with source worktree metadata', () => {
  const source = readFileSync(stackPath, 'utf8');
  assert.match(source, /runtime ensure auraboot "\$name"/u);
  assert.match(source, /--source-root "\$REPO_ROOT"/u);
  assert.match(source, /runtime allocate auraboot "\$name"/u);
  assert.match(source, /legacy dispatcher/u);
  assert.match(source, /--mode "\$runtime_mode"/u);
});

test('fresh gate marks its runtime as verification evidence rather than feature development', () => {
  const source = readFileSync(gatePath, 'utf8');
  const stack = readFileSync(stackPath, 'utf8');
  assert.match(source, /--runtime-mode verification/u);
  assert.match(source, /PLAYWRIGHT_JSON_OUTPUT_FILE="\$AURA_EVIDENCE_ROOT/u);
  assert.match(stack, /export PW_ARTIFACT_DIR=\$evidence_root/u);
});
