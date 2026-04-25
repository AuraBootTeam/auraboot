/**
 * Showcase Smart Component Fields — Deep Interaction E2E Tests
 *
 * Focuses on interactive behaviors of Smart Components that go beyond
 * "field renders" (covered by showcase-smart-components.spec.ts).
 * Each test exercises the component's unique interaction model:
 * cascade drill-down, tree expand/select, user search, date range
 * validation, money formatting, and address-like cascade.
 *
 * Coverage dimensions (component-interaction focused):
 *   D1  Menu Navigation — sidebar click, NOT page.goto
 *   D4  Create Full Form — fill Smart Component fields via multi-step interaction
 *   D5  Form Field Types — verify component renders correct widget type
 *   D6  Create Verification — new record values confirmed via API
 *   D14 Toast / Feedback — mutation shows success feedback
 *
 * Not applicable (no state machine on showcase_all_fields):
 *   D3, D9, D10 — no status tabs / transitions
 *
 * @since 11.0.0
 */

import { test, expect, type Page } from '../../fixtures';
import {
  uniqueId,
  todayStr,
  dateOffsetStr,
  executeCommandViaApi,
  waitForFormReady,
  waitForDynamicPageLoad,
} from '../helpers/index';

test.describe.configure({ mode: 'serial' });

const UID = uniqueId('SCI');

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function field(page: Page, code: string) {
  return page.locator(`[data-testid="form-field-${code}"]`).first();
}

async function navigateToShowcaseList(page: Page): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  const nav = page.locator('nav');
  await nav.first().waitFor({ state: 'visible', timeout: 10_000 });

  // Click parent menu "Field Showcase / 字段展示"
  const rootBtn = nav
    .getByRole('button', { name: /Field Showcase|Showcase|字段展示|能力展示|展示/i })
    .first();
  await rootBtn.scrollIntoViewIfNeeded();
  await rootBtn.evaluate((el: HTMLElement) => el.click());

  // Click leaf menu "全字段类型 / All Field Types"
  const leafLink = nav.locator('a[href*="showcase_all_fields"]').first();
  await leafLink.waitFor({ state: 'attached', timeout: 8_000 });

  const listResp = page.waitForResponse(
    (r) =>
      r.url().includes('/api/dynamic/showcase_all_fields') &&
      r.url().includes('list') &&
      r.status() === 200,
    { timeout: 20_000 },
  );
  await leafLink.evaluate((el: HTMLElement) => el.click());
  await listResp;

  await expect(
    page.locator('table, [data-testid="dynamic-list"]').first(),
  ).toBeVisible({ timeout: 15_000 });
}

async function openCreateForm(page: Page): Promise<void> {
  await navigateToShowcaseList(page);
  const createBtn = page
    .locator('[data-testid="toolbar-btn-create"]')
    .or(page.getByRole('button', { name: /新建|Create/i }))
    .first();
  await createBtn.waitFor({ state: 'visible', timeout: 8_000 });
  await createBtn.evaluate((el: HTMLElement) => el.click());
  await page
    .waitForURL(/showcase.all.fields.*form|\/new|\/create/, { timeout: 15_000 })
    .catch(() => null);
  await waitForFormReady(page, 15_000);
}

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------

