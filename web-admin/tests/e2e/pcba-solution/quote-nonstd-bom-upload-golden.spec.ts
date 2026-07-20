import type { Locator } from '@playwright/test';
import { test, expect } from '../../fixtures';
import {
  createNonStandardBomWorkbook,
  isTransientViteDynamicImportIssue,
  openQuoteDetailFromList,
  queryDynamicRecords,
  seedQuoteForCorrectedBomUpload,
  type CreatedRows,
} from './quote-e2e-helpers';

function parseSnapshot(value: unknown): Record<string, unknown> {
  if (value && typeof value === 'object') return value as Record<string, unknown>;
  if (typeof value !== 'string') return {};
  try {
    const parsed = JSON.parse(value);
    return parsed && typeof parsed === 'object' ? (parsed as Record<string, unknown>) : {};
  } catch {
    return {};
  }
}

async function tableTexts(locator: Locator): Promise<string[]> {
  const count = await locator.count();
  expect(count, 'table must expose semantic cells').toBeGreaterThan(0);
  return locator.evaluateAll((nodes) =>
    nodes.map((node) => (node.textContent || '').replace(/\s+/g, ' ').trim()),
  );
}

/**
 * Golden dimensions: D1 menu entry, D2 rendered business rows, D14 upload feedback, S9 browser +
 * backend evidence. The core action is the UI file upload. API reads only verify its persisted
 * side effects; there is deliberately no direct batch_source_prices or compute_process_fee call.
 *
 * The workbook is a non-standard customer BOM. Its first data row has a blank package but contains
 * a standalone 0201 token in the description, reproducing the reported process-fee match case.
 */
