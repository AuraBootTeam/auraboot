import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
import { test } from 'node:test';
import { verifyArtifact, verifyBinding, backendSourceArtifact } from './oss-stack-identity.mjs';

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

for (const suffix of ['', '-base']) {
  test(`backend source proof uses the selected app artifact ${suffix || 'full'} instead of the legacy jar`, () => {
    const root = mkdtempSync(join(tmpdir(), 'oss-selected-backend-'));
    try {
      writeFileSync(join(root, 'VERSION'), '1.2.3-test\n');
      mkdirSync(join(root, 'platform/platform-app/build/libs'), { recursive: true });
      mkdirSync(join(root, 'platform/build/libs'), { recursive: true });
      const name = `AuraBoot-1.2.3-test${suffix}-boot.jar`;
      const source = join(root, 'platform/platform-app/build/libs', name);
      const staged = join(root, 'staged', name);
      mkdirSync(join(root, 'staged'));
      writeFileSync(source, suffix || 'full');
      writeFileSync(staged, suffix || 'full');
      writeFileSync(join(root, 'platform/build/libs/AuraBoot-1.0.0-boot.jar'), 'stale legacy full');
      assert.equal(backendSourceArtifact(root, staged), source);
      const sha256 = createHash('sha256').update(suffix || 'full').digest('hex');
      const row = { source: backendSourceArtifact(root, staged), staged, sha256 };
      const sources = [{ key: 'core', actual: { root } }];
      assert.equal(verifyArtifact(row, sources).sourceKey, 'core');
      writeFileSync(source, 'changed selected variant');
      assert.throws(() => verifyArtifact(row, sources), /source artifact changed/);
    } finally { rmSync(root, { recursive: true, force: true }); }
  });
}

test('backend source proof rejects undeclared names and versions', () => {
  const root = mkdtempSync(join(tmpdir(), 'oss-undeclared-backend-'));
  try {
    writeFileSync(join(root, 'VERSION'), '1.2.3\n');
    for (const name of ['other.jar', 'AuraBoot-1.0.0-boot.jar', 'AuraBoot-1.2.3-agent-boot.jar']) {
      assert.throws(() => backendSourceArtifact(root, join(root, name)), /selected backend artifact/);
    }
  } finally { rmSync(root, { recursive: true, force: true }); }
});
