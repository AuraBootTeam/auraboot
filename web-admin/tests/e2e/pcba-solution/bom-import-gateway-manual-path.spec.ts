import { test, expect } from '../../fixtures';
import { cleanupRows, dynamicCreate, type CreatedRows } from './quote-e2e-helpers';

const WORKBENCH = '/p/bom_conversion_task_pcba_workbench';

/**
 * Browser ownership for the manual import path stops at the interaction seam:
 * an adjustment-required task exposes its evidence panel and requires an
 * explicit confirmation before dispatch.
 *
 * Route selection and command execution are deterministic backend contracts
 * owned by BomImportPreAnalyzerTest, BomImportGatewayDecisionTest and
 * BomImportGatewayHandlersTest. Re-running several customer workbooks until one
 * happened to park made this browser test depend on the current material
 * library and LLM quality, duplicated those backend tests, and cost minutes.
 */
test.describe('BOM import gateway manual path @smoke', () => {
  test('an adjustment-required task exposes evidence and guards confirmation', async ({ page }) => {
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

      const confirmButton = page
        .getByRole('button', {
          name: /确认并开始匹配|Confirm and Start Matching/i,
        })
        .first();
      await expect(confirmButton, 'manual confirmation remains reachable').toBeVisible({
        timeout: 20_000,
      });
      await confirmButton.click();

      const dialog = page.getByRole('dialog').last();
      await expect(
        dialog,
        'the operator must explicitly accept the identified field sources',
      ).toContainText(
        /确认系统识别的字段来源，并开始匹配物料|Confirm the identified field sources/i,
        { timeout: 15_000 },
      );
      await dialog
        .getByRole('button', { name: /取消|Cancel/i })
        .last()
        .click();
      await expect(dialog).toHaveCount(0);
    } finally {
      await cleanupRows(page, created);
    }
  });
});
