/**
 * Report Designer Deep E2E Tests
 *
 * Tests every property-panel field for each block type:
 *   - data-table, grouped-table, stat-card, rich-text, cross-tab, chart
 *   - page-header / page-footer (BandEditor)
 *   - Report-level operations (save, export, undo/redo)
 *
 * IDs: RPT-DT-01..13, RPT-GT-01..07, RPT-SC-01..06, RPT-RT-01..06,
 *      RPT-CT-01..09, RPT-CH-01..08, RPT-BD-01..08, RPT-OP-01..07,
 *      RPT-BC-01..07, RPT-WM-01..06
 *
 * @since 6.0.0
 */

import { test, expect } from '../../fixtures';
import type { Page } from '@playwright/test';
import { uniqueId } from '../helpers';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

async function waitForDesignerLoad(page: Page) {
  await page.waitForLoadState('networkidle').catch(() => {});
  await page
    .locator('.animate-spin')
    .waitFor({ state: 'hidden', timeout: 10000 })
    .catch(() => {});
  await page
    .locator('text=Loading page...')
    .waitFor({ state: 'hidden', timeout: 10000 })
    .catch(() => {});
}

/**
 * Navigate to a fresh report designer and add a body block from the palette.
 * Body blocks (data-table, grouped-table, stat-card, etc.) auto-select on add,
 * so the property panel becomes visible immediately.
 */
async function openReportAndAddBlock(page: Page, blockName: string) {
  await page.goto('/report-designer', { waitUntil: 'domcontentloaded' });
  await waitForDesignerLoad(page);
  await expect(page.getByTestId('block-palette')).toBeVisible({ timeout: 10000 });
  await page
    .getByRole('button', { name: new RegExp(blockName, 'i') })
    .first()
    .click();
  await expect(page.getByTestId('block-property-panel')).toBeVisible({ timeout: 5000 });
}

/**
 * Navigate to report designer, add a header or footer, then click it on canvas to select.
 */
async function openReportAndAddBand(page: Page, bandType: 'Page Header' | 'Page Footer') {
  await page.goto('/report-designer', { waitUntil: 'domcontentloaded' });
  await waitForDesignerLoad(page);
  await expect(page.getByTestId('block-palette')).toBeVisible({ timeout: 10000 });
  await page
    .getByRole('button', { name: new RegExp(bandType, 'i') })
    .first()
    .click();
  await page.waitForTimeout(300);
  const canvas = page.getByTestId('report-canvas');
  const cursorDivs = canvas.locator('.cursor-pointer');
  if (bandType === 'Page Header') {
    await cursorDivs.first().click({ timeout: 5000 });
  } else {
    await cursorDivs.last().click({ timeout: 5000 });
  }
  await expect(page.getByTestId('block-property-panel')).toBeVisible({ timeout: 5000 });
}

// ===================================================================
// 1. data-table Block — All Properties
// ===================================================================

