import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, symlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { inventoryPackage, auditPackage } from './schema-ownership-audit.mjs';

function fixture() {
  const root = mkdtempSync(join(tmpdir(), 'schema-owner-'));
  writeFileSync(join(root, 'V1__owned.sql'), 'CREATE TABLE crm_record(id bigint);');
  const inventory = inventoryPackage(root, 'crm');
  const contract = { ...inventory, dependencies: ['core'], objects: {
    crm_record: { owner: 'crm', lifecycle: 'static-schema', evidence: 'CRM repository model contract' },
  } };
  contract.migrations[0].statements[0] = { ...contract.migrations[0].statements[0], review: 'approved',
    rationale: 'Product-owned storage declared by the CRM contract.', objects: [{ name: 'crm_record', operation: 'create' }] };
  return { root, contract };
}

test('inventory preserves procedural statement boundaries and never approves them', () => {
  const { root } = fixture();
  writeFileSync(join(root, 'V2__procedure.sql'), "DO $$ BEGIN EXECUTE 'ALTER TABLE crm_record ADD COLUMN note text;'; END $$;");
  const inventory = inventoryPackage(root, 'crm');
  assert.equal(inventory.migrations[1].statements.length, 1);
  assert.equal(inventory.migrations[1].statements[0].review, 'draft');
  assert.deepEqual(inventory.migrations[1].statements[0].objects, []);
});

test('approved review is pinned to complete migration and statement bytes', () => {
  const { root, contract } = fixture();
  assert.equal(auditPackage(root, contract).statementCount, 1);
  writeFileSync(join(root, 'V1__owned.sql'), 'CREATE TABLE crm_record(id text);');
  assert.throws(() => auditPackage(root, contract), /bytes drift/);
});

test('new files and missing or duplicated reviews fail closed', () => {
  const { root, contract } = fixture();
  const missing = structuredClone(contract); missing.migrations[0].statements = [];
  assert.throws(() => auditPackage(root, missing), /denominator/);
  const duplicate = structuredClone(contract); duplicate.migrations.push(duplicate.migrations[0]);
  assert.throws(() => auditPackage(root, duplicate), /denominator|duplicate/);
  writeFileSync(join(root, 'V2__new.sql'), 'ALTER TABLE crm_record ADD COLUMN extra text;');
  assert.throws(() => auditPackage(root, contract), /denominator/);
});

test('foreign schema writes and unreviewed dynamic transitions are rejected', () => {
  const { root, contract } = fixture();
  contract.objects.crm_record.owner = 'core';
  assert.throws(() => auditPackage(root, contract), /foreign schema write/);
  contract.objects.crm_record.owner = 'crm';
  contract.objects.crm_record.lifecycle = 'model-publish';
  assert.throws(() => auditPackage(root, contract), /transition/);
  contract.migrations[0].statements[0].objects[0].transition = 'Legacy storage bridge pending model publication handoff.';
  assert.equal(auditPackage(root, contract).verdict, 'PASS');
});

test('draft, empty object lists and unknown owners are not silently approved', () => {
  const { root, contract } = fixture();
  const draft = structuredClone(contract); draft.migrations[0].statements[0].review = 'draft';
  assert.throws(() => auditPackage(root, draft), /unreviewed/);
  const empty = structuredClone(contract); empty.migrations[0].statements[0].objects = [];
  assert.throws(() => auditPackage(root, empty), /objects missing/);
  contract.objects.crm_record.owner = 'other';
  assert.throws(() => auditPackage(root, contract), /owner/);
});

test('aliases, duplicate versions and empty migrations cannot disappear from scope', () => {
  let { root } = fixture();
  symlinkSync(join(root, 'V1__owned.sql'), join(root, 'V2__alias.sql'));
  assert.throws(() => inventoryPackage(root, 'crm'), /symlink/);
  ({ root } = fixture()); writeFileSync(join(root, 'V01__duplicate.sql'), 'SELECT 1;');
  assert.throws(() => inventoryPackage(root, 'crm'), /duplicate migration version/);
  ({ root } = fixture()); writeFileSync(join(root, 'V2__empty.sql'), '-- only comments');
  assert.throws(() => inventoryPackage(root, 'crm'), /no executable/);
});

test('missing package identity cannot become the string undefined', () => {
  const { root, contract } = fixture();
  assert.throws(() => inventoryPackage(root), /invalid package ID/);
  contract.dependencies = [null];
  assert.throws(() => auditPackage(root, contract), /dependencies/);
});
