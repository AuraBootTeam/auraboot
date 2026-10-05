import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export function auditResults(report) {
  const specs = [];
  const visit = (suite) => {
    specs.push(...(suite.specs || []));
    for (const child of suite.suites || []) visit(child);
  };
  for (const suite of report.suites || []) visit(suite);
  const tests = specs.flatMap((spec) => spec.tests || []);
  const counts = { collected: tests.length, executed: 0, passed: 0, failed: 0, interrupted: 0, skipped: 0, didNotRun: 0, retried: 0 };
  for (const test of tests) {
    const results = test.results || [];
    counts.retried += Math.max(0, results.length - 1);
    if (results.length === 1 && results[0].retry !== 0) counts.retried++;
    const result = results.at(-1);
    if (!result) counts.didNotRun++;
    else if (result.status === 'skipped') counts.skipped++;
    else {
      counts.executed++;
      if (result.status === 'interrupted') counts.interrupted++;
      else if (result.status === 'passed' && test.expectedStatus === 'passed') counts.passed++;
      else counts.failed++;
    }
  }
  const valid = counts.executed > 0 && counts.passed === counts.collected &&
    counts.retried === 0 && (report.errors || []).length === 0;
  return { valid, counts };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const result = auditResults(JSON.parse(readFileSync(process.argv[2], 'utf8')));
    console.log(JSON.stringify(result));
    if (!result.valid) process.exitCode = 1;
  } catch (error) {
    console.error(`Execution evidence invalid: ${error.message}`);
    process.exitCode = 1;
  }
}
