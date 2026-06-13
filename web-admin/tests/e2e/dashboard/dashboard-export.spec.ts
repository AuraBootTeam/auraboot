/**
 * Dashboard Export E2E Tests
 *
 * Validates the export buttons (PDF + Excel) in the dashboard designer toolbar.
 * Export buttons appear after a dashboard is saved (has a pid).
 */

import { test, expect } from '@playwright/test';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { DashboardDesignerPage } from '../../pages/DashboardDesignerPage';

test.describe('Dashboard Export Buttons @smoke', () => {
  test.setTimeout(60000);

  test('EXP-01: Export buttons appear after saving dashboard', async ({ page }) => {
    const dp = new DashboardDesignerPage(page);
    await dp.goto();

    // Before save — no pid, export buttons should NOT be visible
    const excelBtn = page.locator('[data-testid="toolbar-btn-export-excel"]');
    const pdfBtn = page.locator('[data-testid="export-pdf-button"]');
    await expect(excelBtn).not.toBeVisible();

    // Set title via settings, then save
    await dp.openSettings();
    await dp.settingsTitleInput.fill(`ExportTest_${Date.now()}`);
    await dp.saveSettings();
    await dp.save();

    // After save, dashboard has pid → export buttons should appear
    await expect(pdfBtn).toBeVisible({ timeout: 10000 });
    await expect(excelBtn).toBeVisible({ timeout: 10000 });
    await expect(excelBtn).toContainText('Excel');
  });

  test('EXP-02: Excel export button disabled when no widgets', async ({ page }) => {
    const dp = new DashboardDesignerPage(page);
    await dp.goto();

    await dp.openSettings();
    await dp.settingsTitleInput.fill(`ExportEmpty_${Date.now()}`);
    await dp.saveSettings();
    await dp.save();

    const excelBtn = page.locator('[data-testid="toolbar-btn-export-excel"]');
    await expect(excelBtn).toBeVisible({ timeout: 10000 });
    // No widgets → disabled
    await expect(excelBtn).toBeDisabled();
  });

  test('EXP-03: Excel export downloads configured widget data as XLSX', async ({
    page,
  }, testInfo) => {
    const dp = new DashboardDesignerPage(page);
    const dashboardTitle = `ExportData_${Date.now()}`;
    const exportRows = [
      { region: 'North', cases: 12, owner: 'Ops-A' },
      { region: 'South', cases: 9, owner: 'Ops-B' },
    ];

    await dp.goto();
    await dp.openSettings();
    await dp.settingsTitleInput.fill(dashboardTitle);
    await dp.saveSettings();

    await dp.addWidget('数据表格');
    await page.getByTestId('dashboard-datasource-type-select').selectOption('static');
    await page
      .getByTestId('dashboard-datasource-static-json')
      .fill(JSON.stringify(exportRows, null, 2));

    await dp.save();

    const excelBtn = page.getByTestId('toolbar-btn-export-excel');
    await expect(excelBtn).toBeVisible({ timeout: 10000 });
    await expect(excelBtn).toBeEnabled();

    const downloadPromise = page.waitForEvent('download', { timeout: 30_000 });
    await excelBtn.click();
    const download = await downloadPromise;

    expect(download.suggestedFilename()).toBe(`${dashboardTitle}.xlsx`);
    const savedPath = path.join(testInfo.outputDir, download.suggestedFilename());
    await download.saveAs(savedPath);

    const bytes = await readFile(savedPath);
    expect(bytes.subarray(0, 2).toString()).toBe('PK');

    const XLSX = await import('xlsx');
    const workbook = XLSX.read(bytes, { type: 'buffer' });
    expect(workbook.SheetNames).toEqual(['数据表格']);

    const sheetRows = XLSX.utils.sheet_to_json(workbook.Sheets['数据表格']);
    expect(sheetRows).toEqual(exportRows);
  });

  test('EXP-04: PDF export downloads a non-empty rendered dashboard artifact', async ({
    page,
  }, testInfo) => {
    const dp = new DashboardDesignerPage(page);
    const dashboardTitle = `ExportPdf_${Date.now()}`;

    await dp.goto();
    await dp.openSettings();
    await dp.settingsTitleInput.fill(dashboardTitle);
    await dp.saveSettings();

    await dp.addWidget('数据表格');
    await page.getByTestId('dashboard-datasource-type-select').selectOption('static');
    await page
      .getByTestId('dashboard-datasource-static-json')
      .fill(JSON.stringify([{ region: 'East', cases: 18, owner: 'Ops-PDF' }], null, 2));

    await dp.save();

    const pdfBtn = page.getByTestId('export-pdf-button');
    await expect(pdfBtn).toBeVisible({ timeout: 10000 });
    await expect(pdfBtn).toBeEnabled();

    const downloadPromise = page.waitForEvent('download', { timeout: 30_000 });
    await pdfBtn.click();
    const download = await downloadPromise;

    expect(download.suggestedFilename()).toBe(`${dashboardTitle}.pdf`);
    const savedPath = path.join(testInfo.outputDir, download.suggestedFilename());
    await download.saveAs(savedPath);

    const bytes = await readFile(savedPath);
    const pdfText = bytes.toString('latin1');
    expect(bytes.subarray(0, 5).toString('latin1')).toBe('%PDF-');
    expect(bytes.length).toBeGreaterThan(1_000);
    expect(pdfText).toContain('/Type /Page');
    expect(pdfText.includes('/Subtype /Image') || pdfText.includes(dashboardTitle)).toBe(true);
  });
});
