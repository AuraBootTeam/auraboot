export function buildLeadPoolScaleConsoleReport(report) {
  return {
    verdict: report.verdict,
    claim: report.claim,
    runId: report.runId,
    datasetSize: report.datasetSize,
    samples: report.samples,
    indexes: report.indexes,
    budgets: report.budgets,
    measurements: report.measurements,
    failures: report.failures,
  };
}
