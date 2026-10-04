import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mergeCatalogReports } from './oss-gate-catalog-results.mjs';

const report = (ids, status = 'passed') => ({
  suites: [{ specs: ids.map((id) => ({ id, tests: [{ projectId: 'oss', expectedStatus: 'passed', results: status ? [{ status, retry: 0 }] : [] }] })) }],
  errors: [],
});
const catalog = report(['pristine', 'remaining'], null);

test('accepts every catalog identity executed once across both phases', () => {
  const result = mergeCatalogReports(catalog, [report(['pristine']), report(['remaining'])]);
  assert.equal(result.valid, true);
  assert.equal(result.counts.executed, 2);
});
test('rejects a missing identity even when all observed tests pass', () => {
  assert.equal(mergeCatalogReports(catalog, [report(['pristine'])]).valid, false);
});
test('rejects a duplicate substituted for an omitted identity', () => {
  assert.equal(mergeCatalogReports(catalog, [report(['pristine', 'pristine'])]).valid, false);
});
test('rejects an unexpected test added to the full denominator', () => {
  assert.equal(mergeCatalogReports(catalog, [report(['pristine', 'remaining', 'extra'])]).valid, false);
});
for (const status of [null, 'skipped', 'failed']) {
  test(`rejects ${status || 'collection-only'} execution evidence`, () => {
    assert.equal(mergeCatalogReports(catalog, [report(['pristine']), report(['remaining'], status)]).valid, false);
  });
}
test('preserves a phase infrastructure error despite passing tests', () => {
  const phase = report(['remaining']);
  phase.errors.push({ message: 'worker crashed' });
  assert.equal(mergeCatalogReports(catalog, [report(['pristine']), phase]).valid, false);
});

test('merged phase statistics preserve the strict final execution contract', () => {
  const merged = mergeCatalogReports(catalog, [report(['pristine']), report(['remaining'])]).report;
  assert.deepEqual({expected: merged.stats.expected, unexpected: merged.stats.unexpected, skipped: merged.stats.skipped, flaky: merged.stats.flaky},
    {expected: 2, unexpected: 0, skipped: 0, flaky: 0});
  const red = mergeCatalogReports(catalog, [report(['pristine']), report(['remaining'], 'skipped')]);
  assert.equal(red.valid, false);
  assert.equal(red.report.stats.skipped, 1);
});
