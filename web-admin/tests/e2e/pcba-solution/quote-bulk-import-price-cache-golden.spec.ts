import { test, expect } from '../../fixtures';
import {
  cleanupRows,
  dynamicCreate,
  executeCommand,
  isTransientViteDynamicImportIssue,
  openQuoteDetailFromList,
  queryDynamicRecords,
  seedQuoteForCorrectedBomUpload,
  type CreatedRows,
} from './quote-e2e-helpers';
import { utils as XLSXUtils, write as xlsxWrite } from 'xlsx';
import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';

/**
 * Golden: BOM import materializes correlated rows in bulk, and a recently-priced MPN is served from
 * the price cache instead of the external lane.
 *
 * Guards the two delivered performance features:
 *   1. bulk import (platform bulkCreate + three-phase handler) — the risk of batching is *id
 *      correlation*, so this asserts the bidirectional import-row <-> quote-line linkage row by row
 *      (qo_bir_quote_line_id -> line, line.qo_ql_source_ref -> back, and same-row MPN). An
 *      off-by-one / mis-ordered batch fails here.
 *   2. incremental price cache (reuseRecentPrices) — a line whose MPN carries a fresh captured
 *      price is served from cache (snapshot.matchedBy='recent_cache'), not re-sourced.
 *
 * Deterministic by construction: the BOM uses synthetic MPNs that no external price source can
 * match, and the "already priced" state is seeded explicitly. The spec therefore behaves the same
 * whether or not Yunhan credentials are configured on the stack.
 */

