import { test, expect } from '../../fixtures';
import { cleanupRows, dynamicCreate, type CreatedRows } from './quote-e2e-helpers';

const WORKBENCH = '/p/bom_conversion_task_pcba_workbench';

/**
 * Browser ownership for this synthetic task stops at the presentation seam:
 * an adjustment-required task exposes its evidence panel, but a task with no
 * executable issue-decision rows must not invent a confirmation action.
 *
 * Route selection and command execution are deterministic backend contracts
 * owned by BomImportPreAnalyzerTest, BomImportGatewayDecisionTest and
 * BomImportGatewayHandlersTest. The real E15 browser smoke owns the complete
 * atomic-decision journey with persisted issue rows.
 */
test.describe('BOM import gateway manual path @smoke', () => {
  test('a task without issue decisions exposes evidence but no fake confirmation', async ({ page }) => {
    const created: CreatedRows = { quoteId: '', quoteCode: '', rows: [] };
    const suffix = `${Date.now()}${Math.random().toString(16).slice(2, 8)}`;
    const taskNo = `E2E-GATEWAY-MANUAL-${suffix}`;

    try {
      const taskPid = await dynamicCreate(
        page,
        'bom_conversion_task_pcba',
        {
          bom_task_no: taskNo,
          bom_task_source_package: `manual-path-${suffix}`,
          bom_task_status: 'adjustment_required',
          bom_task_raw_filename: `${taskNo}.xlsx`,
          bom_task_total_rows: 1,
          bom_task_header_mapping: JSON.stringify({
            importGatewayDecision: {
              nextAction: 'CONFIRM_FIELDS',
              questionCount: 0,
              requiresHumanReview: true,
            },
          }),
        },
        created.rows,
      );

      await page.goto(`${WORKBENCH}/view/${taskPid}`, {
        waitUntil: 'domcontentloaded',
      });

      const openEvidence = page
        .getByRole('button', {
          name: /查看识别依据|View Recognition Details/i,
        })
        .first();
      await expect(openEvidence, 'manual-path evidence entrance').toBeVisible({
        timeout: 30_000,
      });
      await openEvidence.click();
      await expect(
        page
          .getByRole('button', {
            name: /收起识别依据|Hide Recognition Details/i,
          })
          .first(),
        'evidence panel toggles to its open state',
      ).toBeVisible({ timeout: 20_000 });

      await expect(
        page.getByRole('button', {
          name: /确认并开始匹配|Confirm and Start Matching/i,
        }),
        'a header-only synthetic task must not expose a non-executable confirmation action',
      ).toHaveCount(0);
      await expect(
        page.getByText(/确认字段来源|Confirm the source column/i),
        'a task with zero projected questions must not invent source questions',
      ).toHaveCount(0);
    } finally {
      await cleanupRows(page, created);
    }
  });
});
