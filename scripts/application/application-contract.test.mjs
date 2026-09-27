import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, renameSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { describe, it } from 'node:test';

import {
  buildApplicationGraph,
  assertNoMigrationCollisions,
  resolveApplication,
  sha256,
  sha256Path,
  validateLock,
  validateLockForManifest,
  canonicalJson,
  lockIdentity,
  validateManifest,
  validateWebContribution,
  verifyArtifacts,
} from './application-contract.mjs';

const digest = (character) => `sha256:${character.repeat(64)}`;
const commit = (character) => character.repeat(40);

function manifest() {
  return {
    schemaVersion: 1,
    app: {
      id: 'aura-crm',
      name: 'Aura CRM',
      version: '1.0.0',
      compatibilityEpoch: 1,
      defaultRoute: '/crm',
    },
    platform: {
      runtime: '1.3.0',
      baseImage: { id: 'auraboot-runtime', version: '1.3.0' },
      pluginApi: '1.3.0',
      webShell: '1.3.0',
      pluginSdk: '1.3.0',
      webPackages: [{ package: '@auraboot/ui', version: '1.3.0' }],
      dslSchema: 4,
    },
    backend: {
      mode: 'application',
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
      artifact('oci', 'auraboot-runtime', '1.3.0', 'c'),
      { ...artifact('maven', 'com.auraboot:platform-plugin-api', '1.3.0', '2'), uri: 'maven:com.auraboot:platform-plugin-api:1.3.0' },
      artifact('npm', '@auraboot/web-shell', '1.3.0', '3'),
      artifact('npm', '@auraboot/plugin-sdk', '1.3.0', '4'),
      artifact('npm', '@auraboot/ui', '1.3.0', 'b'),
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
      { id: 'crm.home', kind: 'page', shell: 'application', path: '/crm', export: './routes/home', rendering: 'universal' },
    ],
    contributions: [
      { kind: 'widget', id: 'crm-health', export: './widgets/crm-health' },
    ],
    assets: [
      { id: 'crm-sdk', source: './public/crm', mount: '/crm', cache: 'revalidate' },
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
    assert.equal(first.artifacts.length, 12);
    assert.match(first.identity, /^sha256:[0-9a-f]{64}$/);
    assert.doesNotThrow(() => validateLock(first));
  });

  it('rejects mutable manifest dependency versions', () => {
    const input = manifest();
    input.platform.runtime = '1.3.0-SNAPSHOT';

    assert.throws(() => validateManifest(input), /application manifest is invalid/);
  });

  it('accepts a positive application compatibility epoch and rejects zero', () => {
    assert.equal(validateManifest(manifest()).app.compatibilityEpoch, 1);
    const input = manifest();
    input.app.compatibilityEpoch = 0;
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

  const reseal = (lock, graph = true) => {
    if (graph) lock.composition.graphDigest = sha256(canonicalJson(lock.composition.nodes));
    lock.identity = lockIdentity(lock);
    return lock;
  };

  it('rejects an omitted artifact even when the outer identity is recomputed', () => {
    const lock = resolveFixture();
    lock.artifacts = lock.artifacts.filter((item) => item.type !== 'plugin');
    assert.throws(() => validateLock(reseal(lock)), /missing artifacts/);
  });
  it('rejects an undeclared artifact even when rehashed', () => {
    const lock = resolveFixture();
    lock.artifacts.push({ ...lock.artifacts[0], id: 'undeclared' });
    assert.throws(() => validateLock(reseal(lock)), /not declared/);
  });
  it('checks the embedded graph digest independently of the outer identity', () => {
    const lock = resolveFixture();
    lock.composition.graphDigest = digest('f');
    assert.throws(() => validateLock(reseal(lock, false)), /graph digest mismatch/);
  });
  it('rejects duplicate nodes, noncontiguous order and unavailable web owners', () => {
    const duplicate = resolveFixture();
    duplicate.composition.nodes.push({ ...duplicate.composition.nodes[0], order: duplicate.composition.nodes.length });
    assert.throws(() => validateLock(reseal(duplicate)), /duplicate owner IDs/);
    const order = resolveFixture();
    order.composition.nodes[0].order = 9;
    assert.throws(() => validateLock(reseal(order)), /order must be contiguous/);
    const owner = resolveFixture();
    owner.composition.nodes.find((node) => node.kind === 'route').owner = 'missing';
    assert.throws(() => validateLock(reseal(owner)), /unavailable web owner/);
  });
  it('rejects artifact and graph version drift including mutable versions', () => {
    const lock = resolveFixture();
    const artifact = lock.artifacts.find((item) => item.type === 'plugin');
    artifact.version = '2.0.0';
    assert.throws(() => validateLock(reseal(lock)), /version differs/);
    lock.composition.nodes.find((node) => node.kind === 'plugin').version = 'latest';
    artifact.version = 'latest';
    assert.throws(() => validateLock(reseal(lock)), /mutable release version/);
  });
  it('uses an external identity to reject a self-consistent rewritten lock', () => {
    const lock = resolveFixture();
    const expectedIdentity = lock.identity;
    lock.artifacts[0].digest = digest('f');
    assert.throws(() => validateLock(reseal(lock), { expectedIdentity }), /externally expected identity/);
  });
  it('binds a lock to the source manifest and requires image inputs unless explicitly skipped', () => {
    const lock = resolveFixture();
    assert.equal(validateLockForManifest(lock, manifest()), lock);
    const changed = manifest();
    changed.app.id = 'another-app';
    assert.throws(() => validateLockForManifest(lock, changed), /source manifest/);
    const withoutImage = resolveApplication(manifest(), catalog(), { webContributions: [webContribution()], skipImage: true });
    assert.throws(() => validateLockForManifest(withoutImage, manifest()), /artifact count/);
    assert.equal(validateLockForManifest(withoutImage, manifest(), { skipImage: true }), withoutImage);
  });

  it('the CLI verifies external identity and source manifest before accepting a lock', () => {
    const directory = mkdtempSync(join(tmpdir(), 'application-lock-cli-'));
    const lock = resolveFixture();
    const lockPath = join(directory, 'lock.json');
    const manifestPath = join(directory, 'app.json');
    writeFileSync(lockPath, JSON.stringify(lock));
    writeFileSync(manifestPath, JSON.stringify(manifest()));
    const run = (...extra) => spawnSync(process.execPath, [
      new URL('./application-cli.mjs', import.meta.url).pathname, 'verify-lock',
      '--lock', lockPath, '--manifest', manifestPath, ...extra,
    ], { encoding: 'utf8' });
    assert.equal(run('--expected-identity', lock.identity).status, 0);
    const wrong = run('--expected-identity', digest('f'));
    assert.equal(wrong.status, 1);
    assert.match(wrong.stderr, /externally expected identity/);
    assert.equal(run('--expected-identity').status, 1);
    writeFileSync(manifestPath, JSON.stringify({ ...manifest(), app: { ...manifest().app, id: 'different' } }));
    assert.match(run().stderr, /source manifest/);
  });

  it('produces a deterministic typed composition graph', () => {
    const sourceGraph = buildApplicationGraph(manifest(), [webContribution()], { requireContributions: true });
    const artifactGraph = buildApplicationGraph(manifest(), [webContribution()], { requireContributions: true });

    assert.deepEqual(sourceGraph, artifactGraph);
    assert.equal(sourceGraph.nodes[0].kind, 'runtime');
    assert.equal(sourceGraph.nodes.at(-1).id, 'crm-sdk');
    assert.equal(sourceGraph.nodes.find((node) => node.id === 'crm.home')?.surface, 'page');
    assert.equal(sourceGraph.nodes.find((node) => node.id === 'crm.home')?.shell, 'application');
  });

  it('rejects overlapping asset mounts across contribution owners', () => {
    const inputManifest = manifest();
    inputManifest.frontend.contributions.push({ package: '@auraboot/aura-edu-web', version: '1.0.0' });
    const second = structuredClone(webContribution());
    second.package.name = '@auraboot/aura-edu-web';
    second.plugin.code = 'aura.edu';
    second.routes = [];
    second.contributions = [];
    second.assets = [{ id: 'edu-static', source: './public/crm/edu', mount: '/crm/edu', cache: 'immutable' }];

    assert.throws(
      () => buildApplicationGraph(inputManifest, [webContribution(), second], { requireContributions: true }),
      /asset mounts overlap/,
    );
  });

  it('rejects ambiguous or escaping asset mount paths', () => {
    for (const mount of ['/crm//sdk', '/crm/../sdk']) {
      const contribution = structuredClone(webContribution());
      contribution.assets[0].mount = mount;
      assert.throws(() => validateWebContribution(contribution), /web contribution manifest is invalid/);
    }
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

    const missingPath = join(artifactRoot, lock.artifacts[0].localPath);
    renameSync(missingPath, `${missingPath}.missing`);
    assert.throws(() => verifyArtifacts(lock, { artifactRoot }), /cannot be read/);
    renameSync(`${missingPath}.missing`, missingPath);

    writeFileSync(join(artifactRoot, lock.artifacts[0].localPath), 'mutated');
    assert.throws(() => verifyArtifacts(lock, { artifactRoot }), /checksum mismatch/);
  });

  it('ships a dependency-free release verifier that detects checksum mutation', () => {
    const artifactRoot = mkdtempSync(join(tmpdir(), 'aura-release-verifier-'));
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
    const lockPath = join(artifactRoot, 'application.lock');
    writeFileSync(lockPath, `${JSON.stringify(lock, null, 2)}\n`);
    const verifier = new URL('./application-artifact-verifier.mjs', import.meta.url);
    const args = [verifier.pathname, '--lock', lockPath, '--artifact-root', artifactRoot];

    const pass = spawnSync(process.execPath, args, { encoding: 'utf8' });
    assert.equal(pass.status, 0, pass.stderr);
    assert.match(pass.stdout, /verified 12 staged artifacts/);

    writeFileSync(join(artifactRoot, lock.artifacts[0].localPath), 'mutated');
    const fail = spawnSync(process.execPath, args, { encoding: 'utf8' });
    assert.notEqual(fail.status, 0);
    assert.match(fail.stderr, /checksum mismatch/);
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
      kind: 'page',
      shell: 'application',
      path: '/crm',
      export: './routes/other',
      rendering: 'universal',
    });

    assert.throws(
      () => buildApplicationGraph(manifest(), [duplicate], { requireContributions: true }),
      /duplicate owner IDs/,
    );
  });

  it('fails closed when migration sets reuse a Flyway version', () => {
    const artifactRoot = mkdtempSync(join(tmpdir(), 'aura-migration-collision-'));
    const core = join(artifactRoot, 'core');
    const crm = join(artifactRoot, 'crm');
    mkdirSync(core);
    mkdirSync(crm);
    writeFileSync(join(core, 'V20260912010000__core.sql'), 'select 1;');
    writeFileSync(join(crm, 'V20260912010000__crm.sql'), 'select 2;');

    assert.throws(
      () => assertNoMigrationCollisions([
        { type: 'migration', id: 'core', localPath: 'core' },
        { type: 'migration', id: 'crm', localPath: 'crm' },
      ], { artifactRoot }),
      /migration version collision 20260912010000/,
    );
  });
});
