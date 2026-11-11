import type { APIResponse, Page } from '@playwright/test';
import * as XLSX from 'xlsx';
import { readFile, writeFile } from 'node:fs/promises';
import { test, expect } from '../../fixtures';
import { ensureSidebarExpanded, uniqueId } from '../helpers';
import { makeQuoteRoleUser, ensureQuoteRoleUser, openQuoteRolePage,
  createCorrectedBomWorkbook, createQuoteFromReviewedBom, openQuoteDetailFromList,
  executeCommand, readDynamicRecord, queryDynamicRecords, pollAsyncTaskResult, cleanupRows, cleanupImmutableQuoteArtifacts, type QuoteRoleUser, type CreatedRows,
} from './quote-e2e-helpers';
import { saveWorkbookDownload } from './workbook-download-evidence';

const uid = uniqueId('qod');
let sales: QuoteRoleUser;
let filePolicyRole: { pid: string; code: string };

async function unwrap(response: APIResponse) {
  const body = await response.json();
  expect(response.status(), JSON.stringify(body)).toBe(200);
  expect(String(body.code), JSON.stringify(body)).toBe('0');
  return body.data;
}

async function setDocumentFieldVisibility(admin: Page, access: 'hidden' | 'inherit', fields = [{ modelCode: 'qo_quote_common', fieldCode: 'qo_quote_code' }]) {
  const draftUrl = `/api/permission/roles/${filePolicyRole.pid}/draft`;
  const snapshot = await unwrap(await admin.request.get(draftUrl));
  const selected = snapshot.groups.flatMap((group: any) => group.capabilities)
    .filter((capability: any) => capability.granted && !capability.conventionDerived).map((capability: any) => capability.code);
  await unwrap(await admin.request.put(draftUrl, { data: {
    expectedRevision: snapshot.revision,
    selectedCapabilityCodes: selected,
    revokedPartialCapabilityCodes: [],
    defaultScopeChanged: false,
    applyDefaultToCurrentGrants: false,
    scopeChanges: [],
    fieldChanges: fields.map(field => ({ ...field, access })),
  } }));
}