test.describe('QuoteOps non-standard quick-quote (upload-bom) golden', () => {
  test.describe.configure({ timeout: 150_000 });

  let created: CreatedRows;

  test.beforeEach(async ({ page }) => {
    created = await seedQuoteForCorrectedBomUpload(page);
  });

  test('uploads non-standard BOM and automatically runs Yunhan pricing + process-fee matching', async ({
    page,
  }, testInfo) => {
    const workbookPath = createNonStandardBomWorkbook(
      testInfo.outputPath('customer-nonstd-bom-e2e.xlsx'),
    );
    const consoleIssues: string[] = [];
    page.on('console', (message) => {
      const text = message.text();
      if (isTransientViteDynamicImportIssue(text)) return;
      if (
        /Expression evaluation failed|Cannot read properties|ReferenceError|TypeError/i.test(text)
      ) {
        consoleIssues.push(`${message.type()}: ${text}`);
      }
    });
    page.on('pageerror', (error) => {
      if (isTransientViteDynamicImportIssue(error.message)) return;
      consoleIssues.push(`pageerror: ${error.message}`);
    });

    await openQuoteDetailFromList(page, created);

    await expect(page.getByRole('tab', { name: /资料上传|Source Upload/ })).toBeVisible({
      timeout: 20_000,
    });
    const uploadButton = page.getByTestId('toolbar-btn-upload_corrected_bom');
    await expect(uploadButton).toBeVisible({ timeout: 20_000 });
    await expect(uploadButton).toHaveAccessibleName(/上传BOM资料|Upload BOM Data/i);

    const commandResponsePromise = page.waitForResponse(
      (response) =>
        response
          .url()
          .includes('/api/meta/commands/execute/qo_quote_common:import_corrected_bom') &&
        response.request().method() === 'POST',
      { timeout: 60_000 },
    );
    const fileChooserPromise = page.waitForEvent('filechooser', { timeout: 10_000 });

    await uploadButton.click();
    const fileChooser = await fileChooserPromise;
    await fileChooser.setFiles(workbookPath);

    const commandResponse = await commandResponsePromise;
    const commandBody = await commandResponse.json().catch(() => ({}));
    expect(
      String((commandBody as any).code),
      `import_corrected_bom response: ${JSON.stringify(commandBody).slice(0, 1000)}`,
    ).toBe('0');

    const completionMessage = page.getByText('上传BOM资料已完成', { exact: true });
    await expect(completionMessage).toHaveCount(1, { timeout: 30_000 });
    await expect(completionMessage).toBeVisible();

    await expect
      .poll(
        async () => {
          const imports = await queryDynamicRecords(page, 'qo_bom_import_common', [
            { fieldName: 'qo_bi_quote_id', operator: 'EQ', value: created.quoteId },
          ]);
          return imports.map((row) => ({
            mode: row.qo_bi_source_mode,
            validRows: row.qo_bi_valid_rows,
            hasRawHead: row.qo_bi_raw_head != null && String(row.qo_bi_raw_head).length > 0,
          }));
        },
        { timeout: 30_000, intervals: [500, 1_000, 2_000] },
      )
      .toEqual([expect.objectContaining({ mode: 'quick', validRows: 4, hasRawHead: true })]);

    const importRows = await queryDynamicRecords(page, 'qo_bom_import_row_common', [
      { fieldName: 'qo_bir_quote_id', operator: 'EQ', value: created.quoteId },
    ]);
    expect(importRows).toHaveLength(4);
    expect(
      importRows.every(
        (row) => row.qo_bir_raw_cells != null && String(row.qo_bir_raw_cells).length > 0,
      ),
      'every quick-import row must retain raw cells for the Yunhan upload-bom request',
    ).toBe(true);

    const quoteLines = await queryDynamicRecords(page, 'qo_quote_line_common', [
      { fieldName: 'qo_ql_quote_id', operator: 'EQ', value: created.quoteId },
    ]);
    expect(quoteLines).toHaveLength(4);
    expect(quoteLines.map((row) => String(row.qo_ql_mpn)).sort()).toEqual([
      '1N4148W',
      'CL10B104KB8NNNC',
      'RC0603FR-0710KL',
      'WMF2400TEE',
    ]);

    const resistorLine = quoteLines.find((row) => row.qo_ql_mpn === 'WMF2400TEE');
    expect(resistorLine, 'the blank-package 0201 resistor line must be imported').toBeTruthy();
    expect(String(resistorLine?.qo_ql_package ?? '')).toBe('');
    expect(Number(resistorLine?.qo_ql_qty)).toBe(3);
    expect(String(resistorLine?.qo_ql_description ?? '')).toContain('0201');

    await page.getByRole('button', { name: /^关闭$/ }).click();
    await expect(completionMessage).toHaveCount(0, { timeout: 10_000 });

    const lineIds = quoteLines.map((row) => String(row.pid)).filter(Boolean);
    expect(lineIds).toHaveLength(4);

    // The UI upload must have triggered the real Yunhan quick lane. Missing credentials, source
    // failures, or a regression that requires a manual "运行查价" click all fail this assertion.
    await expect
      .poll(
        async () => {
          const evidence = (
            await Promise.all(
              lineIds.map((lineId) =>
                queryDynamicRecords(page, 'qo_price_evidence_common', [
                  { fieldName: 'qo_pe_quote_line_id', operator: 'EQ', value: lineId },
                  { fieldName: 'qo_pe_source', operator: 'EQ', value: 'yunhan' },
                ]),
              ),
            )
          ).flat();
          const terminal = new Set(['captured', 'usd_review', 'not_found']);
          const linesWithEvidence = new Set(evidence.map((row) => String(row.qo_pe_quote_line_id)));
          return {
            allLinesHaveEvidence: lineIds.every((lineId) => linesWithEvidence.has(lineId)),
            allTerminal:
              evidence.length >= lineIds.length &&
              evidence.every((row) => terminal.has(String(row.qo_pe_status))),
            noBlankSourceRef: evidence.every(
              (row) => String(row.qo_pe_source_ref ?? '').length > 0,
            ),
            notFoundUsesRefreshRef: evidence
              .filter((row) => row.qo_pe_status === 'not_found')
              .every((row) => row.qo_pe_source_ref === 'yunhan:refresh'),
            capturedUsesAutoLane: evidence
              .filter((row) => ['captured', 'usd_review'].includes(String(row.qo_pe_status)))
              .every((row) =>
                ['upload_bom', 'recent_cache'].includes(
                  String(parseSnapshot(row.qo_pe_snapshot).matchedBy),
                ),
              ),
            freshYunhanRequestObserved: evidence.some((row) => {
              const snapshot = parseSnapshot(row.qo_pe_snapshot);
              return (
                row.qo_pe_source_ref === 'yunhan:refresh' &&
                snapshot.commandCode === 'qo_quote_common:batch_source_prices' &&
                String(snapshot.refreshedAt ?? '').length > 0
              );
            }),
          };
        },
        { timeout: 90_000, intervals: [1_000, 2_000, 3_000] },
      )
      .toEqual({
        allLinesHaveEvidence: true,
        allTerminal: true,
        noBlankSourceRef: true,
        notFoundUsesRefreshRef: true,
        capturedUsesAutoLane: true,
        freshYunhanRequestObserved: true,
      });

    // The same UI upload must also have triggered process-fee calculation. The first row proves
    // the match is content-driven: blank package + description token 0201 -> Excel rule row 3.
    let processHits: Record<string, unknown>[] = [];
    await expect
      .poll(
        async () => {
          processHits = await queryDynamicRecords(page, 'qo_process_fee_rule_hit_common', [
            { fieldName: 'qo_pfrh_quote_id', operator: 'EQ', value: created.quoteId },
          ]);
          const resistorHit = processHits.find(
            (row) => String(row.qo_pfrh_quote_line_id) === String(resistorLine?.pid),
          );
          return resistorHit
            ? {
                status: resistorHit.qo_pfrh_match_status,
                stage: resistorHit.qo_pfrh_process_stage,
                basis: resistorHit.qo_pfrh_point_basis,
                unitPoints: Number(resistorHit.qo_pfrh_unit_points),
                totalPoints: Number(resistorHit.qo_pfrh_total_points),
                amount: Number(resistorHit.qo_pfrh_amount),
              }
            : null;
        },
        { timeout: 30_000, intervals: [500, 1_000, 2_000] },
      )
      .toEqual({
        status: 'matched',
        stage: 'SMT',
        basis: 'fixed_points',
        unitPoints: 2,
        totalPoints: 6,
        amount: 0.07,
      });
    expect(processHits).toHaveLength(4);

    const resistorHit = processHits.find(
      (row) => String(row.qo_pfrh_quote_line_id) === String(resistorLine?.pid),
    );
    expect(String(resistorHit?.qo_pfrh_point_formula)).toMatch(/^fixed_points\(2(?:\.0+)?\)$/);
    expect(resistorHit?.qo_pfrh_point_source).toBe('rule-fixed-points');
    expect(String(resistorHit?.qo_pfrh_trace)).toContain('ruleRow=3');

    const matchedRules = await queryDynamicRecords(page, 'qo_process_fee_rule_line_common', [
      { fieldName: 'pid', operator: 'EQ', value: resistorHit?.qo_pfrh_rule_line_id },
    ]);
    expect(matchedRules).toHaveLength(1);
    expect(Number(matchedRules[0]?.qo_pfrl_source_row_no)).toBe(3);
    expect(String(matchedRules[0]?.qo_pfrl_component_type)).toContain('0201');
    expect(Number(matchedRules[0]?.qo_pfrl_point_count)).toBe(2);
    expect(Number(matchedRules[0]?.qo_pfrl_unit_price)).toBe(0.012);

    await page.getByRole('tab', { name: /BOM价格计算|BOM Price/i }).click();
    await expect(page.getByTestId('metric-strip-qo_bom_price_metrics')).toBeVisible({
      timeout: 20_000,
    });

    const firstPriceRow = page.getByTestId(`table-row-${lineIds[0]}`);
    await expect(firstPriceRow).toBeVisible({ timeout: 30_000 });
    const priceTable = firstPriceRow.locator('xpath=ancestor::table[1]');
    const priceHeaders = await tableTexts(
      priceTable.locator('thead th, thead [role="columnheader"]'),
    );
    const yunhanColumn = priceHeaders.indexOf('云汉芯城');
    expect(yunhanColumn, `price headers: ${priceHeaders.join(' | ')}`).toBeGreaterThanOrEqual(0);

    for (const lineId of lineIds) {
      const priceRow = page.getByTestId(`table-row-${lineId}`);
      await expect(priceRow).toBeVisible({ timeout: 20_000 });
      const cells = await tableTexts(priceRow.locator('td, [role="cell"]'));
      expect(cells[yunhanColumn] ?? '', `Yunhan cell for quote line ${lineId}`).toMatch(
        /未命中|\d+(?:\.\d+)?/,
      );
    }

    await testInfo.attach('nonstd-auto-yunhan-price-tab.png', {
      body: await page.screenshot({ fullPage: true }),
      contentType: 'image/png',
    });

    const processPointsTab = page.getByRole('tab', { name: /加工点数|Process Points/i });
    await expect(processPointsTab).toBeVisible();
    await processPointsTab.click();
    await expect(page.getByTestId('metric-strip-qo_process_fee_metrics')).toBeVisible({
      timeout: 20_000,
    });
    await expect(page.getByTestId('metric-strip-item-matched_count')).toContainText(/[1-9]/);

    const resistorHitRow = page
      .locator('[data-testid^="table-row-"]')
      .filter({ hasText: 'WMF2400TEE' });
    await expect(resistorHitRow).toHaveCount(1, { timeout: 20_000 });
    await expect(resistorHitRow).toContainText(/完全匹配|Matched/i);
    await expect(resistorHitRow).toContainText('SMT');
    await expect(resistorHitRow).toContainText(/数量 3 .*单件点数 2 .*合计点数 6|Qty \/ Points/i);

    await resistorHitRow.click();
    const reviewDrawer = page.getByTestId('review-drawer');
    await expect(reviewDrawer).toBeVisible({ timeout: 10_000 });
    await expect(reviewDrawer).toContainText('WMF2400TEE');
    await expect(reviewDrawer).toContainText(/fixed_points\(2(?:\.0+)?\)/);
    await expect(reviewDrawer).toContainText(/Excel行 3|Excel Row 3/i);

    await testInfo.attach('nonstd-process-fee-0201-match.png', {
      body: await page.screenshot({ fullPage: true }),
      contentType: 'image/png',
    });

    await expect(consoleIssues).toEqual([]);
  });
});
