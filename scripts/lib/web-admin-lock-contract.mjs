import assert from 'node:assert/strict';
import { readFileSync, realpathSync } from 'node:fs';
import { createRequire } from 'node:module';
import { resolve, join } from 'node:path';
import { pathToFileURL } from 'node:url';

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

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const [modules, target] = process.argv.slice(2);
    const owner = resolve(realpathSync(modules), '..', '..');
    const { parse } = createRequire(join(owner, 'package.json'))('yaml');
    compareDependencyLocks(parse(readFileSync(join(target, 'pnpm-lock.yaml'), 'utf8')),
      parse(readFileSync(join(owner, 'node_modules/.pnpm/lock.yaml'), 'utf8')));
  } catch {
    console.error('WEB_ADMIN_DEPENDENCY_LOCK_MISMATCH');
    process.exitCode = 1;
  }
}