test.describe('Quote pricing + document excel (QO-04 / QO-07 / XLS-Q) @smoke', () => {
  test.describe.configure({ timeout: 180_000 });
  test.beforeAll(async ({ browser }) => {
    const context = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    try {
      const admin = await context.newPage();
      const roles = await unwrap(await admin.request.get('/api/roles/all'));
      const source = roles.find((role: any) => role.code === 'qo_sales');
      expect(source, 'Existing sales source role is required').toBeTruthy();
      filePolicyRole = await unwrap(await admin.request.post('/api/roles', { data: {
        code: `e2e_doc_fields_${uid}`, name: `Document fields ${uid}`, type: 'custom',
        description: 'Owned document field-policy fixture; original sales role is unchanged',
      } }));
      await unwrap(await admin.request.post(`/api/roles/${source.pid}/copy-permissions`, {
        data: { targetRolePid: filePolicyRole.pid },
      }));
      // Role creation starts with an explicit menu selection. Copy navigation
      // separately; copying business grants must never grant menus implicitly.
      const sourceDraft = await unwrap(await admin.request.get(`/api/permission/roles/${source.pid}/draft`));
      const targetDraftUrl = `/api/permission/roles/${filePolicyRole.pid}/draft`;
      const targetDraft = await unwrap(await admin.request.get(targetDraftUrl));
      await unwrap(await admin.request.put(targetDraftUrl, { data: {
        expectedRevision: targetDraft.revision,
        selectedCapabilityCodes: targetDraft.groups.flatMap((group: any) => group.capabilities)
          .filter((capability: any) => capability.granted && !capability.conventionDerived).map((capability: any) => capability.code),
        revokedPartialCapabilityCodes: [], defaultScopeChanged: true,
        defaultScopeType: sourceDraft.defaultScopeType,
        applyDefaultToCurrentGrants: false, scopeChanges: [],
        menuPids: sourceDraft.navigation.selectedMenuPids,
      } }));
      const copied = await unwrap(await admin.request.get(targetDraftUrl));
      const scopes = (rows: any[]) => rows.map(row => JSON.stringify(row)).sort();
      expect(copied.defaultScopeType).toBe(sourceDraft.defaultScopeType);
      expect(scopes(copied.scopes), 'Copy must preserve every original scope rather than apply a new default').toEqual(scopes(sourceDraft.scopes));
      sales = makeQuoteRoleUser('qo_sales', uid, [filePolicyRole.code]);
      await ensureQuoteRoleUser(admin, sales);
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
      // Nonzero prices are a fixture precondition, not a provider or manual-price UI claim.
      const pricedInputs = await queryDynamicRecords(page, 'qo_quote_line_common', [
        { fieldName: 'qo_ql_quote_id', operator: 'EQ', value: created.quoteId },
      ]);
      const manualInputs = [];
      for (const [index, line] of pricedInputs.entries()) {
        const manual = await executeCommand(page, 'qo_quote_line_common:record_manual_price', {
          unitPrice: index + 1.25, currency: 'CNY', reason: 'Owned quote-document field-policy fixture',
        }, String(line.pid), 'update');
        expect(manual.confirmed).toBe(true);
        expect(Number(manual.unitCost)).toBeGreaterThan(0);
        manualInputs.push({ linePid: line.pid, evidenceId: manual.evidenceId, decisionId: manual.decisionId });
      }
      await info.attach('document-nonzero-price-fixture', { body: JSON.stringify(manualInputs), contentType: 'application/json' });
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
      // Technical artifacts have no public mutation authority, including for
      // an administrator. Rejection must leave the business-owned history intact.
      const rejectedDelete = await admin.request.delete(`/api/dynamic/ab_document_artifact/${artifactHistory[0].pid}`);
      expect(rejectedDelete.status()).toBe(403);
      expect(String((await rejectedDelete.json()).context)).toContain('model.ab_document_artifact.delete');
      expect(await queryDynamicRecords(page, 'ab_document_artifact', [
        { fieldName: 'ab_da_source_record_pid', operator: 'EQ', value: created.quoteId },
      ])).toHaveLength(4);
      await page.reload();
      expect((await readDynamicRecord(page, 'qo_quote_common', created.quoteId)).qo_quote_status).toBe('priced');
      await expect(page.getByRole('button', { name: '生成正式报价文档', exact: true })).toBeVisible({ timeout: 20_000 });
      // Domain write includes reading this quote's own output history. Check
      // both the creating employee and the administrator through the actual tab.
      for (const viewer of [page, admin]) {
        if (viewer === admin) await admin.goto(`/p/qo_quote_common/view/${created.quoteId}`);
        const quoteOutputTab = viewer.getByRole('tab', { name: '报价Excel', exact: true });
        await expect(quoteOutputTab).toBeVisible();
        await quoteOutputTab.click();
        const documentHistoryTable = viewer.getByRole('table').filter({
          has: viewer.getByRole('columnheader', { name: '格式', exact: true }),
        });
        await expect(documentHistoryTable.getByText('pdf', { exact: true }).first()).toBeVisible();
        await expect(documentHistoryTable.getByText('xlsx', { exact: true }).first()).toBeVisible();
        await expect(documentHistoryTable.getByText(new RegExp(created.quoteCode)).first()).toBeVisible();
        await viewer.screenshot({
          path: info.outputPath(viewer === admin ? 'quote-document-history.png' : 'sales-quote-document-history.png'),
          fullPage: true,
        });
      }
      // Job center is owner-scoped. Both document generations were performed
      // by the sales user, while the administrator only verifies output access.
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
      // Immutable files may retain bytes, but current source-field visibility must
      // still govern the same known URL after generation. Restrict only our copied role.
      let restricted = false;
      try {
        await setDocumentFieldVisibility(admin, 'hidden');
        restricted = true;
        const visible = await readDynamicRecord(page, 'qo_quote_common', created.quoteId);
        expect(visible.qo_quote_code == null, 'The copied role must really lose the source field').toBe(true);
        const oldXlsx = await page.request.get(String(generatedOutput.url));
        const oldPdf = await page.request.get(String(generatedOutput.pdfUrl));
        let xlsxStillContainsHiddenCode = false;
        if (oldXlsx.status() === 200) {
          const oldWorkbook = XLSX.read(await oldXlsx.body(), { type: 'buffer' });
          xlsxStillContainsHiddenCode = oldWorkbook.SheetNames.some(name =>
            XLSX.utils.sheet_to_csv(oldWorkbook.Sheets[name]).includes(created.quoteCode));
        }
        await info.attach('existing-document-current-field-policy', {
          body: JSON.stringify({ rolePid: filePolicyRole.pid, quoteId: created.quoteId,
            sourceFieldHidden: visible.qo_quote_code == null,
            xlsxStatus: oldXlsx.status(), pdfStatus: oldPdf.status(), xlsxStillContainsHiddenCode }),
          contentType: 'application/json',
        });
        expect(oldXlsx.status(), 'Old XLSX must not bypass the current hidden source field').toBe(403);
        expect(oldPdf.status(), 'Old PDF must not bypass the current hidden source field').toBe(403);
      } finally {
        if (restricted) await setDocumentFieldVisibility(admin, 'inherit');
      }
      const restoredXlsx = await page.request.get(String(generatedOutput.url));
      const restoredPdf = await page.request.get(String(generatedOutput.pdfUrl));
      expect(restoredXlsx.status()).toBe(200);
      expect(restoredPdf.status()).toBe(200);
      expect(await restoredXlsx.body()).toEqual(await readFile(target));
      expect(await restoredPdf.body()).toEqual(pdfBytes);
      // Exercise the real command/DAL boundary, not only a projector with raw unit-test inputs.
      const financialBefore = await readDynamicRecord(admin, 'qo_quote_common', created.quoteId);
      expect(Number(financialBefore.qo_quote_total_amount), 'The financial field test must have a real positive baseline').toBeGreaterThan(0);
      const priceFields = [
        { modelCode: 'qo_quote_line_common', fieldCode: 'qo_ql_unit_cost' },
        { modelCode: 'qo_quote_line_common', fieldCode: 'qo_ql_line_cost' },
        { modelCode: 'qo_quote_line_common', fieldCode: 'qo_ql_price_snapshot' },
        { modelCode: 'qo_price_evidence_common', fieldCode: 'qo_pe_unit_price' },
        { modelCode: 'qo_price_evidence_common', fieldCode: 'qo_pe_snapshot' },
        { modelCode: 'qo_quote_line_price_decision_common', fieldCode: 'qo_qlpd_unit_price' },
        { modelCode: 'qo_quote_line_price_decision_common', fieldCode: 'qo_qlpd_line_amount' },
        { modelCode: 'qo_quote_line_price_decision_common', fieldCode: 'qo_qlpd_source_snapshot' },
        { modelCode: 'qo_cost_item_common', fieldCode: 'qo_ci_amount' },
      ];
      let pricesRestricted = false;
      try {
        await setDocumentFieldVisibility(admin, 'hidden', priceFields);
        pricesRestricted = true;
        const publicLines = await queryDynamicRecords(page, 'qo_quote_line_common', [
          { fieldName: 'qo_ql_quote_id', operator: 'EQ', value: created.quoteId },
        ]);
        expect(publicLines).toHaveLength(2);
        for (const line of publicLines) {
          expect(line.qo_ql_unit_cost == null).toBe(true);
          expect(line.qo_ql_line_cost == null).toBe(true);
        }
        await openQuoteDetailFromList(page, created);
        const generatedWithHiddenPrices = page.waitForResponse(r => r.url().includes('generate_document') && r.request().method() === 'POST');
        const redactedDownload = page.waitForEvent('download', { timeout: 60_000 }).then(
          download => ({ download, error: null }), error => ({ download: null, error }));
        await page.getByRole('button', { name: '生成正式报价文档', exact: true }).click();
        const redactedResponse = await generatedWithHiddenPrices;
        const redactedBody = await redactedResponse.json();
        await info.attach('document-hidden-price-generation-response', { body: JSON.stringify(redactedBody), contentType: 'application/json' });
        expect(String(redactedBody.code)).toBe('0');
        const redactedTarget = info.outputPath('quote-document-hidden-prices.xlsx');
        const completedDownload = await redactedDownload;
        expect(completedDownload.error).toBeNull();
        expect(completedDownload.download).toBeTruthy();
        await completedDownload.download!.saveAs(redactedTarget);
        await info.attach('document-hidden-price-workbook', { path: redactedTarget, contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' });
        for (const row of await queryDynamicRecords(admin, 'qo_quote_document_common', [
          { fieldName: 'qo_qd_quote_id', operator: 'EQ', value: created.quoteId },
        ])) if (!created.rows.some(owned => owned.pid === String(row.pid))) created.rows.push({ model: 'qo_quote_document_common', pid: String(row.pid) });
        for (const row of await queryDynamicRecords(admin, 'ab_document_artifact', [
          { fieldName: 'ab_da_source_record_pid', operator: 'EQ', value: created.quoteId },
        ])) if (!created.rows.some(owned => owned.pid === String(row.pid))) created.rows.push({ model: 'ab_document_artifact', pid: String(row.pid) });
        const financialAfter = await readDynamicRecord(admin, 'qo_quote_common', created.quoteId);
        await info.attach('document-hidden-price-business-amount', { body: JSON.stringify({
          before: financialBefore.qo_quote_total_amount, after: financialAfter.qo_quote_total_amount,
        }), contentType: 'application/json' });
        expect(Number(financialAfter.qo_quote_total_amount), 'Internal calculation must retain the real amount when public price fields are hidden')
          .toBe(Number(financialBefore.qo_quote_total_amount));
        const redactedWorkbook = XLSX.read(await readFile(redactedTarget), { type: 'buffer' });
        expect(redactedWorkbook.Sheets['BOM明细']['L2']?.v == null || redactedWorkbook.Sheets['BOM明细']['L2']?.v === '').toBe(true);
      } finally {
        if (pricesRestricted) await setDocumentFieldVisibility(admin, 'inherit', priceFields);
      }
      await info.attach('priced-document-persistence', { body: JSON.stringify({ before, priced, generated, sheets: wb.SheetNames }), contentType: 'application/json' });
    } catch (error) {
      await info.attach('priced-document-failure-browser', { body: await page.screenshot(), contentType: 'image/png' });
      await info.attach('priced-document-failure-page', { body: JSON.stringify({ url: page.url(), text: await page.locator('body').innerText() }), contentType: 'application/json' });
      throw error;
    } finally {
      try {
        const cleanup = await cleanupImmutableQuoteArtifacts(created);
        await info.attach('immutable-artifact-fixture-cleanup', { body: JSON.stringify(cleanup), contentType: 'application/json' });
        // The exact owned artifacts were already deleted and verified by the
        // guarded fixture purge; their public read endpoint is not an admin API.
        const purged = new Set(cleanup.deletedIds);
        await cleanupRows(admin, {
          ...created,
          rows: created.rows.filter(row => row.model !== 'ab_document_artifact' || !purged.has(row.pid)),
        });
      } finally { await context.close(); }
    }
  });
});
