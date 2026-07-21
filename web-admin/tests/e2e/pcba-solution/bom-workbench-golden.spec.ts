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

function validateStandardBomWorkbook(filePath: string, created: BomWorkbenchSeed): void {
  const workbook = XLSX.read(fs.readFileSync(filePath), {
    type: 'buffer',
    cellText: false,
    sheetStubs: true,
  });
  expect(workbook.SheetNames).toEqual(['BOM', '变更记录', '转换明细']);

  const bomRows = sheetRows(workbook, 'BOM');
  expect(bomRows.length).toBeGreaterThanOrEqual(6);
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

  // Source-truth semantics (plugins #144): the spec flow is confirm → undo, and undo
  // restores the SOURCE values (name/spec/brand/mpn/package) from the raw line onto the
  // standard row. Locate the row by its stable refdes; the seeded standard-line identity
  // ('10K resistor canonical' / brand 'Yageo') must NOT survive the undo — the export
  // presents the customer's raw values for unconfirmed rows.
  const resistorRow = bomRows.find((row) => cell(row, 7) === 'R1,R2');
  expect(resistorRow, 'standard BOM should include unresolved resistor row (refdes R1,R2)').toBeTruthy();
  expect(cell(resistorRow, 3), 'undo restores the raw-source material name').toBe('10K resistor raw');
  expect(cell(resistorRow, 2), 'unconfirmed multi-candidate material code stays blank').toBe('');
  expect(cell(resistorRow, 4)).toBe('10K 1% 0603');
  expect(cell(resistorRow, 5)).toBe('PCS');
  expect(Number(resistorRow?.[6])).toBe(2);
  expect(cell(resistorRow, 9), 'brand reverts to the raw source (which has none), not the seeded Yageo').toBe('');
  expect(cell(resistorRow, 10)).toBe('RC0603FR-0710KL');

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
  const resistorDetail = detailRows.find((row) => cell(row, 1) === '10K 1% 0603');
  expect(resistorDetail, 'detail sheet should include unresolved resistor evidence').toBeTruthy();
  expect(cell(resistorDetail, 4)).toBe('');
  expect(cell(resistorDetail, 5)).toContain(created.candidateCode);
  expect(cell(resistorDetail, 6)).toBe('YELLOW');
  expect(cell(resistorDetail, 7)).toContain('同规格存在多个候选物料');

  const mcuDetail = detailRows.find((row) => cell(row, 1) === 'LQFP48');
  expect(mcuDetail, 'detail sheet should include green direct-copy evidence').toBeTruthy();
  expect(cell(mcuDetail, 4)).toMatch(/^E2E-U1-/);
  expect(cell(mcuDetail, 6)).toBe('GREEN');
}

async function clickSidebarPage(
  page: Parameters<typeof waitForDynamicPageLoad>[0],
  href: string,
  label: RegExp,
): Promise<void> {
  const nav = page.locator('nav, aside, [role="navigation"]').first();
  const link = nav.locator(`a[href="${href}"]`).or(nav.getByRole('link', { name: label })).first();
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
  test.describe.configure({ timeout: 150_000 });

  test('covers workbench metrics, candidate decision feedback, undo, review queue, and export timeline', async ({
    page,
  }, testInfo) => {
    const created: BomWorkbenchSeed = await seedBomWorkbench(page);
    const consoleIssues: string[] = [];
    page.on('console', (message) => {
      const text = message.text();
      if (isTransientViteDynamicImportIssue(text)) return;
      if (/Expression evaluation failed|Cannot read properties|ReferenceError|TypeError/i.test(text)) {
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
      await clickSidebarPage(page, '/p/bom_conversion_task_pcba_workbench', /BOM 工作台|Workbench/i);
      await expect(page.locator('main')).toContainText(created.marker, { timeout: 20_000 });
      await expect(page.getByText(/打开|Open/).first()).toBeVisible({ timeout: 20_000 });

      const workbenchRow = await findRowInPaginatedList(page, created.marker, 20_000);
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

      await expect(page.getByTestId('status-banner-bom_workbench_task_status')).toHaveCount(0);
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
      await expect(page.getByTestId('review-drawer-badge-reason')).toContainText(/多候选|Multiple/i);
      await expect(page.getByTestId('review-drawer-tab-compare')).toContainText(/原始|Raw/i);
      await expect(page.getByTestId('review-drawer-tab-source')).toContainText(/Profile|LLM/i);
      await expect(page.getByTestId('review-drawer-tab-candidates')).toContainText(
        '10K resistor candidate A',
      );
      await expect(page.getByTestId(`review-drawer-candidate-${created.primaryEvidenceId}`)).toContainText(
        created.candidateCode,
      );

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

      // Close the review drawer before exercising the toolbar-level regenerate action: the
      // floating drawer (fixed z-50) overlays the workbench toolbar and would intercept the
      // click on workbench-action-download_new_bom. Close fully dismisses the drawer (clears the
      // selected row -> inline empty state), so the toolbar is reachable.
      await page
        .getByRole('button', { name: /关闭复核浮层|Close review drawer/i })
        .click();
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
      await expect(page.getByTestId('workbench-action-download_new_bom')).toContainText(/加载|Loading/i, {
        timeout: 10_000,
      });
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
      const exportPath = path.join(testInfo.outputDir, 'standard-bom-download.xlsx');
      await download.saveAs(exportPath);
      validateStandardBomWorkbook(exportPath, created);

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

      // Drawer was closed before the toolbar regenerate action above; assert it stays closed
      // (the export-revisions timeline lives in the workbench body, not the drawer).
      await expect(page.getByTestId('review-drawer')).toHaveCount(0, { timeout: 10_000 });
      await page.getByRole('tab', { name: /导出版本|Export Revisions/i }).click();
      await expect(page.getByTestId('artifact-timeline')).toBeVisible({ timeout: 20_000 });
      await expect(page.getByTestId('artifact-timeline')).toContainText('Rev 2');
      await expect(page.getByTestId('artifact-timeline')).toContainText(`standard-bom-${created.taskId}.xlsx`);

      await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
      await expect(page.locator('a[href="/p/bom_review_queue"]')).toHaveCount(0);
      await expect(consoleIssues).toEqual([]);
    } finally {
      await cleanupRows(page, created);
    }
  });
});
