import assert from 'node:assert/strict';
import test from 'node:test';
import { parseOptions, runGate } from './run-migrator-image-gate.mjs';

test('gate requires an explicit immutable source identity and bounded job namespace', () => {
  const valid = ['--repo-root', '/repo', '--expected-sha', 'a'.repeat(40), '--artifacts', '/evidence', '--job', 'migration-123'];
  assert.equal(parseOptions(valid)['--job'], 'migration-123');
  for (const args of [[], [...valid, '--job', 'other'], [...valid, '--keep', '1'],
    valid.map((v) => v === 'a'.repeat(40) ? 'main' : v),
    valid.map((v) => v === 'migration-123' ? '../other' : v)]) {
    assert.throws(() => parseOptions(args));
  }
});

test('unsupported hosts fail before accessing a checkout or Docker', { skip: process.platform === 'linux' && process.arch === 'x64' }, async () => {
  await assert.rejects(runGate({}), /requires Linux x64/);
});
