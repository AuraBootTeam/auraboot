import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, readFileSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { describe, it } from 'node:test';

import {
  buildArtifactApplicationGraph,
  buildSourceRouteManifest,
  buildSourceApplicationGraph,
  materializeSourceWebAssets,
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
    routes: [{ id: 'crm.home', kind: 'page', shell: 'application', path: '/crm', export: './routes/home', rendering: 'universal' }],
    contributions: [{ kind: 'block', id: 'crm-health', export: './blocks/crm-health' }],
    assets: [{ id: 'crm-sdk', source: './public/crm', mount: '/crm', cache: 'revalidate' }],
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
    mkdirSync(join(sourcePackage, 'routes'), { recursive: true });
    writeFileSync(join(sourcePackage, 'routes/home'), 'export default function Home() {}\n');
    mkdirSync(join(sourcePackage, 'blocks'), { recursive: true });
    writeFileSync(join(sourcePackage, 'blocks/crm-health'), 'export const health = true;\n');
    mkdirSync(join(sourcePackage, 'public/crm'), { recursive: true });
    writeFileSync(join(sourcePackage, 'public/crm/sdk.js'), 'export const crm = true;\n');
    const sourceMapPath = join(root, 'source-map.json');
    writeFileSync(sourceMapPath, `${JSON.stringify({ packages: { '@auraboot/aura-crm-web': sourcePackage } })}\n`);

    const artifactRoot = join(root, 'artifacts');
    const packedRoot = join(root, 'packed/package');
    mkdirSync(packedRoot, { recursive: true });
    writeFileSync(join(packedRoot, 'auraboot.contribution.json'), `${JSON.stringify(contribution(), null, 2)}\n`);
    mkdirSync(join(packedRoot, 'routes'), { recursive: true });
    writeFileSync(join(packedRoot, 'routes/home'), 'export default function Home() {}\n');
    mkdirSync(join(packedRoot, 'blocks'), { recursive: true });
    writeFileSync(join(packedRoot, 'blocks/crm-health'), 'export const health = true;\n');
    mkdirSync(join(packedRoot, 'public/crm'), { recursive: true });
    writeFileSync(join(packedRoot, 'public/crm/sdk.js'), 'export const crm = true;\n');
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
    assert.equal(sourceGraph.nodes.find((node) => node.kind === 'asset')?.mount, '/crm');

    assert.deepEqual(buildSourceRouteManifest(manifest(), sourceMapPath), {
      APPLICATION_ROUTES: [{ path: '/crm', file: join(sourcePackage, 'routes/home') }],
      STANDALONE_ROUTES: [],
      RESOURCE_ROUTES: [],
      PLATFORM_ROUTES: [],
    });
    assert.deepEqual(buildSourceRouteManifest(manifest(), sourceMapPath, dirname(sourcePackage)), {
      APPLICATION_ROUTES: [{ path: '/crm', file: './aura-crm-web/routes/home' }],
      STANDALONE_ROUTES: [],
      RESOURCE_ROUTES: [],
      PLATFORM_ROUTES: [],
    });
    assert.throws(
      () => buildSourceRouteManifest(manifest(), sourceMapPath, join(root, 'unrelated-route-root')),
      /route module escapes its root/,
    );

    const materializedRoot = join(root, 'materialized');
    const materialized = materializeSourceWebAssets(manifest(), sourceMapPath, materializedRoot);
    assert.deepEqual(materialized, [{ owner: '@auraboot/aura-crm-web', id: 'crm-sdk', source: './public/crm', mount: '/crm' }]);
    assert.equal(readFileSync(join(materializedRoot, 'crm/sdk.js'), 'utf8'), 'export const crm = true;\n');
    assert.throws(
      () => materializeSourceWebAssets(manifest(), sourceMapPath, materializedRoot),
      /asset mount already exists/,
    );

    const unsafeTarget = join(root, 'unsafe-materialized');
    symlinkSync(join(sourcePackage, 'auraboot.contribution.json'), join(sourcePackage, 'public/crm/manifest-link'));
    assert.throws(
      () => materializeSourceWebAssets(manifest(), sourceMapPath, unsafeTarget),
      /must not contain symbolic links/,
    );

    writeFileSync(productTarball, 'mutated');
    assert.throws(
      () => buildArtifactApplicationGraph(manifest(), lock, artifactRoot),
      /checksum mismatch/,
    );
  });
});
