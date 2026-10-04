import { test } from 'node:test';
import assert from 'node:assert/strict';
import { compareSnapshotCatalog } from './snapshot-schema-contract.mjs';
const constraint = definition => ({ kind: 'constraint', key: 'ab_plugin_resource.chk_resource_type', definition });
const perElement = "CHECK (resource_type::text = ANY (ARRAY['model'::character varying::text, 'named_query'::character varying::text, 'i18n'::character varying::text]))";
const perArray = "CHECK (resource_type::text = ANY (ARRAY['model'::character varying, 'named_query'::character varying, 'i18n'::character varying]::text[]))";
const column = { kind: 'column', key: 'ab_user_session.user_id', definition: '{"type":"bigint","notNull":true}' };
test('admits the two exact PostgreSQL membership deparse forms', () => {
  const r = compareSnapshotCatalog([constraint(perElement), column], [constraint(perArray), column]);
  assert.equal(r.verdict, 'matching-required-baseline'); assert.equal(r.equivalents.length, 1);
});
for (const [name, value] of [
  ['missing member', perArray.replace(", 'named_query'::character varying", '')],
  ['additional member', perArray.replace("'model'", "'page'")],
  ['negated membership', perArray.replace('= ANY', '<> ALL')],
  ['additional condition', perArray.replace(/\)$/, ' OR true)')],
  ['different field', perArray.replace('resource_type::text', 'status::text')],
  ['different cast type', perArray.replace('::text[]', '::name[]')],
  ['unknown expression', 'CHECK (true)'],
]) test(`rejects ${name}`, () => {
  assert.equal(compareSnapshotCatalog([constraint(perElement)], [constraint(value)]).verdict, 'rejected');
});
test('rejects a missing column and changed type or nullability even when the membership check matches', () => {
  for (const rows of [[constraint(perArray)], [constraint(perArray), { ...column, definition: '{"type":"integer","notNull":true}' }], [constraint(perArray), { ...column, definition: '{"type":"bigint","notNull":false}' }]]) {
    assert.equal(compareSnapshotCatalog([constraint(perElement), column], rows).verdict, 'rejected');
  }
});
test('requires nonempty unique schema inventories', () => {
  assert.throws(() => compareSnapshotCatalog([], [column]));
  assert.throws(() => compareSnapshotCatalog([column], []));
  assert.throws(() => compareSnapshotCatalog([column, column], [column]));
});
