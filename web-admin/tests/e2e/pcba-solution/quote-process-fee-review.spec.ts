import { test, expect } from '../../fixtures';
import { openQuoteDetailFromList, seedProcessFeeReviewQuote } from './quote-e2e-helpers';

function compact(value: string): string {
  return value.replace(/\s+/g, ' ').trim();
}

test.describe('PCBA quote pricing and process-point evidence golden', () => {
  test.describe.configure({ timeout: 120_000 });

  test('shows Excel lineage and explains exact-package points while unsupported facts stay pending', async ({
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
          name: /原始封装 → 标准封装|Raw → Normalized Package/,
        }),
      })
      .first();
    await expect(processTable).toBeVisible({ timeout: 20_000 });
    const headers = (await processTable.locator('thead th').allInnerTexts()).map(compact);
    expect(
      headers[0].toLocaleLowerCase(),
      `unexpected process-point headers: ${JSON.stringify(headers)}`,
    ).toBe('excel行');

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
    await expect(matchedRow).toContainText('0201 → 0201');
    await expect(matchedRow).toContainText(/Excel行\s*3/);
    await expect(matchedRow).toContainText(/4\.00/);
    await expect(matchedRow).toContainText(/封装\s+0201\s+精确命中规则/);

    await processTable.scrollIntoViewIfNeeded();
    await processTable.screenshot({ path: testInfo.outputPath('01-process-points-table.png') });

    await matchedRow.click({ force: true });
    const reviewDrawer = page.getByTestId('review-drawer');
    await expect(reviewDrawer).toBeVisible({ timeout: 20_000 });
    await expect(page.getByTestId('review-drawer-raw-panel')).toBeVisible();
    await expect(page.getByTestId('review-drawer-canonical-panel')).toBeVisible();
    await expect(page.getByTestId('review-drawer-parse-summary')).toBeVisible();

    const drawerText = compact(await reviewDrawer.innerText());
    for (const expected of [
      '本行输入',
      '核算结果',
      '计算依据',
      '原始封装事实',
      '标准封装',
      'BOM封装列',
      'package_exact',
      'Excel行 3',
      '单套用量 4',
      '单件点数 2',
      '单套点数 8',
      '仅依据标准封装 0201 完全相等命中',
      '未使用名称、MPN、位号或关键词',
    ]) {
      expect(drawerText, `process-point drawer is missing ${expected}`).toContain(expected);
    }
    for (const forbidden of ['解析证据与 Profile / LLM Policy', '无需在页面维护规则', '核算结论']) {
      expect(drawerText).not.toContain(forbidden);
    }
    await expect(page.getByTestId('review-drawer-tab-source')).toHaveCount(0);
    await expect(page.getByTestId('review-drawer-tab-candidates')).toHaveCount(0);
    await reviewDrawer.screenshot({
      path: testInfo.outputPath('02-process-point-evidence-drawer.png'),
    });

    await page.getByRole('button', { name: /关闭复核浮层|Close review drawer/ }).click();
    await unmatchedChip.click();
    const unmatchedRow = processTable
      .locator('[data-testid^="table-row-"]')
      .filter({ hasText: 'E2E-UNMATCHED' });
    await expect(unmatchedRow).toHaveCount(1, { timeout: 20_000 });
    await expect(unmatchedRow).toContainText(/规则未命中|Rule Missing/);
    await expect(unmatchedRow).toContainText(/TO-999\s*→\s*TO999/);

    await manualRequiredChip.click();
    const manualRequiredRow = processTable
      .locator('[data-testid^="table-row-"]')
      .filter({ hasText: 'E2E-MIXED' });
    await expect(manualRequiredRow).toHaveCount(1, { timeout: 20_000 });
    await expect(manualRequiredRow).toContainText(/需人工复核|Needs Review/);
    await expect(manualRequiredRow).toContainText(/当前只支持封装精确匹配|补齐封装/);

    await processTable.screenshot({
      path: testInfo.outputPath('03-process-point-pending-states.png'),
    });
  });
});
