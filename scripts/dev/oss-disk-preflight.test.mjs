import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { assertDiskSpace, MIN_FREE_BYTES } from './oss-disk-preflight.mjs';

test('disk preflight rejects insufficient allocatable space, including reserved blocks', () => {
  assert.throws(() => assertDiskSpace({ bavail: 0n, bfree: MIN_FREE_BYTES, bsize: 1n }), /environment-invalid/);
  assert.throws(() => assertDiskSpace({ bavail: MIN_FREE_BYTES - 1n, bsize: 1n }), /requires at least 1024 MiB/);
  assert.equal(assertDiskSpace({ bavail: MIN_FREE_BYTES, bsize: 1n }), MIN_FREE_BYTES);
});

test('unreadable volume exits with environment-invalid status', () => {
  const result = spawnSync(process.execPath, [new URL('./oss-disk-preflight.mjs', import.meta.url).pathname, '/nonexistent-oss-disk-fixture'], { encoding: 'utf8' });
  assert.equal(result.status, 2);
  assert.match(result.stderr, /ENOENT/);
});

test('gate checks disk before slot lookup or runtime mutation', () => {
  const source = readFileSync(new URL('../oss-e2e-gate-run.sh', import.meta.url), 'utf8');
  const check = source.indexOf('node "$SCRIPT_DIR/dev/oss-disk-preflight.mjs"');
  assert.ok(check > 0);
  assert.ok(check < source.indexOf('registered_slot="'));
  assert.ok(check < source.indexOf('STACK_ATTEMPTED=1'));
});
