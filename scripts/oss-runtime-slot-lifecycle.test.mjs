import assert from 'node:assert/strict';
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const gatePath = fileURLToPath(new URL('./oss-e2e-gate-run.sh', import.meta.url));
const stackPath = fileURLToPath(new URL('./oss-golden-stack.sh', import.meta.url));

for (const scenario of ['passed', 'skip-setup', 'skip-route']) {
  test(`canonical warm execution audit: ${scenario}`, () => {
    const fixture = mkdtempSync(join(tmpdir(), 'oss-warm-contract-'));
    try {
      mkdirSync(join(fixture, 'web-admin', 'tests', 'storage'), { recursive: true });
      mkdirSync(join(fixture, 'state'));
      writeFileSync(join(fixture, 'state', 'ports'), '6473 5173 6173');
      writeFileSync(join(fixture, 'pnpm'), `#!/usr/bin/env node
const fs=require('fs'),path=require('path');
const args=process.argv.slice(2),project=args.find(a=>a.startsWith('--project=')).split('=')[1];
fs.appendFileSync(path.join(process.env.FIXTURE_ROOT,'calls.jsonl'),JSON.stringify({project,profile:process.env.PW_PROFILE,args})+'\\n');
const skip=(process.env.SCENARIO==='skip-setup'&&project==='setup')||(process.env.SCENARIO==='skip-route'&&project==='chromium');
const file=process.env.PLAYWRIGHT_JSON_OUTPUT_FILE;fs.mkdirSync(path.dirname(file),{recursive:true});
fs.writeFileSync(file,JSON.stringify({suites:[{specs:[{tests:[{expectedStatus:'passed',results:[{status:skip?'skipped':'passed'}]}]}]}],errors:[]}));
if(project==='auth')fs.writeFileSync('tests/storage/admin.json',JSON.stringify({cookies:[{name:'__session',value:'fixture-cookie'}]}));
`);
      chmodSync(join(fixture, 'pnpm'), 0o755);
      const source = readFileSync(stackPath, 'utf8');
      const warm = source.slice(source.indexOf('cmd_warm() {'), source.indexOf('# ---- env '));
      const result = spawnSync('/bin/bash', ['-c', `
set -euo pipefail
REPO_ROOT="$FIXTURE_ROOT"
SCRIPT_DIR="$REAL_SCRIPT_DIR"
state_dir() { printf '%s/state' "$FIXTURE_ROOT"; }
runtime_env() { printf '%s/evidence' "$FIXTURE_ROOT"; }
cmd_env() { printf 'export AURA_EVIDENCE_ROOT=%s/evidence\\n' "$FIXTURE_ROOT"; }
log() { :; }
die() { echo "$*" >&2; exit 1; }
${warm}
cmd_warm sample
`], { encoding: 'utf8', env: { ...process.env, FIXTURE_ROOT: fixture, SCENARIO: scenario,
        REAL_SCRIPT_DIR: fileURLToPath(new URL('./', import.meta.url)), PATH: `${fixture}:${process.env.PATH}` } });
      const calls = readFileSync(join(fixture, 'calls.jsonl'), 'utf8').trim().split('\n').map(JSON.parse);
      assert.equal(result.status, scenario === 'passed' ? 0 : 1, result.stderr);
      assert.equal(calls[0].profile, 'oss');
      assert.equal(calls[0].project, 'setup');
      assert.equal(calls[0].args.some(arg => arg.startsWith('tests/')), false, 'setup must execute the complete canonical project');
      assert.equal(calls.length, scenario === 'skip-setup' ? 1 : 3);
      if (scenario === 'skip-route') assert.match(result.stderr, /route execution evidence incomplete/);
    } finally { rmSync(fixture, { recursive: true, force: true }); }
  });
}

