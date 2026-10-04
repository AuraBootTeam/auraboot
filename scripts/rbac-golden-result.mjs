import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';

const [reportPath, repeatArg, runtime, repo] = process.argv.slice(2);
const repeat = Number(repeatArg);
if (!reportPath || !Number.isInteger(repeat) || repeat < 1 || !runtime || !repo) {
  throw new Error('Usage: rbac-golden-result.mjs <report.json> <repeat> <runtime> <repo>');
}
const reportBytes = fs.readFileSync(reportPath);
const report = JSON.parse(reportBytes);
const startedAt = Date.parse(report.stats?.startTime);
const duration = report.stats?.duration;
const completedAt = startedAt + duration;
const now = Date.now();
if (!Number.isFinite(startedAt) || !Number.isFinite(duration) || duration < 0
    || startedAt > now || completedAt > now + 5000 || now - completedAt > 10 * 60 * 1000) {
  throw new Error('Missing, future or stale execution timestamp; old reports cannot become a new nightly PASS');
}
const cases = [];
function visit(suites) {
  for (const suite of suites ?? []) {
    for (const spec of suite.specs ?? []) for (const test of spec.tests ?? []) {
      cases.push({ title: spec.title, project: test.projectName, status: test.status, results: test.results });
    }
    visit(suite.suites);
  }
}
visit(report.suites);
const titles = [
  'tenant_admin resolves the full matrix surface and sees admin-tier menus',
  'tenant_member resolves only the L1 baseline, sees no admin-tier menu, and is admitted to the app',
];
if (cases.length !== titles.length * repeat || report.errors?.length) throw new Error('RBAC golden execution count or runner errors invalid');
for (const title of titles) {
  const roleCases = cases.filter(test => test.title === title && test.project === 'chromium');
  if (roleCases.length !== repeat) throw new Error(`Missing or duplicated role result: ${title}`);
  for (const test of roleCases) {
    if (test.status !== 'expected' || test.results?.length !== 1 || test.results[0].status !== 'passed') {
      throw new Error(`Failed, skipped or retried role case: ${title}`);
    }
  }
}
const receiptPath = path.join(path.dirname(reportPath), 'rbac-receipt.json');
if (fs.existsSync(receiptPath)) throw new Error('Refusing to overwrite existing nightly receipt');
const completed = new Date(completedAt);
const receipt = {
  schemaVersion: 1, runtime, completedAt: completed.toISOString(),
  date: new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(completed),
  repo, commit: execFileSync('git', ['-C', repo, 'rev-parse', 'HEAD'], { encoding: 'utf8' }).trim(),
  report: path.resolve(reportPath), reportSha256: createHash('sha256').update(reportBytes).digest('hex'),
  executed: cases.length, passed: cases.length, failed: 0, skipped: 0,
  verdict: 'pass', scope: 'RBAC platform-baseline two browser roles; backend IT and full permission lifecycle separate',
};
fs.writeFileSync(receiptPath, JSON.stringify(receipt, null, 2) + '\n', { flag: 'wx' });
console.log(`[rbac-golden-result] successful execution receipt: ${receiptPath}`);
