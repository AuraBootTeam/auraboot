import fs from 'node:fs';
import path from 'node:path';
import { isDeepStrictEqual } from 'node:util';
import { auditHifiReport } from '../gates/hifi-golden-results.mjs';
import { canonicalJson, requireCondition, sha256 } from './contract.mjs';

function evidencePath(root, target) {
  const base = fs.realpathSync(root), file = fs.realpathSync(target);
  requireCondition(file.startsWith(base + path.sep) && fs.statSync(file).isFile(), 'Evidence path escapes attempt directory');
  return file;
}
export function readEvidenceFile(root, target) { return fs.readFileSync(evidencePath(root, target)); }

function sessionProof(proof) {
  requireCondition(proof && typeof proof.context === 'string' && proof.context.length > 0
    && proof.afterStatus === 401 && ((proof.beforeStatus === 200 && proof.revoked === true)
      || (proof.beforeStatus === 401 && proof.revoked === false)), 'Invalid exact-session cleanup proof');
}
function sessionTree(value) {
  requireCondition(Array.isArray(value) && value.length > 0, 'Missing exact-session cleanup observations');
  for (const row of value) {
    if (Array.isArray(row)) sessionTree(row);
    else if (row && Object.hasOwn(row, 'role') && (typeof row.role === 'string' || row.role === null) && Array.isArray(row.sessions)) sessionTree(row.sessions);
    else sessionProof(row);
  }
}
function auditObservation(name, value) {
  if (name === 'current-session-cleanup') { sessionProof(value); return 0; }
  requireCondition(value && Array.isArray(value.cleanupErrors) && value.cleanupErrors.length === 0
    && !value.journeyError && (!value.pageErrors || Array.isArray(value.pageErrors) && !value.pageErrors.length), `Failed journey/cleanup: ${name}`);
  if (value.sessionCleanup) sessionTree(value.sessionCleanup);
  const sessionRequired = ['profile-wire-observations','profile-email-only-observations','saved-view-wire-observations',
    'saved-view-browser-observations','team-wire-observations','team-browser-observations','team-role-observations',
    'session-operation-observations','tenant-member-search-observations','session-compatibility-evidence'];
  if (sessionRequired.includes(name)) requireCondition(Array.isArray(value.sessionCleanup) && value.sessionCleanup.length > 0, `Missing owned session cleanup: ${name}`);
  const rows = value.rows ?? value.reads ?? value.checks;
  requireCondition(Array.isArray(rows) && rows.length > 0, `Empty operation observations: ${name}`);
  if (name === 'session-compatibility-evidence') {
    requireCondition(rows.length === 1 && rows[0].path === '/api/auth/me' && rows[0].status === 401
      && Array.isArray(value.widgetResults) && value.widgetResults.length > 0
      && value.widgetResults.every(row => row.status === 200 && row.code === '0'), 'Invalid session compatibility observations');
    return 0;
  }
  for (const row of rows) requireCondition(row.shape?.valid === true && Array.isArray(row.shape.errors)
    && row.shape.errors.length === 0 && Number.isInteger(row.status), `Invalid response structure observation: ${name}`);
  if (name === 'team-role-observations') requireCondition(Array.isArray(value.denials)
    && value.denials.length === 12 && value.denials.every(row => row.status === 403), 'Missing team role refusal probes');
  return rows.length;
}

