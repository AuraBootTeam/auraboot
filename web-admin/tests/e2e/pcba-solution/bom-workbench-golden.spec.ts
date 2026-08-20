import { test, expect } from '../../fixtures';
import fs from 'node:fs';
import path from 'node:path';
import * as XLSX from 'xlsx';
import {
  clickRowActionByLocator,
  ensureSidebarExpanded,
  findRowInPaginatedList,
  waitForDynamicPageLoad,
} from '../helpers';
import {
  cleanupRows,
  executeCommand,
  isTransientViteDynamicImportIssue,
  queryDynamicRecords,
  readDynamicRecord,
  seedBomWorkbench,
  type BomWorkbenchSeed,
} from './quote-e2e-helpers';

function sheetRows(workbook: XLSX.WorkBook, sheetName: string): unknown[][] {
  const sheet = workbook.Sheets[sheetName];
  expect(sheet, `Workbook should contain sheet ${sheetName}`).toBeTruthy();
  return XLSX.utils.sheet_to_json(sheet, { header: 1, raw: true, defval: '' }) as unknown[][];
}

function cell(row: unknown[] | undefined, index: number): string {
  return String(row?.[index] ?? '');
}

async function tableHeaders(page: import('@playwright/test').Page): Promise<string[]> {
  const headers = page.locator('thead th, [role="columnheader"]');
  await expect(headers.first()).toBeVisible({ timeout: 15_000 });
  return headers.evaluateAll((nodes) =>
    nodes.map((node) => (node.textContent || '').replace(/\s+/g, ' ').trim()).filter(Boolean),
  );
}

function assertNoExcelErrors(workbook: XLSX.WorkBook): void {
  const excelError = /#(?:NULL!|DIV\/0!|VALUE!|REF!|NAME\?|NUM!|N\/A|GETTING_DATA)/i;
  for (const sheetName of workbook.SheetNames) {
    const sheet = workbook.Sheets[sheetName];
    for (const [address, value] of Object.entries(sheet)) {
      if (address.startsWith('!') || !value || typeof value !== 'object') continue;
      const excelCell = value as XLSX.CellObject;
      expect(excelCell.t, `${sheetName}!${address} must not be an Excel error cell`).not.toBe('e');
      expect(`${sheetName}!${address} formula ${String(excelCell.f ?? '')}`).not.toMatch(
        excelError,
      );
      expect(`${sheetName}!${address} value ${String(excelCell.v ?? '')}`).not.toMatch(excelError);
    }
  }
}

