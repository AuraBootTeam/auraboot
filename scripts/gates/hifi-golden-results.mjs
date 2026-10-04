import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export function auditHifiReport(profile, report, { collection = false, repeat = 1 } = {}) {
  if (!Number.isInteger(repeat) || repeat < 1) throw new Error('repeat must be a positive integer');
  const expected = profile.tests;
  if (!Array.isArray(expected) || !expected.length) throw new Error('empty profile');
  if (new Set(expected.map(t => t.id)).size !== expected.length) throw new Error('duplicate profile ID');
  if (!Array.isArray(report.suites) || !report.suites.length) throw new Error('missing suites / zero tests');
  if (!Array.isArray(report.errors) || report.errors.length) throw new Error('runner errors or missing error ledger');
  const seen = new Map();
  let executed = 0;
  const visit = suites => {
    for (const suite of suites) {
      for (const spec of suite.specs ?? []) {
        for (const test of spec.tests ?? []) {
          const row = expected.find(t => t.title === spec.title && t.project === test.projectName
            && (String(spec.file).replaceAll('\\', '/') === t.file
              || t.file.endsWith('/' + String(spec.file).replaceAll('\\', '/'))));
          if (!row) throw new Error(`unregistered test: ${spec.file} :: ${spec.title} :: ${test.projectName}`);
          seen.set(row.id, (seen.get(row.id) ?? 0) + 1);
          if (test.expectedStatus !== 'passed') throw new Error(`non-pass expectedStatus: ${row.id}`);
          if (!collection) {
            if (test.status !== 'expected' || test.results?.length !== 1
                || test.results[0].status !== 'passed' || test.results[0].retry !== 0) {
              throw new Error(`failed/skipped/retried/incomplete result: ${row.id}`);
            }
            executed++;
          }
        }
      }
      visit(suite.suites ?? []);
    }
  };
  visit(report.suites);
  for (const row of expected) {
    if (seen.get(row.id) !== repeat) throw new Error(`missing or duplicate result: ${row.id}, expected=${repeat}, actual=${seen.get(row.id) ?? 0}`);
  }
  return { collected: expected.length * repeat, executed, passed: executed, failed: 0, skipped: 0 };
}

if (process.argv[1] && fs.realpathSync(path.resolve(process.argv[1])) === fs.realpathSync(fileURLToPath(import.meta.url))) {
  try {
    const [profilePath, reportPath, mode, repeat] = process.argv.slice(2);
    const result = auditHifiReport(JSON.parse(fs.readFileSync(profilePath, 'utf8')),
      JSON.parse(fs.readFileSync(reportPath, 'utf8')), { collection: mode === 'collection', repeat: Number(repeat ?? 1) });
    console.log(JSON.stringify(result));
  } catch (error) {
    console.error(`hifi report rejected: ${error.message}`);
    process.exitCode = 1;
  }
}
