import { test, expect } from '../../fixtures';
import {
  cleanupRows,
  dynamicCreate,
  executeCommand,
  isTransientViteDynamicImportIssue,
  openQuoteDetailFromList,
  prepareReviewedCorrectedBomUpload,
  queryDynamicRecords,
  seedQuoteForCorrectedBomUpload,
  setYunhanMockScenario,
  yunhanMockControlUrl,
  type CreatedRows,
} from './quote-e2e-helpers';
import { utils as XLSXUtils, write as xlsxWrite } from 'xlsx';
import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';

/**
 * Golden: BOM import materializes correlated rows in bulk, and every current row crosses the
 * managed Yunhan connector even when matching historical evidence exists.
 *
 * Guards the two delivered performance features:
 *   1. bulk import (platform bulkCreate + three-phase handler) — the risk of batching is *id
 *      correlation*, so this asserts the bidirectional import-row <-> quote-line linkage row by row
 *      (qo_bir_quote_line_id -> line, line.qo_ql_source_ref -> back, and same-row MPN). An
 *      off-by-one / mis-ordered batch fails here.
 *   2. current sourcing authority — historical recent-cache evidence remains readable for audit
 *      but must never satisfy a new pricing run or create another {@code recent_cache} result.
 *
 * Deterministic by construction: the import runs against the managed mock's not-found scenario,
 * then historical evidence is seeded explicitly before the normal pricing scenario is restored.
 * The managed mock request log proves this run crossed the real connector boundary.
 */

