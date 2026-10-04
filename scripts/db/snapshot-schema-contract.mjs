import assert from 'node:assert/strict';

// PostgreSQL can deparse an IN check as per-element casts or an array cast.
// Admit only the exact resource-type membership grammar, never arbitrary SQL.
function resourceTypes(definition) {
  const match = /^CHECK \(resource_type::text = ANY \(ARRAY\[([^\[\]]+)\](::text\[\])?\)\)$/.exec(definition);
  if (!match) return null;
  const parts = match[1].split(', ');
  const names = [];
  for (const part of parts) {
    const value = /^'([a-z][a-z0-9_]*)'::character varying(::text)?$/.exec(part);
    if (!value || Boolean(value[2]) === Boolean(match[2])) return null;
    names.push(value[1]);
  }
  return JSON.stringify([...new Set(names)].sort());
}
function index(rows) {
  assert(Array.isArray(rows) && rows.length > 0, 'nonempty schema inventory required');
  const result = new Map();
  for (const row of rows) {
    assert(typeof row.kind === 'string' && typeof row.key === 'string'
      && typeof row.definition === 'string' && row.definition.length > 0, 'invalid schema object');
    const key = `${row.kind}:${row.key}`;
    assert(!result.has(key), 'duplicate schema object');
    result.set(key, row.definition);
  }
  return result;
}
export function compareSnapshotCatalog(expected, actual) {
  const baseline = index(expected), target = index(actual), differences = [], equivalents = [];
  for (const [key, definition] of baseline) {
    const value = target.get(key);
    if (definition === value) continue;
    if (key === 'constraint:ab_plugin_resource.chk_resource_type' && value) {
      const left = resourceTypes(definition), right = resourceTypes(value);
      if (left !== null && left === right) {
        equivalents.push({ key, reason: 'same exact resource_type text membership set; element/array cast deparse only' });
        continue;
      }
    }
    differences.push({ key, expected: definition, actual: value ?? null });
  }
  return { verdict: differences.length ? 'rejected' : 'matching-required-baseline',
    expectedObjects: baseline.size, actualObjects: target.size, differences, equivalents };
}