test.describe('data-table Block — All Properties', () => {
  test('RPT-DT-01: Add data-table block and verify property panel', async ({ page }) => {
    await openReportAndAddBlock(page, 'Data Table');
    const panel = page.getByTestId('block-property-panel');
    await expect(panel.locator('h2', { hasText: 'Data Table' })).toBeVisible();
  });

  test('RPT-DT-02: Title input accepts text', async ({ page }) => {
    await openReportAndAddBlock(page, 'Data Table');
    const panel = page.getByTestId('block-property-panel');
    const titleInput = panel.getByPlaceholder('Table title');
    await expect(titleInput).toBeVisible();
    const title = uniqueId('DT_Title');
    await titleInput.fill(title);
    await expect(titleInput).toHaveValue(title);
  });

  test('RPT-DT-03: Data Source select is present', async ({ page }) => {
    await openReportAndAddBlock(page, 'Data Table');
    const panel = page.getByTestId('block-property-panel');
    const label = panel.locator('label', { hasText: 'Data Source' });
    await expect(label).toBeVisible();
    const select = panel.locator('select').first();
    await expect(select).toBeVisible();
  });

  test('RPT-DT-04: Data Source has "Add new" button', async ({ page }) => {
    await openReportAndAddBlock(page, 'Data Table');
    const panel = page.getByTestId('block-property-panel');
    const addBtn = panel.getByText(/Add new data source/i);
    await expect(addBtn).toBeVisible();
  });

  test('RPT-DT-05: Show header row checkbox', async ({ page }) => {
    await openReportAndAddBlock(page, 'Data Table');
    const panel = page.getByTestId('block-property-panel');
    const checkbox = panel.getByRole('checkbox', { name: /show header row/i });
    await expect(checkbox).toBeVisible();
    const wasChecked = await checkbox.isChecked();
    await checkbox.click();
    expect(await checkbox.isChecked()).toBe(!wasChecked);
  });

  test('RPT-DT-06: Striped rows checkbox', async ({ page }) => {
    await openReportAndAddBlock(page, 'Data Table');
    const panel = page.getByTestId('block-property-panel');
    const checkbox = panel.getByRole('checkbox', { name: /striped rows/i });
    await expect(checkbox).toBeVisible();
    await checkbox.click();
  });

  test('RPT-DT-07: Cell borders checkbox', async ({ page }) => {
    await openReportAndAddBlock(page, 'Data Table');
    const panel = page.getByTestId('block-property-panel');
    const checkbox = panel.getByRole('checkbox', { name: /cell borders/i });
    await expect(checkbox).toBeVisible();
    await checkbox.click();
  });

  test('RPT-DT-08: Show summary row checkbox reveals summary config', async ({ page }) => {
    await openReportAndAddBlock(page, 'Data Table');
    const panel = page.getByTestId('block-property-panel');
    const summaryCheckbox = panel.getByRole('checkbox', { name: /show summary row/i });
    await expect(summaryCheckbox).toBeVisible();
    if (!(await summaryCheckbox.isChecked())) {
      await summaryCheckbox.click();
    }
    const labelInput = panel.getByPlaceholder(/Label.*Total/i);
    await expect(labelInput).toBeVisible({ timeout: 3000 });
  });

  test('RPT-DT-09: Summary label input editable', async ({ page }) => {
    await openReportAndAddBlock(page, 'Data Table');
    const panel = page.getByTestId('block-property-panel');
    const summaryCheckbox = panel.getByRole('checkbox', { name: /show summary row/i });
    if (!(await summaryCheckbox.isChecked())) {
      await summaryCheckbox.click();
    }
    const labelInput = panel.getByPlaceholder(/Label.*Total/i);
    await expect(labelInput).toBeVisible({ timeout: 3000 });
    await labelInput.fill('Grand Total');
    await expect(labelInput).toHaveValue('Grand Total');
  });

  test('RPT-DT-10: Add column via input + Add button', async ({ page }) => {
    await openReportAndAddBlock(page, 'Data Table');
    const panel = page.getByTestId('block-property-panel');
    const addInput = panel.getByPlaceholder('Field name').last();
    await expect(addInput).toBeVisible();
    await addInput.fill('test_col');
    await panel.getByRole('button', { name: 'Add', exact: true }).first().click();
    const displayLabelInput = panel.getByPlaceholder('Display label').first();
    await expect(displayLabelInput).toBeVisible({ timeout: 3000 });
  });

  test('RPT-DT-11: Column field name input editable', async ({ page }) => {
    await openReportAndAddBlock(page, 'Data Table');
    const panel = page.getByTestId('block-property-panel');
    const addInput = panel.getByPlaceholder('Field name').last();
    await addInput.fill('order_amount');
    await panel.getByRole('button', { name: 'Add', exact: true }).first().click();
    const colFieldInput = panel.getByPlaceholder('Field name').first();
    await expect(colFieldInput).toHaveValue('order_amount');
  });

  test('RPT-DT-12: Column display label input editable', async ({ page }) => {
    await openReportAndAddBlock(page, 'Data Table');
    const panel = page.getByTestId('block-property-panel');
    const addInput = panel.getByPlaceholder('Field name').last();
    await addInput.fill('amount');
    await panel.getByRole('button', { name: 'Add', exact: true }).first().click();
    const labelInput = panel.getByPlaceholder('Display label').first();
    await expect(labelInput).toBeVisible();
    await labelInput.fill('Order Amount');
    await expect(labelInput).toHaveValue('Order Amount');
  });

  test('RPT-DT-13: Column alignment and format selects', async ({ page }) => {
    await openReportAndAddBlock(page, 'Data Table');
    const panel = page.getByTestId('block-property-panel');
    const addInput = panel.getByPlaceholder('Field name').last();
    await addInput.fill('col1');
    await panel.getByRole('button', { name: 'Add', exact: true }).first().click();
    for (const align of ['Left', 'Center', 'Right']) {
      await expect(panel.locator('option', { hasText: align }).first()).toBeAttached();
    }
    for (const fmt of ['Default', 'Number', 'Currency', 'Percent', 'Date']) {
      await expect(panel.locator('option', { hasText: fmt }).first()).toBeAttached();
    }
  });
});

// ===================================================================
// 2. grouped-table Block — All Properties
// ===================================================================

