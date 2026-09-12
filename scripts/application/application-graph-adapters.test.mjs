import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { describe, it } from 'node:test';

import {
  buildArtifactApplicationGraph,
  buildSourceApplicationGraph,
} from './application-graph-adapters.mjs';
import { resolveApplication, sha256Path } from './application-contract.mjs';

const source = { repository: 'AuraBootTeam/auraboot', commit: 'a'.repeat(40) };

function manifest() {
  return {
    schemaVersion: 1,
    app: { id: 'aura-crm', name: 'Aura CRM', version: '1.0.0', defaultRoute: '/crm' },
    platform: {
      runtime: '1.0.0',
      baseImage: { id: 'auraboot-runtime', version: '1.0.0' },
      pluginApi: '1.0.0',
      webShell: '1.0.0',
      pluginSdk: '1.0.0',
      webPackages: [{ package: '@auraboot/ui', version: '1.0.0' }],
      dslSchema: 4,
    },
    backend: { mode: 'core-only', plugins: [], migrationSets: ['core'] },
    frontend: { contributions: [{ package: '@auraboot/aura-crm-web', version: '1.0.0' }] },
    config: { importOrder: ['core-meta'] },
  };
}

function contribution() {
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
    routes: [{ id: 'crm.home', path: '/crm', export: './routes/home', rendering: 'universal' }],
    contributions: [{ kind: 'block', id: 'crm-health', export: './blocks/crm-health' }],
  };
}

function artifact(type, id, version, localPath, path) {
  return {
    type,
    id,
    version,
    uri: `artifact:${id}@${version}`,
    digest: sha256Path(path),
    localPath,
    source,
  };
}

describe('application graph adapters', () => {
  it('derives identical source and artifact graphs from real package inputs', () => {
    const root = mkdtempSync(join(tmpdir(), 'aura-graph-adapters-'));
    const sourcePackage = join(root, 'source/aura-crm-web');
    mkdirSync(sourcePackage, { recursive: true });
    writeFileSync(join(sourcePackage, 'auraboot.contribution.json'), `${JSON.stringify(contribution(), null, 2)}\n`);
    const sourceMapPath = join(root, 'source-map.json');
    writeFileSync(sourceMapPath, `${JSON.stringify({ packages: { '@auraboot/aura-crm-web': sourcePackage } })}\n`);

    const artifactRoot = join(root, 'artifacts');
    const packedRoot = join(root, 'packed/package');
    mkdirSync(packedRoot, { recursive: true });
    writeFileSync(join(packedRoot, 'auraboot.contribution.json'), `${JSON.stringify(contribution(), null, 2)}\n`);
    const productTarball = join(artifactRoot, 'npm/aura-crm-web-1.0.0.tgz');
    mkdirSync(dirname(productTarball), { recursive: true });
    execFileSync('tar', ['-czf', productTarball, '-C', join(root, 'packed'), 'package']);

    const inputs = [
      ['runtime', 'com.auraboot:runtime', 'runtime/runtime.jar'],
      ['oci', 'auraboot-runtime', 'oci/runtime.bin'],
      ['maven', 'com.auraboot:platform-plugin-api', 'maven/plugin-api.jar'],
      ['npm', '@auraboot/web-shell', 'npm/web-shell.tgz'],
      ['npm', '@auraboot/plugin-sdk', 'npm/plugin-sdk.tgz'],
      ['npm', '@auraboot/ui', 'npm/ui.tgz'],
      ['migration', 'core', 'migrations/core.bin'],
      ['config', 'core-meta', 'config/core-meta.bin'],
    ];
    const artifacts = inputs.map(([type, id, localPath]) => {
      const path = join(artifactRoot, localPath);
      mkdirSync(dirname(path), { recursive: true });
      writeFileSync(path, `${type}:${id}`);
      return artifact(type, id, '1.0.0', localPath, path);
    });
    artifacts.push(artifact('npm', '@auraboot/aura-crm-web', '1.0.0', 'npm/aura-crm-web-1.0.0.tgz', productTarball));
    const catalog = { artifacts };
    const lock = resolveApplication(manifest(), catalog, { webContributions: [contribution()] });

    const sourceGraph = buildSourceApplicationGraph(manifest(), sourceMapPath);
    const artifactGraph = buildArtifactApplicationGraph(manifest(), lock, artifactRoot);
    assert.deepEqual(sourceGraph, artifactGraph);
    assert.equal(sourceGraph.nodes.find((node) => node.kind === 'route')?.id, 'crm.home');

    writeFileSync(productTarball, 'mutated');
    assert.throws(
      () => buildArtifactApplicationGraph(manifest(), lock, artifactRoot),
      /checksum mismatch/,
    );
  });
});
