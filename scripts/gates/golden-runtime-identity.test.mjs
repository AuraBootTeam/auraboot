import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

// Hermetic public-CLI orchestration; native PID/source verification remains separate.
const helper = fileURLToPath(new URL('../lib/golden-runtime-identity.sh', import.meta.url));
function fixture(t, mode = '') {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'golden-identity-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const repo = path.join(root, 'repo');
  for (const dir of ['platform', 'web-admin']) fs.mkdirSync(path.join(repo, dir), { recursive: true });
  fs.mkdirSync(path.join(root, 'bin'));
  fs.mkdirSync(path.join(root, 'foreign'));
  const calls = path.join(root, 'calls'); fs.writeFileSync(calls, '');
  const artifact = path.join(root, 'registered.jar'); fs.writeFileSync(artifact, 'fixture');
  const script = (file, text) => fs.writeFileSync(file, '#!/usr/bin/env bash\n' + text, { mode: 0o755 });
  script(path.join(root, 'aura'), `printf '%s\\n' "$*" >> "$CALLS"
[[ "$MODE:$1:$2" != bind-fail:runtime:migrate ]] || exit 2
[[ "$MODE:$1:$2:$3" != stage-fail:runtime:artifact:stage ]] || exit 2
if [[ "$1:$2:$3" == runtime:artifact:path ]]; then
  [[ "$MODE" != path-fail ]] || exit 2
  printf '%s\\n' "$ARTIFACT"
fi
[[ "$MODE:$1:$2:$3" != register-fail:runtime:process:register ]] || exit 2
`);
  script(path.join(root, 'bin/lsof'), `if [[ "$*" == *'-d cwd'* ]]; then
  [[ "$MODE" != wrong-cwd ]] || { printf 'n%s\\n' "$FOREIGN_CWD"; exit 0; }
  printf 'n%s\\n' "$EXPECTED_CWD"
else
  [[ "$MODE" != no-listener ]] || exit 1
  printf '1000000001\\n'
  [[ "$MODE" != two-listeners ]] || printf '1000000003\\n'
fi
`);
  script(path.join(root, 'bin/ps'), `[[ "$MODE" != wrong-ancestor ]] || { printf '1\\n'; exit 0; }
printf '1000000002\\n'
`);
  const run = (command, extraEnv = {}) => {
    const r = spawnSync('bash', ['-c', 'set -euo pipefail\nsource "$HELPER"\n' + command], {
      env: { ...process.env, PATH: path.join(root, 'bin') + ':' + process.env.PATH,
        HELPER: helper, DEV: path.join(root, 'aura'), CALLS: calls, MODE: mode,
        FOREIGN_CWD: path.join(root, 'foreign'),
        ARTIFACT: mode === 'missing-artifact' ? path.join(root, 'missing.jar') : artifact,
        REPO: repo, WORKSPACE: root, EXPECTED_CWD: path.join(repo, 'web-admin'), ...extraEnv }, encoding: 'utf8',
    });
    return { ...r, calls: fs.readFileSync(calls, 'utf8') };
  };
  return { run, root, repo, artifact };
}

test('binds explicit product and Workspace roots through the canonical source contract', t => {
  const f = fixture(t); const r = f.run('golden_runtime_bind_sources owned "$REPO" "$WORKSPACE"');
  assert.equal(r.status, 0, r.stderr);
  assert.equal(r.calls.trim(), `runtime migrate owned --source auraboot=${f.repo} --source workspace=${f.root}`);
});
test('stages and resolves the immutable backend rather than running the build output', t => {
  const f = fixture(t); const r = f.run('golden_runtime_stage_backend owned "$REPO/platform/build.jar"');
  assert.equal(r.status, 0, r.stderr); assert.equal(r.stdout.trim(), f.artifact);
  assert.match(r.calls, /artifact stage owned --key backend --source auraboot --file/);
  assert.match(r.calls, /artifact path owned backend/);
});
for (const [mode, command] of [
  ['bind-fail', 'golden_runtime_bind_sources owned "$REPO" "$WORKSPACE"'],
  ['stage-fail', 'golden_runtime_stage_backend owned "$REPO/platform/build.jar"'],
  ['path-fail', 'golden_runtime_stage_backend owned "$REPO/platform/build.jar"'],
  ['missing-artifact', 'golden_runtime_stage_backend owned "$REPO/platform/build.jar"'],
]) test(`fails closed on ${mode} without using a private fallback`, t => {
  const r = fixture(t, mode).run(command); assert.notEqual(r.status, 0);
  if (mode === 'stage-fail') assert.doesNotMatch(r.calls, /artifact path/);
});
const register = 'golden_runtime_register_listener owned web 5311 1000000002 "$REPO/web-admin" fixture-token';
test('registers a unique child listener only after verifying cwd and ancestry', t => {
  const r = fixture(t).run(register); assert.equal(r.status, 0, r.stderr);
  assert.equal(r.calls.trim(), 'runtime process register owned --key web --pid 1000000001 --token fixture-token');
});
for (const mode of ['no-listener', 'two-listeners', 'wrong-cwd', 'wrong-ancestor']) {
  test(`refuses ${mode} before mutating the process registry`, t => {
    const r = fixture(t, mode).run(register); assert.notEqual(r.status, 0); assert.equal(r.calls, '');
  });
}
test('does not suppress a failed Workspace process registration', t => {
  assert.notEqual(fixture(t, 'register-fail').run(register).status, 0);
});

// The frozen workspace dependency is an opt-in source identity: it must be a clean git
// checkout that is not the moving control root, or the launcher refuses before any
// registration mutation.
function gitFixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'golden-workspace-dep-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const repo = path.join(root, 'dep');
  fs.mkdirSync(repo, { recursive: true });
  const git = (args) => spawnSync('git', ['-C', repo, ...args], { encoding: 'utf8' });
  git(['init', '--quiet']);
  fs.writeFileSync(path.join(repo, 'frozen.txt'), 'frozen\n');
  git(['add', 'frozen.txt']);
  git(['-c', 'user.email=fixture@auraboot', '-c', 'user.name=fixture', 'commit', '--quiet', '-m', 'frozen dependency']);
  return { root, repo };
}
test('resolves a clean frozen dependency checkout outside the control root', t => {
  const f = fixture(t); const g = gitFixture(t);
  const r = f.run(`golden_workspace_dependency_root "$DEP" "$CONTROL"`, { DEP: g.repo, CONTROL: f.root });
  assert.equal(r.status, 0, r.stderr);
  assert.equal(r.stdout.trim(), fs.realpathSync(g.repo));
});
for (const [name, prepare] of [
  ['the control root itself', null],
  ['a dirty checkout', (g) => fs.writeFileSync(path.join(g.repo, 'dirty.txt'), 'drift\n')],
  ['a non-git directory', (g) => { fs.rmSync(path.join(g.repo, '.git'), { recursive: true, force: true }); }],
]) test(`refuses ${name} before any binding`, t => {
  const f = fixture(t); const g = gitFixture(t);
  prepare?.(g);
  const dep = name === 'the control root itself' ? f.root : g.repo;
  const r = f.run(`golden_workspace_dependency_root "$DEP" "$CONTROL"`, { DEP: dep, CONTROL: f.root });
  assert.notEqual(r.status, 0);
});
test('refuses a missing dependency path', t => {
  const f = fixture(t);
  const r = f.run('golden_workspace_dependency_root "$DEP" "$CONTROL"', { DEP: path.join(f.root, 'absent'), CONTROL: f.root });
  assert.notEqual(r.status, 0);
});