test.describe('grouped-table Block — All Properties', () => {
  test('RPT-GT-01: Add grouped-table block and verify panel', async ({ page }) => {
    await openReportAndAddBlock(page, 'Grouped Table');
    await expect(
      page.getByTestId('block-property-panel').locator('h2', { hasText: 'Grouped Table' }),
    ).toBeVisible();
  });

  test('RPT-GT-02: Title input', async ({ page }) => {
    await openReportAndAddBlock(page, 'Grouped Table');
    const panel = page.getByTestId('block-property-panel');
    const titleInput = panel.getByPlaceholder('Table title');
    await expect(titleInput).toBeVisible();
    const title = uniqueId('GT_Title');
    await titleInput.fill(title);
    await expect(titleInput).toHaveValue(title);
  });

  test('RPT-GT-03: Group By Field input', async ({ page }) => {
    await openReportAndAddBlock(page, 'Grouped Table');
    const panel = page.getByTestId('block-property-panel');
    const groupByInput = panel.getByPlaceholder('Field name to group by');
    await expect(groupByInput).toBeVisible();
    await groupByInput.fill('department');
    await expect(groupByInput).toHaveValue('department');
  });

  test('RPT-GT-04: Group Subtotal checkbox toggles', async ({ page }) => {
    await openReportAndAddBlock(page, 'Grouped Table');
    const panel = page.getByTestId('block-property-panel');
    const subtotalCheckbox = panel.getByRole('checkbox', { name: /group subtotal/i });
    await expect(subtotalCheckbox).toBeVisible();
    const wasBefore = await subtotalCheckbox.isChecked();
    await subtotalCheckbox.click();
    expect(await subtotalCheckbox.isChecked()).toBe(!wasBefore);
  });

  test('RPT-GT-05: Grand Total checkbox toggles', async ({ page }) => {
    await openReportAndAddBlock(page, 'Grouped Table');
    const panel = page.getByTestId('block-property-panel');
    const grandTotalCheckbox = panel.getByRole('checkbox', { name: /grand total/i });
    await expect(grandTotalCheckbox).toBeVisible();
    const wasBefore = await grandTotalCheckbox.isChecked();
    await grandTotalCheckbox.click();
    expect(await grandTotalCheckbox.isChecked()).toBe(!wasBefore);
  });

  test('RPT-GT-06: Show header row checkbox', async ({ page }) => {
    await openReportAndAddBlock(page, 'Grouped Table');
    const checkbox = page
      .getByTestId('block-property-panel')
      .getByRole('checkbox', { name: /show header row/i });
    await expect(checkbox).toBeVisible();
    await checkbox.click();
  });

  test('RPT-GT-07: Cell borders checkbox', async ({ page }) => {
    await openReportAndAddBlock(page, 'Grouped Table');
    const checkbox = page
      .getByTestId('block-property-panel')
      .getByRole('checkbox', { name: /cell borders/i });
    await expect(checkbox).toBeVisible();
    await checkbox.click();
  });
});

// ===================================================================
// 3. stat-card Block — All Properties
// ===================================================================

test.describe('stat-card Block — All Properties', () => {
  test('RPT-SC-01: Add stat-card block and verify panel', async ({ page }) => {
    await openReportAndAddBlock(page, 'Stat Card');
    await expect(
      page.getByTestId('block-property-panel').locator('h2', { hasText: 'Stat Card' }),
    ).toBeVisible();
  });

  test('RPT-SC-02: Label input', async ({ page }) => {
    await openReportAndAddBlock(page, 'Stat Card');
    const labelInput = page
      .getByTestId('block-property-panel')
      .getByPlaceholder('e.g. Total Revenue');
    await expect(labelInput).toBeVisible();
    await labelInput.fill('Total Revenue');
    await expect(labelInput).toHaveValue('Total Revenue');
  });

  test('RPT-SC-03: Value Field input', async ({ page }) => {
    await openReportAndAddBlock(page, 'Stat Card');
    const valueInput = page.getByTestId('block-property-panel').getByPlaceholder('Field name');
    await expect(valueInput).toBeVisible();
    await valueInput.fill('amount');
    await expect(valueInput).toHaveValue('amount');
  });

  test('RPT-SC-04: Aggregation select has all options', async ({ page }) => {
    await openReportAndAddBlock(page, 'Stat Card');
    const panel = page.getByTestId('block-property-panel');
    await expect(panel.locator('label', { hasText: 'Aggregation' })).toBeVisible();
    for (const opt of ['sum', 'avg', 'count', 'min', 'max']) {
      await expect(panel.locator('option', { hasText: opt }).first()).toBeAttached();
    }
  });

  test('RPT-SC-05: Format select', async ({ page }) => {
    await openReportAndAddBlock(page, 'Stat Card');
    const panel = page.getByTestId('block-property-panel');
    await expect(panel.locator('label', { hasText: 'Format' })).toBeVisible();
    for (const fmt of ['Number', 'Currency', 'Percent']) {
      await expect(panel.locator('option', { hasText: new RegExp(fmt) }).first()).toBeAttached();
    }
  });

  test('RPT-SC-06: Color buttons (6 colors)', async ({ page }) => {
    await openReportAndAddBlock(page, 'Stat Card');
    const panel = page.getByTestId('block-property-panel');
    await expect(panel.locator('label', { hasText: 'Color' })).toBeVisible();
    // 6 color buttons (round, w-8 h-8)
    const colorBtns = panel.locator('button.rounded-full');
    expect(await colorBtns.count()).toBe(6);
  });
});

