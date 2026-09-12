import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { describe, it } from 'node:test';

import {
  resolveApplication,
  sha256,
  validateLock,
  validateManifest,
  verifyArtifacts,
} from './application-contract.mjs';

const digest = (character) => `sha256:${character.repeat(64)}`;
const commit = (character) => character.repeat(40);

function manifest() {
  return {
    schemaVersion: 1,
    app: { id: 'aura-crm', name: 'Aura CRM', version: '1.0.0', defaultRoute: '/crm' },
    platform: {
      runtime: '1.3.0',
      pluginApi: '1.3.0',
      webShell: '1.3.0',
      pluginSdk: '1.3.0',
      dslSchema: 4,
    },
    backend: {
      plugins: [{ id: 'com.auraboot.crm', version: '1.0.0' }],
      migrationSets: ['core', 'crm'],
    },
    frontend: { contributions: [{ package: '@auraboot/aura-crm-web', version: '1.0.0' }] },
    config: { importOrder: ['core-meta', 'crm'] },
  };
}

function catalog() {
  const source = { repository: 'AuraBootTeam/auraboot', commit: commit('a') };
  const artifact = (type, id, version, character) => ({
    type,
    id,
    version,
    uri: `${type === 'oci' ? 'oci' : type === 'npm' ? 'npm' : 'artifact'}:${id}@${version}`,
    digest: digest(character),
    source,
  });
  return {
    artifacts: [
      artifact('oci', 'com.auraboot:runtime', '1.3.0', '1'),
      { ...artifact('maven', 'com.auraboot:platform-plugin-api', '1.3.0', '2'), uri: 'maven:com.auraboot:platform-plugin-api:1.3.0' },
      artifact('npm', '@auraboot/web-shell', '1.3.0', '3'),
      artifact('npm', '@auraboot/plugin-sdk', '1.3.0', '4'),
      artifact('plugin', 'com.auraboot.crm', '1.0.0', '5'),
      artifact('migration', 'core', '1.3.0', '6'),
      artifact('migration', 'crm', '1.0.0', '7'),
      artifact('npm', '@auraboot/aura-crm-web', '1.0.0', '8'),
      artifact('config', 'core-meta', '1.3.0', '9'),
      artifact('config', 'crm', '1.0.0', 'a'),
    ],
  };
}

describe('AuraBoot application contract', () => {
  it('resolves a deterministic lock with immutable artifact identities', () => {
    const first = resolveApplication(manifest(), catalog());
    const second = resolveApplication(manifest(), catalog());

    assert.deepEqual(first, second);
    assert.equal(first.artifacts.length, 10);
    assert.match(first.identity, /^sha256:[0-9a-f]{64}$/);
    assert.doesNotThrow(() => validateLock(first));
  });

  it('rejects mutable manifest dependency versions', () => {
    const input = manifest();
    input.platform.runtime = '1.3.0-SNAPSHOT';

    assert.throws(() => validateManifest(input), /application manifest is invalid/);
  });

  it('fails closed when a required artifact is missing', () => {
    const input = catalog();
    input.artifacts = input.artifacts.filter((artifact) => artifact.id !== 'com.auraboot.crm');

    assert.throws(() => resolveApplication(manifest(), input), /resolved to 0 entries/);
  });

  it('rejects duplicate contribution owners', () => {
    const input = manifest();
    input.frontend.contributions.push({ ...input.frontend.contributions[0] });

    assert.throws(() => validateManifest(input), /duplicate owner IDs/);
  });

  it('detects lock tampering', () => {
    const lock = resolveApplication(manifest(), catalog());
    lock.artifacts[0].digest = digest('f');

    assert.throws(() => validateLock(lock), /identity mismatch/);
  });

  it('verifies staged artifact bytes and fails after checksum mutation', () => {
    const artifactRoot = mkdtempSync(join(tmpdir(), 'aura-application-artifacts-'));
    const input = catalog();
    for (const [index, artifact] of input.artifacts.entries()) {
      artifact.localPath = `${artifact.type}/${index}.bin`;
      const path = join(artifactRoot, artifact.localPath);
      mkdirSync(dirname(path), { recursive: true });
      const bytes = Buffer.from(`${artifact.type}:${artifact.id}@${artifact.version}`);
      writeFileSync(path, bytes);
      artifact.digest = sha256(bytes);
    }
    const lock = resolveApplication(manifest(), input);
    assert.doesNotThrow(() => verifyArtifacts(lock, { artifactRoot }));

    writeFileSync(join(artifactRoot, lock.artifacts[0].localPath), 'mutated');
    assert.throws(() => verifyArtifacts(lock, { artifactRoot }), /checksum mismatch/);
  });
});
