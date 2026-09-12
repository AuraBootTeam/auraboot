import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { describe, it } from 'node:test';

import {
  buildApplicationGraph,
  resolveApplication,
  sha256,
  sha256Path,
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
      artifact('runtime', 'com.auraboot:runtime', '1.3.0', '1'),
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

function webContribution() {
  return {
    schemaVersion: 1,
    package: { name: '@auraboot/aura-crm-web', version: '1.0.0' },
    plugin: { code: 'aura.crm', activationPhase: 'application', dependsOn: [] },
    peerDependencies: {
      react: '^19.2.0',
      reactDom: '^19.2.0',
      router: '^7.0.0',
      pluginSdk: '^1.0.0',
    },
    routes: [
      { id: 'crm.home', path: '/crm', export: './routes/home', rendering: 'universal' },
    ],
    contributions: [
      { kind: 'widget', id: 'crm-health', export: './widgets/crm-health' },
    ],
  };
}

function resolveFixture(inputManifest = manifest(), inputCatalog = catalog()) {
  return resolveApplication(inputManifest, inputCatalog, { webContributions: [webContribution()] });
}

describe('AuraBoot application contract', () => {
  it('resolves a deterministic lock with immutable artifact identities', () => {
    const first = resolveFixture();
    const second = resolveFixture();

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

    assert.throws(() => resolveFixture(manifest(), input), /resolved to 0 entries/);
  });

  it('rejects duplicate contribution owners', () => {
    const input = manifest();
    input.frontend.contributions.push({ ...input.frontend.contributions[0] });

    assert.throws(() => validateManifest(input), /duplicate owner IDs/);
  });

  it('detects lock tampering', () => {
    const lock = resolveFixture();
    lock.artifacts[0].digest = digest('f');

    assert.throws(() => validateLock(lock), /identity mismatch/);
  });

  it('produces a deterministic typed composition graph', () => {
    const sourceGraph = buildApplicationGraph(manifest(), [webContribution()], { requireContributions: true });
    const artifactGraph = buildApplicationGraph(manifest(), [webContribution()], { requireContributions: true });

    assert.deepEqual(sourceGraph, artifactGraph);
    assert.equal(sourceGraph.nodes[0].kind, 'runtime');
    assert.equal(sourceGraph.nodes.at(-1).id, 'crm-health');
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
    const lock = resolveFixture(manifest(), input);
    assert.doesNotThrow(() => verifyArtifacts(lock, { artifactRoot }));

    writeFileSync(join(artifactRoot, lock.artifacts[0].localPath), 'mutated');
    assert.throws(() => verifyArtifacts(lock, { artifactRoot }), /checksum mismatch/);
  });

  it('computes deterministic directory digests and detects nested mutations', () => {
    const artifactRoot = mkdtempSync(join(tmpdir(), 'aura-application-directory-'));
    const nested = join(artifactRoot, 'nested');
    mkdirSync(nested);
    writeFileSync(join(artifactRoot, 'a.txt'), 'alpha');
    writeFileSync(join(nested, 'b.txt'), 'beta');

    const first = sha256Path(artifactRoot);
    const second = sha256Path(artifactRoot);
    assert.equal(first, second);

    writeFileSync(join(nested, 'b.txt'), 'mutated');
    assert.notEqual(sha256Path(artifactRoot), first);
  });

  it('fails closed on duplicate route paths in the composed web graph', () => {
    const duplicate = webContribution();
    duplicate.routes.push({
      id: 'crm.other',
      path: '/crm',
      export: './routes/other',
      rendering: 'universal',
    });

    assert.throws(
      () => buildApplicationGraph(manifest(), [duplicate], { requireContributions: true }),
      /duplicate owner IDs/,
    );
  });
});
