import fs from 'node:fs';
import type { Locator } from '@playwright/test';
import os from 'node:os';
import path from 'node:path';
import { test, expect } from '../../fixtures';
import { waitForFormReady } from '../helpers';
import {
  createNonStandardBomWorkbook,
  isTransientViteDynamicImportIssue,
  openQuoteCreateFormFromList,
  openQuoteDetailFromList,
  queryDynamicRecords,
  readDynamicRecord,
  seedQuoteForCorrectedBomUpload,
  setYunhanMockScenario,
  type CreatedRows,
  yunhanMockControlUrl,
} from './quote-e2e-helpers';

/** Pick a reference-select option by its data-value (no text search needed for fixtures). */
async function selectReferenceOption(
  page: import('@playwright/test').Page,
  field: string,
  value: string,
): Promise<void> {
  const trigger = page.getByTestId(`select-trigger-${field}`);
  await expect(trigger).toBeVisible({ timeout: 15_000 });
  await trigger.click();
  const option = page.locator(`[role="option"][data-value="${value}"]`).first();
  await expect(option, `${field} option ${value} should be loaded`).toBeVisible({
    timeout: 15_000,
  });
  await option.click();
}

async function uploadSmartUpload(
  page: import('@playwright/test').Page,
  fieldTestId: string,
  filePath: string,
): Promise<void> {
  const field = page.getByTestId(fieldTestId);
  await expect(field).toBeVisible({ timeout: 15_000 });
  const uploadResponsePromise = page.waitForResponse(
    (response) =>
      response.url().includes('/api/file/upload') && response.request().method() === 'POST',
    { timeout: 30_000 },
  );
  const input = field.locator('input[type="file"]').first();
  await input.setInputFiles(filePath);
  const uploadResponse = await uploadResponsePromise;
  expect(uploadResponse.ok(), `file upload HTTP ${uploadResponse.status()}`).toBe(true);
}
import { validateQuickCustomerBomWorkbook } from './quote-workbook-assertions';

