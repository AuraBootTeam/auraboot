import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtempSync, mkdirSync, writeFileSync, readdirSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const runner = fileURLToPath(new URL('./oss-backend-nightly-coverage.sh', import.meta.url));

function runFixture({ testExit = 0, verifyExit = 0, reportExit = 0 } = {}) {
  const root = mkdtempSync(path.join(tmpdir(), 'oss-nightly-contract-'));
  try {
    const bin = path.join(root, 'bin');
    mkdirSync(bin);
    writeFileSync(path.join(bin, 'java'), '#!/bin/sh\nexit 0\n', { mode: 0o755 });
    writeFileSync(path.join(bin, 'psql'), '#!/bin/sh\necho 1\n', { mode: 0o755 });
    const platform = path.join(root, 'platform');
    mkdirSync(path.join(platform, 'src/main/resources/db/migration/core'), { recursive: true });
    writeFileSync(path.join(platform, 'src/main/resources/db/migration/core/V1__fixture.sql'), '');
    writeFileSync(path.join(platform, 'gradlew'), `#!/bin/sh
echo "$*" >> calls.txt
case "$1" in
test) exit ${testExit} ;;
jacocoTestReport)
  mkdir -p build/reports/jacoco/test
  printf '%s' '<report><counter type="LINE" missed="1" covered="99"/></report>' > build/reports/jacoco/test/jacocoTestReport.xml
  exit ${reportExit} ;;
jacocoTestCoverageVerification) exit ${verifyExit} ;;
*) exit 77 ;;
esac
`, { mode: 0o755 });
    const result = spawnSync('bash', [runner, root], {
      env: { ...process.env, PATH: `${bin}:${process.env.PATH}` }, encoding: 'utf8',
    });
    const runs = path.join(root, '.workspace/nightly-coverage');
    const evidence = path.join(runs, readdirSync(runs)[0]);
    return { status: result.status, output: result.stdout + result.stderr,
      calls: readFileSync(path.join(platform, 'calls.txt'), 'utf8'),
      evidence: readdirSync(evidence), result: readFileSync(path.join(evidence, 'result.txt'), 'utf8') };
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
}

test('successful tests and coverage return success with measured counters', () => {
  const run = runFixture();
  assert.equal(run.status, 0, run.output);
  assert.match(run.result, /covered=99 missed=1 ratio=99\.00%/);
  assert.match(run.result, /test-exit=0/);
});

test('failed tests cannot be hidden by passing coverage; reporting still runs', () => {
  const run = runFixture({ testExit: 1 });
  assert.equal(run.status, 1, run.output);
  assert.match(run.result, /test-exit=1/);
  assert.match(run.calls, /jacocoTestReport -x test/);
  assert.match(run.calls, /jacocoTestCoverageVerification -x test/);
  assert.ok(run.evidence.includes('test.log'));
});

test('coverage failure blocks success even when tests pass', () => {
  const run = runFixture({ verifyExit: 1 });
  assert.equal(run.status, 1, run.output);
  assert.match(run.result, /verify-exit=1/);
});

test('report generation failure is an explicit infrastructure failure', () => {
  const run = runFixture({ reportExit: 1 });
  assert.equal(run.status, 2, run.output);
  assert.match(run.result, /jacocoTestReport failed/);
  assert.doesNotMatch(run.calls, /jacocoTestCoverageVerification/);
});
