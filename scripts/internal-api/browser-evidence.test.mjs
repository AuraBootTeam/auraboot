import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { auditBrowserEvidence, readEvidenceFile } from './browser-evidence.mjs';
import { canonicalJson, sha256 } from './contract.mjs';

function fixture() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'internal-browser-evidence-'));
  const original = path.join(root, 'SAMPLE-01.png'), attached = path.join(root, 'attachment.png');
  const bytes = Buffer.from([137,80,78,71,13,10,26,10,1]); fs.writeFileSync(original, bytes); fs.writeFileSync(attached, bytes);
  const catalog = { scenarios: [{ id: 'SAMPLE-01', test: 'browser', screenshot: 'SAMPLE-01.png' }] };
  const registry = { tests: [{ id: 'browser', title: 'browser', project: 'contract', file: 'web-admin/tests/e2e/a.spec.ts', requiredEvidence: [{ name: 'wire', fields: {} }] }] };
  const identity = { sourceCommit: 'a'.repeat(40), sourceTree: 'b'.repeat(40), catalogSha256: sha256(canonicalJson(catalog)),
    registrySha256: sha256(canonicalJson(registry)), specSha256: 'c'.repeat(64), typesSha256: 'd'.repeat(64), runtimeManifestSha256: 'e'.repeat(64) };
  const wire = { cleanupErrors: [], journeyError: null, sessionCleanup: [{role: null,sessions: [{context: 'actor',beforeStatus: 200,afterStatus: 401,revoked:true},{context:'already-revoked',beforeStatus:401,afterStatus:401,revoked:false}]}], rows: [{ status: 200, shape: { valid: true, errors: [] } }] };
  const attachment = { name: 'wire', contentType: 'application/json', body: Buffer.from(JSON.stringify(wire)).toString('base64') };
  const report = { errors: [], stats: { startTime: '2026-10-19T00:00:01Z', duration: 1000 }, suites: [{ specs: [{ file: 'e2e/a.spec.ts', title: 'browser', tests: [{ projectName: 'contract', expectedStatus: 'passed', status: 'expected', annotations: [{ type: 'internal-api-scenario', description: 'SAMPLE-01' }], results: [{ status: 'passed', retry: 0, attachments: [attachment, { name: 'SAMPLE-01', contentType: 'image/png', path: attached }] }] }] }] }] };
  const reportBytes = Buffer.from(JSON.stringify(report));
  const before = { schemaVersion: 1, nonce: 'unique-attempt-0000000000001', startedAt: '2026-10-19T00:00:00Z', runtimeVerified: true, identity: structuredClone(identity) };
  const after = { schemaVersion: 1, nonce: before.nonce, endedAt: '2026-10-19T00:00:03Z', runtimeVerified: true, sourceUnchanged: true, exitCode: 0, identity: structuredClone(identity), reportSha256: sha256(reportBytes) };
  const review = { sourceCommit: identity.sourceCommit, catalogSha256: identity.catalogSha256, reportSha256: after.reportSha256,
    rows: [{ id: 'SAMPLE-01', screenshotSha256: sha256(bytes), verdict: 'pass', reviewMethod: 'individual-original-image-opened', finding: 'The registered original was individually inspected.' }] };
  return { root, catalog, registry, report, reportBytes, before, after, expected: identity, originals: [{ id: 'SAMPLE-01', path: original }], review };
}
function withFixture(run) { const f = fixture(); try { run(f); } finally { fs.rmSync(f.root, { recursive: true, force: true }); } }
function rebindReport(f) { f.reportBytes = Buffer.from(JSON.stringify(f.report)); f.after.reportSha256 = sha256(f.reportBytes); f.review.reportSha256 = f.after.reportSha256; }
function roleMemberObservation(f, denials) {
  const name='role-member-browser-observations';
  f.registry.tests[0].requiredEvidence[0].name=name;
  f.expected.registrySha256=sha256(canonicalJson(f.registry));
  f.before.identity.registrySha256=f.after.identity.registrySha256=f.expected.registrySha256;
  const attachment=f.report.suites[0].specs[0].tests[0].results[0].attachments[0];
  const payload=JSON.parse(Buffer.from(attachment.body,'base64'));
  const pid='public-member-pid';payload.expectedMemberPid=pid;
  payload.writes=[{method:'post',path:'/api/roles/{rolePid}/members/assign',body:{memberPids:[pid]}},
    {method:'post',path:'/api/roles/{rolePid}/members/remove',body:[pid]}];
  payload.membershipProofs=[{phase:'candidate',memberPids:[pid],assignedAt:null},
    {phase:'assigned-reload',memberPids:[pid],total:1},{phase:'removed-reload',memberPids:[],total:0},
    {phase:'after-denials',memberPids:[],total:0}];
  payload.denials=denials;attachment.name=name;attachment.body=Buffer.from(JSON.stringify(payload)).toString('base64');
  rebindReport(f);
}
const roleMemberDenials=['anonymous','no-role'].flatMap(actor=>[['get',''],['get','/candidates'],['post','/assign'],['post','/remove']]
  .map(([method,suffix])=>({actor,method,suffix,status:actor==='anonymous'?401:403})));

