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
