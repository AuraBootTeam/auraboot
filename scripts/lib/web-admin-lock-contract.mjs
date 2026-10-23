import assert from 'node:assert/strict';
import { readFileSync, realpathSync, existsSync } from 'node:fs';
import { createRequire } from 'node:module';
import { resolve, join, dirname } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createHash } from 'node:crypto';

export function compareDependencyLocks(expected, installed) {
  for (const key of ['lockfileVersion', 'settings', 'overrides']) assert.deepEqual(installed[key], expected[key]);
  for (const importer of ['.', 'web-admin']) {
    assert(expected.importers?.[importer] && installed.importers?.[importer], 'required importer missing');
    assert.deepEqual(installed.importers[importer], expected.importers[importer]);
  }
  // Filtered pnpm installs prune unrelated workspace packages. Every installed
  // resolution still has to agree with the frozen source lock and its integrity.
  for (const section of ['packages', 'snapshots']) {
    assert(installed[section] && expected[section], 'installed resolution section missing');
    for (const [key, value] of Object.entries(installed[section])) assert.deepEqual(value, expected[section][key]);
  }
}

// Composition schema 4 scopes immutable capsules to the exact Core source root
// and dependency inputs. A capsule has no mutable pnpm workspace lock directory.
export function verifyDependencyCapsule(modules, target) {
  const view = realpathSync(modules);
  const root = dirname(view);
  const receipt = JSON.parse(readFileSync(join(root, '.aura-dependency-capsule.json'), 'utf8'));
  assert.equal(receipt.schemaVersion, 1);
  assert.equal(receipt.platform, process.platform);
  assert.equal(receipt.arch, process.arch);
  assert.equal(receipt.nodeAbi, process.versions.modules);
  assert.equal(receipt.dependenciesDir, view);
  assert.equal(receipt.virtualStoreDir, join(root, '.pnpm'));
  assert.equal(realpathSync(receipt.virtualStoreDir), receipt.virtualStoreDir);
  const hash = createHash('sha256');
  hash.update('schema:4\n');
  hash.update(`platform:${process.platform}-${process.arch}\n`);
  hash.update(`node-abi:${process.versions.modules}\n`);
  hash.update(`core-root:${realpathSync(target)}\n`);
  for (const file of ['pnpm-lock.yaml', 'pnpm-workspace.yaml', 'package.json', 'web-admin/package.json']) {
    hash.update(`file:${file}\n`);
    hash.update(readFileSync(join(target, file)));
  }
  const key = `sha256:${hash.digest('hex')}`;
  assert.equal(receipt.dependencyKey, key);
  assert.equal(`sha256:${dirname(view).split('/').pop()}`, key);
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const [modules, target] = process.argv.slice(2);
    if (existsSync(join(dirname(realpathSync(modules)), '.aura-dependency-capsule.json'))) {
      verifyDependencyCapsule(modules, target);
    } else {
    const owner = resolve(realpathSync(modules), '..', '..');
    const { parse } = createRequire(join(owner, 'package.json'))('yaml');
    compareDependencyLocks(parse(readFileSync(join(target, 'pnpm-lock.yaml'), 'utf8')),
      parse(readFileSync(join(owner, 'node_modules/.pnpm/lock.yaml'), 'utf8')));
    }
  } catch {
    console.error('WEB_ADMIN_DEPENDENCY_LOCK_MISMATCH');
    process.exitCode = 1;
  }
}
