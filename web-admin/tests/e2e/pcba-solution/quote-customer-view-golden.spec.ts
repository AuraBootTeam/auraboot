import { test, expect } from '../../fixtures';
import {
  cleanupRows,
  dynamicCreate,
  ensureQuoteRoleUser,
  makeQuoteRoleUser,
  openQuoteRolePage,
  queryDynamicRecords,
  seedQuoteForCorrectedBomUpload,
  type CreatedRows,
} from './quote-e2e-helpers';

test.describe('PCBA quote customer view golden', () => {
  test.describe.configure({ timeout: 120_000 });

  const users: Record<string, import('./quote-e2e-helpers').QuoteRoleUser> = {};
  const uid = `${Date.now()}`;

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    users['sales'] = makeQuoteRoleUser('qo_sales', uid, ['qo_sales']);
    await ensureQuoteRoleUser(page, users['sales']);
    await ctx.close();
  });

  // Q20-01: 客户视角访问报价视图——只读呈现且数据范围受限;写接口拒绝。
  test('Q20-01 customer view renders read-only quote surface; unauthorized mutation is rejected', async ({
    browser,
  }) => {
    const marker = `CUSTVIEW-${Date.now()}`;
    const created: CreatedRows = { quoteId: '', quoteCode: '', rows: [] };
    const adminCtx = await browser.newContext();
    const adminPage = await adminCtx.newPage();
    try {
      // 夹具:自有种子报价(避免历史脏数据),客户视图按 pid 只读呈现
      const seeded = await seedQuoteForCorrectedBomUpload(adminPage);
      const quotePid = seeded.quoteId;
      const quoteCode = seeded.quoteCode;
      created.rows.push({ model: 'qo_quote_common', pid: quotePid });

      // 只读呈现:页面渲染报价编号且无写入口
      await adminPage.goto(`/p/qo_quote_customer_view/view/${quotePid}`, { waitUntil: 'domcontentloaded' });
      await expect(adminPage.locator('main')).toContainText(quoteCode, { timeout: 20_000 });
      expect(await adminPage.getByRole('button', { name: /保存|Save/ }).count()).toBe(0);

      // 写接口拒绝:非 owner 角色直接改报价被拒,记录保持不变
      const { context, page } = await openQuoteRolePage(browser, users['sales']);
      try {
        // 数据范围受限:非 owner 打开同一客户视图,加载被拒(不泄露报价内容)
        await page.goto(`/p/qo_quote_customer_view/view/${quotePid}`, { waitUntil: 'domcontentloaded' });
        await expect(page.locator('main')).toContainText(/加载失败|无法访问|Business error/, {
          timeout: 20_000,
        });
        const denied = await page.request.put(`/api/dynamic/qo_quote_common/${quotePid}`, {
          data: { qo_quote_notes: 'hijacked from customer view' },
        });
        expect([403, 400], `mutation from restricted view must be rejected, got ${denied.status()}`).toContain(
          denied.status(),
        );
        const after = await queryDynamicRecords(adminPage, 'qo_quote_common', [
          { fieldName: 'pid', operator: 'EQ', value: quotePid },
        ]);
        expect(String(after[0].qo_quote_notes ?? '')).not.toContain('hijacked from customer view');
      } finally {
        await context.close();
      }
    } finally {
      await cleanupRows(adminPage, created);
      await adminCtx.close();
    }
  });

  // Q20-02: 下载报价附件——文件名为原文件名而非内部 ID;内容与记录一致。
  test('Q20-02 customer attachment download keeps the original filename and content', async ({
    page,
  }) => {
    const marker = `CUSTATT-${Date.now()}`;
    const originalName = `customer-official-quote-${marker}.pdf`;
    const content = '%PDF-1.4 E2E customer attachment payload\n';
    const created: CreatedRows = { quoteId: '', quoteCode: '', rows: [] };
    try {
      const quote = (
        await queryDynamicRecords(page, 'qo_quote_common', [], { pageSize: 1 })
      )[0];
      const quotePid = String(quote.pid);

      const upload = await page.request.post('/api/file/upload', { multipart: {
        file: { name: originalName, mimeType: 'application/pdf', buffer: Buffer.from(content) },
      }});
      expect(upload.ok(), await upload.text()).toBe(true);
      const fileId = String((await upload.json()).data.fileId);

      await dynamicCreate(page, 'qo_quote_customer_attachment_common', {
        qo_qca_quote_id: quotePid,
        qo_qca_filename: originalName,
        qo_qca_file_id: fileId,
        qo_qca_type: 'official_quote',
        qo_qca_received_at: new Date().toISOString(),
        qo_qca_status: 'sent',
        qo_qca_received_from: 'E2E customer',
      }, created.rows);

      // 下载:文件名保持原文件名(而非内部 ID),内容与记录一致
      const download = await page.request.get(`/api/file/download/${fileId}`);
      expect(download.status()).toBe(200);
      const disposition = download.headers()['content-disposition'] ?? '';
      expect(disposition, `content-disposition: ${disposition}`).toContain(originalName);
      expect(await download.text()).toBe(content);
      const records = await queryDynamicRecords(page, 'qo_quote_customer_attachment_common', [
        { fieldName: 'qo_qca_filename', operator: 'EQ', value: originalName },
      ]);
      expect(records, 'attachment record keeps the original filename').toHaveLength(1);
      expect(String(records[0].qo_qca_file_id)).toBe(fileId);
    } finally {
      await cleanupRows(page, created);
    }
  });
});
