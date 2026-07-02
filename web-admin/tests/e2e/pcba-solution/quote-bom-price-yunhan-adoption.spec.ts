import type { Locator, Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import {
  cleanupRows,
  openQuoteDetailFromList,
  queryDynamicRecords,
  queryNamedDataSourceRecords,
  readDynamicRecord,
  seedBomPriceYunhanQuote,
  type BomPriceYunhanSeed,
} from './quote-e2e-helpers';

/**
 * Yunhan (云汉芯城 / ickey.cn) price source — evidence review + adoption in the BOM price waterfall.
 *
 * Exercises the delivery path a user takes after 运行查价 returns a yunhan online-catalog price:
 * open the review drawer, inspect the 云汉 candidate (unit price / MOQ / MPQ / currency), adopt it,
 * and confirm the adopted price + source persist to the quote line and waterfall. The evidence row
 * is seeded (captured, source=yunhan) so the scenario is deterministic and does not depend on the
 * live ickey sandbox; live sourcing is covered separately by the backend YunhanLiveSmokeTest.
 */
async function tableCellTexts(row: Locator): Promise<string[]> {
  const cells = row.locator('td, [role="cell"]');
  await expect(cells.first()).toBeVisible({ timeout: 20_000 });
  return cells.evaluateAll((nodes) =>
    nodes.map((node) => (node.textContent || '').replace(/\s+/g, ' ').trim()),
  );
}

async function readWaterfallLine(
  page: Page,
  created: BomPriceYunhanSeed,
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

test.describe('PCBA quote BOM price yunhan adoption', () => {
  test.describe.configure({ timeout: 120_000 });

  test('adopts a captured 云汉芯城 candidate from the review drawer and persists source + price', async ({
    page,
  }) => {
    const created = await seedBomPriceYunhanQuote(page);

    try {
      await openQuoteDetailFromList(page, created);
      const bomPriceTab = page.getByRole('tab', { name: /BOM价格计算|BOM Price/i });
      await expect(bomPriceTab).toBeVisible({ timeout: 20_000 });
      await bomPriceTab.click();

      const priceRow = page.getByTestId(`table-row-${created.lineId}`);
      await expect(priceRow).toBeVisible({ timeout: 30_000 });
      await expect(priceRow).toContainText(created.mpn);

      // Before adoption: no adopted price/source, candidate awaits confirmation.
      const beforeWaterfall = await readWaterfallLine(page, created);
      expect(String(beforeWaterfall.adopted_source ?? '')).toBe('');
      expect(String(beforeWaterfall.adopted_source_label ?? '')).toBe('');

      // Open the review drawer and inspect the 云汉 candidate.
      await priceRow.click();
      const reviewDrawer = page.getByTestId('review-drawer');
      await expect(reviewDrawer).toBeVisible({ timeout: 10_000 });
      await expect(reviewDrawer).toContainText(created.mpn);

      const yunhanCandidate = page.getByTestId(
        `review-drawer-candidate-${created.capturedEvidenceId}`,
      );
      await expect(yunhanCandidate).toBeVisible({ timeout: 20_000 });
      await expect(yunhanCandidate).toContainText(/云汉|Yunhan/);
      await expect(yunhanCandidate).toContainText('0.0264');
      await expect(yunhanCandidate).toContainText('CNY');

      // Select the candidate and adopt it (confirm_cost_from_evidence).
      await yunhanCandidate.click();
      const confirmAction = page.getByTestId('review-drawer-candidate-action-confirm_price');
      await expect(confirmAction).toBeEnabled({ timeout: 10_000 });

      const confirmResponse = page.waitForResponse(
        (response) =>
          response
            .url()
            .includes(
              '/api/meta/commands/execute/qo_quote_line_common:confirm_cost_from_evidence',
            ) && response.request().method() === 'POST',
        { timeout: 30_000 },
      );
      await confirmAction.click();
      const response = await confirmResponse;
      const responseBody = await response.json().catch(() => ({}));
      expect(String((responseBody as any).code), JSON.stringify(responseBody).slice(0, 800)).toBe(
        '0',
      );

      // Adopted decision persists as source=yunhan with the captured unit price.
      await expect
        .poll(
          async () => {
            const decisions = await queryDynamicRecords(page, 'qo_quote_line_price_decision_common', [
              { fieldName: 'qo_qlpd_quote_line_id', operator: 'EQ', value: created.lineId },
              { fieldName: 'qo_qlpd_status', operator: 'EQ', value: 'accepted' },
            ]);
            return decisions.map((d) => String(d.qo_qlpd_source)).join(',');
          },
          { timeout: 20_000, intervals: [500, 1_000, 2_000] },
        )
        .toContain('yunhan');

      // Adopted cost lands on the quote line.
      await expect
        .poll(
          async () => {
            const adoptedLine = await readDynamicRecord(page, 'qo_quote_line_common', created.lineId);
            return Number(adoptedLine.qo_ql_unit_cost).toFixed(4);
          },
          { timeout: 20_000, intervals: [500, 1_000, 2_000] },
        )
        .toBe(created.unitPrice.toFixed(4));

      const adoptedLine = await readDynamicRecord(page, 'qo_quote_line_common', created.lineId);
      expect(Number(adoptedLine.qo_ql_line_cost)).toBeCloseTo(created.unitPrice * 100, 2);
      expect(String(adoptedLine.qo_ql_currency ?? '')).toBe('CNY');

      // Waterfall + main table reflect 云汉 as the adopted source.
      await expect
        .poll(
          async () => {
            const line = await readWaterfallLine(page, created);
            return String(line.adopted_source_label ?? '');
          },
          { timeout: 20_000, intervals: [500, 1_000, 2_000] },
        )
        .toBe('云汉');
      const afterWaterfall = await readWaterfallLine(page, created);
      expect(String(afterWaterfall.adopted_source ?? '')).toBe('yunhan');
      expect(Number(afterWaterfall.adopted_price)).toBeCloseTo(created.unitPrice, 4);

      await expect(priceRow).toContainText(/云汉|Yunhan/, { timeout: 20_000 });
      const rowCells = (await tableCellTexts(priceRow)).join(' ');
      expect(rowCells).toContain('0.0264');
    } finally {
      await cleanupRows(page, created);
    }
  });
});
