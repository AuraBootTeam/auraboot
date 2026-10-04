import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { auditResults } from './oss-gate-results.mjs';

function identities(report) {
  const counts = new Map();
  const visit = (suite) => {
    for (const spec of suite.specs || []) {
      for (const test of spec.tests || []) {
        if (!spec.id || !test.projectId) throw new Error('Missing catalog test identity');
        const key = `${spec.id}:${test.projectId}`;
        counts.set(key, (counts.get(key) || 0) + 1);
      }
    }
    for (const child of suite.suites || []) visit(child);
  };
  for (const suite of report.suites || []) visit(suite);
  return counts;
}

export function mergeCatalogReports(catalog, reports) {
  const merged = {
    ...catalog,
    suites: reports.flatMap((report) => report.suites || []),
    errors: [...(catalog.errors || []), ...reports.flatMap((report) => report.errors || [])],
    stats: {
      startTime: reports[0]?.stats?.startTime,
      duration: reports.reduce((sum, report) => sum + (report.stats?.duration || 0), 0),
    },
  };
  const expected = identities(catalog);
  const actual = identities(merged);
  const mismatches = [...new Set([...expected.keys(), ...actual.keys()])]
    .filter((key) => expected.get(key) !== actual.get(key))
    .map((key) => ({ key, expected: expected.get(key) || 0, actual: actual.get(key) || 0 }));
  merged.catalogAudit = {
    collected: [...expected.values()].reduce((sum, count) => sum + count, 0),
    mismatches,
  };
  if (!merged.catalogAudit.collected || mismatches.length) {
    merged.errors.push({ message: 'Full catalog execution identities do not match collection' });
  }
  const execution = auditResults(merged);
  merged.stats.expected = execution.counts.passed;
  merged.stats.unexpected = execution.counts.failed + execution.counts.interrupted + execution.counts.didNotRun;
  merged.stats.skipped = execution.counts.skipped;
  merged.stats.flaky = execution.counts.retried;
  return { report: merged, ...execution };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const [catalogPath, outputPath, ...reportPaths] = process.argv.slice(2);
    if (!catalogPath || !outputPath || !reportPaths.length) throw new Error('Catalog, output and execution reports required');
    const result = mergeCatalogReports(
      JSON.parse(readFileSync(catalogPath, 'utf8')),
      reportPaths.map((path) => JSON.parse(readFileSync(path, 'utf8'))),
    );
    writeFileSync(outputPath, `${JSON.stringify(result.report, null, 2)}\n`);
    console.log(JSON.stringify({ valid: result.valid, counts: result.counts, catalogAudit: result.report.catalogAudit }));
    if (!result.valid) process.exitCode = 1;
  } catch (error) {
    console.error(`Full execution evidence invalid: ${error.message}`);
    process.exitCode = 1;
  }
}
