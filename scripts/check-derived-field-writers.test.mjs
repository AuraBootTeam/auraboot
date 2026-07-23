import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { auditDerivedWriters } from './check-derived-field-writers.mjs';

// Each case builds a plugin that SHOULD be red and asserts it is, then the
// clean case asserts silence. bom is already fixed, so without a fixture this
// gate would ship never having been seen red.

function makePlugin({ fields = [], bindings = [], commands = [] } = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'derived-'));
  const cfg = path.join(root, 'plugins', 'demo', 'config');
  fs.mkdirSync(cfg, { recursive: true });
  fs.writeFileSync(path.join(cfg, 'fields.json'), JSON.stringify({ fields }));
  fs.writeFileSync(path.join(cfg, 'bindings.json'), JSON.stringify({ bindings }));
  fs.writeFileSync(path.join(cfg, 'commands.json'), JSON.stringify({ commands }));
  return path.join(root, 'plugins');
}

const errs = (r) => r.findings.filter((f) => f.level === 'error');
const kinds = (r) => r.findings.filter((f) => f.level === 'error').map((f) => f.kind);

const DERIVED = { code: 'm_norm', feature: { derived: true } };
const BIND = { fieldCode: 'm_norm', modelCode: 'thing' };

test('a declarative create that can write a derived field is a bypass', () => {
  const root = makePlugin({
    fields: [DERIVED], bindings: [BIND],
    commands: [{ code: 'd:create_thing', type: 'create', modelCode: 'thing', inputFields: ['m_name', 'm_norm'] }],
  });
  const r = auditDerivedWriters({ roots: [root] });
  assert.deepEqual(kinds(r), ['bypass']);
  assert.equal(errs(r)[0].field, 'm_norm');
});

test('the same command with a handler is fine — the handler runs the deriver', () => {
  const root = makePlugin({
    fields: [DERIVED], bindings: [BIND],
    commands: [{ code: 'd:create_thing', type: 'custom', handler: 'd:create_thing', modelCode: 'thing', inputFields: ['m_name', 'm_norm'] }],
  });
  assert.deepEqual(auditDerivedWriters({ roots: [root] }).findings, []);
});

test('a declarative command that does NOT list the derived field is fine', () => {
  const root = makePlugin({
    fields: [DERIVED], bindings: [BIND],
    commands: [{ code: 'd:create_thing', type: 'create', modelCode: 'thing', inputFields: ['m_name'] }],
  });
  assert.deepEqual(auditDerivedWriters({ roots: [root] }).findings, []);
});

test('model binding, not prefix, decides ownership', () => {
  // m_norm is bound to `thing`; a declarative writer on a DIFFERENT model that
  // merely shares the name prefix must not be flagged.
  const root = makePlugin({
    fields: [DERIVED], bindings: [BIND],
    commands: [{ code: 'd:create_other', type: 'create', modelCode: 'other', inputFields: ['m_norm'] }],
  });
  assert.deepEqual(auditDerivedWriters({ roots: [root] }).findings, []);
});

test('a derived field bound to no model warns rather than crashing', () => {
  const root = makePlugin({ fields: [DERIVED], bindings: [], commands: [] });
  const r = auditDerivedWriters({ roots: [root] });
  assert.deepEqual(r.findings.map((f) => f.kind), ['unbound-derived']);
});

test('a bypass can be silenced only with a reason', () => {
  const commands = [{ code: 'd:create_thing', type: 'create', modelCode: 'thing', inputFields: ['m_norm'] }];
  const withReason = makePlugin({ fields: [DERIVED], bindings: [BIND], commands });
  assert.deepEqual(
    auditDerivedWriters({ roots: [withReason], config: { allow: { demo: { 'd:create_thing::m_norm': 'legacy import, deriver runs post-hoc' } } } }).findings, []);
  const noReason = makePlugin({ fields: [DERIVED], bindings: [BIND], commands });
  assert.deepEqual(
    kinds(auditDerivedWriters({ roots: [noReason], config: { allow: { demo: { 'd:create_thing::m_norm': '' } } } })), ['allow-without-reason']);
});

test('a plugin with no derived fields is silent, not scanned into noise', () => {
  const root = makePlugin({
    fields: [{ code: 'm_name' }], bindings: [{ fieldCode: 'm_name', modelCode: 'thing' }],
    commands: [{ code: 'd:create_thing', type: 'create', modelCode: 'thing', inputFields: ['m_name'] }],
  });
  assert.deepEqual(auditDerivedWriters({ roots: [root] }).findings, []);
});

test('a missing root fails loudly rather than reporting nothing wrong', () => {
  assert.deepEqual(kinds(auditDerivedWriters({ roots: ['/no/such/plugins'] })), ['missing-root']);
});
