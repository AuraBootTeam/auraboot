import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
import { test } from 'node:test';
import { verifyArtifact, verifyBinding } from './oss-stack-identity.mjs';

test('artifact proof rejects disappeared live files, changed staging and unowned sources', () => {
  const root = mkdtempSync(join(tmpdir(), 'oss-artifact-proof-'));
  try {
    mkdirSync(join(root, 'source'));
    const source = join(root, 'source/boot.jar');
    const staged = join(root, 'staged.jar');
    writeFileSync(source, 'verified-build'); writeFileSync(staged, 'verified-build');
    const sources = [{ key: 'core', actual: { root: join(root, 'source') } }];
    const row = { key: 'core', source, staged, sha256: createHash('sha256').update('verified-build').digest('hex') };
    assert.equal(verifyArtifact(row, sources).sourceKey, 'core');
    writeFileSync(staged, 'stale-build');
    assert.throws(() => verifyArtifact(row, sources), /staged artifact changed/);
    rmSync(staged);
    assert.throws(() => verifyArtifact(row, sources), /runtime artifact missing/);
    writeFileSync(staged, 'verified-build');
    assert.throws(() => verifyArtifact(row, []), /registered source owner/);
    writeFileSync(source, 'replacement-build');
    assert.throws(() => verifyArtifact(row, sources), /source artifact changed/);
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test('runtime proof rejects wrong checkout, frozen source drift and missing or ambiguous listeners', () => {
  const info = { runtime: 'owned', sources: [{ key: 'core', status: 'ok', actual: { root: '/owned/core' } }], environment: { POSTGRES_DB: 'owned_db' }, ports: Object.fromEntries(['backend', 'web', 'bff'].map((key, index) => [key, { status: 'listening', pids: [index + 1] }])) };
  verifyBinding(info, 'owned', '/owned/core');
  assert.throws(() => verifyBinding(info, 'foreign', '/owned/core'), /runtime identity mismatch/);
  assert.throws(() => verifyBinding(info, 'owned', '/foreign/core'), /wrong Core checkout/);
  const drift = structuredClone(info); drift.sources[0].status = 'drift';
  assert.throws(() => verifyBinding(drift, 'owned', '/owned/core'), /source identity drift/);
  const stopped = structuredClone(info); stopped.ports.backend.status = 'stopped';
  assert.throws(() => verifyBinding(stopped, 'owned', '/owned/core'), /listener missing/);
  const ambiguous = structuredClone(info); ambiguous.ports.web.pids.push(99);
  assert.throws(() => verifyBinding(ambiguous, 'owned', '/owned/core'), /ownership ambiguous/);
});