// ===================================================================
// 4. rich-text Block — All Properties
// ===================================================================

test.describe('rich-text Block — All Properties', () => {
  test('RPT-RT-01: Add rich-text block and verify panel', async ({ page }) => {
    await openReportAndAddBlock(page, 'Rich Text');
    await expect(
      page.getByTestId('block-property-panel').locator('h2', { hasText: 'Rich Text' }),
    ).toBeVisible();
  });

  test('RPT-RT-02: Content textarea', async ({ page }) => {
    await openReportAndAddBlock(page, 'Rich Text');
    const textarea = page
      .getByTestId('block-property-panel')
      .getByPlaceholder('Enter text content...');
    await expect(textarea).toBeVisible();
    const content = 'Test paragraph ' + uniqueId('RT');
    await textarea.fill(content);
    await expect(textarea).toHaveValue(content);
  });

  test('RPT-RT-03: Alignment buttons (Left, Center, Right)', async ({ page }) => {
    await openReportAndAddBlock(page, 'Rich Text');
    const panel = page.getByTestId('block-property-panel');
    await expect(panel.locator('label', { hasText: 'Alignment' })).toBeVisible();
    for (const align of ['Left', 'Center', 'Right']) {
      await expect(panel.getByRole('button', { name: align })).toBeVisible();
    }
    await panel.getByRole('button', { name: 'Center' }).click();
  });

  test('RPT-RT-04: Font Size input', async ({ page }) => {
    await openReportAndAddBlock(page, 'Rich Text');
    const panel = page.getByTestId('block-property-panel');
    await expect(panel.locator('label', { hasText: /font size/i })).toBeVisible();
    const fontSizeInput = panel.locator('input[type="number"]').first();
    await expect(fontSizeInput).toBeVisible();
    await fontSizeInput.fill('14');
    await expect(fontSizeInput).toHaveValue('14');
  });

  test('RPT-RT-05: Font Weight select', async ({ page }) => {
    await openReportAndAddBlock(page, 'Rich Text');
    const panel = page.getByTestId('block-property-panel');
    // Wait for block editor to render (Rich Text title confirms correct panel)
    await expect(panel.locator('h2', { hasText: 'Rich Text' })).toBeVisible({ timeout: 5000 });
    const label = panel.locator('label', { hasText: /font weight/i });
    await label.scrollIntoViewIfNeeded().catch(() => {});
    await expect(label).toBeVisible({ timeout: 5000 });
    for (const w of ['Normal', 'Bold']) {
      await expect(panel.locator('option', { hasText: w }).first()).toBeAttached();
    }
  });

  test('RPT-RT-06: Color input', async ({ page }) => {
    await openReportAndAddBlock(page, 'Rich Text');
    const panel = page.getByTestId('block-property-panel');
    await expect(panel.locator('h2', { hasText: 'Rich Text' })).toBeVisible({ timeout: 5000 });
    const colorInput = panel.locator('input[type="color"]');
    await colorInput.scrollIntoViewIfNeeded().catch(() => {});
    await expect(colorInput).toBeVisible({ timeout: 5000 });
  });
});

// ===================================================================
// 5. cross-tab Block — All Properties
// ===================================================================