// Unique per run: the handler writes reused-evidence rows of its own, and any residue left in the
// database must never be able to satisfy a later run's cache assertions.
const RUN_ID = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`.toUpperCase();
const SYNTHETIC_MPNS = ['A1', 'B2', 'C3'].map((suffix) => `E2E-BULKCACHE-${RUN_ID}-${suffix}`);
const CACHED_UNIT_PRICE = 0.1234;

function createSyntheticBomWorkbook(filePath: string): string {
  mkdirSync(path.dirname(filePath), { recursive: true });
  const rows: (string | number)[][] = [['位号', '规格描述', '封装', '数量', '品牌', '料号']];
  SYNTHETIC_MPNS.forEach((mpn, i) => {
    rows.push([`R${i + 1}`, `golden part ${i + 1}`, '0603', (i + 1) * 10, 'E2E', mpn]);
  });
  const worksheet = XLSXUtils.aoa_to_sheet(rows);
  const workbook = XLSXUtils.book_new();
  XLSXUtils.book_append_sheet(workbook, worksheet, 'BOM');
  writeFileSync(filePath, xlsxWrite(workbook, { bookType: 'xlsx', type: 'buffer' }));
  return filePath;
}

/** qo_pe_snapshot comes back from the dynamic-list API as a JSON string; parse it (or pass through). */
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

test.describe('QuoteOps bulk import + price cache golden', () => {
  test.describe.configure({ timeout: 300_000 });

  test('imports correlated rows in bulk and serves recently-priced MPNs from cache', async ({
    page,
  }, testInfo) => {
    const created: CreatedRows = await seedQuoteForCorrectedBomUpload(page);
    const importedLineIds: string[] = [];
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
      // ── 1. upload the BOM through the real UI ──────────────────────────────
      const workbookPath = createSyntheticBomWorkbook(
        testInfo.outputPath('bulk-cache-golden-bom.xlsx'),
      );
      await openQuoteDetailFromList(page, created);
      await expect(page.getByTestId('toolbar-btn-upload_corrected_bom')).toBeVisible({
        timeout: 20_000,
      });

      const importResponsePromise = page.waitForResponse(
        (response) =>
          response.url().includes('/api/meta/commands/execute/qo_quote_common:import_corrected_bom') &&
          response.request().method() === 'POST',
        { timeout: 60_000 },
      );
      const fileChooserPromise = page.waitForEvent('filechooser', { timeout: 10_000 });
      await page.getByTestId('toolbar-btn-upload_corrected_bom').click();
      (await fileChooserPromise).setFiles(workbookPath);

      const importResponse = await importResponsePromise;
      const importBody = (await importResponse.json().catch(() => ({}))) as Record<string, any>;
      expect(
        String(importBody?.code),
        `import_corrected_bom response: ${JSON.stringify(importBody).slice(0, 600)}`,
      ).toBe('0');

      // Import (and its auto-recompute) runs as a background task — wait for it to finish so the
      // pricing step below starts from a settled state.
      const taskCode = String(importBody?.data?.data?.taskCode ?? importBody?.data?.taskCode ?? '');
      if (taskCode) {
        await expect
          .poll(
            async () => {
              const r = await page.request.get(`/api/async-tasks/${encodeURIComponent(taskCode)}`);
              return String(((await r.json().catch(() => ({}))) as any)?.data?.status ?? '').toLowerCase();
            },
            { timeout: 240_000, intervals: [1000, 2000, 3000] },
          )
          .toMatch(/completed|failed|cancelled/);
      }

      // ── 2. bulk import correctness: rows materialized AND correlated ───────
      await expect
        .poll(
          async () =>
            (
              await queryDynamicRecords(page, 'qo_quote_line_common', [
                { fieldName: 'qo_ql_quote_id', operator: 'EQ', value: created.quoteId },
              ])
            ).length,
          { timeout: 120_000, intervals: [1000, 2000, 3000] },
        )
        .toBe(SYNTHETIC_MPNS.length);

      const lines = await queryDynamicRecords(page, 'qo_quote_line_common', [
        { fieldName: 'qo_ql_quote_id', operator: 'EQ', value: created.quoteId },
      ]);
      const importRows = await queryDynamicRecords(page, 'qo_bom_import_row_common', [
        { fieldName: 'qo_bir_quote_id', operator: 'EQ', value: created.quoteId },
      ]);
      expect(importRows).toHaveLength(SYNTHETIC_MPNS.length);

      // The batch writes rows and lines in two separate statements and stitches them by index —
      // an off-by-one or reordered batch shows up as a broken/crossed link here.
      for (const importRow of importRows) {
        const lineId = String(importRow.qo_bir_quote_line_id ?? '');
        expect(lineId, `import row ${importRow.qo_bir_row_no} must link to a quote line`).toBeTruthy();
        const line = lines.find((l) => String(l.pid) === lineId);
        expect(line, `import row ${importRow.qo_bir_row_no} links to a non-existent line ${lineId}`).toBeTruthy();
        expect(
          String(line!.qo_ql_source_ref),
          'the quote line must point back at the import row that produced it',
        ).toBe(String(importRow.pid));
        expect(
          String(line!.qo_ql_mpn),
          'linked row and line must carry the same MPN (crossed links fail here)',
        ).toBe(String(importRow.qo_bir_mpn));
      }
      lines.forEach((l) => importedLineIds.push(String(l.pid)));
      expect(
        lines.map((l) => String(l.qo_ql_mpn)).sort(),
        'every BOM row must produce its quote line',
      ).toEqual([...SYNTHETIC_MPNS].sort());

      // ── 3. price cache: seed a fresh captured price per MPN, then source ───
      // qo_pe_valid_until is a DATE column — the platform rejects datetime values for it.
      const validUntil = new Date(Date.now() + 7 * 24 * 3600 * 1000).toISOString().slice(0, 10);
      for (const mpn of SYNTHETIC_MPNS) {
        await dynamicCreate(
          page,
          'qo_price_evidence_common',
          {
            // attached to a different (synthetic) line: this is the "priced on an earlier import" case
            qo_pe_quote_line_id: `GOLDEN-CACHE-SRC-${mpn}`,
            qo_pe_part_no: mpn,
            qo_pe_source: 'yunhan',
            qo_pe_source_ref: 'golden:cache-seed',
            qo_pe_status: 'captured',
            qo_pe_unit_price: CACHED_UNIT_PRICE,
            qo_pe_currency: 'CNY',
            qo_pe_valid_until: validUntil,
          },
          created.rows,
        );
      }

      await executeCommand(page, 'qo_quote_common:batch_source_prices', {}, created.quoteId, 'execute');

      // Every line must be served from the cache — proven by the reuse marker on its evidence.
      for (const line of lines) {
        await expect
          .poll(
            async () => {
              const evidence = await queryDynamicRecords(page, 'qo_price_evidence_common', [
                { fieldName: 'qo_pe_quote_line_id', operator: 'EQ', value: String(line.pid) },
              ]);
              return evidence.map((e) => ({
                matchedBy: parseSnapshot(e.qo_pe_snapshot).matchedBy,
                status: e.qo_pe_status,
                price: String(e.qo_pe_unit_price ?? ''),
              }));
            },
            { timeout: 120_000, intervals: [1000, 2000, 3000] },
          )
          .toContainEqual(
            expect.objectContaining({ matchedBy: 'recent_cache', status: 'captured' }),
          );
      }

      const firstLineEvidence = await queryDynamicRecords(page, 'qo_price_evidence_common', [
        { fieldName: 'qo_pe_quote_line_id', operator: 'EQ', value: String(lines[0].pid) },
      ]);
      expect(
        firstLineEvidence.some(
          (e) =>
            parseSnapshot(e.qo_pe_snapshot).matchedBy === 'recent_cache' &&
            String(e.qo_pe_unit_price ?? '').startsWith(String(CACHED_UNIT_PRICE)),
        ),
        'the reused evidence must carry the cached unit price, not a re-sourced one',
      ).toBe(true);

      await testInfo.attach('bulk-cache-golden.png', {
        body: await page.screenshot({ fullPage: true }),
        contentType: 'image/png',
      });
      await expect(consoleIssues).toEqual([]);
    } finally {
      // Evidence written by the sourcing handler is not tracked by the seed helpers; register it so
      // this run leaves nothing behind that a later run could mistake for a cache hit.
      for (const lineId of importedLineIds) {
        const evidence = await queryDynamicRecords(page, 'qo_price_evidence_common', [
          { fieldName: 'qo_pe_quote_line_id', operator: 'EQ', value: lineId },
        ]).catch(() => []);
        evidence.forEach((e) =>
          created.rows.push({ model: 'qo_price_evidence_common', pid: String(e.pid) }),
        );
      }
      await cleanupRows(page, created);
    }
  });
});
