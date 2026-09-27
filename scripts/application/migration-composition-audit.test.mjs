import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { inventoryPackage } from './schema-ownership-audit.mjs';
import { auditComposition } from './migration-composition-audit.mjs';

function packageFixture(id, version, dependencies = []) {
  const root = mkdtempSync(join(tmpdir(), 'composition-audit-'));
  const name = `${id}_record`;
  const file = version.startsWith('R__') ? version : `V${version}__${id}.sql`;
  writeFileSync(join(root, file), `CREATE TABLE ${name}(id bigint);`);
  const contract = { ...inventoryPackage(root, id), dependencies,
    objects: { [name]: { owner: id, lifecycle: 'static-schema', evidence: 'fixture owned table' } } };
  Object.assign(contract.migrations[0].statements[0], {
    review: 'approved', rationale: 'fixture table ownership', objects: [{ name, operation: 'create' }],
  });
  return { root, contract };
}

test('composes complete reviewed packages with dependency admission order and exact byte receipts', () => {
  const core = packageFixture('core', '1'); const crm = packageFixture('crm', '2', ['core']);
  const result = auditComposition([crm, core]);
  assert.deepEqual(result.dependencyOrder, ['core', 'crm']);
  assert.equal(result.migrationCount, 2);
  assert.equal(result.packages[0].migrations[0].sha256, crm.contract.migrations[0].sha256);
  assert.equal(result.claim, 'reviewed-migration-composition-only');
});

test('missing, cyclic and duplicate packages fail closed', () => {
  const core = packageFixture('core', '1', ['crm']); const crm = packageFixture('crm', '2', ['core']);
  assert.throws(() => auditComposition([crm]), /missing dependency/);
  assert.throws(() => auditComposition([crm, core]), /cycle/);
  assert.throws(() => auditComposition([crm, crm]), /duplicate package/);
});

test('version aliases and repeatable descriptions cannot collide across packages', () => {
  for (const version of ['1', '01', '1.0', '1_00']) {
    assert.throws(() => auditComposition([packageFixture('core', '1'), packageFixture('crm', version)]), /version collision/);
  }
  assert.throws(() => auditComposition([packageFixture('core', 'R__shared_view.sql'), packageFixture('crm', 'R__shared view.sql')]), /description collision/);
});

test('schema aliases cannot hide a competing owner', () => {
  const core = packageFixture('core', '1'); const crm = packageFixture('crm', '2');
  crm.contract.objects['public.core_record'] = crm.contract.objects.crm_record;
  delete crm.contract.objects.crm_record;
  crm.contract.migrations[0].statements[0].objects[0].name = 'public.core_record';
  assert.throws(() => auditComposition([core, crm]), /conflicting object owner/);
});

test('foreign catalog seed requires the owning package declaration', () => {
  const core = packageFixture('core', '1'); const crm = packageFixture('crm', '2', ['core']);
  crm.contract.objects.shared_catalog = { owner: 'core', lifecycle: 'platform-catalog-data', evidence: 'fixture' };
  crm.contract.migrations[0].statements[0].objects.push({ name: 'shared_catalog', operation: 'seed' });
  assert.throws(() => auditComposition([core, crm]), /not declared by its owner/);
  crm.contract.objects.core_record = crm.contract.objects.shared_catalog;
  delete crm.contract.objects.shared_catalog;
  crm.contract.migrations[0].statements[0].objects[1].name = 'core_record';
  assert.throws(() => auditComposition([core, crm]), /conflicting object lifecycle/);
  crm.contract.objects.core_record.lifecycle = core.contract.objects.core_record.lifecycle;
  assert.equal(auditComposition([core, crm]).verdict, 'PASS');
});

test('same-owner aliases cannot assign both Flyway and model-publish lifecycles', () => {
  const core = packageFixture('core', '1'); const crm = packageFixture('crm', '2', ['core']);
  crm.contract.objects['public.core_record'] = { owner: 'core', lifecycle: 'model-publish', evidence: 'fixture reference' };
  crm.contract.migrations[0].statements[0].objects.push({ name: 'public.core_record', operation: 'reference' });
  for (const packages of [[core, crm], [crm, core]]) {
    assert.throws(() => auditComposition(packages), /conflicting object lifecycle: public.core_record/);
  }
  crm.contract.objects['public.core_record'].lifecycle = 'static-schema';
  assert.equal(auditComposition([core, crm]).verdict, 'PASS');
  core.contract.objects['public.core_record'] = { ...core.contract.objects.core_record, lifecycle: 'model-publish' };
  core.contract.migrations[0].statements[0].objects.push({ name: 'public.core_record', operation: 'reference' });
  assert.throws(() => auditComposition([core]), /conflicting object lifecycle/);
});

test('draft or changed SQL cannot enter a composition via a valid neighboring package', () => {
  const core = packageFixture('core', '1'); const crm = packageFixture('crm', '2', ['core']);
  crm.contract.migrations[0].statements[0].review = 'draft';
  assert.throws(() => auditComposition([core, crm]), /unreviewed/);
  crm.contract.migrations[0].statements[0].review = 'approved';
  writeFileSync(join(crm.root, 'V2__crm.sql'), 'SELECT 1;');
  assert.throws(() => auditComposition([core, crm]), /bytes drift/);
});

test('CLI resolves manifest-relative inputs and returns nonzero for draft packages', () => {
  const core = packageFixture('core', '1');
  const contractPath = join(core.root, 'review.json');
  writeFileSync(contractPath, JSON.stringify(core.contract));
  const manifestPath = join(core.root, 'composition.json');
  writeFileSync(manifestPath, JSON.stringify({ schemaVersion: 1, packages: [{ root: '.', contract: 'review.json' }] }));
  const command = fileURLToPath(new URL('./migration-composition-audit.mjs', import.meta.url));
  const run = () => spawnSync(process.execPath, [command, '--manifest', manifestPath], { cwd: tmpdir(), encoding: 'utf8' });
  let result = run(); assert.equal(result.status, 0, result.stderr);
  assert.equal(JSON.parse(result.stdout).migrationCount, 1);
  core.contract.migrations[0].statements[0].review = 'draft';
  writeFileSync(contractPath, JSON.stringify(core.contract));
  result = run(); assert.equal(result.status, 2); assert.match(result.stderr, /unreviewed/);
});
