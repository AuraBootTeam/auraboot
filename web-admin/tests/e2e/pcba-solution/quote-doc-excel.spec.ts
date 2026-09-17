import * as XLSX from 'xlsx';
import { readFile } from 'node:fs/promises';
import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers';
import { makeQuoteRoleUser, ensureQuoteRoleUser, openQuoteRolePage,
  createCorrectedBomWorkbook, createQuoteFromReviewedBom, openQuoteDetailFromList,
  executeCommand, readDynamicRecord, queryDynamicRecords, pollAsyncTaskResult, cleanupRows, type QuoteRoleUser, type CreatedRows,
} from './quote-e2e-helpers';
import { saveWorkbookDownload } from './workbook-download-evidence';

const uid = uniqueId('qod');
let sales: QuoteRoleUser;

test.describe('Quote pricing + document excel (QO-04 / QO-07 / XLS-Q) @smoke', () => {
  test.describe.configure({ timeout: 180_000 });
  test.beforeAll(async ({ browser }) => {
    const context = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    try {
      sales = makeQuoteRoleUser('qo_sales', uid, ['qo_sales']);
      await ensureQuoteRoleUser(await context.newPage(), sales);
    } finally { await context.close(); }
  });

  test('QO-04/07/XLS-Q costs and prices a reviewed BOM then downloads the actual quote document', async ({ browser, page: admin }, info) => {
    const { context, page } = await openQuoteRolePage(browser, sales);
    const created: CreatedRows = { quoteId: '', quoteCode: '', rows: [] };
    try {
      const account = await executeCommand(page, 'crm:create_account', { crm_acc_name: `Document ${uid}` }, undefined, 'create');
      const accountId = String(account.recordId ?? account.recordPid ?? '');
      expect(accountId).toBeTruthy();
      created.rows.push({ model: 'crm_account_common', pid: accountId });
      const project = await executeCommand(page, 'bom:create_project', {
        bom_project_name: `Document ${uid}`, bom_project_customer_id: accountId, bom_pcba_code: `DOC-${uid}`,
      }, undefined, 'create');
      const projectId = String(project.recordId ?? project.recordPid ?? '');
      expect(projectId).toBeTruthy();
      created.rows.push({ model: 'req_requirement_set_pcba_bom', pid: projectId });
      // A deterministic standard BOM exercises the public upload/review journey.
      // Real-customer file compatibility remains a separate mandatory corpus gate.
      const source = createCorrectedBomWorkbook(info.outputPath('pricing-source.xlsx'));
      const { body: creation } = await createQuoteFromReviewedBom(page, created, source);
      const createResult = creation.data.data;
      const tasks = [...createResult.sourceUploadResults, createResult.correctedBomImport];
      expect(tasks).toHaveLength(4);
      for (const task of tasks) {
        expect(task.async).toBe(true);
        expect(task.taskCode).toBeTruthy();
        await pollAsyncTaskResult(page, String(task.taskCode));
      }
      await expect.poll(async () => (await queryDynamicRecords(page, 'qo_quote_line_common', [
        { fieldName: 'qo_ql_quote_id', operator: 'EQ', value: created.quoteId },
      ])).length, { timeout: 30_000 }).toBe(2);
      const before = await readDynamicRecord(page, 'qo_quote_common', created.quoteId);
      expect(before.qo_quote_status).toBe('draft');
      // Pricing is a costed -> priced state transition, not a draft reachability probe.
      await executeCommand(page, 'qo_quote_common:cost', {}, created.quoteId, 'update');
      expect((await readDynamicRecord(page, 'qo_quote_common', created.quoteId)).qo_quote_status).toBe('costed');
      await executeCommand(page, 'qo_quote_common:price', {}, created.quoteId, 'update');
      const priced = await readDynamicRecord(page, 'qo_quote_common', created.quoteId);
      expect(priced.qo_quote_status).toBe('priced');
      await openQuoteDetailFromList(page, created);
      await expect(page).toHaveURL(new RegExp(`/p/qo_quote_common/view/${created.quoteId}(?:[?#].*)?$`));
      const generate = page.getByRole('button', { name: '生成报价Excel', exact: true });
      await expect(generate).toBeVisible({ timeout: 20_000 });
      const response = page.waitForResponse(r => r.url().includes('generate_document') && r.request().method() === 'POST', { timeout: 60_000 });
      const download = page.waitForEvent('download', { timeout: 60_000 });
      await generate.click();
      const generated = await (await response).json();
      expect(String(generated.code)).toBe('0');
      const file = await download;
      expect(file.suggestedFilename()).toContain(created.quoteCode);
      const target = info.outputPath('priced-quote.xlsx');
      await saveWorkbookDownload(file, target, info, 'quote-priced-document');
      const wb = XLSX.read(await readFile(target), { type: 'buffer' });
      expect(wb.SheetNames).toHaveLength(3);
      const text = wb.SheetNames.map(name => XLSX.utils.sheet_to_csv(wb.Sheets[name])).join('\n');
      expect(text).toContain(created.quoteCode);
      expect(text).toContain('RC0603FR-0710KL');
      expect(text).toContain('STM32F103C8T6');
      expect(text).not.toMatch(/#REF!|#DIV\/0!|#VALUE!|#NAME\?|\bqo_quote_[a-z_]{3,}\b/);
      await page.reload();
      expect((await readDynamicRecord(page, 'qo_quote_common', created.quoteId)).qo_quote_status).toBe('priced');
      await expect(page.getByRole('button', { name: '生成报价Excel', exact: true })).toBeVisible({ timeout: 20_000 });
      await expect(page.getByText(created.quoteCode, { exact: true }).first()).toBeVisible();
      await info.attach('priced-document-browser', { body: await page.screenshot(), contentType: 'image/png' });
      await info.attach('priced-document-persistence', { body: JSON.stringify({ before, priced, generated, sheets: wb.SheetNames }), contentType: 'application/json' });
    } catch (error) {
      await info.attach('priced-document-failure-browser', { body: await page.screenshot(), contentType: 'image/png' });
      await info.attach('priced-document-failure-page', { body: JSON.stringify({ url: page.url(), text: await page.locator('body').innerText() }), contentType: 'application/json' });
      throw error;
    } finally {
      await cleanupRows(admin, created);
      await context.close();
    }
  });
});
