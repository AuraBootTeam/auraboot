import * as XLSX from 'xlsx';
import { readFile, writeFile } from 'node:fs/promises';
import { test, expect } from '../../fixtures';
import { ensureSidebarExpanded, uniqueId } from '../helpers';
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
      const generate = page.getByRole('button', { name: '生成正式报价文档', exact: true });
      await expect(generate).toBeVisible({ timeout: 20_000 });
      const response = page.waitForResponse(r => r.url().includes('generate_document') && r.request().method() === 'POST', { timeout: 60_000 });
      const download = page.waitForEvent('download', { timeout: 60_000 });
      await generate.click();
      const generated = await (await response).json();
      expect(String(generated.code)).toBe('0');
      const generatedOutput = generated.data?.data ?? {};
      expect(generatedOutput.definitionCode).toBe('quote.customer.standard');
      expect(generatedOutput.definitionVersion).toBe('1.0.0');
      expect(Number(generatedOutput.documentVersion)).toBe(1);
      expect(generatedOutput.pdfUrl).toBeTruthy();
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

      const pdf = await page.request.get(String(generatedOutput.pdfUrl));
      expect(pdf.ok(), await pdf.text()).toBe(true);
      expect(pdf.headers()['content-type']).toContain('application/pdf');
      expect(pdf.headers()['content-disposition']).toContain('inline');
      const pdfBytes = await pdf.body();
      expect(pdfBytes.subarray(0, 5).toString('ascii')).toBe('%PDF-');
      expect(pdfBytes.subarray(-6).toString('ascii')).toContain('%%EOF');
      // Headless Chromium treats PDF navigation as a download even when the
      // endpoint correctly declares Content-Disposition: inline. Preserve the
      // real bytes as reviewable evidence instead of relying on its PDF viewer.
      const pdfTarget = info.outputPath('quote-document.pdf');
      await writeFile(pdfTarget, pdfBytes);
      await info.attach('quote-document-pdf', { path: pdfTarget, contentType: 'application/pdf' });

      const firstVersionDocuments = await queryDynamicRecords(page, 'qo_quote_document_common', [
        { fieldName: 'qo_qd_quote_id', operator: 'EQ', value: created.quoteId },
      ]);
      expect(firstVersionDocuments).toHaveLength(2);
      expect(new Set(firstVersionDocuments.map(row => row.qo_qd_format))).toEqual(new Set(['xlsx', 'pdf']));
      expect(new Set(firstVersionDocuments.map(row => Number(row.qo_qd_version)))).toEqual(new Set([1]));
      for (const row of firstVersionDocuments) {
        expect(row.qo_qd_definition_code).toBe('quote.customer.standard');
        expect(row.qo_qd_definition_version).toBe('1.0.0');
        expect(String(row.qo_qd_checksum_sha256)).toMatch(/^[a-f0-9]{64}$/);
        created.rows.push({ model: 'qo_quote_document_common', pid: String(row.pid) });
      }

      const artifacts = await queryDynamicRecords(page, 'ab_document_artifact', [
        { fieldName: 'ab_da_source_record_pid', operator: 'EQ', value: created.quoteId },
      ]);
      expect(artifacts).toHaveLength(2);
      expect(new Set(artifacts.map(row => row.ab_da_format))).toEqual(new Set(['xlsx', 'pdf']));
      expect(new Set(artifacts.map(row => row.ab_da_job_key))).toEqual(
        new Set([`${created.quoteId}:1`]),
      );
      for (const row of artifacts) {
        expect(String(row.ab_da_checksum_sha256)).toMatch(/^[a-f0-9]{64}$/);
        created.rows.push({ model: 'ab_document_artifact', pid: String(row.pid) });
      }

      const regenerated = await executeCommand(
        page,
        'qo_quote_common:generate_document',
        {},
        created.quoteId,
        'update',
      );
      expect(Number(regenerated.documentVersion)).toBe(2);
      const documentHistory = await queryDynamicRecords(page, 'qo_quote_document_common', [
        { fieldName: 'qo_qd_quote_id', operator: 'EQ', value: created.quoteId },
      ]);
      expect(documentHistory).toHaveLength(4);
      expect(new Set(documentHistory.map(row => Number(row.qo_qd_version)))).toEqual(new Set([1, 2]));
      for (const row of documentHistory.filter(row => Number(row.qo_qd_version) === 2)) {
        created.rows.push({ model: 'qo_quote_document_common', pid: String(row.pid) });
      }
      const artifactHistory = await queryDynamicRecords(page, 'ab_document_artifact', [
        { fieldName: 'ab_da_source_record_pid', operator: 'EQ', value: created.quoteId },
      ]);
      expect(artifactHistory).toHaveLength(4);
      for (const row of artifactHistory.filter(row => Number(row.ab_da_document_version) === 2)) {
        created.rows.push({ model: 'ab_document_artifact', pid: String(row.pid) });
      }
      await page.reload();
      expect((await readDynamicRecord(page, 'qo_quote_common', created.quoteId)).qo_quote_status).toBe('priced');
      await expect(page.getByRole('button', { name: '生成正式报价文档', exact: true })).toBeVisible({ timeout: 20_000 });
      const quoteOutputTab = page.getByRole('tab', { name: '报价Excel', exact: true });
      await expect(quoteOutputTab).toBeVisible();
      await quoteOutputTab.click();
      const documentHistoryTable = page.getByRole('table').filter({
        has: page.getByRole('columnheader', { name: '格式', exact: true }),
      });
      await expect(documentHistoryTable.getByText('pdf', { exact: true }).first()).toBeVisible();
      await expect(documentHistoryTable.getByText('xlsx', { exact: true }).first()).toBeVisible();
      await expect(documentHistoryTable.getByText(new RegExp(created.quoteCode)).first()).toBeVisible();
      await page.screenshot({ path: info.outputPath('quote-document-history.png'), fullPage: true });
      await ensureSidebarExpanded(page);
      const jobsMenu = page.locator('nav a[href="/p/c/exchange_job_center"], aside a[href="/p/c/exchange_job_center"]').first();
      await expect(jobsMenu).toBeVisible({ timeout: 20_000 });
      await jobsMenu.click();
      await expect(page).toHaveURL(/\/p\/c\/exchange_job_center(?:[?#].*)?$/);
      await expect(page.getByRole('button', { name: '刷新任务', exact: true })).toBeVisible();
      await expect(page.getByText('正式文档 v2，2 个文件', { exact: true })).toBeVisible({ timeout: 20_000 });
      await expect(page.getByText('正式文档', { exact: true }).first()).toBeVisible();
      await expect(page.getByRole('link', { name: '下载', exact: true }).first()).toBeVisible();
      await page.screenshot({ path: info.outputPath('quote-job-center.png'), fullPage: true });
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