// Unique per run: the handler writes reused-evidence rows of its own, and any residue left in the
// database must never be able to satisfy a later run's cache assertions.
const RUN_ID = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`.toUpperCase();
const SYNTHETIC_MPNS = ['A1', 'B2', 'C3'].map((suffix) => `E2E-BULKCACHE-${RUN_ID}-${suffix}`);
const HISTORICAL_UNIT_PRICE = 0.1234;
const HISTORICAL_SOURCE_REF = `golden:historical-cache-seed:${RUN_ID}`;

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

test.describe('QuoteOps bulk import + current sourcing golden', () => {
  test.describe.configure({ timeout: 300_000 });

  test('imports correlated rows in bulk and ignores historical recent-cache evidence', async ({
    page,
  }, testInfo) => {
    await setYunhanMockScenario(page, 'reprice-v1');
    const created: CreatedRows = await seedQuoteForCorrectedBomUpload(page);
    const importedLineIds: string[] = [];
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

    try {
      // ── 1. upload the BOM through the real UI ──────────────────────────────
      const workbookPath = createSyntheticBomWorkbook(
        testInfo.outputPath('bulk-cache-golden-bom.xlsx'),
      );
      await openQuoteDetailFromList(page, created);
      await expect(page.getByTestId('toolbar-btn-upload_corrected_bom')).toBeVisible({
        timeout: 20_000,
      });

      const uploadDialog = await prepareReviewedCorrectedBomUpload(page, workbookPath);
      const importResponsePromise = page.waitForResponse(
        (response) =>
          response
            .url()
            .includes('/api/meta/commands/execute/qo_quote_common:import_corrected_bom') &&
          response.request().method() === 'POST',
        { timeout: 60_000 },
      );
      await uploadDialog.getByTestId('form-dialog-submit').click();

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
              return String(
                ((await r.json().catch(() => ({}))) as any)?.data?.status ?? '',
              ).toLowerCase();
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
        expect(
          lineId,
          `import row ${importRow.qo_bir_row_no} must link to a quote line`,
        ).toBeTruthy();
        const line = lines.find((l) => String(l.pid) === lineId);
        expect(
          line,
          `import row ${importRow.qo_bir_row_no} links to a non-existent line ${lineId}`,
        ).toBeTruthy();
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

      // ── 3. seed historical cache evidence, then prove it is never adopted ──
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
            qo_pe_source_ref: HISTORICAL_SOURCE_REF,
            qo_pe_status: 'captured',
            qo_pe_unit_price: HISTORICAL_UNIT_PRICE,
            qo_pe_currency: 'CNY',
            qo_pe_valid_until: validUntil,
            qo_pe_snapshot: JSON.stringify({
              matchedBy: 'recent_cache',
              historicalSeed: true,
              reusedFromEvidence: `GOLDEN-LEGACY-${mpn}`,
            }),
          },
          created.rows,
        );
      }

      await setYunhanMockScenario(page, 'release-default');
      await executeCommand(
        page,
        'qo_quote_common:batch_source_prices',
        {},
        created.quoteId,
        'execute',
      );

      // Every current line must get terminal connector evidence without the retired cache marker.
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
          .toEqual(
            expect.arrayContaining([
              expect.objectContaining({
                status: expect.stringMatching(/captured|usd_review|not_found/),
              }),
            ]),
          );

        const currentEvidence = await queryDynamicRecords(page, 'qo_price_evidence_common', [
          { fieldName: 'qo_pe_quote_line_id', operator: 'EQ', value: String(line.pid) },
        ]);
        expect(
          currentEvidence.every(
            (e) => String(parseSnapshot(e.qo_pe_snapshot).matchedBy ?? '') !== 'recent_cache',
          ),
          `current line ${String(line.qo_ql_mpn)} must not be served from historical cache`,
        ).toBe(true);
      }

      // Query by this run's unique MPNs as well as the marker. A global marker query can be
      // paginated by the dynamic-list API after repeated golden runs and silently hide a row.
      const currentRunHistoricalEvidence = (
        await Promise.all(
          SYNTHETIC_MPNS.map((mpn) =>
            queryDynamicRecords(page, 'qo_price_evidence_common', [
              { fieldName: 'qo_pe_source_ref', operator: 'EQ', value: HISTORICAL_SOURCE_REF },
              { fieldName: 'qo_pe_part_no', operator: 'EQ', value: mpn },
            ]),
          ),
        )
      ).flat();
      expect(currentRunHistoricalEvidence).toHaveLength(SYNTHETIC_MPNS.length);
      expect(
        currentRunHistoricalEvidence.every(
          (e) =>
            parseSnapshot(e.qo_pe_snapshot).matchedBy === 'recent_cache' &&
            parseSnapshot(e.qo_pe_snapshot).historicalSeed === true &&
            String(e.qo_pe_unit_price ?? '').startsWith(String(HISTORICAL_UNIT_PRICE)),
        ),
        'legacy cache evidence must remain immutable, auditable, and detached from current lines',
      ).toBe(true);

      const mockRequestsResponse = await page.request.get(
        `${yunhanMockControlUrl()}/__control/requests`,
      );
      expect(mockRequestsResponse.ok(), 'managed Yunhan request log is readable').toBe(true);
      const mockRequestsBody = (await mockRequestsResponse.json()) as {
        requests?: Array<{ path?: unknown; form?: Record<string, unknown> }>;
      };
      const singleRequestedKeywords = (mockRequestsBody.requests ?? [])
        .filter((request) => String(request.path ?? '').endsWith('/get-single-goods-new'))
        .flatMap((request) => {
          const keyword = request.form?.keyword;
          return Array.isArray(keyword) ? keyword.map(String) : [String(keyword ?? '')];
        });
      const batchRequestedKeywords = (mockRequestsBody.requests ?? [])
        .filter((request) => String(request.path ?? '').endsWith('/upload-bom'))
        .flatMap((request) => {
          const raw = request.form?.excel_data;
          const values = Array.isArray(raw) ? raw.map(String) : [String(raw ?? '')];
          return values.flatMap((value) => {
            try {
              const rows = JSON.parse(value) as unknown[][];
              return rows.flatMap((row) => row.map(String));
            } catch {
              return [];
            }
          });
        });
      const requestedKeywords = [...singleRequestedKeywords, ...batchRequestedKeywords];
      expect(
        SYNTHETIC_MPNS.every((mpn) => requestedKeywords.includes(mpn)),
        `every imported MPN must cross the managed connector boundary: ${JSON.stringify(
          mockRequestsBody,
        ).slice(0, 1200)}`,
      ).toBe(true);

      const closeImportResult = page.getByRole('button', { name: /^(关闭|Close)$/i }).last();
      if (await closeImportResult.isVisible().catch(() => false)) {
        await closeImportResult.click();
      }
      await page.getByRole('tab', { name: /BOM价格计算|BOM Price/i }).click();
      for (const line of lines) {
        const row = page.getByTestId(`table-row-${String(line.pid)}`);
        await expect(row, `re-sourced line ${String(line.qo_ql_mpn)} remains visible`).toBeVisible({
          timeout: 20_000,
        });
        await expect(row).toContainText(String(line.qo_ql_mpn));
      }

      await testInfo.attach('bulk-cache-bypass-golden.png', {
        body: await page.screenshot({ fullPage: true }),
        contentType: 'image/png',
      });
      await expect(consoleIssues).toEqual([]);
    } finally {
      // Evidence written by the sourcing handler is not tracked by the seed helpers; register it so
      // this run leaves no generated connector evidence behind.
      for (const lineId of importedLineIds) {
        const evidence = await queryDynamicRecords(page, 'qo_price_evidence_common', [
          { fieldName: 'qo_pe_quote_line_id', operator: 'EQ', value: lineId },
        ]).catch(() => []);
        evidence.forEach((e) =>
          created.rows.push({ model: 'qo_price_evidence_common', pid: String(e.pid) }),
        );
      }
      await cleanupRows(page, created);
      await setYunhanMockScenario(page, 'release-default');
    }
  });
});
