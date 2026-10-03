import assert from 'node:assert/strict';
import { execFileSync, spawn, spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
const source = readFileSync(new URL('./oss-golden-stack.sh', import.meta.url), 'utf8');
const stopped = source.slice(source.indexOf('assert_stack_stopped() {'), source.indexOf('# ---- destroy'));
const killTree = source.slice(source.indexOf('kill_tree() {'), source.indexOf('kill_listener_supervisor() {'));
function fixture(t) {
  const root = mkdtempSync(join(tmpdir(), 'golden-reuse-'));
  const sd = join(root, '.workspace/golden/session'); mkdirSync(sd, { recursive: true });
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const run = (body) => spawnSync('bash', ['-c', `set -euo pipefail\nREPO_ROOT="$1"\nWORKSPACE="$1"\nlog() { :; }\ndie() { echo "$*" >&2; exit 1; }\nstate_dir() { echo "$WORKSPACE/.workspace/golden/$1"; }\n${killTree}\n${stopped}\n${body}`, '--', root], { encoding: 'utf8' });
  return { root, sd, run };
}
async function sleeper(t, cwd) {
  const child = spawn('sleep', ['60'], { cwd, stdio: 'ignore' });
  await new Promise((resolve, reject) => { child.once('spawn', resolve); child.once('error', reject); });
  t.after(() => child.kill());
  return child;
}
test('rebuild rejects live process before touching current runtime or evidence', async (t) => {
  const { root, sd, run } = fixture(t); const child = await sleeper(t, root);
  writeFileSync(join(sd, 'backend.pid'), String(child.pid));
  writeFileSync(join(sd, 'boot-run.jar'), 'old immutable artifact');
  const result = run('assert_stack_stopped session');
  assert.equal(result.status, 1); assert.match(result.stderr, /live backend/);
  assert.equal(readFileSync(join(sd, 'boot-run.jar'), 'utf8'), 'old immutable artifact');
  process.kill(child.pid, 0);
});
test('down rejects a foreign process and leaves it alive', async (t) => {
  const { sd, run } = fixture(t); const child = await sleeper(t, '/');
  writeFileSync(join(sd, 'frontend.pid'), String(child.pid));
  writeFileSync(join(sd, 'frontend.pid.started'), execFileSync('ps', ['-p', String(child.pid), '-o', 'lstart=']));
  const result = run('cmd_down session');
  assert.equal(result.status, 1); assert.match(result.stderr, /foreign frontend PID/);
  process.kill(child.pid, 0);
});
test('down rejects PID reuse before stopping even a process in the same checkout', async (t) => {
  const { root, sd, run } = fixture(t); const child = await sleeper(t, root);
  writeFileSync(join(sd, 'frontend.pid'), String(child.pid));
  writeFileSync(join(sd, 'frontend.pid.started'), 'different start time');
  const result = run('cmd_down session');
  assert.equal(result.status, 1); assert.match(result.stderr, /reused frontend PID/);
  process.kill(child.pid, 0);
});
test('gate retains runtime while logs and Playwright outputs use independent round evidence', () => {
  for (const script of ['oss-e2e-gate-run.sh', 'hifi-golden-gate-run.sh']) {
    const gate = readFileSync(new URL(script, import.meta.url), 'utf8');
    assert.doesNotMatch(gate, /"\$GS" destroy/);
    assert.match(gate, /keeping stack/);
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