function validateStandardBomWorkbook(
  filePath: string,
  created: BomWorkbenchSeed,
  expected: 'unconfirmed' | 'confirmed',
  expectedRow: Record<string, unknown>,
): void {
  expect(fs.statSync(filePath).size, 'downloaded workbook must not be empty').toBeGreaterThan(0);
  const workbook = XLSX.read(fs.readFileSync(filePath), {
    type: 'buffer',
    cellText: false,
    sheetStubs: true,
  });
  expect(workbook.SheetNames).toEqual(['BOM', '变更记录', '转换明细']);
  assertNoExcelErrors(workbook);

  const bomRows = sheetRows(workbook, 'BOM');
  expect(bomRows.length).toBeGreaterThanOrEqual(6);
  expect(cell(bomRows[0], 0)).toBe('捷嘉智造工业互联网(深圳)有限公司');
  expect(cell(bomRows[1], 0)).toContain(created.marker);
  expect(cell(bomRows[2], 0)).toMatch(/^发行日期:\d{4}\.\d{2}\.\d{2}$/);
  expect(cell(bomRows[2], 3)).toMatch(/^PCBA 编码: PCBA-.+  版本：A0$/);
  expect(bomRows[3]).toEqual([
    '序号',
    '层级',
    '物料编码',
    '物料名称',
    '规格描述',
    '单位',
    '用量',
    '位置',
    '工段',
    '品牌/制造商',
    '原料号',
    '备注',
  ]);
  const dataRows = bomRows
    .slice(7)
    .filter((row) => row.some((value) => String(value ?? '').trim() !== ''));
  expect(dataRows, 'BOM sheet must contain exactly the two persisted standard rows').toHaveLength(
    2,
  );
  expect(dataRows.map((row) => Number(row[0]))).toEqual([1, 2]);

  const resistorRow = bomRows.find((row) => cell(row, 7) === 'R1,R2');
  expect(
    resistorRow,
    'standard BOM should include target resistor row (refdes R1,R2)',
  ).toBeTruthy();
  expect(cell(resistorRow, 2)).toBe(expected === 'confirmed' ? created.candidateCode : '');
  expect(cell(resistorRow, 2)).toBe(String(expectedRow.bom_std_material_code ?? ''));
  expect(cell(resistorRow, 3)).toBe(String(expectedRow.bom_std_material_name ?? ''));
  expect(cell(resistorRow, 4)).toBe(String(expectedRow.bom_std_spec ?? ''));
  expect(cell(resistorRow, 5)).toBe(String(expectedRow.bom_std_unit ?? 'PCS'));
  expect(Number(resistorRow?.[6])).toBe(Number(expectedRow.bom_std_qty));
  expect(cell(resistorRow, 7)).toBe(String(expectedRow.bom_std_refdes ?? ''));
  expect(cell(resistorRow, 8)).toBe('');
  expect(cell(resistorRow, 9)).toBe(String(expectedRow.bom_std_brand ?? ''));
  expect(cell(resistorRow, 10)).toBe(String(expectedRow.bom_std_mpn ?? ''));
  expect(cell(resistorRow, 11)).toBe(String(expectedRow.bom_std_remark ?? ''));
  if (expected === 'unconfirmed') {
    // Undo restores source truth and must not leak the previously selected D410 identity.
    expect(cell(resistorRow, 3)).toBe('10K resistor raw');
    expect(cell(resistorRow, 4)).toBe('10K 1% 0603');
    expect(cell(resistorRow, 9)).toBe('');
    expect(cell(resistorRow, 10)).toBe('RC0603FR-0710KL');
    expect(JSON.stringify(resistorRow)).not.toContain(created.candidateCode);
  }

  const mcuRow = bomRows.find((row) => cell(row, 3) === 'MCU direct copy');
  expect(mcuRow, 'standard BOM should include direct-copy MCU row').toBeTruthy();
  expect(cell(mcuRow, 2)).toMatch(/^E2E-U1-/);
  expect(cell(mcuRow, 4)).toBe('LQFP48');
  expect(cell(mcuRow, 10)).toBe('STM32F103C8T6');

  const detailRows = sheetRows(workbook, '转换明细');
  expect(detailRows[0]).toEqual([
    '原始行号',
    '原始描述',
    '系统分类',
    '提取属性',
    '匹配编码',
    '候选编码',
    '颜色',
    '错误原因',
    '数量语义证据',
  ]);
  expect(
    detailRows.slice(1),
    'detail sheet must contain exactly one row per standard row',
  ).toHaveLength(2);
  const resistorDetail = detailRows.find((row) => cell(row, 0) === '1');
  expect(resistorDetail, 'detail sheet should include target resistor evidence').toBeTruthy();
  expect(cell(resistorDetail, 1)).toBe(String(expectedRow.bom_std_spec ?? ''));
  expect(cell(resistorDetail, 2)).toBe(String(expectedRow.bom_std_category ?? ''));
  expect(cell(resistorDetail, 3)).toBe('');
  expect(cell(resistorDetail, 4)).toBe(expected === 'confirmed' ? created.candidateCode : '');
  expect(cell(resistorDetail, 5)).toBe(
    `${created.candidateCode},${created.secondaryCandidateCode}`,
  );
  expect(cell(resistorDetail, 6)).toBe(expected === 'confirmed' ? 'GREEN' : 'YELLOW');
  expect(cell(resistorDetail, 7)).toContain(
    expected === 'confirmed' ? '手工确认' : '同规格存在多个候选物料',
  );
  expect(cell(resistorDetail, 8)).toBe(String(expectedRow.bom_std_quantity_json ?? ''));

  const mcuDetail = detailRows.find((row) => cell(row, 0) === '2');
  expect(mcuDetail, 'detail sheet should include green direct-copy evidence').toBeTruthy();
  expect(cell(mcuDetail, 4)).toMatch(/^E2E-U1-/);
  expect(cell(mcuDetail, 6)).toBe('GREEN');

  const changeRows = sheetRows(workbook, '变更记录');
  // The styled change-log grid lives in Excel B:D, so SheetJS normalizes B to array index 0.
  expect(changeRows[0]?.slice(0, 3)).toEqual(['日期', '版本', '更改记录']);
  expect(cell(changeRows[1], 0)).toBeTruthy();
  expect(cell(changeRows[1], 1)).toBe('A0');
  expect(cell(changeRows[1], 2)).toBe('初版发行');
}

