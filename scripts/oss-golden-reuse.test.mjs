import { goldenStackState } from './lib/golden-stack-state.mjs';
import assert from 'node:assert/strict';
import { execFileSync, spawn, spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
const source = readFileSync(new URL('./oss-golden-stack.sh', import.meta.url), 'utf8');
const stopped = source.slice(source.indexOf('assert_stack_stopped() {'), source.indexOf('# ---- destroy'));
const stateHelpers = source.slice(source.indexOf('state_dir() {'), source.indexOf('# Read a key'));

function fixture(t) {
  const root = mkdtempSync(join(tmpdir(), 'golden-reuse-'));
  const sd = join(root, '.workspace/runtimes/session/oss-stack'); mkdirSync(sd, { recursive: true });
  t.after(() => rmSync(root, { recursive: true, force: true }));
  writeFileSync(join(root, 'aura'), '#!/usr/bin/env node\nprocess.exit(2);\n', { mode: 0o755 });
  const run = (body) => spawnSync('bash', ['-c', `set -euo pipefail\nREPO_ROOT="$1"\nWORKSPACE="$1"\nWORKSPACE_STATE="$1/.workspace"\nSCRIPT_DIR="$2"\nDEV="$1/aura"\nlog() { :; }\ndie() { echo "$*" >&2; exit 1; }\n${stateHelpers}\n${stopped}\n${body}`, '--', root, new URL('.', import.meta.url).pathname], { encoding: 'utf8' });
  return { root, sd, run };
}
async function sleeper(t, cwd) {
  const child = spawn('sleep', ['60'], { cwd, stdio: 'ignore' });
  await new Promise((resolve, reject) => { child.once('spawn', resolve); child.once('error', reject); });
  t.after(() => child.kill());
  return child;
}
test('fresh and repeated stack preparation uses runtime state without creating golden', (t) => {
  const { root, run } = fixture(t);
  const first = run('prepare_state_dir fresh'); const second = run('prepare_state_dir fresh');
  assert.equal(first.status, 0, first.stderr); assert.equal(second.status, 0, second.stderr);
  assert.equal(first.stdout.trim(), join(root, '.workspace/runtimes/fresh/oss-stack'));
  assert.equal(first.stdout, second.stdout);
  assert.equal(existsSync(join(root, '.workspace/golden')), false);
});
test('stopped legacy state moves once and keeps old readers as an alias', (t) => {
  const { root, run } = fixture(t); const legacy = join(root, '.workspace/golden/legacy');
  mkdirSync(legacy, { recursive: true }); writeFileSync(join(legacy, 'backend.log'), 'old evidence');
  const result = run('prepare_state_dir legacy'); assert.equal(result.status, 0, result.stderr);
  const current = join(root, '.workspace/runtimes/legacy/oss-stack');
  assert.equal(realpathSync(legacy), realpathSync(current));
  assert.equal(readFileSync(join(current, 'backend.log'), 'utf8'), 'old evidence');
  assert.equal(run('prepare_state_dir legacy').stdout.trim(), current);
});
test('ambiguous old and new state is refused without moving either copy', (t) => {
  const { root, sd, run } = fixture(t); const legacy = join(root, '.workspace/golden/session');
  mkdirSync(legacy, { recursive: true }); writeFileSync(join(legacy, 'keep'), 'old');
  const result = run('prepare_state_dir session'); assert.equal(result.status, 1);
  assert.equal(readFileSync(join(legacy, 'keep'), 'utf8'), 'old');
  assert.equal(existsSync(sd), true);
});
test('foreign legacy alias cannot be ignored to create another stack', (t) => {
  const { root, run } = fixture(t); const legacy = join(root, '.workspace/golden/foreign');
  mkdirSync(join(root, '.workspace/golden')); symlinkSync(root, legacy);
  const result = run('prepare_state_dir foreign'); assert.equal(result.status, 1);
  assert.match(result.stderr, /another location/);
  assert.equal(existsSync(join(root, '.workspace/runtimes/foreign')), false);
  assert.equal(realpathSync(legacy), realpathSync(root));
});
test('rebuild rejects live process before touching current runtime or evidence', async (t) => {
  const { root, sd, run } = fixture(t); const child = await sleeper(t, root);
  writeFileSync(join(sd, 'backend.pid'), String(child.pid));
  writeFileSync(join(sd, 'boot-run.jar'), 'old immutable artifact');
  const result = run('assert_stack_stopped session');
  assert.equal(result.status, 1); assert.match(result.stderr, /live backend/);
  assert.equal(readFileSync(join(sd, 'boot-run.jar'), 'utf8'), 'old immutable artifact');
  process.kill(child.pid, 0);
});
test('down rejects an unregistered foreign process and leaves it alive', async (t) => {
  const { sd, run } = fixture(t); const child = await sleeper(t, '/');
  writeFileSync(join(sd, 'frontend.pid'), String(child.pid));
  writeFileSync(join(sd, 'frontend.pid.started'), execFileSync('ps', ['-p', String(child.pid), '-o', 'lstart=']));
  const result = run('cmd_down session');
  assert.equal(result.status, 1); assert.match(result.stderr, /owned process stop refused/);
  process.kill(child.pid, 0);
});
test('legacy PID generation alone cannot authorize stopping an unregistered process', async (t) => {
  const { root, sd, run } = fixture(t); const child = await sleeper(t, root);
  writeFileSync(join(sd, 'frontend.pid'), String(child.pid));
  writeFileSync(join(sd, 'frontend.pid.started'), 'different start time');
  const result = run('cmd_down session');
  assert.equal(result.status, 1); assert.match(result.stderr, /owned process stop refused/);
  process.kill(child.pid, 0);
});
test('gate retains runtime while logs and Playwright outputs use independent round evidence', () => {
  for (const script of ['oss-e2e-gate-run.sh', 'hifi-golden-gate-run.sh']) {
    const gate = readFileSync(new URL(script, import.meta.url), 'utf8');
    assert.doesNotMatch(gate, /"\$GS" destroy/);
    assert.match(gate, /keeping stack|runtime retained/);
  }
  assert.match(source, /runtime evidence begin/);
  assert.match(source, /ln -sfn "\$evidence_root\/logs" "\$sd\/logs"/);
  assert.match(source, /export PW_REPORT_DIR=\$evidence_root/);
  assert.match(source, /<maxFileSize>10MB<\/maxFileSize>/);
});

test('log compatibility pointers preserve old bytes and point into each independent round', (t) => {
  const { root, sd } = fixture(t);
  const helper = source.split("<<'PYLOG'\n")[1].split('\nPYLOG')[0];
  const first = join(root, 'evidence/first'); const second = join(root, 'evidence/second');
  for (const round of [first, second]) mkdirSync(join(round, 'logs'), { recursive: true });
  symlinkSync(join(first, 'logs'), join(sd, 'logs'));
  writeFileSync(join(sd, 'backend.log'), 'legacy bytes');
  execFileSync('python3', ['-c', helper, sd, first]);
  writeFileSync(join(first, 'logs/backend.log'), 'first round');
  assert.equal(readFileSync(join(sd, 'backend.log'), 'utf8'), 'first round');
  assert.equal(readFileSync(join(first, 'legacy-logs/backend.log'), 'utf8'), 'legacy bytes');
  rmSync(join(sd, 'logs'));
  symlinkSync(join(second, 'logs'), join(sd, 'logs'));
  execFileSync('python3', ['-c', helper, sd, second]);
  writeFileSync(join(second, 'logs/backend.log'), 'second round');
  assert.equal(readFileSync(join(sd, 'backend.log'), 'utf8'), 'second round');
  assert.equal(readFileSync(join(first, 'logs/backend.log'), 'utf8'), 'first round');
});

test('stop and resume read stable state without creating a legacy directory', (t) => {
  const { root, sd } = fixture(t);
  assert.equal(goldenStackState(join(root, '.workspace'), 'session'), sd);
  assert.equal(existsSync(join(root, '.workspace/golden')), false);
});
test('stop and resume accept only an exact migrated alias and reject split state', (t) => {
  const { root, sd } = fixture(t), state = join(root, '.workspace'), old = join(state, 'golden/session');
  mkdirSync(join(state, 'golden'));
  symlinkSync(sd, old);
  assert.equal(goldenStackState(state, 'session'), sd);
  rmSync(old); mkdirSync(old);
  assert.throws(() => goldenStackState(state, 'session'), /Ambiguous/);
  assert.equal(existsSync(sd), true); assert.equal(existsSync(old), true);
});
test('stop and resume retain unmigrated legacy state and reject a foreign alias', (t) => {
  const { root } = fixture(t), state = join(root, '.workspace'), old = join(state, 'golden/legacy');
  mkdirSync(old, { recursive: true });
  assert.equal(goldenStackState(state, 'legacy'), old);
  rmSync(old, { recursive: true }); symlinkSync(root, old);
  assert.throws(() => goldenStackState(state, 'legacy'), /another location/);
  assert.equal(realpathSync(old), realpathSync(root));
});
test('stable state symlinks and traversal names fail before mutation', (t) => {
  const { root, sd, run } = fixture(t), state = join(root, '.workspace');
  rmSync(sd, { recursive: true }); symlinkSync(root, sd);
  assert.throws(() => goldenStackState(state, 'session'), /Invalid stable/);
  assert.equal(run('prepare_state_dir session').status, 1);
  assert.throws(() => goldenStackState(state, '../foreign'), /Invalid stack runtime/);
  assert.equal(realpathSync(sd), realpathSync(root));
});
