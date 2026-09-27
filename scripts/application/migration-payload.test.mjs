import assert from 'node:assert/strict';
import { chmodSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { inventoryPackage } from './schema-ownership-audit.mjs';
import { stagePayload, verifyPayload } from './migration-payload.mjs';

function fixture() {
  const root = mkdtempSync(join(tmpdir(), 'migration-payload-'));
  const source = join(root, 'V1__record.sql');
  writeFileSync(source, 'CREATE TABLE crm_record(id bigint);');
  const contract = { ...inventoryPackage(root, 'crm'), dependencies: [], objects: {
    crm_record: { owner: 'crm', lifecycle: 'static-schema', evidence: 'fixture storage' },
  } };
  Object.assign(contract.migrations[0].statements[0], { review: 'approved', rationale: 'fixture owner',
    objects: [{ name: 'crm_record', operation: 'create' }] });
  writeFileSync(join(root, 'review.json'), JSON.stringify(contract));
  const input = join(root, 'composition.json');
  writeFileSync(input, JSON.stringify({ schemaVersion: 1, packages: [{ root: '.', contract: 'review.json' }] }));
  // Output is a sibling; source inventories never hide nested output directories.
  const output = join(mkdtempSync(join(tmpdir(), 'migration-output-')), 'payload');
  return { root, source, input, output, contract };
}

test('staged payload is portable and does not consult the original checkout', () => {
  const f = fixture(); const result = stagePayload(f.input, f.output);
  rmSync(f.root, { recursive: true });
  const verified = verifyPayload(f.output, result.manifestSha256);
  assert.equal(verified.migrationCount, 1);
  assert.equal(readFileSync(join(f.output, 'packages/crm/sql/V1__record.sql'), 'utf8'), 'CREATE TABLE crm_record(id bigint);');
  assert.equal(readFileSync(join(f.output, 'payload.json'), 'utf8').includes(f.root), false);
  assert.throws(() => verifyPayload(f.output), /externally pinned/);
});

test('draft review never creates a usable payload', () => {
  const f = fixture(); f.contract.migrations[0].statements[0].review = 'draft';
  writeFileSync(join(f.root, 'review.json'), JSON.stringify(f.contract));
  assert.throws(() => stagePayload(f.input, f.output), /unreviewed/);
});

test('manifest and SQL tampering are independently detected', () => {
  let f = fixture(); let result = stagePayload(f.input, f.output);
  const manifest = join(f.output, 'payload.json'); chmodSync(manifest, 0o644);
  writeFileSync(manifest, readFileSync(manifest, 'utf8') + ' ');
  assert.throws(() => verifyPayload(f.output, result.manifestSha256), /manifest digest mismatch/);
  f = fixture(); result = stagePayload(f.input, f.output);
  const sql = join(f.output, 'packages/crm/sql/V1__record.sql'); chmodSync(sql, 0o644);
  writeFileSync(sql, 'SELECT 1;');
  assert.throws(() => verifyPayload(f.output, result.manifestSha256), /file digest mismatch/);
});

test('missing, extra, symlink and pending files invalidate an otherwise valid payload', () => {
  for (const mutation of ['missing', 'extra', 'link', 'pending']) {
    const f = fixture(); const result = stagePayload(f.input, f.output);
    const sql = join(f.output, 'packages/crm/sql/V1__record.sql');
    if (mutation === 'missing') rmSync(sql);
    if (mutation === 'extra') writeFileSync(join(f.output, 'injected.sql'), 'SELECT 1;');
    if (mutation === 'link') { rmSync(sql); symlinkSync(f.source, sql); }
    if (mutation === 'pending') writeFileSync(join(f.output, '.incomplete'), 'incomplete');
    assert.throws(() => verifyPayload(f.output, result.manifestSha256), /denominator|symlink/);
  }
});

test('assembly is exclusive and deterministic for identical reviewed inputs', () => {
  const f = fixture(); const result = stagePayload(f.input, f.output);
  assert.throws(() => stagePayload(f.input, join(f.root, 'payload')), /outside source/);
  assert.throws(() => stagePayload(f.input, f.output), /EEXIST/);
  const second = stagePayload(f.input, `${f.output}-second`);
  assert.equal(result.manifestSha256, second.manifestSha256);
});
