import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const migrationDir = join(repoRoot, 'platform/src/main/resources/db/migration/core');
const floorFile = join(repoRoot, 'scripts/db/migration-version-floor.txt');

function parseFloor() {
  const record = {};
  for (const line of readFileSync(floorFile, 'utf8').split('\n')) {
    const match = /^(version|count)=(.+)$/.exec(line.trim());
    if (match) record[match[1]] = match[2];
  }
  return record;
}

function migrationVersions() {
  return readdirSync(migrationDir)
    .map(name => /^V(\d{14})__.*\.sql$/.exec(name))
    .filter(Boolean)
    .map(match => match[1]);
}

test('core migration floor records the highest released version and exact file count', () => {
  const floor = parseFloor();
  assert.match(floor.version, /^\d{14}$/, 'floor file must declare a 14-digit version');
  assert.match(floor.count, /^\d+$/, 'floor file must declare a migration count');
  const versions = migrationVersions();
  assert.ok(versions.length > 0, 'core migration directory must not be empty');
  const highest = versions.reduce((a, b) => (a > b ? a : b));
  assert.equal(
    highest,
    floor.version,
    'a migration above the recorded floor exists; raise scripts/db/migration-version-floor.txt in the same PR',
  );
  assert.equal(
    versions.length,
    Number(floor.count),
    'migration file count changed without updating the floor record; backdated versions below released databases are unrecoverable, so raise the floor and count together',
  );
});