test('browser evidence separates functional execution from exact original review and source runtime identity', () => withFixture(f => {
  const accepted = auditBrowserEvidence(f); assert.equal(accepted.counts.passed, 1); assert.equal(accepted.responses, 1); assert.equal(accepted.visualVerdict, 'pass');
  roleMemberObservation(f,roleMemberDenials);assert.equal(auditBrowserEvidence(f).responses,1);
  // Distinct journeys may legitimately land on the same login/settings screen.
  const second = path.join(f.root, 'SAMPLE-02.png'); fs.copyFileSync(f.originals[0].path, second);
  f.originals.push({ id: 'SAMPLE-02', path: second });
  f.catalog.scenarios.push({ id: 'SAMPLE-02', test: 'browser', screenshot: 'SAMPLE-02.png' });
  f.report.suites[0].specs[0].tests[0].annotations.push({ type: 'internal-api-scenario', description: 'SAMPLE-02' });
  f.report.suites[0].specs[0].tests[0].results[0].attachments.push({ name: 'SAMPLE-02', contentType: 'image/png', path: second });
  f.review.rows.push({ ...f.review.rows[0], id: 'SAMPLE-02' });
  f.expected.catalogSha256 = sha256(canonicalJson(f.catalog));
  f.before.identity.catalogSha256 = f.after.identity.catalogSha256 = f.review.catalogSha256 = f.expected.catalogSha256;
  rebindReport(f);
  assert.equal(auditBrowserEvidence(f).images.length, 2);
  delete f.review; const functional = auditBrowserEvidence(f); assert.equal(functional.functionalVerdict, 'pass'); assert.equal(functional.visualVerdict, 'pending');
}));

test('browser evidence rejects stale failed skipped retried missing mismatched or unreviewed attempts', () => {
  const mutations = [
    f => { f.after.nonce = 'different'; }, f => { f.before.identity.sourceCommit = 'f'.repeat(40); },
    f => { roleMemberObservation(f,roleMemberDenials.slice(1)); },
    f => { roleMemberObservation(f,roleMemberDenials.map(row=>({...row,status:200}))); },
    f => { roleMemberObservation(f,[roleMemberDenials[1],...roleMemberDenials.slice(1)]); },
    f => { roleMemberObservation(f,roleMemberDenials); const a=f.report.suites[0].specs[0].tests[0].results[0].attachments[0];const p=JSON.parse(Buffer.from(a.body,'base64'));p.writes[1].body={memberPids:['public-member-pid']};a.body=Buffer.from(JSON.stringify(p)).toString('base64');rebindReport(f); },
    f => { roleMemberObservation(f,roleMemberDenials); const a=f.report.suites[0].specs[0].tests[0].results[0].attachments[0];const p=JSON.parse(Buffer.from(a.body,'base64'));p.membershipProofs[2].memberPids=['public-member-pid'];a.body=Buffer.from(JSON.stringify(p)).toString('base64');rebindReport(f); },
    f => { f.after.identity.runtimeManifestSha256 = 'f'.repeat(64); }, f => { f.after.sourceUnchanged = false; },
    f => { f.before.runtimeVerified = false; }, f => { f.after.exitCode = 1; },
    f => { f.after.reportSha256 = 'f'.repeat(64); }, f => { f.before.startedAt = '2026-10-19T00:00:02Z'; },
    f => { f.report.errors.push('runner error'); rebindReport(f); },
    f => { f.report.suites = []; rebindReport(f); },
    f => { f.report.suites[0].specs[0].tests[0].results[0].retry = 1; rebindReport(f); },
    f => { f.report.suites[0].specs[0].tests[0].results[0].status = 'skipped'; rebindReport(f); },
    f => { f.report.suites[0].specs[0].tests[0].results[0].attachments.shift(); rebindReport(f); },
    f => { f.report.suites[0].specs[0].tests[0].results[0].attachments.pop(); rebindReport(f); },
    f => { f.report.suites[0].specs[0].tests[0].annotations = []; rebindReport(f); },
    f => { f.originals = []; }, f => { fs.appendFileSync(f.originals[0].path, 'mismatch'); },
    f => { f.review.rows = []; }, f => { f.review.rows[0].verdict = 'pending'; },
    f => { f.review.rows[0].screenshotSha256 = 'f'.repeat(64); },
    f => { f.review.sourceCommit = 'f'.repeat(40); }, f => { f.review.rows[0].finding = ''; },
    f => { f.report.suites[0].specs[0].tests[0].results[0].attachments[0].body = Buffer.from(JSON.stringify({ cleanupErrors: ['residue'], rows: [] })).toString('base64'); rebindReport(f); },
    f => { f.report.suites[0].specs[0].tests[0].results[0].attachments[0].body = Buffer.from(JSON.stringify({ cleanupErrors: [], rows: [] })).toString('base64'); rebindReport(f); },
  ];
  for (const [index, mutate] of mutations.entries()) withFixture(f => { mutate(f); assert.throws(() => auditBrowserEvidence(f), `mutation ${index}`); });
  withFixture(f => { const outside = fs.mkdtempSync(path.join(os.tmpdir(), 'outside-evidence-')); try {
    fs.writeFileSync(path.join(outside, 'payload.json'), '{}'); fs.symlinkSync(path.join(outside, 'payload.json'), path.join(f.root, 'link.json'));
    assert.throws(() => readEvidenceFile(f.root, path.join(f.root, 'link.json')), /escapes/);
    f.report.suites[0].specs[0].tests[0].results[0].attachments[0] = { name: 'wire', contentType: 'application/json', path: path.join(f.root, 'link.json') }; rebindReport(f);
    assert.throws(() => auditBrowserEvidence(f), /escapes/);
  } finally { fs.rmSync(outside, { recursive: true, force: true }); } });
});