/** Fixed registered journeys only. This does not certify all API migration or Linux release qualification. */
export function auditBrowserEvidence({ registry, catalog, report, reportBytes, before, after, expected, root, originals, review }) {
  const same = (a, b, message) => requireCondition(isDeepStrictEqual(a, b), message);
  requireCondition(before.schemaVersion === 1 && after.schemaVersion === 1 && typeof before.nonce === 'string'
    && before.nonce.length >= 20 && before.nonce === after.nonce, 'Missing or mismatched attempt identity');
  for (const [key, value] of Object.entries(expected)) {
    requireCondition(value !== undefined && value !== null, `Missing expected identity: ${key}`);
    same(before.identity[key], value, `Before identity mismatch: ${key}`);
    same(after.identity[key], value, `After identity mismatch: ${key}`);
  }
  requireCondition(/^[a-f0-9]{40}$/.test(expected.sourceCommit) && /^[a-f0-9]{64}$/.test(expected.catalogSha256)
    && /^[a-f0-9]{64}$/.test(expected.runtimeManifestSha256), 'Invalid source/catalog/runtime identity');
  requireCondition(/^[a-f0-9]{40}$/.test(expected.sourceTree)
    && ['specSha256','typesSha256','registrySha256'].every(key => /^[a-f0-9]{64}$/.test(expected[key])), 'Missing source input digests');
  same(expected.catalogSha256, sha256(canonicalJson(catalog)), 'Catalog content digest mismatch');
  same(expected.registrySha256, sha256(canonicalJson(registry)), 'Source registry digest mismatch');
  requireCondition(after.exitCode === 0 && after.sourceUnchanged === true
    && after.runtimeVerified === true && before.runtimeVerified === true, 'Runner or source/runtime verification failed');
  const reportSha256 = sha256(reportBytes);
  same(JSON.parse(String(reportBytes)), report, 'Parsed report differs from report bytes');
  same(after.reportSha256, reportSha256, 'Report digest mismatch');
  const start = Date.parse(before.startedAt), end = Date.parse(after.endedAt), runStart = Date.parse(report.stats?.startTime);
  const duration = report.stats?.duration;
  requireCondition(Number.isFinite(start) && Number.isFinite(end) && Number.isFinite(runStart)
    && Number.isFinite(duration) && duration > 0 && end >= start && end >= runStart && runStart >= start && runStart + duration <= end + 1000,
  'Report predates attempt or has invalid execution times');
  const executed = [];
  const visit = suites => { for (const suite of suites) {
    for (const spec of suite.specs ?? []) for (const test of spec.tests ?? []) executed.push({ spec, test, result: test.results[0] });
    visit(suite.suites ?? []);
  } };
  requireCondition(Array.isArray(report.suites), 'Missing report suites');
  visit(report.suites);
  // Preflight paths before the shared validator opens mandatory JSON attachments.
  for (const { result } of executed) for (const attachment of result?.attachments ?? [])
    if (attachment.path) evidencePath(root, attachment.path);
  const counts = auditHifiReport(registry, report);
  let responses = 0;
  for (const { spec, test, result } of executed) {
    const registered = registry.tests.find(row => row.title === spec.title && row.project === test.projectName
      && (row.file === spec.file.replaceAll('\\', '/') || row.file.endsWith('/' + spec.file.replaceAll('\\', '/'))));
    requireCondition(registered, 'Executed test missing source registry');
    const jsonNames = result.attachments.filter(row => row.contentType === 'application/json').map(row => row.name).sort();
    same(jsonNames, registered.requiredEvidence.map(row => row.name).sort(), 'Unexpected JSON evidence');
    for (const required of registered.requiredEvidence) {
      const attachment = result.attachments.find(row => row.name === required.name);
      const bytes = attachment.body ? Buffer.from(attachment.body, 'base64') : readEvidenceFile(root, attachment.path);
      responses += auditObservation(required.name, JSON.parse(String(bytes)));
    }
  }
  requireCondition(Array.isArray(originals) && originals.length === catalog.scenarios.length, 'Missing original images');
  const images = catalog.scenarios.map(scene => {
    const owners = executed.filter(row => row.spec.title === scene.test);
    requireCondition(owners.length === 1, `Missing or duplicate scene owner: ${scene.id}`);
    const { test, result } = owners[0], annotations = [...(test.annotations ?? []), ...(result.annotations ?? [])];
    requireCondition(annotations.some(row => row.type === 'internal-api-scenario' && row.description === scene.id), `Missing scene annotation: ${scene.id}`);
    const attached = result.attachments.filter(row => row.name === scene.id && row.contentType === 'image/png');
    const original = originals.filter(row => row.id === scene.id);
    requireCondition(attached.length === 1 && original.length === 1
      && path.basename(original[0].path) === scene.screenshot, `Missing or duplicated image: ${scene.id}`);
    const originalBytes = readEvidenceFile(root, original[0].path), bytes = readEvidenceFile(root, attached[0].path);
    requireCondition(originalBytes.subarray(0, 8).equals(Buffer.from([137,80,78,71,13,10,26,10]))
      && originalBytes.equals(bytes), `Invalid or mismatched PNG image: ${scene.id}`);
    return { id: scene.id, screenshotPath: original[0].path, screenshotSha256: sha256(bytes), visual: 'pending' };
  });
  requireCondition(new Set(images.map(row => fs.realpathSync(row.screenshotPath))).size === images.length, 'Reused scene original path');
  if (review) {
    same(review.sourceCommit, expected.sourceCommit, 'Review source mismatch');
    same(review.reportSha256, reportSha256, 'Review report mismatch');
    same(review.catalogSha256, expected.catalogSha256, 'Review catalog mismatch');
    requireCondition(Array.isArray(review.rows) && review.rows.length === images.length, 'Missing original reviews');
    for (const image of images) {
      const matches = review.rows.filter(row => row.id === image.id);
      requireCondition(matches.length === 1, `Missing or duplicate original review: ${image.id}`);
      const row = matches[0];
      same(row.screenshotSha256, image.screenshotSha256, `Review image mismatch: ${image.id}`);
      requireCondition(row.verdict === 'pass' && row.reviewMethod === 'individual-original-image-opened'
        && typeof row.finding === 'string' && row.finding.trim().length >= 8, `Original not reviewed/passed: ${image.id}`);
      image.visual = 'pass'; image.finding = row.finding;
    }
  }
  return { schemaVersion: 1, claim: 'registered-browser-catalog-only; not all migration or Linux release acceptance',
    identity: expected, reportSha256, counts, responses, functionalVerdict: 'pass', visualVerdict: review ? 'pass' : 'pending', images };
}
