import { test, expect } from '../../fixtures';
import { cleanupRows, seedProcessFeeReviewQuote } from './quote-e2e-helpers';

test.describe('PCBA quote process fee review', () => {
  test.describe.configure({ timeout: 90_000 });

  test('filters manual-required and unmatched process fee hits with structured metering evidence', async ({ page }) => {
    const created = await seedProcessFeeReviewQuote(page);
    try {
      await page.goto(`/p/qo_quote_common/view/${created.quoteId}`, { waitUntil: 'domcontentloaded' });
      const processPointsTab = page.getByRole('tab', { name: /加工点数|Process Points/ });
      await expect(processPointsTab).toBeVisible({ timeout: 20_000 });
      await processPointsTab.click();

      const manualRequiredChip = page.getByTestId('metric-strip-item-partial');
      const unmatchedChip = page.getByTestId('metric-strip-item-unmatched');
      await expect(manualRequiredChip).toContainText('1', { timeout: 20_000 });
      await expect(unmatchedChip).toContainText('1');

      await unmatchedChip.click();
      const unmatchedRow = page.locator('[data-testid^="table-row-"]').filter({ hasText: 'E2E-UNMATCHED' });
      await expect(unmatchedRow).toHaveCount(1, { timeout: 20_000 });
      await expect(unmatchedRow).toContainText(/规则未命中|Rule Missing|unmatched/i);
      await unmatchedRow.click();

      const reviewDrawer = page.getByTestId('review-drawer');
      await expect(reviewDrawer).toBeVisible({ timeout: 10_000 });
      await expect(page.getByTestId('review-drawer-badge-status')).toContainText(/规则未命中|Rule Missing/);
      await expect(reviewDrawer).toContainText('NO_RULE_PKG');
      await expect(reviewDrawer).toContainText('quote_line_points');
      await expect(reviewDrawer).toContainText(/单件点数|Unit Points/);
      await expect(reviewDrawer).toContainText(/合计点数|Total Points/);
      await expect(reviewDrawer).toContainText(/数量 3 .*单件点数 2(?:\.0+)? .*合计点数 6(?:\.0+)?|Qty \/ Points/);
      await expect(reviewDrawer).toContainText('UNMATCHED');

      await page.getByRole('button', { name: /关闭复核浮层|Close review drawer/ }).click();
      await expect(reviewDrawer).toHaveCount(0);
      await manualRequiredChip.click();
      const manualRequiredRow = page.locator('[data-testid^="table-row-"]').filter({ hasText: 'E2E-MIXED' });
      await expect(manualRequiredRow).toHaveCount(1, { timeout: 20_000 });
      await expect(manualRequiredRow).toContainText(/需人工复核|Needs Review|manual_required/i);
      await manualRequiredRow.click();

      await expect(page.getByTestId('review-drawer-badge-status')).toContainText(/需人工复核|Needs Review/);
      await expect(reviewDrawer).toContainText('SMT+DIP');
      await expect(reviewDrawer).toContainText('MIXED-PKG');
      await expect(reviewDrawer).toContainText('manual_required');
      await expect(reviewDrawer).toContainText(/核对BOM点数、工序或封装资料|review/i);
    } finally {
      await cleanupRows(page, created);
    }
  });
});
