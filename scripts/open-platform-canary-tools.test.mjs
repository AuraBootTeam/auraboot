import { spawnSync } from 'node:child_process';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';

const script = fileURLToPath(new URL('./ci/test_open_platform_canary_tools.py', import.meta.url));
for (const mutation of [false, true, false]) {
  const result = spawnSync('python3', [script, ...(mutation ? ['--mutation-accept-invalid-signature'] : [])],
    { encoding: 'utf8', timeout: 30000, env: { ...process.env, PYTHONDONTWRITEBYTECODE: '1' } });
  assert.equal(result.error, undefined, String(result.error));
  assert.match(result.stderr, /Ran 20 tests/);
  assert.equal(result.status, mutation ? 1 : 0, result.stderr);
  if (mutation) {
    assert.match(result.stderr, /FAIL: test_tampered_signature_rejected/);
    assert.match(result.stderr, /FAIL: test_parse_reserialize_does_not_match_signature/);
  }
  console.log(mutation ? 'receiver invalid-signature mutation: EXPECTED_RED' : 'canary tools self-tests: PASS (20)');
}

const runnerScript = fileURLToPath(new URL('./ci/test_open_platform_canary_runner.py', import.meta.url));
for (const mutation of [false, true, false]) {
  const result = spawnSync('python3', [runnerScript, ...(mutation ? ['--mutation-claim-complete'] : [])],
    { encoding: 'utf8', timeout: 30000, env: { ...process.env, PYTHONDONTWRITEBYTECODE: '1' } });
  assert.equal(result.error, undefined, String(result.error));
  assert.match(result.stderr, /Ran 8 tests/);
  assert.equal(result.status, mutation ? 1 : 0, result.stderr);
  if (mutation) {
    assert.match(result.stderr, /FAIL: test_denominator_and_partial_verdict_cannot_be_shrunk/);
    assert.match(result.stderr, /FAIL: test_failure_keeps_all_contracts_and_never_echoes_secret/);
  }
  console.log(mutation ? 'runner false-completion mutation: EXPECTED_RED' : 'canary runner safety self-tests: PASS (8)');
}
