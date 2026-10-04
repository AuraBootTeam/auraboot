import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync, copyFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';

function run(t, args = [], failures = {}) {
  const root = mkdtempSync(join(tmpdir(), 'rbac-runner-contract-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  mkdirSync(join(root, 'scripts'));
  mkdirSync(join(root, 'web-admin'));
  mkdirSync(join(root, 'bin'));
  copyFileSync(process.env.RBAC_RUNNER_UNDER_TEST ?? new URL('./rbac-golden-run.sh', import.meta.url), join(root, 'scripts/rbac-golden-run.sh'));
  writeFileSync(join(root, 'scripts/oss-golden-stack.sh'), `#!/usr/bin/env bash
printf '%s\\n' "$*" >> "$CALLS"
case "$1" in
  up) exit "${failures.up ?? 0}";;
  import) exit "${failures.import ?? 0}";;
  env)
    [[ "${failures.env ?? 0}" == 0 ]] || exit "${failures.env ?? 0}"
    echo 'export PLAYWRIGHT_BASE_URL=http://127.0.0.1:5171'
    echo 'export BACKEND_URL=http://127.0.0.1:6471'
    echo 'export PW_ARTIFACT_DIR=${root}/evidence/artifacts'
    echo 'export PW_REPORT_DIR=${root}/evidence/report'
    echo 'export PW_RESULTS_JSON=${root}/evidence/report/results.json'
    ;;
  *) exit 99;;
esac
`, { mode: 0o755 });
  // This hermetic runner contract spies on result-verifier invocation. The real
  // verifier's empty/skip/retry/role semantics are covered by rbac-golden-result.test.mjs.
  writeFileSync(join(root, 'scripts/rbac-golden-result.mjs'), `import fs from 'node:fs';
fs.appendFileSync(process.env.CALLS, 'verify\\n');
process.exit(${failures.verify ?? 0});
`);
  writeFileSync(join(root, 'bin/pnpm'), `#!/usr/bin/env bash
printf 'browser %s\\n' "$*" >> "$CALLS"
[[ "$PLAYWRIGHT_BASE_URL" == http://127.0.0.1:5171 ]] || exit 97
[[ "$BACKEND_URL" == http://127.0.0.1:6471 ]] || exit 98
exit "${failures.browser ?? 0}"
`, { mode: 0o755 });
  // A retry must fail immediately rather than making a test sleep for ten minutes.
  writeFileSync(join(root, 'bin/sleep'), '#!/usr/bin/env bash\necho forbidden-sleep >> "$CALLS"\nexit 96\n', { mode: 0o755 });
  const callsPath = join(root, 'calls');
  writeFileSync(callsPath, '');
  const result = spawnSync('bash', [join(root, 'scripts/rbac-golden-run.sh'), ...args], {
    env: { ...process.env, CALLS: callsPath, PATH: `${join(root, 'bin')}:${process.env.PATH}` },
    encoding: 'utf8', timeout: 5000,
  });
  return { ...result, calls: readFileSync(callsPath, 'utf8').trim().split('\n').filter(Boolean) };
}

test('successful run preserves runtime and forwards stable identity and repetitions', t => {
  const r = run(t, ['--name', 'owned-runtime', '--slot', '71', '--repeat', '2']);
  assert.equal(r.status, 0, r.stderr);
  assert.deepEqual(r.calls, [
    'up owned-runtime --slot 71 --ttl 2h --no-warm --runtime-mode development',
    'import owned-runtime', 'env owned-runtime',
    'browser exec playwright test tests/e2e/rbac/ --project=chromium --no-deps --repeat-each=2 --reporter=line,json',
    'verify',
  ]);
  assert.match(r.stdout, /retained stack 'owned-runtime'/);
});
for (const phase of ['up', 'import', 'env', 'browser', 'verify']) {
  test(`${phase} failure keeps exact exit code without retry or destructive cleanup`, t => {
    const r = run(t, [], { [phase]: 23 });
    assert.equal(r.status, 23, r.stderr);
    assert.equal(r.calls.length, ['up', 'import', 'env', 'browser', 'verify'].indexOf(phase) + 1);
    assert.ok(r.calls.every(c => !/destroy|close|forbidden-sleep/.test(c)));
    assert.doesNotMatch(r.stdout, /RBAC GOLDEN: PASS/);
  });
}
test('--keep remains compatible and verification mode is forwarded', t => {
  const r = run(t, ['--keep', '--runtime-mode', 'verification']);
  assert.equal(r.status, 0, r.stderr);
  assert.match(r.calls[0], /--runtime-mode verification$/);
  assert.ok(r.calls.every(c => !/destroy|close/.test(c)));
});
for (const args of [['--repeat', '0'], ['--slot', '-1'], ['--name', '../foreign'], ['--runtime-mode', 'override']]) {
  test(`invalid arguments fail before runtime operations: ${args.join(' ')}`, t => {
    const r = run(t, args);
    assert.equal(r.status, 2);
    assert.deepEqual(r.calls, []);
  });
}
test('help has no runtime side effects', t => {
  const r = run(t, ['--help']);
  assert.equal(r.status, 0);
  assert.deepEqual(r.calls, []);
  assert.match(r.stdout, /retained/);
});
