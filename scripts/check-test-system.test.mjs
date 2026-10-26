import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

// Exercise the actual orchestrator; only its independent gate processes are stubs.
function runGate(failedGate = '') {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'aura-test-system-contract-'));
  try {
    fs.mkdirSync(path.join(root, 'scripts'));
    fs.mkdirSync(path.join(root, 'bin'));
    fs.copyFileSync(new URL('./check-test-system.sh', import.meta.url),
      path.join(root, 'scripts/check-test-system.sh'));
    const log = path.join(root, 'calls.log');
    fs.writeFileSync(path.join(root, 'bin/node'), [
      '#!/bin/sh',
      'printf "%s\\n" "$*" >> "$CALL_LOG"',
      'if [ -n "$FAIL_GATE" ]; then',
      '  case "$*" in *"$FAIL_GATE"*) exit 9 ;; esac',
      'fi',
      'exit 0',
      '',
    ].join('\n'), { mode: 0o755 });
    const result = spawnSync('bash', ['scripts/check-test-system.sh'], {
      cwd: root, encoding: 'utf8',
      env: { ...process.env, PATH: path.join(root, 'bin') + path.delimiter + process.env.PATH,
        CALL_LOG: log, FAIL_GATE: failedGate },
    });
    return { ...result, calls: fs.readFileSync(log, 'utf8').trim().split('\n') };
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
}

test('all successful child gates produce exit zero', () => {
  const result = runGate();
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /\[test-system\] PASS/);
  assert.equal(result.calls.length, 7);
});

for (const failure of ['--test', 'check-command-reachability.mjs', 'check-scripts-index.mjs']) {
  test('failure in ' + failure + ' remains nonzero after subsequent successful gates', () => {
    const result = runGate(failure);
    assert.equal(result.status, 1, result.stderr);
    assert.match(result.stdout, /\[test-system\] FAIL/);
    assert.doesNotMatch(result.stdout, /\[test-system\] PASS/);
    assert.equal(result.calls.length, 7, 'The umbrella must still run the whole gate inventory');
    assert.ok(result.calls.some(call => call.includes('check-scripts-index.mjs')));
  });
}
