import { test, expect } from '../../fixtures';
import {
  cleanupRows,
  createNonStandardBomWorkbook,
  executeCommand,
  isTransientViteDynamicImportIssue,
  openQuoteDetailFromList,
  queryDynamicRecords,
  seedQuoteForCorrectedBomUpload,
  type CreatedRows,
} from './quote-e2e-helpers';

/** qo_pe_snapshot comes back from the dynamic-list API as a JSON string; parse it (or pass through
 * an already-parsed object). Mirrors the helper used by quote-bom-price-manual-adoption. */
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

/**
 * Golden: non-standard "quick quote" via the Yunhan upload-bom lane.
 *
 * The upload entry is unchanged — the same "上传修正BOM" button now auto-detects a non-standard
 * (templateless) customer BOM and imports it as a quick quote instead of rejecting it. This golden
 * uploads a non-standard workbook through the real UI, then asserts the end-to-end quick behaviour:
 *   1. import detected as quick (qo_bi_source_mode='quick') with the raw columns captured
 *      (qo_bi_raw_head + qo_bir_raw_cells) for the upload-bom lane;
 *   2. quote lines parsed from the arbitrary columns (mpn / package / qty) via alias detection;
 *   3. pricing runs through the upload-bom lane (when Yunhan is configured on the stack): at least
 *      one parsed row prices via yunhan with snapshot.matchedBy='upload_bom' (the ickey sandbox
 *      catalog is partial, so we assert "at least one" rather than a specific MPN).
 *
 * Registered in playwright.config.ts `quoteOpsCurrentSpecNames` (the focused QuoteOps/BOM gate)
 * after proving one green run on a host-first enterprise-overlay stack. The pricing leg is
 * env-guarded so the spec does not hard-fail when Yunhan is unconfigured on a stack.
 */
