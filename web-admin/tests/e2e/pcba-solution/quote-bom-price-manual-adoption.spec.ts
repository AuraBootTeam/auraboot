import type { Locator, Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import {
  cleanupRows,
  queryDynamicRecords,
  queryNamedDataSourceRecords,
  readDynamicRecord,
  seedBomPriceManualReviewQuote,
  type BomPriceManualReviewSeed,
} from './quote-e2e-helpers';

const MANUAL_UNIT_PRICE = 2.3456;
const MANUAL_SUPPLIER = 'E2E Manual Supplier';
const MANUAL_SOURCE_NOTE = 'E2E source: business phone quote';
const MANUAL_REASON = 'E2E business裁决: record and adopt manual price';
const MANUAL_VALID_UNTIL = '2030-12-31';
const EXPECTED_LINE_COST = Number((MANUAL_UNIT_PRICE * 10).toFixed(2));

async function tableHeaders(table: Locator): Promise<string[]> {
  const headers = table.locator('thead th, [role="columnheader"]');
  await expect(headers.first()).toBeVisible({ timeout: 20_000 });
  return headers.evaluateAll((nodes) =>
    nodes.map((node) => (node.textContent || '').replace(/\s+/g, ' ').trim()).filter(Boolean),
  );
}

async function tableCellTexts(row: Locator): Promise<string[]> {
  const cells = row.locator('td, [role="cell"]');
  await expect(cells.first()).toBeVisible({ timeout: 20_000 });
  return cells.evaluateAll((nodes) =>
    nodes.map((node) => (node.textContent || '').replace(/\s+/g, ' ').trim()),
  );
}

function parseJsonObject(value: unknown): Record<string, any> {
  if (value && typeof value === 'object') return value as Record<string, any>;
  if (typeof value !== 'string') return {};
  try {
    const parsed = JSON.parse(value);
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch {
    return {};
  }
}

async function readWaterfallLine(
  page: Page,
  created: BomPriceManualReviewSeed,
): Promise<Record<string, unknown>> {
  const rows = await queryNamedDataSourceRecords(page, 'qo_quote_bom_price_waterfall', {
    quoteId: created.quoteId,
  });
  const line = rows.find(
    (row) =>
      String(row.pid ?? '') === created.lineId ||
      String(row.qo_quote_line_id ?? '') === created.lineId,
  );
  expect(line, `BOM price waterfall should contain line ${created.lineId}`).toBeTruthy();
  return line as Record<string, unknown>;
}

async function readManualEvidence(page: Page, lineId: string): Promise<Record<string, unknown>> {
  const records = await queryDynamicRecords(
    page,
    'qo_price_evidence_common',
    [
      { fieldName: 'qo_pe_quote_line_id', operator: 'EQ', value: lineId },
      { fieldName: 'qo_pe_source', operator: 'EQ', value: 'manual' },
    ],
    { pageSize: 10 },
  );
  expect(records.length, `manual evidence should exist for line ${lineId}`).toBe(1);
  return records[0];
}

test.describe('PCBA quote BOM price manual adoption', () => {
  test.describe.configure({ timeout: 120_000 });

  test('covers table columns, drawer evidence, manual price entry, and adopted source persistence', async ({
    page,
  }) => {
    const created = await seedBomPriceManualReviewQuote(page);

    try {
      const waterfallLoad = page
        .waitForResponse(
          (response) => {
            const url = decodeURIComponent(response.url());
            return (
              response.status() === 200 &&
              url.includes('/api/datasource/list') &&
              url.includes('nq:qo_quote_bom_price_waterfall') &&
              url.includes(created.quoteId)
            );
          },
          { timeout: 30_000 },
        )
        .catch(() => null);

      await page.goto(`/p/qo_quote_common/view/${created.quoteId}#bom_price`, {
        waitUntil: 'domcontentloaded',
      });
      const bomPriceTab = page.getByRole('tab', { name: /BOM价格计算|BOM Price/i });
      await expect(bomPriceTab).toBeVisible({ timeout: 20_000 });
      await bomPriceTab.click();
      await waterfallLoad;

      const priceRow = page.getByTestId(`table-row-${created.lineId}`);
      await expect(priceRow).toBeVisible({ timeout: 30_000 });
      await expect(priceRow).toContainText(created.mpn);
      await expect(priceRow).toContainText(/1\.1111|1\.111|1\.11/);

      const priceTable = priceRow.locator('xpath=ancestor::table[1]');
      const headers = await tableHeaders(priceTable);
      expect(headers).toEqual([
        '物料',
        'BOM用量',
        '金蝶历史采购价',
        '立创商城',
        '云汉芯城',
        '华强电子网',
        'DeepSeek建议',
        '供应商 RFQ',
        '采用价格',
        '采用来源',
        '当前状态',
      ]);
      const cellTextByHeader = new Map(
        (await tableCellTexts(priceRow)).map((text, index) => [
          headers[index] ?? `column-${index}`,
          text,
        ]),
      );
      expect(cellTextByHeader.get('BOM用量')).toBe('10');
      expect(cellTextByHeader.get('金蝶历史采购价')).toBe('未命中');
      expect(cellTextByHeader.get('采用来源') ?? '').toMatch(/^[-—–]?$/);
      expect(cellTextByHeader.get('当前状态')).toContain('暂无价格');

      const headerText = (await priceTable.locator('thead').innerText()).replace(/\s+/g, ' ');
      expect(headerText).not.toContain('证据');
      expect(headerText).not.toContain('失败原因');
      expect(headerText).not.toContain('刷新');

      const beforeWaterfall = await readWaterfallLine(page, created);
      expect(String(beforeWaterfall.bom_qty ?? '')).toBe('10');
      expect(String(beforeWaterfall.kingdee_recent_price ?? '')).toBe('未命中');
      expect(String(beforeWaterfall.adopted_source ?? '')).toBe('');
      expect(String(beforeWaterfall.adopted_source_label ?? '')).toBe('');
      expect(String(beforeWaterfall.current_price_status ?? '')).toBe('暂无价格');
      expect(String(beforeWaterfall.deepseek_suggested_price ?? '')).toContain('1.1111');

      await priceRow.click();
      const reviewDrawer = page.getByTestId('review-drawer');
      await expect(reviewDrawer).toBeVisible({ timeout: 10_000 });
      await expect(reviewDrawer).toContainText(created.mpn);
      await expect(
        page.getByTestId(`review-drawer-candidate-${created.suggestedEvidenceId}`),
      ).toContainText(/DeepSeek建议|DeepSeek/);
      await expect(
        page.getByTestId(`review-drawer-candidate-${created.suggestedEvidenceId}`),
      ).toContainText(/建议价|Suggested/);

      const failedCandidate = page.getByTestId(
        `review-drawer-candidate-${created.failedEvidenceId}`,
      );
      await expect(failedCandidate).toContainText(/金蝶历史采购|Kingdee/);
      await expect(failedCandidate).toContainText(/未命中|Not Found/);
      await failedCandidate.click();
      await expect(reviewDrawer).toContainText('E2E historical price missing');
      await expect(reviewDrawer).toContainText(/未命中可用价格|未命中/);
      await expect(reviewDrawer).toContainText(
        /补充人工价或供应商报价|manual price|supplier quote/i,
      );

      const manualAction = page.getByTestId('review-drawer-candidate-action-record_manual_price');
      await expect(manualAction).toBeEnabled();
      await manualAction.click();

      const form = page.getByTestId('review-drawer-action-form');
      await expect(form).toBeVisible({ timeout: 5_000 });
      await expect(form).toContainText('录入人工价');
      await expect(page.getByTestId('review-drawer-action-form-submit')).toContainText(
        '录入并采用',
      );

      const unitPriceField = page.getByTestId('review-drawer-action-form-field-unitPrice');
      await page.getByTestId('review-drawer-action-form-field-sourceNote').fill(MANUAL_SOURCE_NOTE);
      await page.getByTestId('review-drawer-action-form-submit').click();
      const unitPriceValidation = await unitPriceField.evaluate(
        (node) => (node as HTMLInputElement).validationMessage,
      );
      expect(unitPriceValidation, 'manual unit price is required before submit').toBeTruthy();

      await unitPriceField.fill(String(MANUAL_UNIT_PRICE));
      await page.getByTestId('review-drawer-action-form-field-supplierName').fill(MANUAL_SUPPLIER);
      await page.getByTestId('review-drawer-action-form-field-reason').fill(MANUAL_REASON);
      await page.getByTestId('review-drawer-action-form-field-validUntil').fill(MANUAL_VALID_UNTIL);

      const manualCommandResponse = page.waitForResponse(
        (response) =>
          response
            .url()
            .includes('/api/meta/commands/execute/qo_quote_line_common:record_manual_price') &&
          response.request().method() === 'POST',
        { timeout: 30_000 },
      );
      await page.getByTestId('review-drawer-action-form-submit').click();
      const response = await manualCommandResponse;
      const responseBody = await response.json().catch(() => ({}));
      expect(String((responseBody as any).code), JSON.stringify(responseBody).slice(0, 800)).toBe(
        '0',
      );

      const requestBody = response.request().postDataJSON() as Record<string, any>;
      expect(requestBody.targetRecordId).toBe(created.lineId);
      expect(requestBody.operationType).toBe('UPDATE');
      expect(requestBody.payload).toMatchObject({
        source: 'manual',
        unitPrice: String(MANUAL_UNIT_PRICE),
        currency: 'CNY',
        supplierName: MANUAL_SUPPLIER,
        sourceNote: MANUAL_SOURCE_NOTE,
        reason: MANUAL_REASON,
        validUntil: MANUAL_VALID_UNTIL,
      });
      await expect(page.getByTestId('review-drawer-action-form')).toHaveCount(0, {
        timeout: 10_000,
      });

      await expect
        .poll(
          async () => {
            const manualEvidence = await readManualEvidence(page, created.lineId);
            return String(manualEvidence.qo_pe_status ?? '');
          },
          { timeout: 20_000, intervals: [500, 1_000, 2_000] },
        )
        .toBe('confirmed');

      const manualEvidence = await readManualEvidence(page, created.lineId);
      expect(String(manualEvidence.qo_pe_source ?? '')).toBe('manual');
      expect(String(manualEvidence.qo_pe_supplier_name ?? '')).toBe(MANUAL_SUPPLIER);
      expect(Number(manualEvidence.qo_pe_unit_price)).toBeCloseTo(MANUAL_UNIT_PRICE, 4);
      expect(String(manualEvidence.qo_pe_currency ?? '')).toBe('CNY');
      expect(String(manualEvidence.qo_pe_valid_until ?? '')).toContain(MANUAL_VALID_UNTIL);
      expect(String(manualEvidence.qo_pe_override_reason ?? '')).toBe(MANUAL_REASON);
      const snapshot = parseJsonObject(manualEvidence.qo_pe_snapshot);
      expect(snapshot.source).toBe('manual');
      expect(snapshot.adoptionMode).toBe('record_and_adopt');
      expect(snapshot.manualInput?.sourceNote).toBe(MANUAL_SOURCE_NOTE);
      expect(snapshot.manualInput?.reason).toBe(MANUAL_REASON);

      await expect
        .poll(
          async () => {
            const adoptedLine = await readDynamicRecord(
              page,
              'qo_quote_line_common',
              created.lineId,
            );
            return Number(adoptedLine.qo_ql_unit_cost).toFixed(4);
          },
          { timeout: 20_000, intervals: [500, 1_000, 2_000] },
        )
        .toBe(MANUAL_UNIT_PRICE.toFixed(4));

      const adoptedLine = await readDynamicRecord(page, 'qo_quote_line_common', created.lineId);
      expect(Number(adoptedLine.qo_ql_line_cost)).toBeCloseTo(EXPECTED_LINE_COST, 2);
      expect(String(adoptedLine.qo_ql_currency ?? '')).toBe('CNY');
      expect(String(adoptedLine.qo_ql_risk ?? '')).toBe('none');

      await expect
        .poll(
          async () => {
            const line = await readWaterfallLine(page, created);
            return `${line.adopted_source_label ?? ''}|${line.current_price_status ?? ''}`;
          },
          { timeout: 20_000, intervals: [500, 1_000, 2_000] },
        )
        .toBe('人工|人工价已采用');
      const afterWaterfall = await readWaterfallLine(page, created);
      expect(Number(afterWaterfall.adopted_price)).toBeCloseTo(MANUAL_UNIT_PRICE, 4);

      await expect(priceRow).toContainText(/人工价已采用|Manual price adopted/, {
        timeout: 20_000,
      });
      await expect(priceRow).toContainText(/人工|Manual/);
      await expect(priceRow).toContainText(/2\.3456|2\.346|2\.35/);
      await expect(reviewDrawer).toContainText(/人工|Manual/, { timeout: 20_000 });
      await expect(reviewDrawer).toContainText(/已确认|Confirmed/);
    } finally {
      await cleanupRows(page, created);
    }
  });
});
