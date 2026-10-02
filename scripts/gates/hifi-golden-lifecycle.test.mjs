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
  executable(path.join(root, 'aura'), `#!/usr/bin/env bash
printf 'aura %s\\n' "$*" >> "$CALLS"
if [[ "$*" == 'runtime list' ]]; then printf 'NAME MODE SLOT\\n'; fi
`);
  executable(path.join(repo, 'scripts/oss-golden-stack.sh'), `#!/usr/bin/env bash
printf 'stack %s\\n' "$*" >> "$CALLS"
if [[ "$1" == up && "$FAKE_MODE" == up-failure ]]; then exit 2; fi
if [[ "$1" == env ]]; then printf 'export PLAYWRIGHT_BASE_URL=http://localhost:1 BACKEND_URL=http://localhost:2\\n'; fi
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
  return { root, calls, run: () => spawnSync('bash', [path.join(repo, 'scripts/hifi-golden-gate-run.sh'), '--name', 'owned-run', '--slot', '900'], {
    encoding: 'utf8', timeout: 10000,
    env: { ...process.env, AURA_WORKSPACE_ROOT: root, CALLS: calls, FAKE_MODE: mode,
      PATH: path.join(root, 'bin') + path.delimiter + process.env.PATH },
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
