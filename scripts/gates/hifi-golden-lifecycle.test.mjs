import { registerFixtureWorkspace } from './fixtures/workspace-control.mjs';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

// Hermetic orchestration contract only: fake processes never start a real stack.
function fixture(t, mode = '') {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'hifi-lifecycle-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const repo = path.join(root, 'oss');
  fs.mkdirSync(path.join(repo, 'scripts/gates'), { recursive: true });
  fs.mkdirSync(path.join(repo, 'web-admin'));
  fs.mkdirSync(path.join(root, 'bin'));
  fs.copyFileSync(new URL('../hifi-golden-gate-run.sh', import.meta.url), path.join(repo, 'scripts/hifi-golden-gate-run.sh'));
  for (const name of ['hifi-golden-profile.json', 'hifi-golden-results.mjs']) {
    fs.copyFileSync(new URL(name, import.meta.url), path.join(repo, 'scripts/gates', name));
  }
  const executable = (file, source) => fs.writeFileSync(file, source, { mode: 0o755 });
  executable(path.join(root, 'bin/lsof'), '#!/usr/bin/env bash\nexit 1\n');
  executable(path.join(root, 'bin/pdftotext'), '#!/usr/bin/env bash\nexit 0\n');
  executable(path.join(root, 'aura'), `#!/usr/bin/env bash
printf 'aura %s\\n' "$*" >> "$CALLS"
if [[ "$*" == 'runtime list' ]]; then printf 'NAME MODE SLOT\\n'; fi
`);
  executable(path.join(repo, 'scripts/oss-golden-stack.sh'), `#!/usr/bin/env bash
printf 'stack %s\\n' "$*" >> "$CALLS"
if [[ "$1" == up && "$FAKE_MODE" == up-failure ]]; then exit 2; fi
if [[ "$1" == env ]]; then
  printf 'export PLAYWRIGHT_BASE_URL=http://localhost:1 BACKEND_URL=http://localhost:2\\n'
  if [[ "$FAKE_MODE" == evidence-round || "$FAKE_MODE" == evidence-collision ]]; then
    round="$AURA_WORKSPACE_ROOT/.workspace/evidence/owned-run/rounds/native-round"
    mkdir -p "$round"
    [[ "$FAKE_MODE" != evidence-collision ]] || printf 'retain old bytes' >"$round/collection.json"
    printf 'export AURA_EVIDENCE_ROOT=%q\\n' "$round"
  fi
fi
`);
  executable(path.join(root, 'bin/pnpm'), `#!/usr/bin/env node
const fs=require('node:fs');
fs.appendFileSync(process.env.CALLS,'pnpm '+process.argv.slice(2).join(' ')+'\\n');
const collection=process.argv.includes('--list');
const profile=JSON.parse(fs.readFileSync('../scripts/gates/hifi-golden-profile.json'));
const report={errors:[],suites:[{specs:profile.tests.map(t=>({file:t.file,title:t.title,
tests:[{projectName:t.project,expectedStatus:'passed',status:'expected',results:collection?[]:[{status:'passed',retry:0}]}]}))}]};
if(!collection && process.env.FAKE_MODE==='missing-result')report.suites[0].specs.pop();
if(collection)console.log(JSON.stringify(report));else fs.writeFileSync(process.env.PLAYWRIGHT_JSON_OUTPUT_FILE,JSON.stringify(report));
`);
  const calls = path.join(root, 'calls.log');
  fs.writeFileSync(calls, '');
  registerFixtureWorkspace(root, repo);
  return { root, repo, calls, run: (extraEnv = {}, slotArgs = ['--slot', '900']) => spawnSync('bash', [path.join(repo, 'scripts/hifi-golden-gate-run.sh'), '--name', 'owned-run', ...slotArgs], {
    encoding: 'utf8', timeout: 10000,
    env: { ...process.env, AURA_WORKSPACE_ROOT: root, CALLS: calls, FAKE_MODE: mode,
      PATH: path.join(root, 'bin') + path.delimiter + process.env.PATH, ...extraEnv },
  }) };
}

