import { test, expect } from '../../fixtures';
import { openQuoteDetailFromList, seedProcessFeeReviewQuote } from './quote-e2e-helpers';

function compact(value: string): string {
  return value.replace(/\s+/g, ' ').trim();
}

test.describe('PCBA quote pricing and process-point evidence golden', () => {
  test.describe.configure({ timeout: 120_000 });

  test('maps every Excel row onto one flat table row and keeps review facts out of a drawer', async ({
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

    const processTable = page
      .locator('table')
      .filter({
        has: page.getByRole('columnheader', {
          name: /厂商\/MPN|Manufacturer \/ MPN/,
        }),
      })
      .first();
    await expect(processTable).toBeVisible({ timeout: 20_000 });
    const headers = (await processTable.locator('thead th').allInnerTexts()).map(compact);
    expect(
      headers,
      `unexpected process-point headers: ${JSON.stringify(headers)}`,
    ).toEqual([
      '原始行',
      '位号',
      '状态',
      '物料/规格',
      '厂商/MPN',
      '工序/依据',
      'GERBER事实',
      '数量/点数',
      '说明/处理',
    ]);

    const matchedChip = page.getByTestId('metric-strip-item-matched');
    const manualRequiredChip = page.getByTestId('metric-strip-item-partial');
    const unmatchedChip = page.getByTestId('metric-strip-item-unmatched');
    await expect(matchedChip).toContainText('1');
    await expect(manualRequiredChip).toContainText('1');
    await expect(unmatchedChip).toContainText('1');

    await matchedChip.click();
    const matchedRow = processTable
      .locator('[data-testid^="table-row-"]')
      .filter({ hasText: 'E2E-EXACT' });
    await expect(matchedRow).toHaveCount(1, { timeout: 20_000 });
    const matchedCells = matchedRow.locator('td');
    await expect(matchedCells.nth(0)).toHaveText('14');
    await expect(matchedRow).toContainText('R14');
    await expect(matchedRow).toContainText(/E2E-EXACT-/);
    await expect(matchedRow).toContainText(/4(\.\d+)?\s*×\s*2(\.\d+)?\s*=\s*8(\.\d+)?/);
    await expect(matchedRow).toContainText('SMT');
    // A matched row stays quiet: the note/action column carries no review noise.
    await expect(matchedCells.nth(8)).toHaveText('');

    await processTable.scrollIntoViewIfNeeded();
    await processTable.screenshot({ path: testInfo.outputPath('01-process-points-table.png') });

    // The review drawer is retired: clicking a row must not open any overlay.
    await matchedRow.click({ force: true });
    await expect(page.getByTestId('review-drawer')).toHaveCount(0);

    await unmatchedChip.click();
    const unmatchedRow = processTable
      .locator('[data-testid^="table-row-"]')
      .filter({ hasText: 'E2E-UNMATCHED' });
    await expect(unmatchedRow).toHaveCount(1, { timeout: 20_000 });
    await expect(unmatchedRow).toContainText(/规则未命中|Rule Missing/);
    await expect(unmatchedRow).toContainText(/标准封装\s*TO999\s*未命中规则/);
    await expect(unmatchedRow).toContainText(/补齐封装或调整规则/);

    await manualRequiredChip.click();
    const manualRequiredRow = processTable
      .locator('[data-testid^="table-row-"]')
      .filter({ hasText: 'E2E-MIXED' });
    await expect(manualRequiredRow).toHaveCount(1, { timeout: 20_000 });
    await expect(manualRequiredRow).toContainText(/需人工复核|Needs Review/);
    await expect(manualRequiredRow).toContainText(/封装缺失、无法识别或规则口径尚未启用/);
    await expect(manualRequiredRow).toContainText(/补齐封装或调整规则/);

    await processTable.screenshot({
      path: testInfo.outputPath('03-process-point-pending-states.png'),
    });
  });
});
