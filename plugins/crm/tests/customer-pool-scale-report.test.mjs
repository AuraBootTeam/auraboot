import assert from 'node:assert/strict';
import test from 'node:test';

import { buildCustomerPoolScaleConsoleReport } from '../scripts/lib/customer-pool-scale-report.mjs';

test('customer-pool scale console report omits environment-derived database and actor identifiers', () => {
  const consoleReport = buildCustomerPoolScaleConsoleReport({
    verdict: 'pass',
    claim: 'database evidence only',
    database: 'secret-database',
    tenantId: 911,
    userId: 922,
    userPid: 'secret-user-pid',
    runId: 'fixed-run',
    datasetSize: 10000,
    samples: 30,
    marker: 'secret-marker',
    indexes: [],
    budgets: {},
    measurements: {},
    failures: [],
  });

  assert.deepEqual(Object.keys(consoleReport), [
    'verdict',
    'claim',
    'runId',
    'datasetSize',
    'samples',
    'indexes',
    'budgets',
    'measurements',
    'failures',
  ]);
  assert.doesNotMatch(JSON.stringify(consoleReport), /secret-database|secret-user-pid|secret-marker|911|922/);
});