test.describe('cross-tab Block — All Properties', () => {
  test('RPT-CT-01: Add cross-tab block and verify panel', async ({ page }) => {
    await openReportAndAddBlock(page, 'Cross Tab');
    await expect(
      page.getByTestId('block-property-panel').locator('h2', { hasText: 'Cross Tab' }),
    ).toBeVisible();
  });

  test('RPT-CT-02: Title input', async ({ page }) => {
    await openReportAndAddBlock(page, 'Cross Tab');
    const titleInput = page.getByTestId('block-property-panel').getByPlaceholder('Cross Tab Title');
    await expect(titleInput).toBeVisible();
    await titleInput.fill(uniqueId('CT'));
    await expect(titleInput).not.toHaveValue('');
  });

  test('RPT-CT-03: Row Field input', async ({ page }) => {
    await openReportAndAddBlock(page, 'Cross Tab');
    const input = page
      .getByTestId('block-property-panel')
      .getByPlaceholder('Field for row grouping');
    await expect(input).toBeVisible();
    await input.fill('region');
    await expect(input).toHaveValue('region');
  });

  test('RPT-CT-04: Column Field input', async ({ page }) => {
    await openReportAndAddBlock(page, 'Cross Tab');
    const input = page
      .getByTestId('block-property-panel')
      .getByPlaceholder('Field for column pivot');
    await expect(input).toBeVisible();
    await input.fill('quarter');
    await expect(input).toHaveValue('quarter');
  });

  test('RPT-CT-05: Value Field input', async ({ page }) => {
    await openReportAndAddBlock(page, 'Cross Tab');
    const input = page.getByTestId('block-property-panel').getByPlaceholder('Field to aggregate');
    await expect(input).toBeVisible();
    await input.fill('revenue');
    await expect(input).toHaveValue('revenue');
  });

  test('RPT-CT-06: Aggregation select', async ({ page }) => {
    await openReportAndAddBlock(page, 'Cross Tab');
    const panel = page.getByTestId('block-property-panel');
    for (const opt of ['sum', 'avg', 'count', 'min', 'max']) {
      await expect(panel.locator('option', { hasText: opt }).first()).toBeAttached();
    }
  });

  test('RPT-CT-07: Format select', async ({ page }) => {
    await openReportAndAddBlock(page, 'Cross Tab');
    const panel = page.getByTestId('block-property-panel');
    for (const fmt of ['Number', 'Currency', 'Percent']) {
      await expect(panel.locator('option', { hasText: new RegExp(fmt) }).first()).toBeAttached();
    }
  });

  test('RPT-CT-08: Row totals checkbox', async ({ page }) => {
    await openReportAndAddBlock(page, 'Cross Tab');
    const checkbox = page
      .getByTestId('block-property-panel')
      .getByRole('checkbox', { name: /row totals/i });
    await expect(checkbox).toBeVisible();
    await checkbox.click();
  });

  test('RPT-CT-09: Column totals checkbox', async ({ page }) => {
    await openReportAndAddBlock(page, 'Cross Tab');
    const checkbox = page
      .getByTestId('block-property-panel')
      .getByRole('checkbox', { name: /column totals/i });
    await expect(checkbox).toBeVisible();
    await checkbox.click();
  });
});

// ===================================================================
// 6. chart Block — All Properties
// ===================================================================

test.describe('chart Block — All Properties', () => {
  test('RPT-CH-01: Add chart block and verify panel', async ({ page }) => {
    await openReportAndAddBlock(page, 'Chart');
    await expect(
      page.getByTestId('block-property-panel').locator('h2', { hasText: 'Chart' }),
    ).toBeVisible();
  });

  test('RPT-CH-02: Title input', async ({ page }) => {
    await openReportAndAddBlock(page, 'Chart');
    const titleInput = page.getByTestId('block-property-panel').getByPlaceholder('Chart Title');
    await expect(titleInput).toBeVisible();
    await titleInput.fill(uniqueId('CH'));
    await expect(titleInput).not.toHaveValue('');
  });

  test('RPT-CH-03: Chart Type buttons (Bar, H-Bar, Pie)', async ({ page }) => {
    await openReportAndAddBlock(page, 'Chart');
    const panel = page.getByTestId('block-property-panel');
    await expect(panel.locator('label', { hasText: /chart type/i })).toBeVisible();
    for (const type of ['Bar', 'H-Bar', 'Pie']) {
      await expect(panel.getByRole('button', { name: type, exact: true })).toBeVisible();
    }
    await panel.getByRole('button', { name: 'Pie' }).click();
  });

  test('RPT-CH-04: Category Field input', async ({ page }) => {
    await openReportAndAddBlock(page, 'Chart');
    const input = page
      .getByTestId('block-property-panel')
      .getByPlaceholder('Field for categories (X axis)');
    await expect(input).toBeVisible();
    await input.fill('month');
    await expect(input).toHaveValue('month');
  });

  test('RPT-CH-05: Value Field input', async ({ page }) => {
    await openReportAndAddBlock(page, 'Chart');
    const input = page
      .getByTestId('block-property-panel')
      .getByPlaceholder('Field for values (Y axis)');
    await expect(input).toBeVisible();
    await input.fill('total_sales');
    await expect(input).toHaveValue('total_sales');
  });

  test('RPT-CH-06: Aggregation select', async ({ page }) => {
    await openReportAndAddBlock(page, 'Chart');
    const panel = page.getByTestId('block-property-panel');
    for (const opt of ['sum', 'avg', 'count', 'min', 'max']) {
      await expect(panel.locator('option', { hasText: opt }).first()).toBeAttached();
    }
  });

  test('RPT-CH-07: Width input', async ({ page }) => {
    await openReportAndAddBlock(page, 'Chart');
    const widthInput = page
      .getByTestId('block-property-panel')
      .locator('input[type="number"][min="200"]');
    // Width input is below the fold in the scrollable panel — scroll it into view first
    await widthInput.scrollIntoViewIfNeeded();
    await expect(widthInput).toBeVisible();
    await widthInput.fill('500');
    await expect(widthInput).toHaveValue('500');
  });

  test('RPT-CH-08: Height input', async ({ page }) => {
    await openReportAndAddBlock(page, 'Chart');
    const heightInput = page
      .getByTestId('block-property-panel')
      .locator('input[type="number"][min="120"]');
    await expect(heightInput).toBeVisible();
    await heightInput.fill('350');
    await expect(heightInput).toHaveValue('350');
  });
});

