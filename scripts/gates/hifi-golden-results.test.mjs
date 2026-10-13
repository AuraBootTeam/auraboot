import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { auditHifiReport, profileGrep } from './hifi-golden-results.mjs';
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

test('profile selection matches literal complete titles', () => {
  const pattern = new RegExp(profileGrep({ tests: [{ title: 'write (A+B) [record]?' }] }));
  assert.ok(pattern.test('oss file suite write (A+B) [record]?'));
  assert.ok(!pattern.test('oss file suite write A+B record'));
  assert.ok(!pattern.test('oss file suite write (A+B) [record]? extra'));
  assert.throws(() => profileGrep({ tests: [] }));
});

const ossProfile = JSON.parse(fs.readFileSync(new URL('./oss-e2e-gate-profile.json', import.meta.url)));
const makeOssReport = () => ({ errors: [], suites: [{ specs: ossProfile.tests.map(row => ({
  file: row.file, title: row.title, tests: [{ projectName: row.project, expectedStatus: 'passed',
    status: 'expected', results: [{ status: 'passed', retry: 0, attachments: (row.requiredEvidence ?? []).map(evidence => ({
      name: evidence.name, contentType: 'application/json', body: Buffer.from(JSON.stringify(evidence.fields)).toString('base64'),
    })) }] }],
})) }] });
for (const row of ossProfile.tests.filter(row => row.id.startsWith('DOP-BROWSER-'))) {
  for (const fault of ['omitted', 'duplicated', 'skipped', 'retried']) {
    test(`${row.id} ${fault} rejects a previously complete OSS report`, () => {
      assert.equal(auditHifiReport(ossProfile, makeOssReport()).passed, ossProfile.tests.length);
      const report = makeOssReport();
      const specs = report.suites[0].specs;
      const index = specs.findIndex(spec => spec.title === row.title);
      if (fault === 'omitted') specs.splice(index, 1);
      if (fault === 'duplicated') specs.push(specs[index]);
      if (fault === 'skipped') specs[index].tests[0].results[0].status = 'skipped';
      if (fault === 'retried') specs[index].tests[0].results[0].retry = 1;
      assert.throws(() => auditHifiReport(ossProfile, report));
      assert.equal(auditHifiReport(ossProfile, makeOssReport()).passed, ossProfile.tests.length);
    });
  }
}

test('mandatory dynamic notification evidence cannot be omitted, duplicated or reduced to static fixture green', () => {
  for (const fault of ['missing', 'duplicate', 'mocked', 'incomplete']) {
    const report = makeOssReport();
    const spec = report.suites[0].specs.find(item => item.title === ossProfile.tests.find(row => row.id === 'DOP-BROWSER-03').title);
    const result = spec.tests[0].results[0];
    if (fault === 'missing') result.attachments = [];
    else if (fault === 'duplicate') result.attachments.push(result.attachments[0]);
    else {
      const value = JSON.parse(Buffer.from(result.attachments[0].body, 'base64'));
      if (fault === 'mocked') value.routeMocks = true;
      else value.cases.pop();
      result.attachments[0].body = Buffer.from(JSON.stringify(value)).toString('base64');
    }
    assert.throws(() => auditHifiReport(ossProfile, report));
    assert.equal(auditHifiReport(ossProfile, makeOssReport()).passed, ossProfile.tests.length);
  }
});
