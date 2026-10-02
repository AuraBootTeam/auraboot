import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';

export const expectedTitles = [
  'prefix and keyword filters preserve all 21 records across both pages',
  'authenticated baseline member is denied every admin CRUD and review endpoint',
  'create, edit, submit, reject with reason, approve and delete through the admin UI',
  'real stale-review rejection preserves reason and dialog on a business failure',
];
export function validateReport(report, now = Date.now()) {
  const start = Date.parse(report.stats?.startTime);
  const duration = report.stats?.duration;
  if (!Number.isFinite(start) || !Number.isFinite(duration) || duration < 0
      || start > now || start + duration > now + 5000 || now - start - duration > 600000) {
    throw new Error('Missing, future or stale browser execution timestamp');
  }
  if (report.errors?.length) throw new Error('Browser runner errors');
  const cases = [];
  function visit(suites) {
    for (const suite of suites ?? []) {
      for (const spec of suite.specs ?? []) for (const test of spec.tests ?? []) cases.push({ title: spec.title, test });
      visit(suite.suites);
    }
  }
  visit(report.suites);
  if (cases.length !== expectedTitles.length) throw new Error('Missing or extra browser cases');
  for (const title of expectedTitles) {
    const matches = cases.filter(c => c.title === title);
    if (matches.length !== 1) throw new Error(`Missing or duplicate browser case: ${title}`);
    const test = matches[0].test;
    if (test.projectName !== 'chromium' || test.status !== 'expected' || test.results?.length !== 1
        || test.results[0].status !== 'passed' || (test.results[0].retry ?? 0) !== 0) {
      throw new Error(`Failed, skipped, retried or wrong-project browser case: ${title}`);
    }
  }
  return { completedAt: new Date(start + duration).toISOString(), executed: cases.length };
}
if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(import.meta.filename)) {
  const [reportPath, runtime, repo, expectedCommit] = process.argv.slice(2);
  if (!reportPath || !runtime || !repo || !/^[a-f0-9]{40}$/.test(expectedCommit ?? '')) throw new Error('Usage: <report.json> <runtime> <repo> <expected-commit>');
  const bytes = fs.readFileSync(reportPath);
  const result = validateReport(JSON.parse(bytes));
  const commit = execFileSync('git', ['-C', repo, 'rev-parse', 'HEAD'], { encoding: 'utf8' }).trim();
  const dirty = execFileSync('git', ['-C', repo, 'status', '--porcelain'], { encoding: 'utf8' }).trim();
  if (dirty || commit !== expectedCommit) throw new Error('Browser source changed during acceptance');
  fs.writeFileSync(path.join(path.dirname(reportPath), 'i18n-browser-receipt.json'), JSON.stringify({
    schemaVersion: 1, runtime, repo, commit, ...result, passed: result.executed, failed: 0, skipped: 0,
    report: path.resolve(reportPath), reportSha256: createHash('sha256').update(bytes).digest('hex'),
    verdict: 'pass', scope: 'Four i18n admin browser cases; tenant isolation, AI and visual review remain separate',
  }, null, 2) + '\n', { flag: 'wx' });
}
