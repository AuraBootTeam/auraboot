import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, chmodSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const sampler = fileURLToPath(new URL('./ci/sample-open-platform-db.sh', import.meta.url));
function fixture(exitCode) {
  const root = mkdtempSync(path.join(tmpdir(), 'open-platform-db-sampling-'));
  const stop = path.join(root, 'stop');
  const docker = path.join(root, 'docker');
  writeFileSync(docker, `#!/usr/bin/env bash
set -eu
printf '%s\\n' '{"waits":[{"wait_event_type":"Lock","sessions":2,"blocked":1}]}'
touch "$SAMPLER_STOP"
exit ${exitCode}
`);
  chmodSync(docker, 0o755);
  return { root, stop, env: { ...process.env, PATH: root + path.delimiter + process.env.PATH, SAMPLER_STOP: stop } };
}

test('collects a sample and stops cleanly on the owned stop marker', () => {
  const f = fixture(0);
  const result = spawnSync('bash', [sampler, 'job-owned-pg', f.stop], { env: f.env, encoding: 'utf8', timeout: 10000 });
  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(JSON.parse(result.stdout).waits, [{ wait_event_type: 'Lock', sessions: 2, blocked: 1 }]);
});

test('database collection failure stays nonzero even when the stop marker is written', () => {
  const f = fixture(7);
  const result = spawnSync('bash', [sampler, 'job-owned-pg', f.stop], { env: f.env, encoding: 'utf8', timeout: 10000 });
  assert.equal(result.status, 7, result.stderr);
});

test('an already stopped sampler makes no database calls', () => {
  const f = fixture(7);
  writeFileSync(f.stop, '');
  const result = spawnSync('bash', [sampler, 'job-owned-pg', f.stop], { env: f.env, encoding: 'utf8', timeout: 10000 });
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.stdout, '');
});
