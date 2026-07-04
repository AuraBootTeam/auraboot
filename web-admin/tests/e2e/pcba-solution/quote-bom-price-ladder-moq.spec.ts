import { test, expect } from '../../fixtures';
import {
  cleanupRows,
  openQuoteDetailFromList,
  seedBomPriceLadderMoqQuote,
  type BomPriceLadderMoqSeed,
} from './quote-e2e-helpers';

/**
 * Yunhan (云汉芯城 / ickey.cn) volume pricing — the review drawer's 阶梯价 (price ladder) and
 * 起订提醒 (MOQ over-buy warning) columns.
 *
 * Both are computed columns on the `qo_quote_line_price_evidence_details` named query (surfaced
 * as drawer detailFields), so a static config/audit gate cannot prove they render — this golden
 * seeds captured yunhan evidence deterministically and reads the real drawer:
 * - Ladder line: 5-tier ladder over demand qty 1000 -> the full tier table shows, no MOQ warning.
 * - MOQ line: demand qty 2 below MOQ 5000 -> the over-buy warning shows, with the effective spend
 *   computed from the MOQ-tier ladder price (5000 x 0.0039 = 19.50), not the low-qty unit price.
 */
test.describe('PCBA quote BOM price ladder + MOQ warning', () => {
  test.describe.configure({ timeout: 120_000 });

  test('shows the yunhan price ladder and MOQ over-buy warning in the review drawer', async ({
    page,
  }) => {
    const created: BomPriceLadderMoqSeed = await seedBomPriceLadderMoqQuote(page);

    try {
      await openQuoteDetailFromList(page, created);
      const bomPriceTab = page.getByRole('tab', { name: /BOM价格计算|BOM Price/i });
      await expect(bomPriceTab).toBeVisible({ timeout: 20_000 });
      await bomPriceTab.click();

      // --- Ladder line: multi-tier ladder shows, no MOQ warning (qty 1000 >= MOQ 100) ---
      const ladderRow = page.getByTestId(`table-row-${created.ladderLineId}`);
      await expect(ladderRow).toBeVisible({ timeout: 30_000 });
      await expect(ladderRow).toContainText(created.ladderMpn);
      await ladderRow.click();

      const drawer = page.getByTestId('review-drawer');
      await expect(drawer).toBeVisible({ timeout: 10_000 });
      const ladderCandidate = page.getByTestId(
        `review-drawer-candidate-${created.ladderEvidenceId}`,
      );
      await expect(ladderCandidate).toBeVisible({ timeout: 20_000 });
      // 阶梯价: full tier table, 4dp, only shown because there is more than one tier.
      await expect(ladderCandidate).toContainText('阶梯价');
      await expect(ladderCandidate).toContainText('100+: 0.0092');
      await expect(ladderCandidate).toContainText('1000+: 0.0090');
      await expect(ladderCandidate).toContainText('50000+: 0.0082');
      // 起订提醒: none — demand qty (1000) is at/above MOQ (100).
      await expect(ladderCandidate).not.toContainText('需按 MOQ');

      // Close the drawer before switching rows (it overlays the table).
      await page.getByRole('button', { name: /关闭复核浮层|Close review drawer/ }).click();
      await expect(drawer).toHaveCount(0, { timeout: 10_000 });

      // --- MOQ line: over-buy warning shows with the MOQ-tier effective spend ---
      const moqRow = page.getByTestId(`table-row-${created.moqLineId}`);
      await expect(moqRow).toBeVisible({ timeout: 20_000 });
      await expect(moqRow).toContainText(created.moqMpn);
      await moqRow.click();

      await expect(drawer).toBeVisible({ timeout: 10_000 });
      const moqCandidate = page.getByTestId(`review-drawer-candidate-${created.moqEvidenceId}`);
      await expect(moqCandidate).toBeVisible({ timeout: 20_000 });
      // 起订提醒: must order MOQ 5000 for a demand of 2; effective spend uses the 5000-tier price
      // (0.0039), i.e. 5000 x 0.0039 = 19.50 — NOT the low-qty unit price (0.0051).
      await expect(moqCandidate).toContainText('起订提醒');
      await expect(moqCandidate).toContainText('需按 MOQ 5000 起订');
      await expect(moqCandidate).toContainText('需求 2');
      await expect(moqCandidate).toContainText('19.50');
      // and its own ladder still renders (3 tiers).
      await expect(moqCandidate).toContainText('阶梯价');
      await expect(moqCandidate).toContainText('5000+: 0.0039');
    } finally {
      await cleanupRows(page, created);
    }
  });
});
