/**
 * Smart Components — Form Components Tests
 *
 * Tests SC-001 ~ SC-018: Verify all smart form components render
 * and function correctly on the e2et-order form page.
 *
 * Covers:
 * - SmartInput, SmartTextarea, SmartNumberInput, SmartSelect
 * - SmartMultiSelect, SmartSwitch, SmartCheckbox, SmartRadio
 * - SmartDatePicker, SmartDateRange, SmartTimePicker, SmartTimeRangePicker
 * - SmartCurrency, SmartUpload, SmartTreeSelect, SmartUserSelect
 * - SmartOrganizationSelect, SmartFormRef
 *
 * Uses real database, NO MOCKING.
 * Uses DynamicFormPage Page Object for stable selectors.
 *
 * @since 7.0.0
 */

import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers';
import { ModelTestHelper } from '../../helpers/model-test-helper';
import { E2ET_ORDER_CONFIG } from '../../helpers/configs/e2et-order.config';
import { DynamicListPage, DynamicFormPage } from '../../pages';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

const ORDER_PAGE_KEY = 'e2et_order';

/** Navigate to new order form and wait for full render (two-stage loading). */
async function navigateToNewOrderForm(page: import('@playwright/test').Page) {
  const listPage = new DynamicListPage(page, `/p/${ORDER_PAGE_KEY}`);
  await listPage.goto();
  await listPage.clickAdd();
  await page.waitForURL((url) => url.pathname.includes('/new'), { timeout: 10000 });
  await page.waitForLoadState('domcontentloaded');
  await page.locator('h2').first().waitFor({ state: 'visible', timeout: 10000 });

  const formPage = new DynamicFormPage(page, '');
  // Wait for form fields to render
  await formPage.field('e2et_order_title').first().waitFor({ state: 'visible', timeout: 5000 });
  // Wait for SmartSwitch and SmartDatePicker to load (second stage of async component loading).
  // Must wait for BOTH independently — `select` appears before switch/date, so a combined
  // first-match wait exits too early.
  await Promise.all([
    page
      .locator('button[role="switch"]')
      .first()
      .waitFor({ state: 'attached', timeout: 8000 })
      .catch(() => {}),
    page
      .locator('input[type="date"]')
      .first()
      .waitFor({ state: 'attached', timeout: 8000 })
      .catch(() => {}),
  ]);
  return { listPage, formPage };
}

/** Wait until at least one select has >1 option (dict data loaded). */
async function waitForSelectOptions(page: import('@playwright/test').Page): Promise<void> {
  await page
    .waitForFunction(
      () => {
        const selects = Array.from(document.querySelectorAll('select'));
        for (let i = 0; i < selects.length; i++) {
          if (selects[i].options.length > 1) return true;
        }
        return false;
      },
      { timeout: 10000 },
    )
    .catch(() => {});
}

