import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { auditHifiReport } from './hifi-golden-results.mjs';
const profile = JSON.parse(fs.readFileSync(new URL('./hifi-golden-profile.json', import.meta.url)));
const makeReport = () => ({ errors: [], suites: [{ specs: profile.tests.map(t => ({
  file: t.file, title: t.title, tests: [{ projectName: t.project, expectedStatus: 'passed',
    status: 'expected', results: [{ status: 'passed', retry: 0 }] }],
})) }] });
test('collection never counts as execution', () => {
  const report = makeReport();
  for (const spec of report.suites[0].specs) spec.tests[0].results = [];
  assert.equal(auditHifiReport(profile, report, { collection: true }).executed, 0);
  assert.throws(() => auditHifiReport(profile, report));
});
test('complete exact profile is accepted', () => {
  assert.equal(auditHifiReport(profile, makeReport()).passed, profile.tests.length);
});
for (const [name, mutate] of [
  ['zero tests', r => { r.suites = []; }],
  ['missing test', r => { r.suites[0].specs.pop(); }],
  ['duplicate test', r => { r.suites[0].specs.push(r.suites[0].specs[0]); }],
  ['unregistered test', r => { r.suites[0].specs[0].title = 'unknown'; }],
  ['wrong project', r => { r.suites[0].specs[0].tests[0].projectName = 'other'; }],
  ['failure', r => { r.suites[0].specs[0].tests[0].results[0].status = 'failed'; }],
  ['skip', r => { r.suites[0].specs[0].tests[0].expectedStatus = 'skipped'; }],
  ['retry', r => { r.suites[0].specs[0].tests[0].results[0].retry = 1; }],
  ['early exit', r => { r.suites[0].specs[0].tests[0].results = []; }],
  ['runner error', r => { r.errors.push({ message: 'failed worker' }); }],
]) test(`${name} fails closed`, () => {
  const report = makeReport(); mutate(report); assert.throws(() => auditHifiReport(profile, report));
});
test('restoring a rejected report restores green', () => {
  const report = makeReport(); report.suites[0].specs.pop();
  assert.throws(() => auditHifiReport(profile, report));
  assert.equal(auditHifiReport(profile, makeReport()).passed, profile.tests.length);
});
