import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

// Shell orchestration with hermetic dependencies; no native/browser claim.
function fixture(t, mode = '') {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'oss-gate-lifecycle-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const repo = path.join(root, 'oss');
  fs.mkdirSync(path.join(repo, 'scripts/gates'), { recursive: true });
  for (const file of ['oss-e2e-gate-profile.json', 'hifi-golden-results.mjs']) {
    fs.copyFileSync(new URL(file, import.meta.url), path.join(repo, 'scripts/gates', file));
  }
  fs.mkdirSync(path.join(repo, 'web-admin'));
  fs.mkdirSync(path.join(root, 'bin'));
  fs.copyFileSync(new URL('../oss-e2e-gate-run.sh', import.meta.url), path.join(repo, 'scripts/oss-e2e-gate-run.sh'));
  const calls = path.join(root, 'calls');
  fs.writeFileSync(calls, '');
  const script = (file, body) => fs.writeFileSync(file, '#!/usr/bin/env bash\n' + body, { mode: 0o755 });
  script(path.join(root, 'aura'), `printf 'aura %s\\n' "$*" >> "$CALLS"
if [[ "$*" == 'runtime list' ]]; then
  [[ "$MODE" != list-failure ]] || exit 1
  printf 'NAME MODE SLOT\\n'
  [[ "$MODE" != name-collision ]] || printf 'owned-run verification 900\\n'
  [[ "$MODE" != slot-collision ]] || printf 'foreign verification 900\\n'
fi
[[ "$MODE:$1:$2" != verify-failure:runtime:verify ]]
`);
  fs.copyFileSync(path.join(root, 'aura'), path.join(root, 'dev.sh'));
  script(path.join(root, 'bin/lsof'), '[[ "$MODE" == port-collision ]]\n');
  script(path.join(repo, 'scripts/oss-golden-stack.sh'), `printf 'stack %s\\n' "$*" >> "$CALLS"
[[ "$MODE:$1" != up-failure:up ]] || exit 1
if [[ "$1" == env ]]; then printf 'export AURA_EVIDENCE_ROOT=%q PLAYWRIGHT_BASE_URL=http://localhost:1 BACKEND_URL=http://localhost:2 BFF_PORT=3\\n' "$EVIDENCE"; fi
`);
  fs.writeFileSync(path.join(root, 'bin/pnpm'), `#!/usr/bin/env node
const fs=require('node:fs');
const args=process.argv.slice(2);
fs.appendFileSync(process.env.CALLS,'pnpm '+args.join(' ')+'\\n');
const collection=args.includes('--list');
if(!collection && process.env.MODE==='test-failure')process.exit(1);
if(!collection && process.env.MODE==='signal'){process.kill(process.ppid,'SIGTERM');process.exit(0);}
const profile=JSON.parse(fs.readFileSync('../scripts/gates/oss-e2e-gate-profile.json','utf8'));
const report={errors:[],suites:[{specs:profile.tests.map(t=>({file:t.file,title:t.title,tests:[{
projectName:t.project,expectedStatus:'passed',status:'expected',results:collection?[]:[{status:'passed',retry:0}]}]}))}]};
if(process.env.MODE==='zero-tests')report.suites[0].specs=[];
if(!collection && process.env.MODE==='missing-result')report.suites[0].specs.pop();
if(!collection && process.env.MODE==='skipped-result')report.suites[0].specs[0].tests[0].results[0].status='skipped';
if(!collection && process.env.MODE==='retried-result')report.suites[0].specs[0].tests[0].results.push({status:'passed',retry:1});
if(collection)console.log(JSON.stringify(report));else{if(process.env.PLAYWRIGHT_JSON_OUTPUT_FILE)fs.writeFileSync(process.env.PLAYWRIGHT_JSON_OUTPUT_FILE,JSON.stringify(report));console.log('12 passed');}
`, { mode: 0o755 });
  const env = { ...process.env, AURA_WORKSPACE_ROOT: root, AURA_WORKSPACE_STATE_DIR: path.join(root, 'state'),
    AURA_CI_JOB_ID: 'test-job', AURA_REGRESSION_SLOT: '900', MODE: mode, CALLS: calls,
    EVIDENCE: path.join(root, 'evidence'), PATH: path.join(root, 'bin') + path.delimiter + process.env.PATH };
  return { root, repo, calls, env, run: (args = ['--name', 'owned-run']) => spawnSync('bash',
    [path.join(repo, 'scripts/oss-e2e-gate-run.sh'), ...args], { env, encoding: 'utf8', timeout: 10000 }) };
}
for (const mode of ['name-collision', 'slot-collision', 'port-collision', 'list-failure']) {
  test(`refuses ${mode} before stack writes`, t => {
    const f = fixture(t, mode); const r = f.run();
    assert.equal(r.status, 2, r.stdout + r.stderr);
    assert.doesNotMatch(fs.readFileSync(f.calls, 'utf8'), /stack |pnpm /);
  });
}
test('preexisting shared state survives without subprocess writes', t => {
  const f = fixture(t);
  fs.mkdirSync(path.join(f.env.AURA_WORKSPACE_STATE_DIR, 'env'), { recursive: true });
  const file = path.join(f.env.AURA_WORKSPACE_STATE_DIR, 'env/owned-run.env');
  fs.writeFileSync(file, 'foreign');
  assert.equal(f.run().status, 2);
  assert.equal(fs.readFileSync(file, 'utf8'), 'foreign');
  assert.equal(fs.readFileSync(f.calls, 'utf8'), '');
});
for (const [mode, status] of [['', 0], ['up-failure', 2], ['verify-failure', 2], ['test-failure', 1], ['signal', 143]]) {
  test(`retains runtime and propagates ${mode || 'success'}`, t => {
    const f = fixture(t, mode); const r = f.run();
    assert.equal(r.status, status, r.stdout + r.stderr);
    assert.match(r.stdout, /retained runtime/);
    const calls = fs.readFileSync(f.calls, 'utf8');
    assert.doesNotMatch(calls, /destroy|down|stop|--fresh-db/);
    assert.match(calls, /stack up owned-run --slot 900 .*--require-new-db/);
    if (mode === 'up-failure' || mode === 'verify-failure') assert.doesNotMatch(calls, /pnpm /);
    if (status === 0) {
      assert.match(calls, /aura runtime verify owned-run/);
      const execution = calls.split('\n').find(line => line.startsWith('pnpm ') && !line.includes('--list'));
      const paths = execution.match(/tests\/e2e\/[^\s]+/g);
      assert.deepEqual(paths, ['tests/e2e/page-designer/form-buttons-refresh-runtime.spec.ts',
        'tests/e2e/showcase/runtime-rendering-e2e.spec.ts', 'tests/e2e/saved-view/saved-view-gantt.spec.ts',
        'tests/e2e/saved-view/saved-view-kanban.spec.ts']);
    }
  });
}
test('default name comes from CI job and uses its slot lease', t => {
  const f = fixture(t); const r = f.run([]);
  assert.equal(r.status, 0, r.stdout + r.stderr);
  assert.match(fs.readFileSync(f.calls, 'utf8'), /stack up oss-e2e-test-job --slot 900 /);
});
for (const [mode, success, queries] of [['absent', true, 2], ['existing', false, 1], ['probe-failure', false, 1], ['race', false, 2]]) {
  test(`new-database creation fails closed: ${mode}`, t => {
    const f = fixture(t, mode);
    const psql = path.join(f.root, 'bin/psql');
    fs.writeFileSync(psql, `#!/usr/bin/env bash
sql="$(cat)"
printf '%s\\n' "$sql" >> "$CALLS"
if [[ "$sql" == *'pg_database'* ]]; then
  [[ "$MODE" != probe-failure ]] || exit 1
  if [[ "$MODE" == existing ]]; then echo 1; else echo 0; fi
else
  [[ "$MODE" != race ]] || exit 1
fi
`, { mode: 0o755 });
    const helper = new URL('../lib/golden-new-database.sh', import.meta.url).pathname;
    const r = spawnSync('bash', ['-c', 'source "$1"; golden_create_new_database localhost 5432 owner secret owned_db', 'fixture', helper], {
      env: f.env, encoding: 'utf8', timeout: 10000 });
    assert.equal(r.status === 0, success, r.stdout + r.stderr);
    const calls = fs.readFileSync(f.calls, 'utf8');
    assert.equal(calls.trim().split('\n').length, queries);
    assert.doesNotMatch(calls, /DROP|DELETE|secret/);
    if (queries === 2) assert.match(calls, /CREATE DATABASE %I/);
  });
}
test('real stack launcher rejects an existing database before infra or schema writes', t => {
  const f = fixture(t);
  fs.copyFileSync(new URL('../oss-golden-stack.sh', import.meta.url), path.join(f.repo, 'scripts/oss-golden-stack.sh'));
  fs.mkdirSync(path.join(f.repo, 'scripts/lib'));
  for (const file of ['web-admin-node-modules.sh', 'golden-new-database.sh']) {
    fs.copyFileSync(new URL('../lib/' + file, import.meta.url), path.join(f.repo, 'scripts/lib', file));
  }
  const state = f.env.AURA_WORKSPACE_STATE_DIR;
  fs.mkdirSync(path.join(state, 'env'), { recursive: true });
  fs.writeFileSync(path.join(f.root, 'dev.sh'), `#!/usr/bin/env bash
printf 'dev %s\\n' "$*" >> "$CALLS"
if [[ "$*" == runtime ]]; then echo 'runtime ensure'; fi
if [[ "$1:$2" == runtime:ensure ]]; then
  cat > "$AURA_WORKSPACE_STATE_DIR/env/owned-run.env" <<'ENV'
SERVER_PORT=7300
VITE_PORT=6000
BFF_PORT=7000
POSTGRES_DB=owned_db
REDIS_DATABASE=0
POSTGRES_HOST=localhost
POSTGRES_PORT=5432
POSTGRES_USER=owner
POSTGRES_PASSWORD=fixture
ENV
fi
`, { mode: 0o755 });
  fs.writeFileSync(path.join(f.root, 'bin/psql'), '#!/usr/bin/env bash\ncat >> "$CALLS"\necho 1\n', { mode: 0o755 });
  const result = spawnSync('bash', [path.join(f.repo, 'scripts/oss-golden-stack.sh'), 'up', 'owned-run', '--slot', '900', '--require-new-db'], {
    env: f.env, encoding: 'utf8', timeout: 10000 });
  assert.equal(result.status, 1, result.stdout + result.stderr);
  assert.match(result.stderr, /database freshness could not be established/);
  const calls = fs.readFileSync(f.calls, 'utf8');
  assert.match(calls, /runtime ensure/);
  assert.doesNotMatch(calls, /infra ensure|CREATE|DROP|schema-current/);
  assert.ok(fs.existsSync(path.join(state, 'env/owned-run.env')), 'failed allocation must remain inspectable');
  assert.equal(fs.existsSync(path.join(state, 'golden/owned-run/pgenv')), false);
});
for (const mode of ['zero-tests', 'missing-result', 'skipped-result', 'retried-result']) {
  test(`successful Playwright exit cannot hide ${mode}`, t => {
    const f = fixture(t, mode); const result = f.run();
    assert.equal(result.status, 1, result.stdout + result.stderr);
    assert.doesNotMatch(result.stdout, /OSS E2E GATE: PASS/);
    assert.doesNotMatch(fs.readFileSync(f.calls, 'utf8'), /destroy|down|stop/);
  });
}