test.describe('QuoteOps non-standard quick-quote (upload-bom) golden', () => {
  test.describe.configure({ timeout: 120_000 });

  test('uploads a non-standard BOM, imports it as quick, and prices via upload-bom', async ({
    page,
  }, testInfo) => {
    const created: CreatedRows = await seedQuoteForCorrectedBomUpload(page);
    const workbookPath = createNonStandardBomWorkbook(
      testInfo.outputPath('customer-nonstd-bom-e2e.xlsx'),
    );
    const consoleIssues: string[] = [];
    page.on('console', (message) => {
      const text = message.text();
      if (isTransientViteDynamicImportIssue(text)) return;
      if (/Expression evaluation failed|Cannot read properties|ReferenceError|TypeError/i.test(text)) {
        consoleIssues.push(`${message.type()}: ${text}`);
      }
    });
    page.on('pageerror', (error) => {
      if (isTransientViteDynamicImportIssue(error.message)) return;
      consoleIssues.push(`pageerror: ${error.message}`);
    });

    try {
      await openQuoteDetailFromList(page, created);

      await expect(page.getByRole('tab', { name: /资料上传|Source Upload/ })).toBeVisible({
        timeout: 20_000,
      });
      await expect(page.getByTestId('toolbar-btn-upload_corrected_bom')).toBeVisible({
        timeout: 20_000,
      });

      const commandResponsePromise = page.waitForResponse(
        (response) =>
          response.url().includes('/api/meta/commands/execute/qo_quote_common:import_corrected_bom') &&
          response.request().method() === 'POST',
        { timeout: 60_000 },
      );
      const fileChooserPromise = page.waitForEvent('filechooser', { timeout: 10_000 });

      await page.getByTestId('toolbar-btn-upload_corrected_bom').click();
      const fileChooser = await fileChooserPromise;
      await fileChooser.setFiles(workbookPath);

      // The non-standard BOM must import (not be rejected as "not a standard BOM").
      const commandResponse = await commandResponsePromise;
      const commandBody = await commandResponse.json().catch(() => ({}));
      expect(
        String((commandBody as any).code),
        `import_corrected_bom response: ${JSON.stringify(commandBody).slice(0, 1000)}`,
      ).toBe('0');
      await expect(
        page.getByRole('alert').filter({ hasText: /completed|完成|导入|Imported/i }).first(),
      ).toBeVisible({ timeout: 20_000 });

      // ── 1. import detected as quick + raw columns captured ──
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
          { timeout: 20_000, intervals: [500, 1000, 1500] },
        )
        .toEqual([expect.objectContaining({ mode: 'quick', validRows: 3, hasRawHead: true })]);

      const importRows = await queryDynamicRecords(page, 'qo_bom_import_row_common', [
        { fieldName: 'qo_bir_quote_id', operator: 'EQ', value: created.quoteId },
      ]);
      expect(
        importRows.some((row) => row.qo_bir_raw_cells != null && String(row.qo_bir_raw_cells).length > 0),
        'raw row cells must be captured for the upload-bom lane',
      ).toBe(true);

      // ── 2. lines parsed from arbitrary columns (mpn / package via alias detection) ──
      const quoteLines = await queryDynamicRecords(page, 'qo_quote_line_common', [
        { fieldName: 'qo_ql_quote_id', operator: 'EQ', value: created.quoteId },
      ]);
      expect(quoteLines).toHaveLength(3);
      expect(quoteLines.map((row) => String(row.qo_ql_mpn)).sort()).toEqual([
        '1N4148W',
        'CL10B104KB8NNNC',
        'RC0603FR-0710KL',
      ]);
      expect(quoteLines.find((row) => row.qo_ql_mpn === '1N4148W')).toEqual(
        expect.objectContaining({ qo_ql_package: 'SOD-123', qo_ql_qty: 10 }),
      );

      // Dismiss the "上传修正BOM已完成" success dialog before navigating tabs — the result
      // modal's backdrop otherwise intercepts pointer events on the tab bar (same close
      // step as the standard corrected-bom-upload golden).
      await page.getByRole('button', { name: /^关闭$/ }).click();
      await expect(
        page.getByText(/上传修正BOM已完成|任务已成功完成/).first(),
      ).toBeHidden({ timeout: 10_000 });

      // UI: the price + process tabs render for the quick quote
      await page.getByRole('tab', { name: /BOM价格计算|BOM Price/i }).click();
      await expect(page.getByTestId('metric-strip-qo_bom_price_metrics')).toBeVisible({
        timeout: 20_000,
      });
      await expect(page.getByRole('tab', { name: /加工点数|Process/i })).toBeVisible();
      await testInfo.attach('nonstd-quick-quote-price-tab.png', {
        body: await page.screenshot({ fullPage: true }),
        contentType: 'image/png',
      });

      // ── 3. pricing via the upload-bom lane (env-guarded on Yunhan credentials) ──
      const priceResult = await executeCommand(
        page,
        'qo_quote_common:batch_source_prices',
        {},
        created.quoteId,
        'execute',
      );
      if (priceResult.sourceMode === 'quick') {
        // Yunhan configured -> the quick upload-bom lane engaged. Assert that at least one of
        // the parsed rows priced through the real upload-bom lane (snapshot.matchedBy='upload_bom',
        // status='captured'). We assert "at least one" rather than pinning to a specific MPN: the
        // ickey *sandbox* catalog is partial (e.g. 1N4148W is not_found there), so pinning to a
        // single part is brittle. This still fails closed — a regression that breaks the lane
        // yields zero captured rows and no upload_bom evidence.
        const lineIds = quoteLines.map((row) => String(row.pid)).filter(Boolean);
        expect(lineIds).toHaveLength(3);
        await expect
          .poll(
            async () => {
              const evidence: Array<{ status: unknown; matchedBy: unknown }> = [];
              for (const lineId of lineIds) {
                const ev = await queryDynamicRecords(page, 'qo_price_evidence_common', [
                  { fieldName: 'qo_pe_quote_line_id', operator: 'EQ', value: lineId },
                ]);
                for (const row of ev.filter((r) => r.qo_pe_source === 'yunhan')) {
                  evidence.push({
                    status: row.qo_pe_status,
                    matchedBy: parseSnapshot(row.qo_pe_snapshot).matchedBy,
                  });
                }
              }
              return evidence;
            },
            { timeout: 60_000, intervals: [1000, 2000, 3000] },
          )
          .toContainEqual(expect.objectContaining({ status: 'captured', matchedBy: 'upload_bom' }));
      } else {
        // Yunhan not configured on this stack: the lane falls back to the waterfall. The quick
        // import + raw capture above is still fully asserted; log for visibility.
        testInfo.annotations.push({
          type: 'note',
          description: `Yunhan not configured; upload-bom pricing leg skipped (sourceMode=${String(priceResult.sourceMode)})`,
        });
      }

      await expect(consoleIssues).toEqual([]);
    } finally {
      await cleanupRows(page, created);
    }
  });
});