async function clickSidebarPage(
  page: Parameters<typeof waitForDynamicPageLoad>[0],
  href: string,
  label: RegExp,
): Promise<void> {
  const nav = page.locator('nav, aside, [role="navigation"]').first();
  const link = nav
    .locator(`a[href="${href}"]`)
    .or(nav.getByRole('link', { name: label }))
    .first();
  await expect(link).toBeVisible({ timeout: 10_000 });
  for (let attempt = 0; attempt < 2; attempt += 1) {
    await link.scrollIntoViewIfNeeded();
    await link.click();
    const navigated = await page
      .waitForURL((url) => url.pathname === href, { timeout: 8_000 })
      .then(() => true)
      .catch(() => false);
    if (navigated) break;
    if (attempt === 1) {
      await expect.poll(() => new URL(page.url()).pathname).toBe(href);
    }
  }
  await waitForDynamicPageLoad(page, 20_000);
  const main = page.locator('main');
  const contentLoaded = await expect(main)
    .toContainText(label, { timeout: 20_000 })
    .then(() => true)
    .catch(() => false);
  if (!contentLoaded) {
    // Fresh Vite runtimes can force a one-time dependency-optimization reload after menu entry.
    await page.reload({ waitUntil: 'domcontentloaded' });
    await waitForDynamicPageLoad(page, 20_000);
    await expect(main).toContainText(label, { timeout: 20_000 });
  }
}