test('capacity refusal exits environment-invalid without teardown of an absent allocation', () => {
  const fixture = mkdtempSync(join(tmpdir(), 'oss-capacity-contract-'));
  try {
    const calls = join(fixture, 'calls.log');
    writeFileSync(join(fixture, 'dev.sh'), '#!/bin/bash\n');
    writeFileSync(join(fixture, 'aura'), `#!/bin/bash
printf '%s\\n' "$*" >> "$AURA_FIXTURE_CALLS"
case "$1 $2" in
  'runtime list') printf 'NAME REPO SLOT\\n';;
  'runtime show') printf '{"allocation":null}';;
  'runtime ') printf 'runtime ensure\\n';;
  'runtime ensure') printf 'capacity reached\\n' >&2; exit 9;;
  *) exit 11;;
esac
`);
    writeFileSync(join(fixture, 'lsof'), '#!/bin/bash\nexit 1\n');
    // Disk is an independent external prerequisite. Keep this fixture focused
    // on allocation capacity regardless of the test host's available space.
    writeFileSync(join(fixture, 'node'), `#!/bin/bash
if [[ "$1" == */oss-disk-preflight.mjs ]]; then exit 0; fi
exec '${process.execPath}' "$@"
`);
    for (const file of ['aura', 'lsof', 'node']) chmodSync(join(fixture, file), 0o755);
    const result = spawnSync('/bin/bash', [gatePath, '--name', 'capacity-fixture', '--slot', '249'], { encoding: 'utf8',
      env: { ...process.env, TMPDIR: fixture, PATH: `${fixture}:${process.env.PATH}`, AURA_FIXTURE_CALLS: calls,
        AURA_WORKSPACE_ROOT: fixture, AURA_WORKSPACE_STATE_DIR: join(fixture, 'state') } });
    assert.equal(result.status, 2, result.stderr);
    assert.match(result.stdout, /no allocation registered/);
    assert.match(result.stderr, /ENVIRONMENT-INVALID/);
    const commands = readFileSync(calls, 'utf8');
    assert.match(commands, /runtime ensure auraboot capacity-fixture/);
    assert.doesNotMatch(commands, /runtime close|runtime destroy|infra cleanup/);
  } finally { rmSync(fixture, { recursive: true, force: true }); }
});

test('public stack env routes screenshots, downloads and seed logs to managed evidence', () => {
  const fixture = mkdtempSync(join(tmpdir(), 'oss-env-contract-'));
  try {
    const state = join(fixture, 'state');
    const evidence = join(fixture, 'evidence');
    mkdirSync(join(state, 'golden', 'sample'), { recursive: true });
    mkdirSync(join(state, 'env'));
    writeFileSync(join(fixture, 'dev.sh'), '#!/bin/bash\n');
    writeFileSync(join(fixture, 'aura'), '#!/bin/bash\nexit 1\n');
    chmodSync(join(fixture, 'aura'), 0o755);
    writeFileSync(join(state, 'golden', 'sample', 'ports'), '6473 5173 6173\n');
    writeFileSync(join(state, 'env', 'sample.env'), `AURA_EVIDENCE_ROOT=${evidence}\n`);
    const result = spawnSync('/bin/bash', [stackPath, 'env', 'sample'], { encoding: 'utf8',
      env: { ...process.env, AURA_WORKSPACE_ROOT: fixture, AURA_WORKSPACE_STATE_DIR: state } });
    assert.equal(result.status, 0, result.stderr);
    const evaluated = spawnSync('/bin/bash', ['-c', `${result.stdout}\nprintf '%s\\n' "$AURA_EVIDENCE_DIR" "$SEED_LOG_DIR"`], { encoding: 'utf8' });
    assert.equal(evaluated.status, 0, evaluated.stderr);
    assert.deepEqual(evaluated.stdout.trim().split('\n'), [join(evidence, 'playwright', 'evidence'), join(evidence, 'logs', 'seed')]);
    assert.equal(existsSync(join(evidence, 'playwright', 'evidence')), true);
  } finally { rmSync(fixture, { recursive: true, force: true }); }
});

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