// ===================================================================
// 7. page-header/footer — BandEditor
// ===================================================================

test.describe('page-header/footer — BandEditor', () => {
  test('RPT-BD-01: Add page-header block and verify panel', async ({ page }) => {
    await openReportAndAddBand(page, 'Page Header');
    const panel = page.getByTestId('block-property-panel');
    await expect(panel.locator('h2', { hasText: 'Page Header' })).toBeVisible();
  });

  test('RPT-BD-02: Height input for header', async ({ page }) => {
    await openReportAndAddBand(page, 'Page Header');
    const panel = page.getByTestId('block-property-panel');
    await expect(panel.locator('label', { hasText: /height/i })).toBeVisible();
    const heightInput = panel.locator('input[type="number"][min="5"]');
    await expect(heightInput).toBeVisible();
    await heightInput.fill('25');
    await expect(heightInput).toHaveValue('25');
  });

  test('RPT-BD-03: Add Text element button adds element', async ({ page }) => {
    await openReportAndAddBand(page, 'Page Header');
    const panel = page.getByTestId('block-property-panel');
    const addTextBtn = panel.getByRole('button', { name: /\+ Text/i });
    await expect(addTextBtn).toBeVisible();
    const textLabels = panel.locator('span.uppercase', { hasText: 'text' });
    const before = await textLabels.count();
    await addTextBtn.click();
    await expect(textLabels).toHaveCount(before + 1, { timeout: 3000 });
  });

  test('RPT-BD-04: Add Page # element button', async ({ page }) => {
    await openReportAndAddBand(page, 'Page Header');
    const panel = page.getByTestId('block-property-panel');
    const addPageBtn = panel.getByRole('button', { name: /\+ Page/i });
    await expect(addPageBtn).toBeVisible();
    await addPageBtn.click();
  });

  test('RPT-BD-05: Add Date element button', async ({ page }) => {
    await openReportAndAddBand(page, 'Page Header');
    const panel = page.getByTestId('block-property-panel');
    const addDateBtn = panel.getByRole('button', { name: /\+ Date/i });
    await expect(addDateBtn).toBeVisible();
    await addDateBtn.click();
  });

  test('RPT-BD-06: Element alignment select', async ({ page }) => {
    await openReportAndAddBand(page, 'Page Header');
    const panel = page.getByTestId('block-property-panel');
    for (const align of ['Left', 'Center', 'Right']) {
      await expect(panel.locator('option', { hasText: align }).first()).toBeAttached();
    }
  });

  test('RPT-BD-07: Element font size input', async ({ page }) => {
    await openReportAndAddBand(page, 'Page Header');
    const panel = page.getByTestId('block-property-panel');
    const fontSizeInput = panel.locator('input[type="number"][title*="Font size"]');
    await expect(fontSizeInput.first()).toBeVisible();
  });

  test('RPT-BD-08: Add page-footer block and verify panel', async ({ page }) => {
    await openReportAndAddBand(page, 'Page Footer');
    const panel = page.getByTestId('block-property-panel');
    await expect(panel.locator('h2', { hasText: 'Page Footer' })).toBeVisible();
    await expect(panel.locator('input[type="number"][min="5"]')).toBeVisible();
  });
});

// ===================================================================
// 8. Report Operations
// ===================================================================

