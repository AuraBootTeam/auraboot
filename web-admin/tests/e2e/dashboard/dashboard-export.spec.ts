/**
 * Dashboard Export E2E Tests
 *
 * Validates the export buttons (PDF + Excel) in the dashboard designer toolbar.
 * Export buttons appear after a dashboard is saved (has a pid).
 */

import { test, expect } from '@playwright/test';
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
});
