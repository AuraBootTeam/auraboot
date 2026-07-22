import test from 'node:test';
import assert from 'node:assert/strict';
import { compareManifests } from './check-coverage-manifest-freshness.mjs';

// Each case is a drift that SHOULD be caught. A freshness gate that has never
// seen a stale file is indistinguishable from one that does not look.

const m = (rows, untested) => ({
  groups: [{ id: 'p', title: 'p', rows: rows.map((id) => ({ id, action: id })) }],
  stats: { commands: rows.length, untested },
});

test('an unchanged manifest is silent', () => {
  assert.deepEqual(compareManifests(m(['a', 'b'], 1), m(['a', 'b'], 1)).findings, []);
});

test('a command with no row is an error — the denominator shrank silently', () => {
  const r = compareManifests(m(['a'], 0), m(['a', 'b'], 1));
  assert.ok(r.findings.some((f) => f.level === 'error' && f.kind === 'missing-row'));
});

test('coverage going backwards is an error', () => {
  const r = compareManifests(m(['a', 'b'], 0), m(['a', 'b'], 2));
  assert.ok(r.findings.some((f) => f.kind === 'coverage-regressed'));
});

test('coverage improving is not an error — being ahead of the file is allowed', () => {
  assert.deepEqual(compareManifests(m(['a', 'b'], 2), m(['a', 'b'], 0)).findings, []);
});

test('a row for a command that no longer exists warns rather than blocks', () => {
  const r = compareManifests(m(['a', 'gone'], 0), m(['a'], 0));
  assert.deepEqual(r.findings.map((f) => f.level), ['warn']);
});