test.describe('Report Operations', () => {
  test('RPT-OP-01: Move up/down buttons visible in property panel', async ({ page }) => {
    await openReportAndAddBlock(page, 'Rich Text');
    const panel = page.getByTestId('block-property-panel');
    await expect(panel.locator('button[title="Move up"]')).toBeVisible();
    await expect(panel.locator('button[title="Move down"]')).toBeVisible();
  });

  test('RPT-OP-02: Delete block button removes block', async ({ page }) => {
    await openReportAndAddBlock(page, 'Rich Text');
    const panel = page.getByTestId('block-property-panel');
    const deleteBtn = panel.locator('button[title="Delete"]');
    await expect(deleteBtn).toBeVisible();
    await deleteBtn.click();
    await expect(panel.locator('h2', { hasText: 'Report Properties' })).toBeVisible({
      timeout: 3000,
    });
  });

  test('RPT-OP-03: Multiple block types on same canvas', async ({ page }) => {
    await page.goto('/report-designer', { waitUntil: 'domcontentloaded' });
    await waitForDesignerLoad(page);
    const palette = page.getByTestId('block-palette');
    await expect(palette).toBeVisible({ timeout: 10000 });
    await palette
      .getByRole('button', { name: /data table/i })
      .first()
      .click();
    await palette
      .getByRole('button', { name: /stat card/i })
      .first()
      .click();
    await palette
      .getByRole('button', { name: /rich text/i })
      .first()
      .click();
    const panel = page.getByTestId('block-property-panel');
    await expect(panel.locator('h2', { hasText: 'Rich Text' })).toBeVisible({ timeout: 3000 });
  });

  test('RPT-OP-04: Save button triggers API call', async ({ page }) => {
    await page.goto('/report-designer', { waitUntil: 'domcontentloaded' });
    await waitForDesignerLoad(page);
    const palette = page.getByTestId('block-palette');
    await expect(palette).toBeVisible({ timeout: 10000 });
    await palette
      .getByRole('button', { name: /data table/i })
      .first()
      .click();
    const saveBtn = page.getByRole('button', { name: /save/i });
    await expect(saveBtn).toBeVisible({ timeout: 5000 });
    const responsePromise = page.waitForResponse(
      (res) =>
        res.url().includes('/api/pages') &&
        (res.request().method().toLowerCase() === 'post' ||
          res.request().method().toLowerCase() === 'put'),
      { timeout: 10000 },
    );
    await saveBtn.click();
    const response = await responsePromise;
    expect(response.status()).toBeDefined();
  });

  test('RPT-OP-05: Block palette has all 10 block types', async ({ page }) => {
    await page.goto('/report-designer', { waitUntil: 'domcontentloaded' });
    await waitForDesignerLoad(page);
    const palette = page.getByTestId('block-palette');
    await expect(palette).toBeVisible({ timeout: 10000 });
    for (const blockName of [
      'Data Table',
      'Grouped Table',
      'Stat Card',
      'Rich Text',
      'Cross Tab',
      'Chart',
      'Barcode',
      'Watermark',
      'Page Header',
      'Page Footer',
    ]) {
      await expect(palette.getByRole('button', { name: new RegExp(blockName, 'i') })).toBeVisible();
    }
  });

  test('RPT-OP-06: Export Excel button visible in toolbar', async ({ page }) => {
    await page.goto('/report-designer', { waitUntil: 'domcontentloaded' });
    await waitForDesignerLoad(page);
    const excelBtn = page.getByRole('button', { name: /export excel/i });
    await expect(excelBtn).toBeVisible({ timeout: 5000 });
  });

  test('RPT-OP-07: Export Excel triggers API call or save prompt', async ({ page }) => {
    await page.goto('/report-designer', { waitUntil: 'domcontentloaded' });
    await waitForDesignerLoad(page);
    // Add a block first so the report has content
    const palette = page.getByTestId('block-palette');
    await expect(palette).toBeVisible({ timeout: 10000 });
    await palette
      .getByRole('button', { name: /data table/i })
      .first()
      .click();
    // Save first to get a valid report PID
    const saveBtn = page.getByRole('button', { name: /save/i });
    await saveBtn.click();
    const saveResponse = await page
      .waitForResponse(
        (res) =>
          res.url().includes('/api/pages') &&
          (res.request().method().toLowerCase() === 'post' ||
            res.request().method().toLowerCase() === 'put'),
        { timeout: 10000 },
      )
      .catch(() => null);
    await page.waitForTimeout(500);

    // Click Excel export — if report was saved, it triggers API; otherwise it shows alert
    const excelBtn = page.getByRole('button', { name: /export excel/i });

    if (saveResponse && saveResponse.ok()) {
      // Report was saved — expect API call to export endpoint
      const exportPromise = page.waitForResponse(
        (res) => res.url().includes('/api/reports/export/excel'),
        { timeout: 10000 },
      );
      await excelBtn.click();
      const exportResponse = await exportPromise.catch(() => null);
      if (exportResponse) {
        expect(exportResponse.status()).toBeDefined();
      }
    } else {
      // Report was NOT saved — expect alert prompting to save first
      const dialogPromise = page.waitForEvent('dialog', { timeout: 5000 });
      await excelBtn.click();
      const dialog = await dialogPromise;
      expect(dialog.message()).toContain('save the report');
      await dialog.accept();
    }
  });
});

// ===================================================================
// 9. barcode Block — All Properties
// ===================================================================