test.describe('Showcase Smart Component — Deep Interaction', () => {
  test.setTimeout(120_000);

  // =========================================================================
  // 1. CascadeSelect multi-level interaction
  // =========================================================================
  test.fixme('SCI-001 — CascadeSelect: level 1 → level 2 populates → level 3 populates → all have values; clear level 1 resets others', async ({
    page,
  }) => {
    test.setTimeout(30000);
    await openCreateForm(page);

    // Fill required name
    const nameInput = field(page, 'sc_name').locator('input').first();
    await nameInput.fill(`SCI-001 Cascade ${UID}`);

    // Locate cascade field container
    const cascadeContainer = field(page, 'sc_cascade_category');
    await cascadeContainer.scrollIntoViewIfNeeded();
    await expect(cascadeContainer).toBeVisible({ timeout: 5_000 });

    // CascadeSelect uses custom CascadeDropdown components with data-testid triggers
    const trigger0 = page.locator('[data-testid="cascade-trigger-sc_cascade_category-0"]');
    const trigger1 = page.locator('[data-testid="cascade-trigger-sc_cascade_category-1"]');
    const trigger2 = page.locator('[data-testid="cascade-trigger-sc_cascade_category-2"]');

    // Wait for cascade options to load from API
    await expect(trigger0).toBeVisible({ timeout: 10_000 });

    // Level 2 and 3 triggers should be disabled initially
    await expect(trigger1).toBeDisabled();
    await expect(trigger2).toBeDisabled();

    // -- Select level 1: "Electronics" --
    await trigger0.click();
    const electronicsOption = page.locator('[data-testid="cascade-option-sc_cascade_category-0-electronics"]');
    await expect(electronicsOption).toBeVisible({ timeout: 3_000 });
    await electronicsOption.click();

    // Level 1 trigger should show "Electronics" / "电子产品"
    const trigger0Text = await trigger0.innerText();
    expect(
      trigger0Text.includes('Electronics') || trigger0Text.includes('电子产品'),
      `Level 1 should show Electronics, got: "${trigger0Text}"`,
    ).toBeTruthy();

    // Level 2 should now be enabled
    await expect(trigger1).toBeEnabled({ timeout: 3_000 });

    // -- Select level 2: "Phone" --
    await trigger1.click();
    const phoneOption = page.locator('[data-testid="cascade-option-sc_cascade_category-1-electronics_phone"]');
    await expect(phoneOption).toBeVisible({ timeout: 3_000 });
    await phoneOption.click();

    const trigger1Text = await trigger1.innerText();
    expect(
      trigger1Text.includes('Phone') || trigger1Text.includes('手机'),
      `Level 2 should show Phone, got: "${trigger1Text}"`,
    ).toBeTruthy();

    // Level 3 should now be enabled
    await expect(trigger2).toBeEnabled({ timeout: 3_000 });

    // -- Select level 3: "Smartphone" --
    await trigger2.click();
    const smartOption = page.locator('[data-testid="cascade-option-sc_cascade_category-2-electronics_phone_smart"]');
    await expect(smartOption).toBeVisible({ timeout: 3_000 });
    await smartOption.click();

    const trigger2Text = await trigger2.innerText();
    expect(
      trigger2Text.includes('Smartphone') || trigger2Text.includes('智能手机'),
      `Level 3 should show Smartphone, got: "${trigger2Text}"`,
    ).toBeTruthy();

    // Selected path display should show all 3 levels
    const pathDisplay = cascadeContainer.locator('text=Selected:').first();
    if (await pathDisplay.isVisible({ timeout: 2_000 }).catch(() => false)) {
      const displayText = await pathDisplay.innerText();
      expect(displayText.length, 'Display text should be non-empty').toBeGreaterThan(0);
    }

    // -- Select a different level 1 value to verify levels 2 and 3 reset --
    await trigger0.click();
    const serviceOption = page.locator('[data-testid="cascade-option-sc_cascade_category-0-service"]');
    if (await serviceOption.isVisible({ timeout: 2_000 }).catch(() => false)) {
      await serviceOption.click();
      // Level 2 and 3 triggers should have reset (show placeholder)
      await expect(trigger2).toBeDisabled({ timeout: 3_000 });
    } else {
      // Re-select electronics to close dropdown
      await electronicsOption.click();
    }
  });

  // =========================================================================
  // 2. TreeSelect dropdown interaction
  // =========================================================================
  test('SCI-002 — TreeSelect: open dropdown → see tree nodes → click leaf node → value displayed → clear', async ({
    page,
  }) => {
    await openCreateForm(page);

    // Fill required name
    const nameInput = field(page, 'sc_name').locator('input').first();
    await nameInput.fill(`SCI-002 Tree ${UID}`);

    // Locate tree select field
    const treeField = field(page, 'sc_tree_node');
    await treeField.scrollIntoViewIfNeeded();
    await expect(treeField).toBeVisible({ timeout: 5_000 });

    // The TreeSelect renders a custom div trigger; click it to open dropdown
    // It contains a hidden <input> and a clickable div wrapper
    const triggerDiv = treeField.locator('.cursor-pointer').first();
    await triggerDiv.click();

    // Dropdown should open showing tree nodes
    // The TreeSelect renders nodes in a dropdown div with max-h-60
    const dropdown = treeField.locator('.max-h-60, .overflow-y-auto').first();
    await expect(dropdown).toBeVisible({ timeout: 3_000 });

    // Should see root-level tree nodes (Technology, Product, Operations)
    // These are parent nodes; with leafOnly=true by default, we need to expand first
    const techNode = dropdown.locator('text=技术部').or(dropdown.locator('text=Technology')).first();
    await expect(techNode, 'Root tree node "Technology/技术部" should be visible').toBeVisible({
      timeout: 3_000,
    });

    // Expand "Technology" by clicking the expand arrow
    const expandBtn = techNode.locator('..').locator('button').first();
    if (await expandBtn.isVisible({ timeout: 2_000 }).catch(() => false)) {
      await expandBtn.click();
    } else {
      // If no explicit expand button, click the parent node row
      await techNode.click();
    }

    // Child nodes should appear: Frontend Team, Backend Team, QA Team
    const frontendNode = dropdown
      .locator('text=前端组')
      .or(dropdown.locator('text=Frontend Team'))
      .first();
    await expect(frontendNode, 'Child node "Frontend Team/前端组" should appear').toBeVisible({
      timeout: 3_000,
    });

    // Click a leaf node to select it
    await frontendNode.click();

    // Dropdown should close for single-select
    // Verify the selected value is displayed in the trigger
    const triggerText = await triggerDiv.innerText();
    expect(
      triggerText.includes('前端组') || triggerText.includes('Frontend'),
      `Trigger should show selected label, got: "${triggerText}"`,
    ).toBeTruthy();

    // Hidden input should hold the value
    const hiddenInput = treeField.locator('input[type="hidden"]').first();
    const hiddenValue = await hiddenInput.inputValue();
    expect(hiddenValue, 'Hidden input should contain "tech_frontend"').toBe('tech_frontend');

    // -- Clear the value --
    // TreeSelect has a clearable X button
    const clearBtn = treeField.locator('button').filter({ has: page.locator('svg') }).first();
    if (await clearBtn.isVisible({ timeout: 2_000 }).catch(() => false)) {
      await clearBtn.click();
      const clearedValue = await hiddenInput.inputValue();
      expect(clearedValue, 'Value should be cleared').toBe('');
    }
  });

  // =========================================================================
  // 3. UserSelect search
  // =========================================================================
  test('SCI-003 — UserSelect: click trigger → search input → type keyword → results from API → select user → value shown', async ({
    page,
  }) => {
    await openCreateForm(page);

    // Fill required name
    const nameInput = field(page, 'sc_name').locator('input').first();
    await nameInput.fill(`SCI-003 User ${UID}`);

    // Locate user select field
    const userField = field(page, 'sc_assignee');
    await userField.scrollIntoViewIfNeeded();
    await expect(userField).toBeVisible({ timeout: 5_000 });

    // Click the trigger to open dropdown
    const trigger = page.locator('[data-testid="user-select-trigger-sc_assignee"]');
    await expect(trigger).toBeVisible({ timeout: 3_000 });
    await trigger.click();

    // Search input should appear
    const searchInput = page.locator('[data-testid="user-select-search-sc_assignee"]');
    await expect(searchInput, 'Search input should appear in dropdown').toBeVisible({
      timeout: 3_000,
    });

    // Wait for the initial user list to load (API call to /api/tenant/members/search)
    const memberApiResponse = page.waitForResponse(
      (r) => r.url().includes('/api/tenant/members/search') && r.status() === 200,
      { timeout: 10_000 },
    );

    // Type a search keyword — "admin" should match the test admin user
    await searchInput.fill('admin');
    const apiResp = await memberApiResponse;
    const apiBody = await apiResp.json();
    expect(
      (apiBody as any)?.code === '0' || (apiBody as any)?.code === 0,
      'Member search API should succeed',
    ).toBeTruthy();

    // Wait for results to render
    const userOption = page.locator('[data-testid^="user-select-option-sc_assignee-"]').first();
    await expect(userOption, 'At least one user option should appear').toBeVisible({
      timeout: 5_000,
    });

    // Get the user name before selecting
    const userName = await userOption.locator('.font-medium').first().innerText();
    expect(userName.length, 'User name should not be empty').toBeGreaterThan(0);

    // Select the user
    await userOption.click();

    // Trigger should now display the selected user name
    const triggerText = await trigger.innerText();
    expect(
      triggerText.includes(userName),
      `Trigger should show selected user "${userName}", got: "${triggerText}"`,
    ).toBeTruthy();

    // Hidden input should hold the user ID
    const hiddenInput = userField.locator('input[type="hidden"]').first();
    const hiddenValue = await hiddenInput.inputValue();
    expect(hiddenValue.length, 'Hidden input should contain a user ID').toBeGreaterThan(0);
  });

  // =========================================================================
  // 4. DateRange start/end validation
  // =========================================================================
  test('SCI-004 — DateRange: fill valid range → both inputs have values; HTML min/max enforce order', async ({
    page,
  }) => {
    await openCreateForm(page);

    // Fill required name (wait for input to be ready)
    const nameInput = field(page, 'sc_name').locator('input').first();
    await nameInput.waitFor({ state: 'visible', timeout: 10_000 });
    await nameInput.fill(`SCI-004 DateRange ${UID}`);

    // Locate date range inputs
    const startInput = page.locator('[data-testid="daterange-sc_date_range-start"]');
    const endInput = page.locator('[data-testid="daterange-sc_date_range-end"]');
    await startInput.scrollIntoViewIfNeeded();
    await expect(startInput).toBeVisible({ timeout: 5_000 });
    await expect(endInput).toBeVisible({ timeout: 5_000 });

    // Verify both are type="date" (correct component type rendered)
    const startType = await startInput.getAttribute('type');
    const endType = await endInput.getAttribute('type');
    expect(startType, 'Start input should be type="date"').toBe('date');
    expect(endType, 'End input should be type="date"').toBe('date');

    // -- Fill a valid range: start = today, end = today + 14 --
    const startDate = todayStr();
    const endDate = dateOffsetStr(14);
    await startInput.fill(startDate);
    await endInput.fill(endDate);

    // Verify both inputs have values
    const startVal = await startInput.inputValue();
    const endVal = await endInput.inputValue();
    expect(startVal, `Start should be ${startDate}`).toBe(startDate);
    expect(endVal, `End should be ${endDate}`).toBe(endDate);

    // After setting start, the end input should have min attribute = start date
    // This prevents selecting an end date before the start
    const endMin = await endInput.getAttribute('min');
    expect(endMin, 'End input min attribute should equal start date').toBe(startDate);

    // After setting end, the start input should have max attribute = end date
    const startMax = await startInput.getAttribute('max');
    expect(startMax, 'Start input max attribute should equal end date').toBe(endDate);

    // -- Test reverse order: set start AFTER end --
    // Clear and set end first, then start before end
    await startInput.fill('');
    await endInput.fill(dateOffsetStr(7));
    const afterEndMin = await startInput.getAttribute('max');
    // Start max should be constrained to end date
    expect(afterEndMin, 'Start max should be end date').toBe(dateOffsetStr(7));

    await startInput.fill(todayStr());
    const finalStart = await startInput.inputValue();
    const finalEnd = await endInput.inputValue();
    expect(finalStart, 'Start should have a value').toBeTruthy();
    expect(finalEnd, 'End should have a value').toBeTruthy();
  });

  // =========================================================================
  // 5. MoneyInput formatting
  // =========================================================================
  test('SCI-005 — MoneyInput: enter decimal → clamped to 2 places; currency symbol visible; zero accepted', async ({
    page,
  }) => {
    await openCreateForm(page);

    // Fill required name
    const nameInput = field(page, 'sc_name').locator('input').first();
    await nameInput.fill(`SCI-005 Money ${UID}`);

    // Locate money input field
    const budgetField = field(page, 'sc_budget');
    await budgetField.scrollIntoViewIfNeeded();
    await expect(budgetField).toBeVisible({ timeout: 5_000 });

    // Currency symbol "¥" should be visible
    const currencySymbol = budgetField.locator('text=¥').first();
    await expect(currencySymbol, 'Currency symbol ¥ should be visible').toBeVisible({
      timeout: 3_000,
    });

    // MoneyInput renders an <input type="text" inputMode="decimal">
    const moneyInput = budgetField.locator('input[inputmode="decimal"]').first();
    await expect(moneyInput).toBeVisible({ timeout: 3_000 });

    // -- Enter "12345.678" → should be formatted to 2 decimal places on blur --
    await moneyInput.click();
    await moneyInput.fill('12345.678');

    // Trigger blur to activate clamping
    await moneyInput.blur();

    // After blur, the value should be clamped to 2 decimal places (may include thousand separator)
    const displayValue = await moneyInput.inputValue();
    // Strip thousand separators for comparison
    const normalized = displayValue.replace(/,/g, '');
    expect(
      normalized,
      `MoneyInput should clamp to 2 decimal places, got: "${displayValue}"`,
    ).toBe('12345.68');

    // -- Enter "0" → should be accepted (min=0) --
    await moneyInput.click();
    await moneyInput.fill('0');
    await moneyInput.blur();
    const zeroValue = await moneyInput.inputValue();
    expect(zeroValue.replace(/,/g, ''), 'Zero should be accepted as a valid value').toBe('0.00');

    // -- Enter a large value --
    await moneyInput.click();
    await moneyInput.fill('99999.99');
    await moneyInput.blur();
    const largeValue = await moneyInput.inputValue();
    expect(largeValue.replace(/,/g, ''), 'Large value should be formatted correctly').toBe('99999.99');
  });

  // =========================================================================
  // 6. CascadeSelect as "address-like" three-level cascade
  // =========================================================================
  test('SCI-006 — CascadeSelect three-level drill-down: L1→L2 populates→L3 populates; all selects have specific values', async ({
    page,
  }) => {
    await openCreateForm(page);

    // Fill required name
    const nameInput = field(page, 'sc_name').locator('input').first();
    await nameInput.fill(`SCI-006 Address ${UID}`);

    // Use cascade with a different path: Service → Consulting → Strategy
    const cascadeContainer = field(page, 'sc_cascade_category');
    await cascadeContainer.scrollIntoViewIfNeeded();
    await expect(cascadeContainer).toBeVisible({ timeout: 5_000 });

    const trigger0 = page.locator('[data-testid="cascade-trigger-sc_cascade_category-0"]');
    const trigger1 = page.locator('[data-testid="cascade-trigger-sc_cascade_category-1"]');
    const trigger2 = page.locator('[data-testid="cascade-trigger-sc_cascade_category-2"]');

    // -- Select level 1: "Service" --
    await trigger0.click();
    const serviceOption = page.locator('[data-testid="cascade-option-sc_cascade_category-0-service"]');
    await expect(serviceOption).toBeVisible({ timeout: 3_000 });
    await serviceOption.click();

    const trigger0Text = await trigger0.innerText();
    expect(
      trigger0Text.includes('Service') || trigger0Text.includes('服务'),
      `Level 1 should show Service, got: "${trigger0Text}"`,
    ).toBeTruthy();

    // Level 2 should become enabled with Service children
    await expect(trigger1).toBeEnabled({ timeout: 3_000 });

    // -- Select level 2: "Consulting" --
    await trigger1.click();
    const consultingOption = page.locator('[data-testid="cascade-option-sc_cascade_category-1-service_consulting"]');
    await expect(consultingOption).toBeVisible({ timeout: 3_000 });
    await consultingOption.click();

    const trigger1Text = await trigger1.innerText();
    expect(
      trigger1Text.includes('Consulting') || trigger1Text.includes('咨询'),
      `Level 2 should show Consulting, got: "${trigger1Text}"`,
    ).toBeTruthy();

    // Level 3 should become enabled
    await expect(trigger2).toBeEnabled({ timeout: 3_000 });

    // -- Select level 3: "Strategy" --
    await trigger2.click();
    const strategyOption = page.locator('[data-testid="cascade-option-sc_cascade_category-2-service_consulting_strategy"]');
    await expect(strategyOption).toBeVisible({ timeout: 3_000 });
    await strategyOption.click();

    const trigger2Text = await trigger2.innerText();
    expect(
      trigger2Text.includes('Strategy') || trigger2Text.includes('战略'),
      `Level 3 should show Strategy, got: "${trigger2Text}"`,
    ).toBeTruthy();

    // -- Submit the form to verify full cascade value is saved --
    await page.keyboard.press('Escape').catch(() => null);

    const saveBtn = page
      .locator('[data-testid="form-btn-submit"]')
      .or(page.getByRole('button', { name: /保存|Save/i }))
      .first();
    await saveBtn.scrollIntoViewIfNeeded();

    const saveResp = page.waitForResponse(
      (r) => r.url().includes('/api/meta/commands/execute') && r.status() === 200,
      { timeout: 15_000 },
    );
    await saveBtn.click({ force: true });
    const resp = await saveResp.catch(() => null);

    // [D14] Verify command success
    if (resp) {
      const body = await resp.json().catch(() => ({}));
      expect(
        (body as any)?.code === '0' || (body as any)?.code === 0,
        'Save command should succeed',
      ).toBeTruthy();

      // [D6] Verify the cascade value was stored correctly
      const resultData = (body as any)?.data?.data ?? {};
      const recordId = resultData?.recordId ?? resultData?.pid ?? '';
      if (recordId) {
        const verifyResp = await page.request.get(
          `/api/dynamic/showcase_all_fields/${recordId}`,
        );
        if (verifyResp.ok()) {
          const verifyBody = await verifyResp.json();
          const record = (verifyBody as any)?.data;
          if (record?.sc_cascade_category) {
            // Value should contain the full path or the leaf value
            const cascadeVal = String(record.sc_cascade_category);
            expect(
              cascadeVal.includes('strategy') || cascadeVal.includes('service_consulting_strategy'),
              `Cascade value should contain strategy path, got: "${cascadeVal}"`,
            ).toBeTruthy();
          }
        }
      }
    }
  });

  // =========================================================================
  // 7. OrganizationSelect interaction
  // =========================================================================
  test('SCI-007 — OrganizationSelect: open dropdown → tree hierarchy → expand parent → select child → value displayed; clear resets', async ({
    page,
  }) => {
    await openCreateForm(page);

    // Fill required name
    const nameInput = field(page, 'sc_name').locator('input').first();
    await nameInput.fill(`SCI-007 Org ${UID}`);

    // Locate org select field
    const orgField = field(page, 'sc_department');
    await orgField.scrollIntoViewIfNeeded();
    await expect(orgField).toBeVisible({ timeout: 5_000 });

    // The OrganizationSelect renders a clickable div trigger with Building2 icon
    const trigger = orgField.locator('.cursor-pointer').first();
    await expect(trigger).toBeVisible({ timeout: 3_000 });

    // Click trigger to open dropdown
    await trigger.click();

    // Dropdown should appear with tree hierarchy and search input
    const dropdown = orgField.locator('.max-h-60, .overflow-y-auto').first();
    await expect(dropdown).toBeVisible({ timeout: 3_000 });

    // Search input should be visible in the dropdown
    const searchInput = orgField.locator('input[type="text"]').first();
    await expect(searchInput, 'Search input should appear in org dropdown').toBeVisible({
      timeout: 3_000,
    });

    const departmentOption = dropdown.locator('[data-testid^="organization-select-option-"]').first();
    await expect(departmentOption, 'At least one organization option should be visible').toBeVisible({
      timeout: 5_000,
    });
    const selectedOrgName = (await departmentOption.locator('.font-medium').first().innerText()).trim();
    const selectedOptionId = (await departmentOption.getAttribute('data-testid'))?.replace(
      'organization-select-option-',
      '',
    );

    // Select the first real department from the current seeded organization tree.
    await departmentOption.click();

    // Trigger should now display the selected org name
    const triggerText = await trigger.innerText();
    expect(
      triggerText.includes(selectedOrgName),
      `Trigger should show "${selectedOrgName}", got: "${triggerText}"`,
    ).toBeTruthy();

    // Hidden input should hold the org ID
    const hiddenInput = orgField.locator('input[type="hidden"]').first();
    const hiddenValue = await hiddenInput.inputValue();
    expect(hiddenValue, 'Hidden input should contain selected org ID').toBe(selectedOptionId);

    // -- Clear the value --
    // OrganizationSelect has a FieldActionButton with X icon for clearing
    // Look for the clear button that appears when a value is selected
    const clearAction = orgField.locator('button').filter({ has: page.locator('svg') }).last();
    if (await clearAction.isVisible({ timeout: 2_000 }).catch(() => false)) {
      await clearAction.click();
      // After clearing, hidden input should be empty
      const clearedValue = await hiddenInput.inputValue();
      expect(clearedValue, 'Value should be cleared after clicking X').toBe('');
    }
  });

  // =========================================================================
  // 8. (removed) CoordinatesPicker interaction — B9 widget retired 2026-04-19.
  //    Product decided not to ship a real map SDK; the prior runtime was a
  //    mock with hardcoded preset cities and offered no real value.
  // =========================================================================

  // =========================================================================
  // 9. TimeRangePicker interaction
  // =========================================================================
  test('SCI-009 — TimeRangePicker: open popover → click preset → start+end populated; manual time selection works; clear resets', async ({
    page,
  }) => {
    await openCreateForm(page);

    // Fill required name
    const nameInput = field(page, 'sc_name').locator('input').first();
    await nameInput.fill(`SCI-009 TimeRange ${UID}`);

    // Locate time range picker field
    const timeField = field(page, 'sc_working_hours');
    await timeField.scrollIntoViewIfNeeded();
    await expect(timeField).toBeVisible({ timeout: 5_000 });

    // Click the trigger to open dropdown
    const trigger = timeField.locator('.cursor-pointer').first();
    await expect(trigger).toBeVisible({ timeout: 3_000 });
    await trigger.click();

    // Dropdown should open with preset ranges on the left and time grid on the right
    const dropdown = timeField.locator('.absolute.z-50').first();
    await expect(dropdown, 'TimeRange dropdown should open').toBeVisible({ timeout: 3_000 });

    // Preset ranges should be visible
    const presetSection = dropdown.locator('text=预设范围').or(dropdown.locator('text=Preset')).first();
    await expect(presetSection, 'Preset section header should be visible').toBeVisible({
      timeout: 3_000,
    });

    // Click "工作时间" preset (09:00 - 18:00)
    const workingHoursPreset = dropdown.locator('text=工作时间').or(dropdown.locator('text=Working Hours')).first();
    await expect(workingHoursPreset, 'Working Hours preset should be visible').toBeVisible({
      timeout: 3_000,
    });
    await workingHoursPreset.click();

    // Dropdown should close after preset selection
    await expect(dropdown).not.toBeVisible({ timeout: 3_000 });

    // Trigger should display "09:00 - 18:00"
    const triggerText = await trigger.innerText();
    expect(
      triggerText.includes('09:00') && triggerText.includes('18:00'),
      `Trigger should show "09:00 - 18:00", got: "${triggerText}"`,
    ).toBeTruthy();

    // Hidden inputs should hold start and end times
    const startHidden = timeField.locator('input[type="hidden"]').first();
    const endHidden = timeField.locator('input[type="hidden"]').last();
    const startVal = await startHidden.inputValue();
    const endVal = await endHidden.inputValue();
    expect(startVal, 'Start time should be "09:00"').toBe('09:00');
    expect(endVal, 'End time should be "18:00"').toBe('18:00');

    // -- Test "全天" preset --
    await trigger.click();
    const reopenedDropdown = timeField.locator('.absolute.z-50').first();
    await expect(reopenedDropdown).toBeVisible({ timeout: 3_000 });

    const allDayPreset = reopenedDropdown.locator('text=全天').or(reopenedDropdown.locator('text=All Day')).first();
    await allDayPreset.click();

    const allDayStart = await startHidden.inputValue();
    const allDayEnd = await endHidden.inputValue();
    expect(allDayStart, 'All day start should be "00:00"').toBe('00:00');
    expect(allDayEnd, 'All day end should be "23:59"').toBe('23:59');

    // -- Clear the value --
    // TimeRangePicker has a clear X button via FieldActionButton
    const clearBtn = timeField.locator('button svg').filter({ hasText: '' }).last().locator('..');
    if (await clearBtn.isVisible({ timeout: 2_000 }).catch(() => false)) {
      await clearBtn.click();
      const clearedStart = await startHidden.inputValue();
      const clearedEnd = await endHidden.inputValue();
      expect(clearedStart, 'Start should be cleared').toBe('');
      expect(clearedEnd, 'End should be cleared').toBe('');
    }
  });

  // =========================================================================
  // 10. AddressField three-level cascade
  // =========================================================================
  test('SCI-010 — AddressField: province → city populates → district populates; stored value is JSON', async ({
    page,
  }) => {
    await openCreateForm(page);

    // Fill required name
    const nameInput = field(page, 'sc_name').locator('input').first();
    await nameInput.fill(`SCI-010 Address ${UID}`);

    // Locate address field container
    const addrField = field(page, 'sc_address');
    await addrField.scrollIntoViewIfNeeded();
    await expect(addrField).toBeVisible({ timeout: 5_000 });

    const provinceSelect = addrField.getByLabel('province');
    const citySelect = addrField.getByLabel('city');
    const districtSelect = addrField.getByLabel('district');
    const detailTextarea = addrField.getByLabel('detail-address');
    const hiddenInput = addrField.locator('input[type="hidden"][name="sc_address"]');

    await expect(provinceSelect).toBeVisible({ timeout: 3_000 });
    await expect(citySelect).toBeVisible({ timeout: 3_000 });
    await expect(districtSelect).toBeVisible({ timeout: 3_000 });
    await expect(citySelect).toBeDisabled();
    await expect(districtSelect).toBeDisabled();

    await provinceSelect.selectOption('广东省');
    await expect(provinceSelect).toHaveValue('广东省');
    await expect(citySelect).toBeEnabled({ timeout: 3_000 });

    await citySelect.selectOption('深圳市');
    await expect(citySelect).toHaveValue('深圳市');
    await expect(districtSelect).toBeEnabled({ timeout: 3_000 });

    await districtSelect.selectOption('南山区');
    await expect(districtSelect).toHaveValue('南山区');

    await detailTextarea.fill('科技园测试地址 100 号');

    const storedValue = JSON.parse(await hiddenInput.inputValue());
    expect(storedValue).toMatchObject({
      province: '广东省',
      city: '深圳市',
      district: '南山区',
      detail: '科技园测试地址 100 号',
    });
  });

  // =========================================================================
  // 11. AiField interaction
  // =========================================================================
  test('SCI-011 — AiField: textarea exists, AI button exists and is clickable, dropdown shows all operations', async ({
    page,
  }) => {
    await openCreateForm(page);

    // Fill required name
    const nameInput = field(page, 'sc_name').locator('input').first();
    await nameInput.fill(`SCI-011 AI ${UID}`);

    // Locate AI field container
    const aiField = field(page, 'sc_ai_summary');
    await aiField.scrollIntoViewIfNeeded();
    await expect(aiField).toBeVisible({ timeout: 5_000 });

    // [D5] Textarea should be rendered (not a plain text input)
    const textarea = aiField.locator('textarea').first();
    await expect(textarea, 'AiField should render a <textarea>').toBeVisible({ timeout: 3_000 });

    // Textarea should be editable — type something
    await textarea.fill('Test AI content for SCI-011');
    const typedValue = await textarea.inputValue();
    expect(typedValue, 'Textarea should accept typed input').toBe('Test AI content for SCI-011');

    // Main AI button should exist with gradient styling
    const aiButton = aiField.locator('button').filter({ hasText: /AI\s+(Generate|Summarize|Translate|Classify|Extract)/i }).first();
    await expect(aiButton, 'Main AI action button should be visible').toBeVisible({ timeout: 3_000 });
    const aiButtonText = await aiButton.innerText();
    expect(
      aiButtonText.match(/AI\s+(Generate|Summarize|Translate|Classify|Extract)/),
      `AI button should show operation label, got: "${aiButtonText}"`,
    ).toBeTruthy();

    // The "More operations" dropdown toggle (chevron button)
    const moreOpsBtn = aiField.locator('button[title="More AI operations"]').first();
    await expect(moreOpsBtn, 'More operations button should exist').toBeVisible({ timeout: 3_000 });

    // Click to open the operations dropdown
    await moreOpsBtn.click();

    // All 5 operations should be listed in the dropdown
    const opsDropdown = aiField.locator('.min-w-\\[160px\\]').first();
    await expect(opsDropdown, 'Operations dropdown should open').toBeVisible({ timeout: 3_000 });

    const expectedOps = ['Generate', 'Summarize', 'Translate', 'Classify', 'Extract'];
    for (const op of expectedOps) {
      const opButton = opsDropdown.locator('button').filter({ hasText: op }).first();
      await expect(opButton, `Operation "${op}" should be listed`).toBeVisible({ timeout: 2_000 });
    }

    // Close dropdown by clicking the toggle again
    await moreOpsBtn.click();
    await expect(opsDropdown).not.toBeVisible({ timeout: 2_000 });

    // Verify the textarea can be cleared
    await textarea.fill('');
    const clearedValue = await textarea.inputValue();
    expect(clearedValue, 'Textarea should be clearable').toBe('');
  });
});
