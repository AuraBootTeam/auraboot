import type { Locator } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { ensureSidebarExpanded, findRowInPaginatedList } from '../helpers';
import {
  cleanupRows,
  clickSidebarPage,
  openQuoteDetailFromList,
  queryNamedDataSourceRecords,
  seedBomPriceManualReviewQuote,
  type BomPriceManualReviewSeed,
} from './quote-e2e-helpers';

const PURCHASE_ANALYSIS_RECENT_PRICE_LABEL = '近期价(系数后)';
const YUNHAN_PRICE_LABEL = '云汉(系数后)';
const DEEPSEEK_PRICE_LABEL = 'DeepSeek(系数后)';

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

async function openWorkbench(page: import('@playwright/test').Page, created: BomPriceManualReviewSeed) {
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
  await openQuoteDetailFromList(page, created);
  const bomPriceTab = page.getByRole('tab', { name: /BOM价格计算|BOM Price/i });
  await expect(bomPriceTab).toBeVisible({ timeout: 20_000 });
  await bomPriceTab.click();
  await waterfallLoad;
  const priceRow = page.getByTestId(`table-row-${created.lineId}`);
  await expect(priceRow).toBeVisible({ timeout: 30_000 });
  return priceRow;
}

test.describe('PCBA quote BOM price workbench golden', () => {
  test.describe.configure({ timeout: 120_000 });

  // Q04-01: 报价详情行列表与 BOM 价格工作台的页面合同——列顺序、字典状态标签、
  // 报价单列表创建人显名与时间渲染;截图留档。
  test('Q04-01 workbench page contract: column order, dict status label, creator display name and time render', async ({
    page,
  }, info) => {
    const created = await seedBomPriceManualReviewQuote(page);
    try {
      // 报价单列表:创建人列渲染显名(非原始 uid),创建时间为 datetime 渲染(非 ISO-T 原文)
      const quote = await page.request.get(`/api/dynamic/qo_quote_common/${created.quoteId}`);
      expect(quote.ok()).toBe(true);
      const quoteCode = String(((await quote.json()).data ?? {}).qo_quote_code ?? '');
      expect(quoteCode, 'quote carries a code').toBeTruthy();
      await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
      await ensureSidebarExpanded(page);
      await clickSidebarPage(page, '/p/qo_quote_common', /报价单|Quotes/i);
      const row = await findRowInPaginatedList(page, quoteCode, 20_000);
      const cells = await row.locator('td').allInnerTexts();
      const creator = cells.find((text) => /Admin|管理员|admin/i.test(text));
      expect(creator, `creator display name rendered, got: ${cells.join(' | ')}`).toBeTruthy();
      // 创建人单元格为显名而非裸 uid
      expect(creator?.trim(), 'creator is a display name, not a raw uid').not.toMatch(/^\d{10,}$/);
      for (const text of cells) {
        expect(text, `no ISO-T raw time in list row: ${text}`).not.toMatch(/^\d{4}-\d{2}-\d{2}T/);
      }

      // BOM 价格工作台:列顺序与 SoT 页面合同一致,状态列为字典标签
      const priceRow = await openWorkbench(page, created);
      await expect(priceRow).toContainText(created.mpn);
      const priceTable = priceRow.locator('xpath=ancestor::table[1]');
      const headers = await tableHeaders(priceTable);
      expect(headers).toEqual([
        'Excel行',
        '物料',
        '单套用量',
        PURCHASE_ANALYSIS_RECENT_PRICE_LABEL,
        YUNHAN_PRICE_LABEL,
        DEEPSEEK_PRICE_LABEL,
        '采用价格',
        '采用来源',
        '当前状态',
      ]);
      const cellTextByHeader = new Map(
        (await tableCellTexts(priceRow)).map((text, index) => [headers[index] ?? `column-${index}`, text]),
      );
      expect(cellTextByHeader.get('Excel行')).toBe('2');
      expect(cellTextByHeader.get('单套用量')).toBe('10');
      // 当前状态为字典业务标签,不是 raw 状态值
      expect(cellTextByHeader.get('当前状态')).toContain('AI建议待确认');
      const bodyText = await page.locator('main').innerText();
      expect(bodyText).not.toContain('"current_price_status"');
      expect(bodyText).not.toContain('pending_reason');
      await info.attach('q04-01-workbench-contract', {
        body: await page.screenshot({ fullPage: true }),
        contentType: 'image/png',
      });
    } finally {
      await cleanupRows(page, created);
    }
  });

  // Q04-02: 行级查价状态与候选价、采纳记录一致;pending 原因可反查
  // (api→browser:查价状态经数据源 API 断言,候选与 pending 原因经抽屉 UI 断言)
  test('Q04-02 line price status, candidates and adoption records stay consistent; pending reason is reachable', async ({
    page,
  }) => {
    const created = await seedBomPriceManualReviewQuote(page);
    try {
      const priceRow = await openWorkbench(page, created);

      // 数据源 API:行查价状态、采纳记录与候选价一致(尚未采纳 → 来源为空、建议价在)
      const line = await queryNamedDataSourceRecords(page, 'qo_quote_bom_price_waterfall', {
        quoteId: created.quoteId,
      }).then((rows) =>
        rows.find(
          (row) =>
            String(row.pid ?? '') === created.lineId ||
            String(row.qo_quote_line_id ?? '') === created.lineId,
        ),
      );
      expect(line, `waterfall contains line ${created.lineId}`).toBeTruthy();
      expect(String(line!.current_price_status ?? '')).toBe('AI建议待确认');
      expect(String(line!.adopted_source ?? '')).toBe('');
      expect(String(line!.adopted_source_label ?? '')).toBe('');
      expect(String(line!.deepseek_suggested_price ?? '')).toContain('CNY 1.11');

      // 采纳记录为空(与状态一致)
      const adoptedDecisions = await page.request.get(
        `/api/dynamic/qo_quote_line_price_decision_common/list?pageNum=1&pageSize=20`,
      );
      expect(adoptedDecisions.ok()).toBe(true);
      const decisions = (await adoptedDecisions.json()).data?.records ?? [];
      const mine = (Array.isArray(decisions) ? decisions : []).filter(
        (row: Record<string, unknown>) =>
          String(row.qo_qlpd_quote_line_id ?? '') === created.lineId &&
          String(row.qo_qlpd_status ?? '') === 'accepted',
      );
      expect(mine, 'no accepted adoption while status is AI建议待确认').toHaveLength(0);

      // 抽屉反查 pending 原因:DeepSeek 候选在(建议价),金蝶候选未命中原因可读
      await priceRow.click();
      const reviewDrawer = page.getByTestId('review-drawer');
      await expect(reviewDrawer).toBeVisible({ timeout: 10_000 });
      await expect(
        page.getByTestId(`review-drawer-candidate-${created.suggestedEvidenceId}`),
      ).toContainText(/DeepSeek建议|DeepSeek/);
      const failedCandidate = page.getByTestId(`review-drawer-candidate-${created.failedEvidenceId}`);
      await expect(failedCandidate).toContainText(/金蝶历史采购|Kingdee/);
      await failedCandidate.click();
      await expect(reviewDrawer).toContainText(/未命中可用价格|未命中/);
    } finally {
      await cleanupRows(page, created);
    }
  });
});