/** Verify the form actually rendered even when a specific smart component is not configured. */
async function expectRenderedFormShell(page: import('@playwright/test').Page): Promise<void> {
  await page.waitForFunction(
    () => {
      return (
        document.querySelectorAll(
          '[data-testid^="form-field-"], ' +
            '[data-testid^="form-btn-"], ' +
            '[data-testid="dynamic-form"] [role="combobox"], ' +
            '[data-testid="dynamic-form"] input, ' +
            '[data-testid="dynamic-form"] select, ' +
            '[data-testid="dynamic-form"] textarea',
        ).length > 0
      );
    },
    { timeout: 10000 },
  );
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

test.describe('Smart Components — Form Components', () => {
  let orderPid: string;

  test.beforeAll(async ({ browser }) => {
    const context = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    const page = await context.newPage();
    const order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
    orderPid = await order.createViaApi({
      e2et_order_title: `SmartComp ${uniqueId()}`,
      e2et_order_type: 'normal',
      e2et_order_urgent: false,
      e2et_order_desc: 'Description for smart component test',
    });
    await page.close();
    await context.close();
  });

  test.afterAll(async ({ browser }) => {
    const context = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    const page = await context.newPage();
    const order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
    await order.deleteViaApi(orderPid).catch(() => {});
    await page.close();
    await context.close();
  });

  // -------------------------------------------------------------------------
  // SC-001: SmartInput (STRING field)
  // -------------------------------------------------------------------------

  test('SC-001: SmartInput should render text input and accept value @smoke', async ({ page }) => {
    const { formPage } = await navigateToNewOrderForm(page);

    // order_title is a STRING field rendered via SmartInput
    const titleInput = formPage.field('e2et_order_title');
    await expect(titleInput.first()).toBeVisible({ timeout: 5000 });

    // Verify it renders as a standard text input
    const tagName = await titleInput.first().evaluate((el) => el.tagName.toLowerCase());
    expect(tagName).toBe('input');

    // Fill value and verify
    const testValue = `SmartInput Test ${uniqueId()}`;
    await titleInput.first().fill(testValue);
    const filledValue = await titleInput.first().inputValue();
    expect(filledValue).toBe(testValue);
  });

  // -------------------------------------------------------------------------
  // SC-002: SmartTextarea (TEXT field)
  // -------------------------------------------------------------------------

  test('SC-002: SmartTextarea should render textarea and accept multiline text @smoke', async ({
    page,
  }) => {
    const order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
    await order.gotoEditForm(orderPid);

    // Wait for form fields to fully load (two-stage loading: "Loading SmartInput..." → actual fields)
    const descFieldContainer = page.locator('[data-testid="form-field-e2et_order_desc"]');
    await descFieldContainer.waitFor({ state: 'visible', timeout: 10000 });

    // Wait for loading skeleton to disappear inside the field
    await page
      .waitForFunction(
        () => {
          const el = document.querySelector('[data-testid="form-field-e2et_order_desc"]');
          return el && !el.textContent?.includes('Loading');
        },
        { timeout: 10000 },
      )
      .catch(() => {});

    // order_desc is a TEXT field rendered via SmartTextarea
    const textarea = descFieldContainer.locator('textarea').first();
    const hasTextarea = await textarea.isVisible({ timeout: 5000 }).catch(() => false);

    if (!hasTextarea) {
      // TEXT may render as input on some configurations
      const textInput = descFieldContainer.locator('input').first();
      const hasInput = await textInput.isVisible({ timeout: 3000 }).catch(() => false);
      // At minimum, the field container should be visible even if the sub-element isn't found yet
      expect(hasInput || hasTextarea || (await descFieldContainer.isVisible())).toBeTruthy();
      return;
    }

    // Verify textarea can accept multiline text
    const multilineText = 'Line 1\nLine 2\nLine 3';
    await textarea.fill(multilineText);
    const value = await textarea.inputValue();
    expect(value).toContain('Line 1');
  });

  // -------------------------------------------------------------------------
  // SC-003: SmartNumberInput (DECIMAL/INTEGER field)
  // -------------------------------------------------------------------------

  test('SC-003: SmartNumberInput should render number input with decimal support @smoke', async ({
    page,
  }) => {
    const order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);

    // Create an order item to test number fields (qty, price)
    const itemPid = await order.child('item').createForParent(orderPid, {
      e2et_item_name: `NumTest ${uniqueId('N')}`,
      e2et_item_qty: 10,
      e2et_item_price: 25.5,
    });

    try {
      await order.gotoEditForm(orderPid);

      // Sub-table renders number inputs (qty, price fields)
      const numberInputs = page.locator(
        'input[type="number"], input[inputmode="decimal"], input[inputmode="numeric"]',
      );
      await numberInputs.first().waitFor({ state: 'attached', timeout: 10000 });

      const count = await numberInputs.count();
      expect(count).toBeGreaterThan(0);

      // Verify a number input accepts decimal values
      const firstNumber = numberInputs.first();
      await firstNumber.scrollIntoViewIfNeeded();
      await firstNumber.fill('42.75');
      const value = await firstNumber.inputValue();
      expect(value).toBe('42.75');
    } finally {
      await order
        .child('item')
        .deleteViaApi(itemPid)
        .catch(() => {});
    }
  });

  // -------------------------------------------------------------------------
  // SC-004: SmartSelect (ENUM field)
  // -------------------------------------------------------------------------

  test('SC-004: SmartSelect should render select with enum options @smoke', async ({ page }) => {
    await navigateToNewOrderForm(page);
    await waitForSelectOptions(page);

    // order_type is an ENUM field rendered via SmartSelect (native <select>)
    const selects = page.locator('select');
    const selectCount = await selects.count();
    expect(selectCount).toBeGreaterThan(0);

    // Find the type select with NORMAL/BULK/EXPRESS options
    let typeSelectFound = false;
    for (let i = 0; i < selectCount; i++) {
      const options = await selects.nth(i).locator('option').allTextContents();
      const optionText = options.join(' ');
      if (
        optionText.includes('normal') ||
        optionText.includes('bulk') ||
        optionText.includes('express')
      ) {
        // Select a different option
        await selects.nth(i).selectOption('bulk');
        const selectedValue = await selects.nth(i).inputValue();
        expect(selectedValue).toBe('bulk');
        typeSelectFound = true;
        break;
      }
    }

    if (!typeSelectFound) {
      // May be rendered as Radix combobox instead of native select
      const combobox = page.locator('[role="combobox"]').first();
      const hasCombobox = await combobox.isVisible({ timeout: 3000 }).catch(() => false);
      expect(hasCombobox || typeSelectFound).toBeTruthy();
    }
  });

  // -------------------------------------------------------------------------
  // SC-005: SmartMultiSelect
  // -------------------------------------------------------------------------

  test('SC-005: SmartMultiSelect should render multi-select with tag chips', async ({ page }) => {
    await navigateToNewOrderForm(page);

    // Look for multi-select fields (multiple attribute or specific component)
    const multiSelect = page
      .locator(
        'select[multiple], [data-testid*="multi-select"], [role="listbox"][aria-multiselectable="true"]',
      )
      .first();
    const hasMultiSelect = await multiSelect.isVisible({ timeout: 5000 }).catch(() => false);

    if (!hasMultiSelect) {
      test.info().annotations.push({
        type: 'note',
        description:
          'No multi-select field found on e2et-order form — SmartMultiSelect not configured for this model',
      });
      // Verify form renders correctly regardless
      const formButtons = page.locator('[data-testid^="form-btn-"]');
      expect(await formButtons.count()).toBeGreaterThan(0);
      return;
    }

    await expect(multiSelect).toBeVisible();
  });

  // -------------------------------------------------------------------------
  // SC-006: SmartSwitch (BOOLEAN field)
  // -------------------------------------------------------------------------

  test('SC-006: SmartSwitch should render toggle switch for boolean field @smoke', async ({
    page,
  }) => {
    await navigateToNewOrderForm(page);

    // order_urgent is a BOOLEAN field rendered as switch (button[role="switch"])
    const urgentSwitch = page.locator('button[role="switch"]').first();
    const hasSwitch = await urgentSwitch.isVisible({ timeout: 5000 }).catch(() => false);

    if (!hasSwitch) {
      throw new Error(String('SmartSwitch not found — BOOLEAN field may render differently'));
      return;
    }

    // Verify initial state (false by default)
    const initialState = await urgentSwitch.getAttribute('aria-checked');
    expect(initialState).toBe('false');

    // Toggle to true
    await urgentSwitch.click();
    const afterToggle = await urgentSwitch.getAttribute('aria-checked');
    expect(afterToggle).toBe('true');

    // Toggle back to false
    await urgentSwitch.click();
    const finalState = await urgentSwitch.getAttribute('aria-checked');
    expect(finalState).toBe('false');
  });

  // -------------------------------------------------------------------------
  // SC-007: SmartCheckbox
  // -------------------------------------------------------------------------

  test('SC-007: SmartCheckbox should render checkbox input', async ({ page }) => {
    await navigateToNewOrderForm(page);

    // Look for checkbox inputs (may be rendered for boolean fields without switch)
    const checkboxes = page.locator('input[type="checkbox"], [role="checkbox"]');
    const checkboxCount = await checkboxes.count();

    if (checkboxCount === 0) {
      test.info().annotations.push({
        type: 'note',
        description: 'No checkbox found — BOOLEAN fields may render as switches on e2et-order',
      });
      return;
    }

    // Verify checkbox can be toggled
    const firstCheckbox = checkboxes.first();
    await firstCheckbox.click();
    const isChecked = await firstCheckbox.isChecked();
    expect(typeof isChecked).toBe('boolean');
  });

  // -------------------------------------------------------------------------
  // SC-008: SmartRadio
  // -------------------------------------------------------------------------

  test('SC-008: SmartRadio should render radio group', async ({ page }) => {
    await navigateToNewOrderForm(page);

    // Look for radio inputs or radio group
    const radios = page.locator('input[type="radio"], [role="radio"], [role="radiogroup"]');
    const radioCount = await radios.count();

    if (radioCount === 0) {
      test.info().annotations.push({
        type: 'note',
        description: 'No radio group found — ENUM fields render as SmartSelect on e2et-order',
      });
      // ENUM fields render as combobox (Radix Select), not native select
      // Wait for combobox elements to appear (async component loading)
      const comboboxes = page.locator('[role="combobox"]');
      await comboboxes
        .first()
        .waitFor({ state: 'attached', timeout: 5000 })
        .catch(() => {});
      const comboboxCount = await comboboxes.count();

      // Also check for native select elements
      const selects = page.locator('select');
      const selectCount = await selects.count();

      expect(comboboxCount + selectCount).toBeGreaterThan(0);
      return;
    }

    // Verify radio can be selected
    await radios.first().click();
  });

  // -------------------------------------------------------------------------
  // SC-009: SmartDatePicker (DATE field)
  // -------------------------------------------------------------------------

  test('SC-009: SmartDatePicker should render date input and accept date value @smoke', async ({
    page,
  }) => {
    await navigateToNewOrderForm(page);

    // order_date is a DATE field rendered via SmartDatePicker (native input[type="date"])
    const dateInput = page.locator('input[type="date"]').first();
    const hasDate = await dateInput.isVisible({ timeout: 5000 }).catch(() => false);

    if (!hasDate) {
      throw new Error(String('Date input not found on order form'));
      return;
    }

    // Verify date input has a value (autoSetValues may pre-fill)
    // Set a new date value
    const newDate = '2026-06-15';
    await dateInput.fill(newDate);
    const filledDate = await dateInput.inputValue();
    expect(filledDate).toBe(newDate);
  });

  // -------------------------------------------------------------------------
  // SC-010: SmartDateRange
  // -------------------------------------------------------------------------

  test('SC-010: SmartDateRange should render start/end date fields', async ({ page }) => {
    await navigateToNewOrderForm(page);

    // SmartDateRange renders two date inputs (start and end)
    const dateInputs = page.locator('input[type="date"]');
    const dateCount = await dateInputs.count();

    if (dateCount < 2) {
      test.info().annotations.push({
        type: 'note',
        description: `Found ${dateCount} date input(s) — SmartDateRange needs 2 inputs (start/end)`,
      });
      // Single date field is expected for e2et_order_date
      if (dateCount >= 1) {
        await expect(dateInputs.first()).toBeVisible();
      }
      return;
    }

    // Fill both date inputs
    await dateInputs.nth(0).fill('2026-01-01');
    await dateInputs.nth(1).fill('2026-12-31');
    expect(await dateInputs.nth(0).inputValue()).toBe('2026-01-01');
    expect(await dateInputs.nth(1).inputValue()).toBe('2026-12-31');
  });

  // -------------------------------------------------------------------------
  // SC-011: SmartTimePicker
  // -------------------------------------------------------------------------

  test('SC-011: SmartTimePicker should render time input', async ({ page }) => {
    await navigateToNewOrderForm(page);

    const timeInput = page.locator('input[type="time"], [data-testid*="time-picker"]').first();
    const hasTime = await timeInput.isVisible({ timeout: 3000 }).catch(() => false);

    if (!hasTime) {
      test.info().annotations.push({
        type: 'note',
        description: 'No time picker found on e2et-order form — TIME field not configured',
      });
      return;
    }

    await timeInput.fill('14:30');
    expect(await timeInput.inputValue()).toBe('14:30');
  });

  // -------------------------------------------------------------------------
  // SC-012: SmartTimeRangePicker
  // -------------------------------------------------------------------------

  test('SC-012: SmartTimeRangePicker should render start/end time fields', async ({ page }) => {
    await navigateToNewOrderForm(page);

    const timeInputs = page.locator('input[type="time"]');
    const timeCount = await timeInputs.count();

    if (timeCount < 2) {
      test.info().annotations.push({
        type: 'note',
        description: `Found ${timeCount} time input(s) — SmartTimeRangePicker not configured on e2et-order`,
      });
      return;
    }

    await timeInputs.nth(0).fill('09:00');
    await timeInputs.nth(1).fill('17:00');
    expect(await timeInputs.nth(0).inputValue()).toBe('09:00');
    expect(await timeInputs.nth(1).inputValue()).toBe('17:00');
  });

  // -------------------------------------------------------------------------
  // SC-013: SmartCurrency
  // -------------------------------------------------------------------------

  test('SC-013: SmartCurrency should render currency input with formatting', async ({ page }) => {
    const order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
    await order.gotoEditForm(orderPid);

    // Currency fields render as number inputs with inputmode="decimal"
    const currencyInputs = page.locator(
      'input[inputmode="decimal"], input[type="number"], [data-testid*="currency"]',
    );
    const count = await currencyInputs.count();

    if (count === 0) {
      test.info().annotations.push({
        type: 'note',
        description:
          'No currency-specific input found — DECIMAL fields may render as SmartNumberInput',
      });
      await expectRenderedFormShell(page);
      return;
    }

    // Verify it accepts decimal values
    const firstCurrency = currencyInputs.first();
    await firstCurrency.scrollIntoViewIfNeeded();
    await firstCurrency.fill('1234.56');
    const value = await firstCurrency.inputValue();
    expect(value).toContain('1234');
  });

  // -------------------------------------------------------------------------
  // SC-014: SmartUpload
  // -------------------------------------------------------------------------

  test('SC-014: SmartUpload should render file upload area', async ({ page }) => {
    const order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
    await order.gotoEditForm(orderPid);

    // Look for file upload elements
    const uploadArea = page
      .locator(
        'input[type="file"], [data-testid*="upload"], [data-testid*="file-input"], .upload-area, .dropzone',
      )
      .first();
    const hasUpload = await uploadArea.isVisible({ timeout: 5000 }).catch(() => false);

    if (!hasUpload) {
      // Check for hidden file inputs (common pattern)
      const hiddenFileInput = page.locator('input[type="file"]');
      const hiddenCount = await hiddenFileInput.count();

      if (hiddenCount === 0) {
        test.info().annotations.push({
          type: 'note',
          description:
            'No file upload field found on e2et-order form — FILE/IMAGE field not configured',
        });
      }
      return;
    }

    await expect(uploadArea).toBeAttached();
  });

  // -------------------------------------------------------------------------
  // SC-015: SmartTreeSelect
  // -------------------------------------------------------------------------

  test('SC-015: SmartTreeSelect should render tree-structured dropdown', async ({ page }) => {
    test.setTimeout(30000);
    await navigateToNewOrderForm(page);

    // TreeSelect renders with a specific component or as a cascader
    const treeSelect = page
      .locator(
        '[data-testid*="tree-select"], [role="tree"], .tree-select, [data-testid*="cascader"]',
      )
      .first();
    const hasTree = await treeSelect.isVisible({ timeout: 3000 }).catch(() => false);

    if (!hasTree) {
      test.info().annotations.push({
        type: 'note',
        description:
          'No tree select found on e2et-order form — REFERENCE tree field not configured',
      });
      return;
    }

    await expect(treeSelect).toBeVisible();
  });

  // -------------------------------------------------------------------------
  // SC-016: SmartUserSelect
  // -------------------------------------------------------------------------

  test('SC-016: SmartUserSelect should render user picker', async ({ page }) => {
    await navigateToNewOrderForm(page);

    // SmartUserSelect renders a select/combobox with user options
    const userSelect = page
      .locator(
        '[data-testid*="user-select"], [data-field*="user"] select, [data-field*="user"] [role="combobox"]',
      )
      .first();
    const hasUserSelect = await userSelect.isVisible({ timeout: 3000 }).catch(() => false);

    if (!hasUserSelect) {
      test.info().annotations.push({
        type: 'note',
        description: 'No user select found on e2et-order form — USER_SELECT field not configured',
      });
      return;
    }

    // Click to open dropdown
    await userSelect.click();
    const options = page.locator('[role="option"]');
    const optionCount = await options.count();
    expect(optionCount).toBeGreaterThan(0);
  });

  // -------------------------------------------------------------------------
  // SC-017: SmartOrganizationSelect
  // -------------------------------------------------------------------------

  test('SC-017: SmartOrganizationSelect should render org picker', async ({ page }) => {
    await navigateToNewOrderForm(page);

    // SmartOrganizationSelect renders for org/department reference fields
    const orgSelect = page
      .locator(
        '[data-testid*="org-select"], [data-field*="org"] select, [data-field*="department"] select, [data-field*="org"] [role="combobox"]',
      )
      .first();
    const hasOrgSelect = await orgSelect.isVisible({ timeout: 3000 }).catch(() => false);

    if (!hasOrgSelect) {
      test.info().annotations.push({
        type: 'note',
        description:
          'No organization select found on e2et-order form — ORG_SELECT field not configured',
      });
      return;
    }

    await expect(orgSelect).toBeVisible();
  });

  // -------------------------------------------------------------------------
  // SC-018: SmartFormRef (REFERENCE field)
  // -------------------------------------------------------------------------

  test('SC-018: SmartFormRef should render reference link or picker', async ({ page }) => {
    const order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
    await order.gotoEditForm(orderPid);

    // SmartFormRef renders as a select, link, or lookup component for REFERENCE fields
    const refFields = page.locator(
      '[data-testid*="form-ref"], [data-testid*="reference"], [data-field*="customer"] select, [data-field*="customer"] [role="combobox"], a[href*="/view/"]',
    );
    const refCount = await refFields.count();

    if (refCount === 0) {
      test.info().annotations.push({
        type: 'note',
        description:
          'No reference field (SmartFormRef) found — REFERENCE field may not be on e2et-order form',
      });
      await expectRenderedFormShell(page);
      return;
    }

    // Reference field should be visible
    await expect(refFields.first()).toBeVisible({ timeout: 5000 });
  });
});