test.describe('BOM standardization workbench golden', () => {
  test.describe.configure({ timeout: 210_000 });

  test('persists D410 confirm and undo, then validates both unconfirmed and confirmed Excel revisions', async ({
    page,
  }, testInfo) => {
    const created: BomWorkbenchSeed = await seedBomWorkbench(page, {
      candidateCodes: ['D410000000100', 'D41HT00000100'],
    });
    const consoleIssues: string[] = [];
    page.on('console', (message) => {
      const text = message.text();
      if (isTransientViteDynamicImportIssue(text)) return;
      if (
        /Expression evaluation failed|Cannot read properties|ReferenceError|TypeError/i.test(text)
      ) {
        consoleIssues.push(`${message.type()}: ${text}`);
      }
    });
    page.on('pageerror', (error) => {
      if (isTransientViteDynamicImportIssue(error.message)) return;
      consoleIssues.push(`pageerror: ${error.message}`);
    });

    try {
      await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
      await ensureSidebarExpanded(page);
      await clickSidebarPage(
        page,
        '/p/bom_conversion_task_pcba_workbench',
        /BOM 工作台|Workbench/i,
      );
      await expect(page.locator('main')).toContainText(created.marker, { timeout: 20_000 });
      await expect(page.getByText(/打开|Open/).first()).toBeVisible({ timeout: 20_000 });
      expect(await tableHeaders(page)).toEqual([
        '客户',
        '项目',
        '编号',
        '状态',
        '创建人',
        '创建时间',
        '修改时间',
        '操作',
      ]);

      const workbenchRow = await findRowInPaginatedList(page, created.marker, 20_000);
      const workbenchCells = workbenchRow.locator('td');
      await expect(workbenchCells.nth(0)).toContainText(`${created.marker} customer`);
      await expect(workbenchCells.nth(1)).toContainText(`${created.marker} project`);
      await expect(workbenchCells.nth(0).locator('.text-accent')).toHaveCount(0);
      await expect(workbenchCells.nth(1).locator('.text-accent')).toHaveCount(0);
      const creatorText = (await workbenchCells.nth(4).innerText()).trim();
      expect(creatorText, 'creator should resolve to a user name instead of a blank/raw id').not.toBe('-');
      expect(creatorText).not.toMatch(/^\d+$/);
      await Promise.all([
        page
          .waitForURL(
            (url) =>
              url.pathname === `/p/bom_conversion_task_pcba_workbench/view/${created.taskId}`,
            { timeout: 20_000 },
          )
          .catch(() => null),
        clickRowActionByLocator(page, workbenchRow, 'open_workbench', '打开'),
      ]);
      await waitForDynamicPageLoad(page, 20_000);

      const completedBanner = page.getByTestId('status-banner-bom_workbench_task_status');
      await expect(completedBanner).toBeVisible({ timeout: 20_000 });
      await expect(completedBanner).toContainText(/BOM 匹配已完成|BOM matching completed/i);
      await expect(page.getByTestId('workbench-action-download_new_bom')).toBeVisible({
        timeout: 20_000,
      });
      await expect(page.getByTestId('metric-strip-bom_workbench_metrics')).toBeVisible({
        timeout: 20_000,
      });
      await expect(page.getByTestId('metric-strip-item-green')).toContainText('1');
      await expect(page.getByTestId('metric-strip-item-yellow')).toContainText('1');
      await expect(page.getByTestId('metric-strip-item-red')).toContainText('0');

      await expect(page.getByTestId('metric-strip-bom_workbench_reason_filters')).toBeVisible();
      await expect(page.getByTestId('metric-strip-item-reason_multi_candidate')).toContainText('1');
      await page.getByTestId('metric-strip-item-reason_multi_candidate').click();
      await expect(page.locator('tbody')).toContainText('10K resistor canonical', {
        timeout: 20_000,
      });
      await expect(page.locator('tbody')).not.toContainText('MCU direct copy');

      // The reason metric-strip's "show all / clear" chip is keyed reason_all
      // (sets reasonFilterCodes=[]); the legacy reason_clear_filter key no longer exists.
      await page.getByTestId('metric-strip-item-reason_all').click();
      await expect(page.locator('tbody')).toContainText('MCU direct copy', { timeout: 20_000 });

      await page.locator('tbody tr').filter({ hasText: '10K resistor canonical' }).first().click();
      await expect(page.getByTestId('review-drawer')).toBeVisible({ timeout: 20_000 });
      await expect(page.getByTestId('review-drawer')).toContainText('10K resistor canonical');
      await expect(page.getByTestId('review-drawer-badge-reason')).toContainText(
        /多候选|Multiple/i,
      );
      await expect(page.getByTestId('review-drawer-tab-compare')).toContainText(/原始|Raw/i);
      await expect(page.getByTestId('review-drawer-tab-source')).toContainText(/Profile|LLM/i);
      await expect(page.getByTestId('review-drawer-tab-candidates')).toContainText(
        '10K resistor candidate A',
      );
      await expect(
        page.getByTestId(`review-drawer-candidate-${created.primaryEvidenceId}`),
      ).toContainText(created.candidateCode);

      await page.getByTestId(`review-drawer-candidate-${created.primaryEvidenceId}`).click();
      const confirmResponsePromise = page.waitForResponse(
        (response) =>
          response.url().includes('/api/meta/commands/execute/bom:confirm_candidate') &&
          response.request().method() === 'POST',
        { timeout: 30_000 },
      );
      await page.getByTestId('review-drawer-candidate-action-confirm_candidate').click();
      const confirmResponse = await confirmResponsePromise;
      const confirmBody = await confirmResponse.json().catch(() => ({}));
      expect(
        String((confirmBody as any).code),
        `bom:confirm_candidate response: ${JSON.stringify(confirmBody).slice(0, 1000)}`,
      ).toBe('0');

      await expect
        .poll(
          async () => {
            const row = await readDynamicRecord(
              page,
              'bom_standard_line_pcba',
              created.standardLineId,
            );
            const task = await readDynamicRecord(page, 'bom_conversion_task_pcba', created.taskId);
            return {
              materialCode: row.bom_std_material_code,
              reasonCode: row.bom_std_reason_code,
              manualConfirmed: String(row.bom_std_manual_confirmed),
              editedAfterCompletion: String(task.bom_task_edited_after_completion),
              greenCount: task.bom_task_green_count,
              yellowCount: task.bom_task_yellow_count,
            };
          },
          { timeout: 20_000, intervals: [500, 1000, 1500] },
        )
        .toEqual({
          materialCode: created.candidateCode,
          reasonCode: 'manual_confirm',
          manualConfirmed: 'true',
          editedAfterCompletion: 'true',
          greenCount: 2,
          yellowCount: 0,
        });

      // A command response is insufficient evidence: reload from the workbench route and prove
      // the persisted state is what the operator sees before allowing undo.
      await page.reload({ waitUntil: 'domcontentloaded' });
      await waitForDynamicPageLoad(page, 20_000);
      await expect(page.getByTestId('metric-strip-item-green')).toContainText('2');
      await expect(page.getByTestId('metric-strip-item-yellow')).toContainText('0');
      await page.locator('tbody tr').filter({ hasText: 'R1,R2' }).first().click();
      await expect(page.getByTestId('review-drawer')).toContainText(created.candidateCode, {
        timeout: 20_000,
      });
      await expect(page.getByTestId('review-drawer-candidate-action-undo_decision')).toBeEnabled({
        timeout: 20_000,
      });
      const undoResponsePromise = page.waitForResponse(
        (response) =>
          response.url().includes('/api/meta/commands/execute/bom:undo_decision') &&
          response.request().method() === 'POST',
        { timeout: 30_000 },
      );
      await page.getByTestId('review-drawer-candidate-action-undo_decision').click();
      const undoResponse = await undoResponsePromise;
      const undoBody = await undoResponse.json().catch(() => ({}));
      expect(
        String((undoBody as any).code),
        `bom:undo_decision response: ${JSON.stringify(undoBody).slice(0, 1000)}`,
      ).toBe('0');

      await expect
        .poll(
          async () => {
            const row = await readDynamicRecord(
              page,
              'bom_standard_line_pcba',
              created.standardLineId,
            );
            const decisions = await queryDynamicRecords(page, 'bom_review_decision', [
              { fieldName: 'bom_rd_task_id', operator: 'EQ', value: created.taskId },
            ]);
            return {
              materialCode: row.bom_std_material_code,
              reasonCode: row.bom_std_reason_code,
              manualConfirmed: String(row.bom_std_manual_confirmed),
              decisions: decisions.map((decision) => decision.bom_rd_decision_type).sort(),
            };
          },
          { timeout: 20_000, intervals: [500, 1000, 1500] },
        )
        .toEqual({
          materialCode: '',
          reasonCode: 'match_multi_candidate',
          manualConfirmed: 'false',
          decisions: ['manual_confirm', 'undo'],
        });

      // Reload again so the cancel/undo acceptance is based on persisted UI state, not an
      // optimistic drawer update. The candidate remains available, but D410 is no longer chosen.
      await page.reload({ waitUntil: 'domcontentloaded' });
      await waitForDynamicPageLoad(page, 20_000);
      await expect(page.getByTestId('metric-strip-item-green')).toContainText('1');
      await expect(page.getByTestId('metric-strip-item-yellow')).toContainText('1');
      await page.locator('tbody tr').filter({ hasText: 'R1,R2' }).first().click();
      await expect(page.getByTestId('review-drawer')).toContainText(created.candidateCode, {
        timeout: 20_000,
      });
      await expect(
        page.getByTestId('review-drawer-candidate-action-confirm_candidate'),
      ).toBeVisible();
      const unconfirmedExportRow = await readDynamicRecord(
        page,
        'bom_standard_line_pcba',
        created.standardLineId,
      );

      // Close the review drawer before exercising the toolbar-level regenerate action: the
      // floating drawer (fixed z-50) overlays the workbench toolbar and would intercept the
      // click on workbench-action-download_new_bom. Close fully dismisses the drawer (clears the
      // selected row -> inline empty state), so the toolbar is reachable.
      await page.getByRole('button', { name: /关闭复核浮层|Close review drawer/i }).click();
      await expect(page.getByTestId('review-drawer')).toHaveCount(0, { timeout: 10_000 });
      await expect(page.getByTestId('review-drawer-empty')).toBeVisible({ timeout: 10_000 });

      const regenerateResponsePromise = page.waitForResponse(
        (response) =>
          response.url().includes('/api/meta/commands/execute/bom:regenerate_export') &&
          response.request().method() === 'POST',
        { timeout: 45_000 },
      );
      const downloadPromise = page.waitForEvent('download', { timeout: 45_000 });
      await page.getByTestId('workbench-action-download_new_bom').click();
      await expect(page.getByTestId('workbench-action-download_new_bom')).toContainText(
        /加载|Loading/i,
        {
          timeout: 10_000,
        },
      );
      const regenerateResponse = await regenerateResponsePromise;
      const regenerateBody = await regenerateResponse.json().catch(() => ({}));
      expect(
        String((regenerateBody as any).code),
        `bom:regenerate_export response: ${JSON.stringify(regenerateBody).slice(0, 1000)}`,
      ).toBe('0');
      const regenerateData = ((regenerateBody as any).data?.data ?? {}) as Record<string, unknown>;
      expect(String(regenerateData.exportFileId ?? '')).toBeTruthy();
      const download = await downloadPromise;
      expect(download.suggestedFilename()).toMatch(/standard-bom-.*\.xlsx$/);
      expect(download.suggestedFilename()).toBe(`standard-bom-${created.taskId}.xlsx`);
      const exportPath = path.join(testInfo.outputDir, 'standard-bom-unconfirmed.xlsx');
      await download.saveAs(exportPath);
      validateStandardBomWorkbook(exportPath, created, 'unconfirmed', unconfirmedExportRow);

      await expect
        .poll(
          async () => {
            const task = await readDynamicRecord(page, 'bom_conversion_task_pcba', created.taskId);
            const revisions = await queryDynamicRecords(page, 'bom_export_revision', [
              { fieldName: 'bom_er_task_id', operator: 'EQ', value: created.taskId },
            ]);
            return {
              editedAfterCompletion: String(task.bom_task_edited_after_completion),
              exportFileId: String(task.bom_task_export_file_id ?? ''),
              revisionCount: revisions.length,
            };
          },
          { timeout: 20_000, intervals: [500, 1000, 1500] },
        )
        .toEqual({
          editedAfterCompletion: 'false',
          exportFileId: String(regenerateData.exportFileId),
          revisionCount: 2,
        });

      // Reconfirm the exact D410 candidate after proving that the undo export contained no stale
      // selection. This produces a second independently parsed artifact for the final state.
      await page.locator('tbody tr').filter({ hasText: 'R1,R2' }).first().click();
      await expect(page.getByTestId('review-drawer')).toBeVisible({ timeout: 20_000 });
      await page.getByTestId(`review-drawer-candidate-${created.primaryEvidenceId}`).click();
      const reconfirmResponsePromise = page.waitForResponse(
        (response) =>
          response.url().includes('/api/meta/commands/execute/bom:confirm_candidate') &&
          response.request().method() === 'POST',
        { timeout: 30_000 },
      );
      await page.getByTestId('review-drawer-candidate-action-confirm_candidate').click();
      const reconfirmResponse = await reconfirmResponsePromise;
      const reconfirmBody = await reconfirmResponse.json().catch(() => ({}));
      expect(
        String((reconfirmBody as any).code),
        `second bom:confirm_candidate response: ${JSON.stringify(reconfirmBody).slice(0, 1000)}`,
      ).toBe('0');

      await expect
        .poll(
          async () => {
            const row = await readDynamicRecord(
              page,
              'bom_standard_line_pcba',
              created.standardLineId,
            );
            const decisions = await queryDynamicRecords(page, 'bom_review_decision', [
              { fieldName: 'bom_rd_task_id', operator: 'EQ', value: created.taskId },
            ]);
            return {
              materialCode: row.bom_std_material_code,
              reasonCode: row.bom_std_reason_code,
              manualConfirmed: String(row.bom_std_manual_confirmed),
              decisions: decisions.map((decision) => decision.bom_rd_decision_type).sort(),
            };
          },
          { timeout: 20_000, intervals: [500, 1000, 1500] },
        )
        .toEqual({
          materialCode: created.candidateCode,
          reasonCode: 'manual_confirm',
          manualConfirmed: 'true',
          decisions: ['manual_confirm', 'manual_confirm', 'undo'],
        });

      await page.reload({ waitUntil: 'domcontentloaded' });
      await waitForDynamicPageLoad(page, 20_000);
      await expect(page.getByTestId('metric-strip-item-green')).toContainText('2');
      await expect(page.getByTestId('metric-strip-item-yellow')).toContainText('0');
      await page.locator('tbody tr').filter({ hasText: 'R1,R2' }).first().click();
      await expect(page.getByTestId('review-drawer')).toContainText(created.candidateCode, {
        timeout: 20_000,
      });
      await expect(page.getByTestId('review-drawer-candidate-action-undo_decision')).toBeEnabled();
      const confirmedExportRow = await readDynamicRecord(
        page,
        'bom_standard_line_pcba',
        created.standardLineId,
      );
      await page.getByRole('button', { name: /关闭复核浮层|Close review drawer/i }).click();
      await expect(page.getByTestId('review-drawer')).toHaveCount(0, { timeout: 10_000 });

      const finalRegenerateResponsePromise = page.waitForResponse(
        (response) =>
          response.url().includes('/api/meta/commands/execute/bom:regenerate_export') &&
          response.request().method() === 'POST',
        { timeout: 45_000 },
      );
      const finalDownloadPromise = page.waitForEvent('download', { timeout: 45_000 });
      await page.getByTestId('workbench-action-download_new_bom').click();
      const finalRegenerateResponse = await finalRegenerateResponsePromise;
      const finalRegenerateBody = await finalRegenerateResponse.json().catch(() => ({}));
      expect(
        String((finalRegenerateBody as any).code),
        `final bom:regenerate_export response: ${JSON.stringify(finalRegenerateBody).slice(0, 1000)}`,
      ).toBe('0');
      const finalRegenerateData = ((finalRegenerateBody as any).data?.data ?? {}) as Record<
        string,
        unknown
      >;
      expect(String(finalRegenerateData.exportFileId ?? '')).toBeTruthy();
      const finalDownload = await finalDownloadPromise;
      expect(finalDownload.suggestedFilename()).toBe(`standard-bom-${created.taskId}.xlsx`);
      const finalExportPath = path.join(testInfo.outputDir, 'standard-bom-confirmed.xlsx');
      await finalDownload.saveAs(finalExportPath);
      validateStandardBomWorkbook(finalExportPath, created, 'confirmed', confirmedExportRow);

      await expect
        .poll(
          async () => {
            const task = await readDynamicRecord(page, 'bom_conversion_task_pcba', created.taskId);
            const revisions = await queryDynamicRecords(page, 'bom_export_revision', [
              { fieldName: 'bom_er_task_id', operator: 'EQ', value: created.taskId },
            ]);
            return {
              editedAfterCompletion: String(task.bom_task_edited_after_completion),
              exportFileId: String(task.bom_task_export_file_id ?? ''),
              revisionCount: revisions.length,
            };
          },
          { timeout: 20_000, intervals: [500, 1000, 1500] },
        )
        .toEqual({
          editedAfterCompletion: 'false',
          exportFileId: String(finalRegenerateData.exportFileId),
          revisionCount: 3,
        });

      await expect(page.getByTestId('review-drawer')).toHaveCount(0, { timeout: 10_000 });
      await page.getByRole('tab', { name: /导出版本|Export Revisions/i }).click();
      await expect(page.getByTestId('artifact-timeline')).toBeVisible({ timeout: 20_000 });
      await expect(page.getByTestId('artifact-timeline')).toContainText('Rev 3');
      await expect(page.getByTestId('artifact-timeline')).toContainText('Rev 2');
      await expect(page.getByTestId('artifact-timeline')).toContainText(
        `standard-bom-${created.taskId}.xlsx`,
      );

      await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
      await expect(page.locator('a[href="/p/bom_review_queue"]')).toHaveCount(0);
      await expect(consoleIssues).toEqual([]);
    } finally {
      await cleanupRows(page, created);
    }
  });
});