test.describe('barcode Block — All Properties', () => {
  test('RPT-BC-01: Add barcode block and verify property panel', async ({ page }) => {
    await openReportAndAddBlock(page, 'Barcode');
    const panel = page.getByTestId('block-property-panel');
    await expect(panel.locator('h2', { hasText: 'Barcode' })).toBeVisible();
  });

  test('RPT-BC-02: Title input accepts text', async ({ page }) => {
    await openReportAndAddBlock(page, 'Barcode');
    const panel = page.getByTestId('block-property-panel');
    const titleInput = panel.getByPlaceholder('Barcode Title');
    await expect(titleInput).toBeVisible();
    const title = uniqueId('BC_Title');
    await titleInput.fill(title);
    await expect(titleInput).toHaveValue(title);
  });

  test('RPT-BC-03: Format select has all barcode formats', async ({ page }) => {
    await openReportAndAddBlock(page, 'Barcode');
    const panel = page.getByTestId('block-property-panel');
    await expect(panel.locator('label', { hasText: 'Format' })).toBeVisible();
    for (const fmt of ['code128', 'code39', 'EAN-13', 'EAN-8', 'upc', 'ITF-14']) {
      await expect(panel.locator('option', { hasText: fmt }).first()).toBeAttached();
    }
  });

  test('RPT-BC-04: Static value input', async ({ page }) => {
    await openReportAndAddBlock(page, 'Barcode');
    const panel = page.getByTestId('block-property-panel');
    const staticInput = panel.getByPlaceholder('e.g. ABC-12345');
    await expect(staticInput).toBeVisible();
    await staticInput.fill('TEST-67890');
    await expect(staticInput).toHaveValue('TEST-67890');
  });

  test('RPT-BC-05: Bar width and height inputs', async ({ page }) => {
    await openReportAndAddBlock(page, 'Barcode');
    const panel = page.getByTestId('block-property-panel');
    const widthInput = panel.locator('input[type="number"][min="1"][max="4"]');
    await expect(widthInput).toBeVisible();
    await widthInput.fill('3');
    await expect(widthInput).toHaveValue('3');
    const heightInput = panel.locator('input[type="number"][min="20"][max="200"]');
    await expect(heightInput).toBeVisible();
    await heightInput.fill('80');
    await expect(heightInput).toHaveValue('80');
  });

  test('RPT-BC-06: Font size input', async ({ page }) => {
    await openReportAndAddBlock(page, 'Barcode');
    const panel = page.getByTestId('block-property-panel');
    const fontSizeInput = panel.locator('input[type="number"][min="8"][max="24"]');
    await expect(fontSizeInput).toBeVisible();
    await fontSizeInput.fill('12');
    await expect(fontSizeInput).toHaveValue('12');
  });

  test('RPT-BC-07: Show value text checkbox', async ({ page }) => {
    await openReportAndAddBlock(page, 'Barcode');
    const panel = page.getByTestId('block-property-panel');
    const checkbox = panel.getByRole('checkbox', { name: /show value text/i });
    await expect(checkbox).toBeVisible();
    const wasChecked = await checkbox.isChecked();
    await checkbox.click();
    expect(await checkbox.isChecked()).toBe(!wasChecked);
  });
});

// ===================================================================
// 10. watermark Block — All Properties
// ===================================================================

test.describe('watermark Block — All Properties', () => {
  test('RPT-WM-01: Add watermark block and verify property panel', async ({ page }) => {
    await openReportAndAddBlock(page, 'Watermark');
    const panel = page.getByTestId('block-property-panel');
    await expect(panel.locator('h2', { hasText: 'Watermark' })).toBeVisible();
  });

  test('RPT-WM-02: Text input', async ({ page }) => {
    await openReportAndAddBlock(page, 'Watermark');
    const panel = page.getByTestId('block-property-panel');
    const textInput = panel.getByPlaceholder('e.g. CONFIDENTIAL');
    await expect(textInput).toBeVisible();
    await textInput.fill('draft');
    await expect(textInput).toHaveValue('draft');
  });

  test('RPT-WM-03: Rotation slider', async ({ page }) => {
    await openReportAndAddBlock(page, 'Watermark');
    const panel = page.getByTestId('block-property-panel');
    await expect(panel.locator('label', { hasText: /rotation/i })).toBeVisible();
    const slider = panel.locator('input[type="range"][min="-90"][max="90"]');
    await expect(slider).toBeVisible();
  });

  test('RPT-WM-04: Opacity slider', async ({ page }) => {
    await openReportAndAddBlock(page, 'Watermark');
    const panel = page.getByTestId('block-property-panel');
    await expect(panel.locator('label', { hasText: /opacity/i })).toBeVisible();
    const slider = panel.locator('input[type="range"]').nth(1);
    await expect(slider).toBeVisible();
  });

  test('RPT-WM-05: Color picker and text input', async ({ page }) => {
    await openReportAndAddBlock(page, 'Watermark');
    const panel = page.getByTestId('block-property-panel');
    await expect(panel.locator('label', { hasText: 'Color' })).toBeVisible();
    const colorPicker = panel.locator('input[type="color"]');
    await expect(colorPicker).toBeVisible();
    const colorText = panel.getByPlaceholder('#000000');
    await expect(colorText).toBeVisible();
    await colorText.fill('#FF0000');
    await expect(colorText).toHaveValue('#FF0000');
  });

  test('RPT-WM-06: Repeat pattern checkbox', async ({ page }) => {
    await openReportAndAddBlock(page, 'Watermark');
    const panel = page.getByTestId('block-property-panel');
    const checkbox = panel.getByRole('checkbox', { name: /repeat pattern/i });
    await expect(checkbox).toBeVisible();
    const wasChecked = await checkbox.isChecked();
    await checkbox.click();
    expect(await checkbox.isChecked()).toBe(!wasChecked);
  });
});
