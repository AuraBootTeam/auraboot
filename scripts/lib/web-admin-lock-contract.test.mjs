import assert from 'node:assert/strict';
import { test } from 'node:test';
import { compareDependencyLocks, verifyDependencyCapsule } from './web-admin-lock-contract.mjs';

const expected = { lockfileVersion: '9.0', settings: { autoInstallPeers: true }, overrides: { sanitizer: '2' },
  importers: { '.': { devDependencies: { yaml: { version: '2' } } },
    'web-admin': { dependencies: { sanitizer: { specifier: '^2', version: '2.1' } } } },
  packages: { 'sanitizer@2.1': { resolution: { integrity: 'verified-integrity' } }, unrelated: {} },
  snapshots: { 'sanitizer@2.1': {}, unrelated: {} } };

test('filtered installs may omit unrelated workspace resolutions', () => {
  const installed = structuredClone(expected);
  delete installed.packages.unrelated; delete installed.snapshots.unrelated;
  compareDependencyLocks(expected, installed);
});

test('a readable dependency view from another lock is rejected', () => {
  const installed = structuredClone(expected);
  installed.importers['web-admin'].dependencies.sanitizer.version = '1.9';
  assert.throws(() => compareDependencyLocks(expected, installed));
});

test('changed overrides, package integrity or missing importers fail closed', () => {
  for (const mutate of [x => { x.overrides.sanitizer = '1'; },
    x => { x.packages['sanitizer@2.1'].resolution.integrity = 'different'; },
    x => { delete x.importers['web-admin']; }]) {
    const installed = structuredClone(expected); mutate(installed);
    assert.throws(() => compareDependencyLocks(expected, installed));
  }
});

// Malformed receipts must fail before trusting any declared dependency key.
test('immutable capsule receipts reject foreign platform and redirected views', async () => {
  const { mkdtempSync, mkdirSync, writeFileSync, rmSync } = await import('node:fs');
  const { tmpdir } = await import('node:os');
  const { join } = await import('node:path');
  const root = mkdtempSync(join(tmpdir(), 'agent-capsule-contract-'));
  const view = join(root, 'node_modules');
  mkdirSync(view);
  try {
    for (const receipt of [
      { schemaVersion: 0 },
      { schemaVersion: 1, platform: 'foreign-platform' },
      { schemaVersion: 1, platform: process.platform, arch: process.arch,
        nodeAbi: process.versions.modules, dependenciesDir: join(root, 'another-view') },
    ]) {
      writeFileSync(join(root, '.aura-dependency-capsule.json'), JSON.stringify(receipt));
      assert.throws(() => verifyDependencyCapsule(view, root));
    }
  } finally { rmSync(root, { recursive: true }); }
});