const QUICK_CONFIRM_COMMAND = 'qo_quote_common:quick_confirm_costs_by_category';

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
    const mpnSuffix = `-E2E-${Date.now()}-${Math.random().toString(16).slice(2, 8)}`;
    const expectedMpns = [
      `1N4148W${mpnSuffix}`,
      `CL10B104KB8NNNC${mpnSuffix}`,
      `RC0603FR-0710KL${mpnSuffix}`,
      `WMF2400TEE${mpnSuffix}`,
    ];
    const workbookPath = createNonStandardBomWorkbook(
      testInfo.outputPath('customer-nonstd-bom-e2e.xlsx'),
      mpnSuffix,
    );
    await setYunhanMockScenario(page, 'release-default');
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

    // Materials upload happens only at quote creation now: the non-standard BOM
    // goes through the create form's BomUploadReview field (first-10-row preview +
    // explicit column roles), and the create command carries the confirmed columns.
    const accountId = created.rows.find((row) => row.model === 'crm_account_common')?.pid;
    const projectId = created.rows.find((row) => row.model === 'req_requirement_set_pcba_bom')?.pid;
    expect(accountId, 'scaffold account id').toBeTruthy();
    expect(projectId, 'scaffold project id').toBeTruthy();

    await openQuoteCreateFormFromList(page);
    await waitForFormReady(page, 20_000);
    await selectReferenceOption(page, 'qo_quote_crm_account_id', accountId!);
    await selectReferenceOption(page, 'qo_quote_project_id', projectId!);

    const gerberFixture = path.join(os.tmpdir(), `nonstd-gerber-${Date.now()}.zip`);
    fs.writeFileSync(gerberFixture, 'PK\x05\x06');
    const cplFixture = path.join(os.tmpdir(), `nonstd-cpl-${Date.now()}.csv`);
    fs.writeFileSync(cplFixture, 'Designator,Mid X,Mid Y,Layer\nR1,0,0,top\n');
    await uploadSmartUpload(page, 'form-field-gerber_source_file', gerberFixture);
    await uploadSmartUpload(page, 'form-field-cpl_source_file', cplFixture);

    const reviewField = page.getByTestId('bom-upload-review-corrected_bom_file');
    await expect(reviewField).toBeVisible({ timeout: 15_000 });
    await page
      .getByTestId('bom-upload-review-file-corrected_bom_file')
      .setInputFiles(workbookPath);
    await expect(page.getByText('原始 BOM 前 10 行')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText('将发送给云汉的列')).toBeVisible();
    await expect(page.getByText('已选 6 列')).toBeVisible();
    await expect(page.getByTestId('bom-upload-review-column-0')).not.toBeChecked();
    await expect(page.getByTestId('bom-upload-review-column-1')).toBeChecked();
    await expect(page.getByTestId('bom-upload-review-grid')).toContainText('240Ω ±1% 1/20W 0201');
    await testInfo.attach('nonstd-bom-upload-column-review.png', {
      body: await reviewField.screenshot(),
      contentType: 'image/png',
    });

    const commandRequestPromise = page.waitForRequest(
      (request) =>
        request.url().includes('/api/meta/commands/execute/qo_quote_common:create') &&
        request.method() === 'POST',
      { timeout: 60_000 },
    );
    const commandResponsePromise = page.waitForResponse(
      (response) =>
        response.url().includes('/api/meta/commands/execute/qo_quote_common:create') &&
        response.request().method() === 'POST',
      { timeout: 60_000 },
    );
    await page.getByTestId('form-btn-save').click();

    const commandRequest = await commandRequestPromise;
    const commandRequestBody = commandRequest.postDataJSON() as {
      payload?: Record<string, unknown>;
      params?: { payload?: Record<string, unknown> };
    };
    // FetchOptions.params is serialized as the HTTP body; keep the nested fallback
    // for compatibility with direct request helpers used by older test harnesses.
    const commandPayload = commandRequestBody.payload ?? commandRequestBody.params?.payload ?? {};
    const selectedColumns = commandPayload.bom_selected_columns as Array<{
      index?: unknown;
      role?: unknown;
    }>;
    expect(selectedColumns).toHaveLength(6);
    expect(selectedColumns.map((column) => Number(column.index))).not.toContain(0);
    expect(selectedColumns.map((column) => String(column.role))).toEqual([
      'refdes',
      'spec',
      'package',
      'quantity',
      'manufacturer',
      'mpn',
    ]);
    await testInfo.attach('nonstd-bom-upload-command-request.json', {
      body: JSON.stringify(commandRequestBody, null, 2),
      contentType: 'application/json',
    });

    const commandResponse = await commandResponsePromise;
    const commandBody = await commandResponse.json().catch(() => ({}));
    expect(
      String((commandBody as any).code),
      `qo_quote_common:create response: ${JSON.stringify(commandBody).slice(0, 1000)}`,
    ).toBe('0');
    const createdQuoteId = String(
      (commandBody as any).data?.data?.recordId ??
        (commandBody as any).data?.data?.quoteId ??
        '',
    );
    expect(createdQuoteId, 'create should return the new quote id').toBeTruthy();
    created.quoteId = createdQuoteId;
    // The create command mints a backend-generated quote code, so the scaffold's
    // marker code no longer identifies the quote under test. Read the code back
    // from the created record or the list navigation opens the wrong (empty) row.
    const createdQuote = await readDynamicRecord(page, 'qo_quote_common', createdQuoteId);
    created.quoteCode = String(createdQuote.qo_quote_code ?? '');
    expect(created.quoteCode, 'created quote exposes its code').toBeTruthy();

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
            hasColumnMapping:
              row.qo_bi_column_mapping != null && String(row.qo_bi_column_mapping).length > 0,
            hasProjectionFingerprint: /^[0-9a-f]{64}$/.test(
              String(row.qo_bi_projection_fingerprint ?? ''),
            ),
          }));
        },
        { timeout: 30_000, intervals: [500, 1_000, 2_000] },
      )
      .toEqual([
        expect.objectContaining({
          mode: 'quick',
          validRows: 4,
          hasRawHead: true,
          hasColumnMapping: true,
          hasProjectionFingerprint: true,
        }),
      ]);

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
    expect(quoteLines.map((row) => String(row.qo_ql_mpn)).sort()).toEqual(expectedMpns);

    const resistorLine = quoteLines.find((row) => row.qo_ql_mpn === `WMF2400TEE${mpnSuffix}`);
    expect(resistorLine, 'the blank-package 0201 resistor line must be imported').toBeTruthy();
    expect(String(resistorLine?.qo_ql_package ?? '')).toBe('');
    expect(Number(resistorLine?.qo_ql_qty)).toBe(3);
    expect(String(resistorLine?.qo_ql_description ?? '')).toContain('0201');

    await openQuoteDetailFromList(page, created);

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
            capturedUsesUploadBomLane: evidence
              .filter((row) => ['captured', 'usd_review'].includes(String(row.qo_pe_status)))
              .every((row) => String(parseSnapshot(row.qo_pe_snapshot).matchedBy) === 'upload_bom'),
            // The scenario reset clears the managed mock's request log before upload. Combined with
            // per-run unique MPNs, an observed upload-bom request proves this run crossed the real
            // protocol boundary instead of being satisfied by retained recent-cache evidence.
            mockUploadRequestObserved: await page.request
              .get(`${yunhanMockControlUrl()}/__control/requests`)
              .then(async (response) => {
                expect(response.ok(), 'read managed Yunhan mock request log').toBe(true);
                const body = (await response.json()) as {
                  requests?: Array<{ path?: unknown }>;
                };
                return (body.requests ?? []).some((request) =>
                  String(request.path ?? '').endsWith('/search-v1/products/upload-bom'),
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
        capturedUsesUploadBomLane: true,
        mockUploadRequestObserved: true,
      });

    // The same UI upload must also have triggered process-point calculation. The matching row
    // proves package normalization + exact package matching -> Excel rule row 3. The backend owns
    // points only; the exported quote template owns the point-unit-price multiplication.
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
        amount: 0,
      });
    expect(processHits).toHaveLength(4);

    const resistorHit = processHits.find(
      (row) => String(row.qo_pfrh_quote_line_id) === String(resistorLine?.pid),
    );
    expect(String(resistorHit?.qo_pfrh_point_formula)).toBe('3 × 2 = 6');
    expect(resistorHit?.qo_pfrh_point_source).toBe('rule-fixed-points');
    expect(String(resistorHit?.qo_pfrh_trace)).toContain('ruleRow=3');

    const matchedRules = await queryDynamicRecords(page, 'qo_process_fee_rule_line_common', [
      { fieldName: 'pid', operator: 'EQ', value: resistorHit?.qo_pfrh_rule_line_id },
    ]);
    expect(matchedRules).toHaveLength(1);
    expect(Number(matchedRules[0]?.qo_pfrl_source_row_no)).toBe(3);
    expect(String(matchedRules[0]?.qo_pfrl_component_type)).toContain('0201');
    expect(Number(matchedRules[0]?.qo_pfrl_point_count)).toBe(2);
    expect(matchedRules[0]?.qo_pfrl_unit_price ?? null).toBeNull();

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
    const yunhanColumn = priceHeaders.findIndex((header) =>
      /云汉(?:芯城|\(系数后\))|Yunhan(?: \(After Factor\))?/.test(header),
    );
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
    const processTable = resistorHitRow.locator('xpath=ancestor::table[1]');
    const processHeaders = await tableTexts(
      processTable.locator('thead th, thead [role="columnheader"]'),
    );
    const processCells = await tableTexts(resistorHitRow.locator('td, [role="cell"]'));
    for (const [label, expected] of [
      [/^(数量|单套用量|Qty)$/i, '3'],
      [/^(单件点数|Unit Points)$/i, '2'],
      [/^(合计点数|单套点数|Total Points)$/i, '6'],
    ] as const) {
      const column = processHeaders.findIndex((header) => label.test(header));
      expect(column, `process headers: ${processHeaders.join(' | ')}`).toBeGreaterThanOrEqual(0);
      expect(Number(processCells[column])).toBe(Number(expected));
    }

    // The review drawer is retired; the flat nine-column table carries the facts.
    await expect(page.getByTestId('review-drawer')).toHaveCount(0);
    const processHeaders2 = await tableTexts(
      resistorHitRow.locator('xpath=ancestor::table[1]').locator('thead th, thead [role="columnheader"]'),
    );
    const processCells2 = await tableTexts(resistorHitRow.locator('td, [role="cell"]'));
    const noteColumn2 = processHeaders2.findIndex((header) => /说明\/处理|Note \/ Action/i.test(header));
    expect(noteColumn2, `process headers2: ${processHeaders2.join(' | ')}`).toBeGreaterThanOrEqual(0);
    expect(processCells2[noteColumn2] ?? '').toBe('');
    const pointsColumn2 = processHeaders2.findIndex((header) => /数量\/点数|Qty \/ Points/i.test(header));
    expect(String(processCells2[pointsColumn2] ?? '')).toMatch(/3(\.0+)?\s*×\s*2(\.0+)?\s*=\s*6(\.0+)?/);

    await testInfo.attach('nonstd-process-fee-0201-match.png', {
      body: await page.screenshot({ fullPage: true }),
      contentType: 'image/png',
    });

    await page.getByRole('tab', { name: /报价Excel|Quote Excel/ }).click();
    const generateAction = page.getByTestId('workbench-action-generate_quote_excel');
    await expect(generateAction).toBeVisible({ timeout: 20_000 });
    const generateResponsePromise = page.waitForResponse(
      (response) =>
        response.url().includes('/api/meta/commands/execute/qo_quote_common:generate_document') &&
        response.request().method() === 'POST',
      { timeout: 60_000 },
    );
    const downloadPromise = page.waitForEvent('download', { timeout: 60_000 });
    await generateAction.click();
    const generateResponse = await generateResponsePromise;
    const generateBody = await generateResponse.json().catch(() => ({}));
    expect(
      String((generateBody as any).code),
      `generate_document response: ${JSON.stringify(generateBody).slice(0, 800)}`,
    ).toBe('0');
    const download = await downloadPromise;
    const quoteWorkbookPath = path.join(testInfo.outputDir, 'nonstd-customer-bom-quote.xlsx');
    await download.saveAs(quoteWorkbookPath);
    validateQuickCustomerBomWorkbook(quoteWorkbookPath, mpnSuffix);
    await testInfo.attach('nonstd-customer-bom-quote.xlsx', {
      path: quoteWorkbookPath,
      contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    });

    // Quick confirmation is quote-scoped and category-scoped. It must stay on the current quote,
    // show live counts before mutation, submit selected categories (not selected row ids), and
    // leave the non-selected diode untouched.
    await page.getByRole('tab', { name: /BOM价格计算|BOM Price/i }).click();
    const quickConfirmAction = page.getByTestId('workbench-action-quick_confirm_prices');
    await expect(quickConfirmAction).toBeVisible({ timeout: 20_000 });
    const categoryPreviewResponse = page.waitForResponse(
      (response) =>
        response
          .url()
          .includes(`/api/ext/qoe/quotes/${created.quoteId}/price-confirmation-categories`) &&
        response.request().method() === 'GET',
      { timeout: 30_000 },
    );
    await quickConfirmAction.click();
    const categoryPreview = await categoryPreviewResponse;
    expect(categoryPreview.ok(), 'category confirmation preview endpoint').toBe(true);
    const categoryPreviewBody = (await categoryPreview.json()) as {
      items?: Array<{
        value?: string;
        totalCount?: number;
        confirmableCount?: number;
        disabled?: boolean;
      }>;
    };
    const resistorPreview = categoryPreviewBody.items?.find((item) => item.value === 'resistor');
    const capacitorPreview = categoryPreviewBody.items?.find((item) => item.value === 'capacitor');
    expect(resistorPreview).toMatchObject({ totalCount: 2, confirmableCount: 2, disabled: false });
    expect(capacitorPreview).toMatchObject({ totalCount: 1, confirmableCount: 1, disabled: false });
    await expect(page).toHaveURL(new RegExp(`/p/qo_quote_common/view/${created.quoteId}`));

    const quickConfirmDialog = page.getByTestId('form-dialog');
    await expect(quickConfirmDialog).toBeVisible();
    await expect(quickConfirmDialog).toContainText(/按类别快速确认价格|Quick confirm prices/i);
    await expect(quickConfirmDialog).toContainText(/共 2 行 · 可确认 2/);
    await expect(quickConfirmDialog).toContainText(/共 1 行 · 可确认 1/);
    await quickConfirmDialog.getByRole('checkbox', { name: /电阻|Resistor/i }).check();
    await quickConfirmDialog.getByRole('checkbox', { name: /电容|Capacitor/i }).check();
    await quickConfirmDialog
      .getByTestId('form-dialog-field-confirm_scope')
      .getByRole('checkbox')
      .check();
    await testInfo.attach('nonstd-category-quick-confirm-selection.png', {
      body: await quickConfirmDialog.screenshot(),
      contentType: 'image/png',
    });

    const quickConfirmRequest = page.waitForRequest(
      (request) =>
        request.url().includes(`/api/meta/commands/execute/${QUICK_CONFIRM_COMMAND}`) &&
        request.method() === 'POST',
      { timeout: 30_000 },
    );
    const quickConfirmResponse = page.waitForResponse(
      (response) =>
        response.url().includes(`/api/meta/commands/execute/${QUICK_CONFIRM_COMMAND}`) &&
        response.request().method() === 'POST',
      { timeout: 30_000 },
    );
    await quickConfirmDialog.getByTestId('form-dialog-submit').click();
    const observedQuickConfirmRequest = await quickConfirmRequest;
    expect(observedQuickConfirmRequest.postDataJSON()).toMatchObject({
      targetRecordPid: created.quoteId,
      operationType: 'UPDATE',
      payload: {
        component_categories: ['resistor', 'capacitor'],
        confirm_scope: true,
      },
    });
    const quickConfirmBody = await (await quickConfirmResponse).json();
    expect(String(quickConfirmBody.code), JSON.stringify(quickConfirmBody).slice(0, 800)).toBe('0');

    const quickConfirmReceipt = page.getByTestId('workbench-result-receipt');
    await expect(quickConfirmReceipt).toBeVisible({ timeout: 20_000 });
    await expect(page.getByTestId('workbench-result-receipt-field-confirmed')).toContainText('3');
    await expect(page.getByTestId('workbench-result-receipt-field-review')).toContainText('0');
    await expect(page.getByTestId('workbench-result-receipt-field-unpriced')).toContainText('0');
    await testInfo.attach('nonstd-category-quick-confirm-result.png', {
      body: await page.screenshot({ fullPage: true }),
      contentType: 'image/png',
    });

    const quickConfirmedLines = quoteLines.filter((row) =>
      ['resistor', 'capacitor'].includes(String(row.qo_ql_component_category)),
    );
    const excludedDiode = quoteLines.find(
      (row) => String(row.qo_ql_component_category) === 'diode',
    );
    expect(quickConfirmedLines).toHaveLength(3);
    expect(excludedDiode, 'the non-selected diode category must remain untouched').toBeTruthy();
    await expect
      .poll(
        async () => {
          const confirmedRows = (
            await Promise.all(
              quickConfirmedLines.map((line) =>
                queryDynamicRecords(page, 'qo_quote_line_common', [
                  { fieldName: 'pid', operator: 'EQ', value: line.pid },
                ]),
              ),
            )
          ).flat();
          const diodeRows = await queryDynamicRecords(page, 'qo_quote_line_common', [
            { fieldName: 'pid', operator: 'EQ', value: excludedDiode?.pid },
          ]);
          return {
            confirmedCount: confirmedRows.filter(
              (row) =>
                String(row.qo_ql_price_decision_id ?? '').length > 0 &&
                Number(row.qo_ql_unit_cost ?? 0) > 0,
            ).length,
            acceptedByCategoryCommand: confirmedRows.every(
              (row) => row.qo_ql_price_accepted_by === 'category_quick_confirmation',
            ),
            diodeDecisionId: String(diodeRows[0]?.qo_ql_price_decision_id ?? ''),
          };
        },
        { timeout: 30_000, intervals: [500, 1_000, 2_000] },
      )
      .toEqual({
        confirmedCount: 3,
        acceptedByCategoryCommand: true,
        diodeDecisionId: '',
      });

    await expect(consoleIssues).toEqual([]);
  });
});
