import { test, expect } from '../../fixtures';
import {
  cleanupRows,
  queryDynamicRecords,
  seedQuoteForCorrectedBomUpload,
  type CreatedRows,
} from './quote-e2e-helpers';

/**
 * Q20-01 缺陷探针(暂不入门禁):客户报价视图页对常规报价渲染
 * 「加载失败 Business error」(admin 打开自有报价同样失败),页面组合层缺陷,
 * 修复后转入门禁并补齐 binding。
 */
test.describe('PCBA quote customer view defect probe', () => {
  test.describe.configure({ timeout: 120_000 });

  test('Q20-01 probe: customer view loads for the quote owner', async ({ page }, info) => {
    const created: CreatedRows = { quoteId: '', quoteCode: '', rows: [] };
    const seeded = await seedQuoteForCorrectedBomUpload(page);
    created.rows.push({ model: 'qo_quote_common', pid: seeded.quoteId });
    try {
      await page.goto(`/p/qo_quote_customer_view/view/${seeded.quoteId}`, { waitUntil: 'domcontentloaded' });
      await expect(page.locator('main')).toContainText(seeded.quoteCode, { timeout: 20_000 });
      expect(await page.getByRole('button', { name: /保存|Save/ }).count()).toBe(0);
      const rows = await queryDynamicRecords(page, 'qo_quote_common', [
        { fieldName: 'pid', operator: 'EQ', value: seeded.quoteId },
      ]);
      expect(rows).toHaveLength(1);
      await info.attach('q20-01-probe', {
        body: await page.screenshot({ fullPage: true }),
        contentType: 'image/png',
      });
    } finally {
      await cleanupRows(page, created);
    }
  });
});
