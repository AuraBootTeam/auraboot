import { test } from 'node:test';
import assert from 'node:assert/strict';
import { expectedTitles, validateReport } from './i18n-admin-browser-result.mjs';
function report() {
  return { stats: { startTime: new Date(Date.now() - 1000).toISOString(), duration: 500 }, suites: [{ specs: expectedTitles.map(title => ({ title, tests: [{ projectName: 'chromium', status: 'expected', results: [{ status: 'passed', retry: 0 }] }] })) }] };
}
test('exact four successful browser cases are accepted', () => assert.equal(validateReport(report()).executed, 4));
for (const [name, mutate] of [
  ['empty execution', r => { r.suites = []; }],
  ['duplicate title', r => { r.suites[0].specs[1] = r.suites[0].specs[0]; }],
  ['skipped case', r => { r.suites[0].specs[0].tests[0].results[0].status = 'skipped'; }],
  ['failed case', r => { r.suites[0].specs[0].tests[0].results[0].status = 'failed'; }],
  ['wrong project', r => { r.suites[0].specs[0].tests[0].projectName = 'setup'; }],
  ['retry disguised as one pass', r => { r.suites[0].specs[0].tests[0].results[0].retry = 1; }],
  ['runner error', r => { r.errors = [{ message: 'worker crashed' }]; }],
  ['stale timestamp', r => { r.stats.startTime = new Date(Date.now() - 86400000).toISOString(); }],
]) test(`rejects false browser green: ${name}`, () => { const r = report(); mutate(r); assert.throws(() => validateReport(r)); });