test('existing runtime is rejected before any subprocess mutates it', t => {
  const f = fixture(t);
  fs.mkdirSync(path.join(f.root, '.workspace/env'), { recursive: true });
  fs.writeFileSync(path.join(f.root, '.workspace/env/owned-run.env'), 'foreign');
  assert.equal(f.run().status, 2);
  assert.equal(fs.readFileSync(f.calls, 'utf8'), '');
  assert.equal(fs.readFileSync(path.join(f.root, '.workspace/env/owned-run.env'), 'utf8'), 'foreign');
});
test('shared CI state rejects an existing runtime before collection or allocation', t => {
  const f = fixture(t);
  const state = path.join(f.root, 'shared-state');
  fs.mkdirSync(path.join(state, 'env'), { recursive: true });
  fs.writeFileSync(path.join(state, 'env/owned-run.env'), 'foreign');
  const result = f.run({ AURA_WORKSPACE_STATE_DIR: state });
  assert.equal(result.status, 2, result.stdout + result.stderr);
  assert.equal(fs.readFileSync(f.calls, 'utf8'), '');
  assert.equal(fs.readFileSync(path.join(state, 'env/owned-run.env'), 'utf8'), 'foreign');
});
test('legacy CI sibling checkouts locate the frozen workspace CLI', t => {
  const f = fixture(t);
  const workspace = path.join(f.root, 'auraboot-workspace');
  fs.mkdirSync(workspace);
  fs.copyFileSync(path.join(f.root, 'aura'), path.join(workspace, 'aura'));
  registerFixtureWorkspace(workspace, f.repo);
  const result = f.run({ AURA_WORKSPACE_ROOT: '', AURA_CI_WORKSPACE_ROOT: '' });
  assert.equal(result.status, 0, result.stdout + result.stderr);
  assert.equal(JSON.parse(fs.readFileSync(path.join(workspace, '.workspace/evidence/owned-run/execution-ledger.json'))).executed, 8);
});
test('CI execution uses the orchestrator lease instead of an independent slot', t => {
  const f = fixture(t);
  const result = f.run({ AURA_REGRESSION_SLOT: '214' }, []);
  assert.equal(result.status, 0, result.stdout + result.stderr);
  assert.match(fs.readFileSync(f.calls, 'utf8'), /stack up owned-run --slot 214 /);
  assert.doesNotMatch(fs.readFileSync(f.calls, 'utf8'), /--slot 73 /);
});
test('gate evidence and real golden-stack env use the same shared CI state', t => {
  const f = fixture(t);
  const state = path.join(f.root, 'shared-state');
  const result = f.run({ AURA_WORKSPACE_STATE_DIR: state });
  assert.equal(result.status, 0, result.stdout + result.stderr);
  const evidence = path.join(state, 'evidence/owned-run');
  assert.ok(fs.existsSync(path.join(evidence, 'execution-ledger.json')), 'gate must write its ledger into the shared runtime state');
  assert.equal(JSON.parse(fs.readFileSync(path.join(evidence, 'execution-ledger.json'))).executed, 8);
  assert.equal(fs.existsSync(path.join(f.root, '.workspace/evidence/owned-run')), false);
  fs.copyFileSync(new URL('../oss-golden-stack.sh', import.meta.url), path.join(f.repo, 'scripts/oss-golden-stack.sh'));
  fs.mkdirSync(path.join(f.repo, 'scripts/lib'), { recursive: true });
  fs.copyFileSync(new URL('../lib/web-admin-node-modules.sh', import.meta.url), path.join(f.repo, 'scripts/lib/web-admin-node-modules.sh'));
  fs.copyFileSync(new URL('../lib/golden-new-database.sh', import.meta.url), path.join(f.repo, 'scripts/lib/golden-new-database.sh'));
  fs.copyFileSync(new URL('../lib/golden-runtime-identity.sh', import.meta.url), path.join(f.repo, 'scripts/lib/golden-runtime-identity.sh'));
  fs.writeFileSync(path.join(f.root, 'dev.sh'), '#!/usr/bin/env bash\nexit 0\n', { mode: 0o755 });
  fs.mkdirSync(path.join(state, 'golden/owned-run'), { recursive: true });
  fs.mkdirSync(path.join(state, 'env'), { recursive: true });
  fs.writeFileSync(path.join(state, 'golden/owned-run/ports'), '7300 6000 7000\n');
  fs.writeFileSync(path.join(state, 'env/owned-run.env'), `AURA_EVIDENCE_ROOT=${evidence}\n`);
  const env = spawnSync('bash', [path.join(f.repo, 'scripts/oss-golden-stack.sh'), 'env', 'owned-run'], {
    encoding: 'utf8', timeout: 10000,
    env: { ...process.env, AURA_WORKSPACE_ROOT: f.root, AURA_WORKSPACE_STATE_DIR: state },
  });
  assert.equal(env.status, 0, env.stdout + env.stderr);
  assert.match(env.stdout, /PLAYWRIGHT_BASE_URL=http:\/\/127\.0\.0\.1:6000/);
  assert.ok(env.stdout.includes(`export AURA_EVIDENCE_ROOT=${evidence}`));
});
for (const [mode, status] of [['', 0], ['up-failure', 2], ['missing-result', 1]]) {
  test(`runtime retention and exact verdict: ${mode || 'success'}`, t => {
    const f = fixture(t, mode);
    const result = f.run();
    assert.equal(result.status, status, result.stdout + result.stderr);
    assert.match(result.stdout, /runtime retained/);
    const calls = fs.readFileSync(f.calls, 'utf8');
    assert.doesNotMatch(calls, /stack (down|destroy)|runtime (stop|destroy)/);
    assert.match(calls, /--list/);
    if (status === 0) {
      const ledger = JSON.parse(fs.readFileSync(path.join(f.root, '.workspace/evidence/owned-run/execution-ledger.json')));
      assert.equal(ledger.executed, 8);
      assert.equal(ledger.passed, 8);
    }
  });
}

test('native evidence round retains its matching collection and execution ledgers', t => {
  const f = fixture(t, 'evidence-round'), result = f.run();
  assert.equal(result.status, 0, result.stdout + result.stderr);
  const round = path.join(f.root, '.workspace/evidence/owned-run/rounds/native-round');
  for (const name of ['collection.json', 'collection.stderr.log', 'collection-ledger.json', 'runtime-verify.log', 'results.json', 'execution-ledger.json']) {
    assert.equal(fs.existsSync(path.join(round, name)), true, name);
  }
  assert.equal(JSON.parse(fs.readFileSync(path.join(round, 'collection-ledger.json'))).executed, 0);
  assert.equal(JSON.parse(fs.readFileSync(path.join(round, 'execution-ledger.json'))).passed, 8);
});
test('evidence migration refuses prior round bytes before browser execution', t => {
  const f = fixture(t, 'evidence-collision'), result = f.run();
  assert.equal(result.status, 2, result.stdout + result.stderr);
  const round = path.join(f.root, '.workspace/evidence/owned-run/rounds/native-round');
  assert.equal(fs.readFileSync(path.join(round, 'collection.json'), 'utf8'), 'retain old bytes');
  assert.equal(fs.existsSync(path.join(round, 'results.json')), false);
});
