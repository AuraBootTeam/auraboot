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
    users['viewer'] = makeQuoteRoleUser('qo_view', `${uid}v`, []);
    await ensureQuoteRoleUser(page, users['viewer']);
    await ctx.close();
  });

  // Q20-01: 客户报价视图对常规报价可加载(曾因 <key>_detail 页未注册 404 加载失败,
  // 已补注册别名页修复);视图只读——无保存入口,直写被拒绝。
  test('Q20-01 customer view loads for a regular quote and stays read-only', async ({ page }, info) => {
    const created: CreatedRows = { quoteId: '', quoteCode: '', rows: [] };
    const seeded = await seedQuoteForCorrectedBomUpload(page);
    created.rows.push({ model: 'qo_quote_common', pid: seeded.quoteId });
    try {
      await page.goto(`/p/qo_quote_customer_view/view/${seeded.quoteId}`, { waitUntil: 'domcontentloaded' });
      await expect(page.locator('main')).toContainText(seeded.quoteCode, { timeout: 20_000 });
      await expect(page.locator('main')).not.toContainText(/加载失败|Business error/);
      expect(await page.getByRole('button', { name: /保存|Save/ }).count()).toBe(0);

      // 只读面:只读角色(viewer)对报价直写被拒绝;admin 特权写是设计内行为,不作断言
      // viewer 会话在测试内自建(空角色用户 + UI 登录):不依赖 checkout 本地的
      // tests/storage/viewer.json 残留文件,fresh checkout 也能跑。
      const { context: viewerContext, page: viewerPage } = await openQuoteRolePage(
        await page.context().browser()!,
        users['viewer'],
      );
      const writeAttempt = await viewerPage.request.put(`/api/dynamic/qo_quote_common/${seeded.quoteId}`, {
        data: { qo_quote_notes: 'Q20-01 read-only bypass attempt' },
      });
      const writeBody = await writeAttempt.json().catch(() => ({}));
      expect(
        writeAttempt.ok() && String((writeBody as { code?: unknown }).code ?? '0') === '0',
        `read-only persona write attempt must be rejected: ${JSON.stringify(writeBody).slice(0, 240)}`,
      ).toBe(false);
      await viewerContext.close();

      const rows = await queryDynamicRecords(page, 'qo_quote_common', [
        { fieldName: 'pid', operator: 'EQ', value: seeded.quoteId },
      ]);
      expect(rows).toHaveLength(1);
      await info.attach('q20-01-customer-view', {
        body: await page.screenshot({ fullPage: true }),
        contentType: 'image/png',
      });
    } finally {
      await cleanupRows(page, created);
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
      // 自播种报价:不依赖库中已有数据(fresh 库为空也可跑)。
      const seeded = await seedQuoteForCorrectedBomUpload(page);
      created.quoteId = seeded.quoteId;
      created.rows.push({ model: 'qo_quote_common', pid: seeded.quoteId });
      const quotePid = seeded.quoteId;

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
