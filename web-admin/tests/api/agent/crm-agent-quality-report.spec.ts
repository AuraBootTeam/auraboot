import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { expect, test } from '@playwright/test';

test.describe('CRM Agent routing quality report', () => {
  test('AV-07 aggregates tool routing, fallback, confirmation, and retry metrics', async ({}, testInfo) => {
    const evidenceDir = testInfo.outputPath('agent-evidence');
    fs.mkdirSync(evidenceDir, { recursive: true });

    fs.writeFileSync(
      path.join(evidenceDir, 'agent-evidence-S1-01.json'),
      JSON.stringify({
        scenarioId: 'S1-01',
        coverageLevel: 'L3',
        error: null,
        confirmations: [],
        transientRetryCount: 0,
        toolCalls: [
          { toolName: 'nq_crm_lead_pipeline_stats', success: true, total: 4 },
          { toolName: 'platform_execute_sql', success: true, total: 1 },
        ],
      }),
    );
    fs.writeFileSync(
      path.join(evidenceDir, 'agent-evidence-S2-05.json'),
      JSON.stringify({
        scenarioId: 'S2-05',
        coverageLevel: 'L3',
        error: null,
        confirmations: [
          { toolId: 'confirm-1', toolName: 'cmd_crm_convert_lead', input: { recordId: '01TEST' } },
        ],
        transientRetryCount: 2,
        toolCalls: [
          { toolName: 'cmd_crm_convert_lead', success: true },
          { toolName: 'cmd_crm_missing_tool', success: false, error: 'Tool unavailable' },
        ],
      }),
    );

    const reportModule = await import(
      pathToFileURL(path.resolve('tests/api/agent/crm-agent-quality-report.mjs')).href
    );
    const report = reportModule.buildRoutingQualityReport(evidenceDir, {
      generatedAt: '2026-04-27T00:00:00.000Z',
    });

    expect(report.summary).toEqual({
      scenarioCount: 2,
      toolCallCount: 4,
      unavailableToolCount: 1,
      sqlFallbackCount: 1,
      confirmationCount: 1,
      retryCount: 2,
    });
    expect(report.scenarios.map((scenario: any) => scenario.scenarioId)).toEqual([
      'S1-01',
      'S2-05',
    ]);
    expect(report.scenarios.find((scenario: any) => scenario.scenarioId === 'S2-05')).toEqual(
      expect.objectContaining({
        toolCallCount: 2,
        unavailableToolCount: 1,
        confirmationCount: 1,
        retryCount: 2,
      }),
    );
  });
});
