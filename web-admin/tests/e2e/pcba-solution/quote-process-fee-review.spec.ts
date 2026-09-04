import { test, expect } from '../../fixtures';
import { openQuoteDetailFromList, seedProcessFeeReviewQuote } from './quote-e2e-helpers';

function compact(value: string): string {
  return value.replace(/\s+/g, ' ').trim();
}

test.describe('PCBA quote pricing and process-point evidence golden', () => {
  test.describe.configure({ timeout: 120_000 });

  test('keeps the gerber-caliber board summary, seven-column rows and trace-backed unresolved reasons', async ({
    page,
  }, testInfo) => {
    // Unique fixture identifiers make old retained evidence unable to satisfy this run.
    const created = await seedProcessFeeReviewQuote(page);

    await openQuoteDetailFromList(page, created);
    await expect(page.getByRole('button', { name: /价格系数\(百分比\)\s+100\.00%/ })).toBeVisible({
      timeout: 20_000,
    });

    const processPointsTab = page.getByRole('tab', { name: /加工点数|Process Points/ });
    await expect(processPointsTab).toBeVisible({ timeout: 20_000 });
    await processPointsTab.click();

    // Gerber-only caliber (2026-09-04): the board summary card is the primary
    // presentation. Without Gerber histograms every row stays unresolved, so the
    // card reports 部分识别 plus the aggregated trace-backed reason.
    await expect(
      page.getByRole('heading', { name: /板级加工点数汇总|Board Process Point Summary/ }),
    ).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText(/部分识别/)).toBeVisible();
    await expect(
      page.getByText(/3 行待复核[:：]\s*未上传 Gerber，无法按面积区间计点/),
    ).toBeVisible();

    const processTable = page
      .locator('table')
      .filter({
        has: page.getByRole('columnheader', {
          name: /原始行|Source Row/,
        }),
      })
      .first();
    await expect(processTable).toBeVisible({ timeout: 20_000 });
    const headers = (await processTable.locator('thead th').allInnerTexts()).map(compact);
    expect(
      headers,
      `unexpected process-point headers: ${JSON.stringify(headers)}`,
    ).toEqual(['原始行', '位号', '状态', '物料/规格', 'GERBER事实', '数量/点数', '说明/处理']);

    const matchedChip = page.getByTestId('metric-strip-item-matched');
    const manualRequiredChip = page.getByTestId('metric-strip-item-partial');
    const unmatchedChip = page.getByTestId('metric-strip-item-unmatched');
    await expect(matchedChip).toContainText('0');
    await expect(manualRequiredChip).toContainText('3');
    await expect(unmatchedChip).toContainText('0');

    await manualRequiredChip.click();
    for (const mpn of ['E2E-EXACT', 'E2E-UNMATCHED', 'E2E-MIXED']) {
      const row = processTable.locator('[data-testid^="table-row-"]').filter({ hasText: mpn });
      await expect(row).toHaveCount(1, { timeout: 20_000 });
      await expect(row).toContainText(/需人工复核|Needs Review/);
      // GERBER事实 stays a bare dash: the empty "- · 0point" concatenation is gone,
      // and the note column carries the trace-backed reason plus the action.
      await expect(row).toContainText(
        /未上传 Gerber，无法按面积区间计点；补齐 Gerber 资料或确认解析结果后重新计算/,
      );
    }

    await processTable.scrollIntoViewIfNeeded();
    await processTable.screenshot({ path: testInfo.outputPath('01-process-points-table.png') });

    // The review drawer is retired: clicking a row must not open any overlay.
    const anyRow = processTable.locator('[data-testid^="table-row-"]').filter({ hasText: 'E2E-EXACT' });
    await anyRow.click({ force: true });
    await expect(page.getByTestId('review-drawer')).toHaveCount(0);

    // Matched / rule-missing filters have no rows to show before Gerber is parsed.
    await matchedChip.click();
    for (const mpn of ['E2E-EXACT', 'E2E-UNMATCHED', 'E2E-MIXED']) {
      await expect(
        processTable.locator('[data-testid^="table-row-"]').filter({ hasText: mpn }),
      ).toHaveCount(0);
    }
    await unmatchedChip.click();
    await expect(
      processTable.locator('[data-testid^="table-row-"]').filter({ hasText: 'E2E-EXACT' }),
    ).toHaveCount(0);

    await processTable.screenshot({
      path: testInfo.outputPath('03-process-point-pending-states.png'),
    });
  });
});
