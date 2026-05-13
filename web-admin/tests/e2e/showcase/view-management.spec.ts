/**
 * View Management Panel — E2E Tests
 *
 * Verifies the complete view management lifecycle on the showcase model:
 * - Panel open/close and view listing
 * - Creating different view types (Table, Kanban, Calendar, Gallery)
 * - Config step for views that require field configuration
 * - View rendering verification (actual kanban/calendar renders, not just "panel closed")
 * - View edit (name, description, scope)
 * - View delete with confirmation
 * - Switching between views
 *
 * Uses showcase model (/p/showcase-all-fields) which has seeded data.
 *
 * NOTE: This is a platform management panel test (not CRUD model test),
 * so D1-D14 dimensions do not fully apply. However, the "delete function test"
 * principle applies: if ViewManagePanel code were deleted, every test here must fail.
 */

import { test, expect, type Page } from '@playwright/test';
import { uniqueId } from '../helpers/index';
import {
  createDefaultTableView,
  restoreDefaultTableView,
  type DefaultTableViewState,
} from './helpers/default-table-view';

// ---------------------------------------------------------------------------
// Serial mode — tests share state (created views flow through lifecycle)
// ---------------------------------------------------------------------------
test.describe.configure({ mode: 'serial' });

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------
const SHOWCASE_LIST_URL = '/p/showcase_all_fields';
const MODEL_CODE = 'showcase_all_fields';
const PAGE_KEY = 'showcase_all_fields';
const UID = uniqueId('VW');

// Track created view names for later tests
let createdTableViewName = '';
let createdKanbanViewName = '';
let defaultTableView: DefaultTableViewState | null = null;

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Navigate to showcase list and wait for table to render with data. */
async function gotoShowcaseList(page: Page) {
  await page.goto(SHOWCASE_LIST_URL, { waitUntil: 'domcontentloaded' });
  await page.locator('table tbody tr').first().waitFor({ state: 'visible', timeout: 20_000 });
}

/** Open the View Management panel by clicking the ViewSelector button. */
async function openViewManagePanel(page: Page) {
  // The ViewSelector is a button with the current view name or "Select view"
  // It has aria-haspopup="listbox" — clicking it opens the manage panel directly
  const viewBtn = page.locator('button[aria-haspopup="listbox"]').first();
  await expect(viewBtn).toBeVisible({ timeout: 10_000 });
  await viewBtn.click();

  // The ViewManagePanel renders as a dialog with role="dialog"
  const panel = page.locator('[role="dialog"][aria-modal="true"]');
  await expect(panel).toBeVisible({ timeout: 5_000 });
  return panel;
}

/** Close the panel through the explicit close control. */
async function closePanel(page: Page) {
  const panel = page.locator('[role="dialog"][aria-modal="true"]');
  const closeButton = panel.getByRole('button', { name: /Close panel/i });
  await expect(closeButton).toBeVisible({ timeout: 5_000 });
  await closeButton.click();
  await panel.waitFor({ state: 'hidden', timeout: 5_000 });
}

/** Click "+ New View" to show the type picker grid */
async function openTypePicker(page: Page) {
  const newViewBtn = page.getByRole('button', { name: /New View/i });
  await expect(newViewBtn).toBeVisible({ timeout: 5_000 });
  await newViewBtn.click();
  // Wait for "Choose type" label to appear
  await expect(page.getByText('Choose type')).toBeVisible({ timeout: 5_000 });
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

test.describe('View Management Panel', () => {
  test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

  test.beforeAll(async ({ request }) => {
    defaultTableView = await createDefaultTableView(request, MODEL_CODE, PAGE_KEY, 'view management');
  });

  test.afterAll(async ({ request }) => {
    await restoreDefaultTableView(request, defaultTableView);
    defaultTableView = null;
  });

  test('Panel opens with View Management heading and shows existing views', async ({ page }) => {
    test.setTimeout(60_000);
    await gotoShowcaseList(page);

    const panel = await openViewManagePanel(page);

    // Verify panel title
    await expect(panel.locator('#view-manage-panel-title')).toHaveText('View Management');

    // Verify "+ New View" button exists
    await expect(panel.getByRole('button', { name: /New View/i })).toBeVisible();

    // Panel shows either existing views (group headers) or the empty state message
    const groupHeaders = panel.locator('text=/Global Views|Team Views|Personal Views/');
    const emptyState = panel.getByText('No saved views available');
    // At least one of these must be visible
    const hasViews = await groupHeaders.first().isVisible().catch(() => false);
    const hasEmptyState = await emptyState.isVisible().catch(() => false);
    expect(hasViews || hasEmptyState).toBe(true);

    await closePanel(page);
  });

  test('Create Table view — instant creation, no config step', async ({ page }) => {
    test.setTimeout(60_000);
    await gotoShowcaseList(page);

    const panel = await openViewManagePanel(page);
    await openTypePicker(page);

    // Verify type picker grid shows 8 types
    const typeGrid = panel.locator('.grid');
    const typeButtons = typeGrid.locator('button');
    // Should have Table, Kanban, Calendar, Gallery, Gantt, Tree, Timeline, Form
    await expect(typeButtons).toHaveCount(8);

    // Verify specific type labels
    await expect(typeGrid.getByText('Table')).toBeVisible();
    await expect(typeGrid.getByText('Kanban')).toBeVisible();
    await expect(typeGrid.getByText('Calendar')).toBeVisible();
    await expect(typeGrid.getByText('Gallery')).toBeVisible();
    await expect(typeGrid.getByText('Gantt')).toBeVisible();

    // Click "Table" — should create immediately and close panel (no config step)
    const tableBtn = typeGrid.locator('button').filter({ hasText: 'Table' });
    await tableBtn.click();

    // Panel should close (Table doesn't need config step)
    const panelLoc = page.locator('[role="dialog"][aria-modal="true"]');
    await panelLoc.waitFor({ state: 'hidden', timeout: 10_000 });

    // Verify the page still shows a table with data rows
    const tableRows = page.locator('[data-testid="dynamic-list"] table tbody tr');
    await expect(tableRows.first()).toBeVisible({ timeout: 10_000 });
    const rowCount = await tableRows.count();
    expect(rowCount).toBeGreaterThan(0);

    // Remember the created view name for later
    const viewBtn = page.locator('button[aria-haspopup="listbox"]').first();
    createdTableViewName = (await viewBtn.innerText()).trim();
    // Table view name should contain "Table"
    expect(createdTableViewName).toMatch(/table/i);
  });

  test('Create Kanban view — config step appears with Group By field', async ({ page }) => {
    test.setTimeout(60_000);
    await gotoShowcaseList(page);

    const panel = await openViewManagePanel(page);
    await openTypePicker(page);

    // Click "Kanban" type
    const kanbanBtn = panel.locator('.grid button').filter({ hasText: 'Kanban' });
    await kanbanBtn.click();

    // Config step should appear (kanban needs groupByField)
    const configTitle = panel.getByText(/Configure Kanban View/i);
    await expect(configTitle).toBeVisible({ timeout: 10_000 });

    // Verify "Group By" field label with required asterisk
    await expect(panel.getByText('Group By')).toBeVisible();
    const requiredMark = panel.locator('span.text-red-500');
    await expect(requiredMark.first()).toBeVisible();

    // Verify "Title Field" optional field also exists
    await expect(panel.getByText('Title Field')).toBeVisible();

    // "Done" button should be disabled (required field not set)
    const doneBtn = panel.getByRole('button', { name: /^Done$/ });
    await expect(doneBtn).toBeDisabled();

    // Select the first available option in the Group By dropdown
    const groupBySelect = panel.locator('select').first();
    await expect(groupBySelect).toBeVisible();

    // Wait for model fields to load into the dropdown (async fetch)
    // The select should have more than just the placeholder option
    await expect(async () => {
      const optCount = await groupBySelect.locator('option').count();
      expect(optCount).toBeGreaterThan(1);
    }).toPass({ timeout: 10_000 });

    // Select the first real option (index 1, since 0 is placeholder)
    const options = groupBySelect.locator('option');
    const firstOptionText = await options.nth(1).innerText();
    expect(firstOptionText).toBeTruthy();
    expect(firstOptionText).not.toBe('Select field...');
    await groupBySelect.selectOption({ index: 1 });

    // IMPORTANT: KanbanView requires BOTH groupByField AND titleField to render.
    // The config panel marks titleField as optional, but the view code checks both.
    // We must also set the Title Field to avoid "Kanban not configured".
    const titleSelect = panel.locator('select').nth(1);
    await expect(titleSelect).toBeVisible();
    await titleSelect.selectOption({ index: 1 });

    // "Done" button should now be enabled
    await expect(doneBtn).toBeEnabled();

    const kanbanNavigation = page.waitForURL(/(?:\?|&)view=/, { timeout: 10_000 });

    // Click Done
    await doneBtn.click();
    await kanbanNavigation;

    // Panel should close after saving config
    const panelLoc = page.locator('[role="dialog"][aria-modal="true"]');
    await panelLoc.waitFor({ state: 'hidden', timeout: 10_000 });

    // The active view should now be the kanban view we just created
    // The view selector may show the newly created view or fallback to another
    const viewBtn = page.locator('button[aria-haspopup="listbox"]').first();
    createdKanbanViewName = (await viewBtn.innerText()).trim();
    // View name may be auto-generated or match the type — accept any non-empty name
    expect(createdKanbanViewName.length).toBeGreaterThan(0);

    // Reload to ensure the saved viewConfig is fetched from server
    await page.reload({ waitUntil: 'load' });
    // Wait for view to fully load (kanban needs API data).
    await page.waitForLoadState('networkidle').catch(() => {});

    // CRITICAL: Verify Kanban view actually renders.
    // Poll for either the rendered kanban or the "not configured" fallback
    // so we don't race the client render. Bounded to 5s.
    const notConfigured = page.getByText('Kanban not configured');
    const emptyNotConfigured = page.locator('[data-testid="view-empty-not-configured"]');
    await expect
      .poll(
        async () => {
          if (await notConfigured.isVisible().catch(() => false)) return 'not-configured';
          if (await emptyNotConfigured.isVisible().catch(() => false)) return 'empty';
          // Any kanban-like board present? (.flex.gap-4.overflow-x-auto is the
          // observed structural marker, see saved-view-kanban.spec.ts)
          if (await page.locator('.flex.gap-4.overflow-x-auto').first().isVisible().catch(() => false)) {
            return 'rendered';
          }
          return 'pending';
        },
        { timeout: 5_000 },
      )
      .not.toBe('pending');
    const isNotConfigured = await notConfigured.isVisible().catch(() => false);

    if (isNotConfigured) {
      // If kanban shows "not configured" after saving config + reload,
      // this is a real bug: the config step saved the fields but the parent
      // component or API didn't persist/reload viewConfig correctly.
      // We still mark this as a legitimate test finding.
      test.fail(true, 'BUG: Kanban shows "not configured" after saving groupByField + titleField in config step. The viewConfig may not persist correctly.');
      return;
    }

    // Check that the "view-empty-not-configured" state does NOT appear
    await expect(emptyNotConfigured).not.toBeVisible({ timeout: 3_000 });
  });

  test('Create Calendar view — config step with Date Field', async ({ page }) => {
    test.setTimeout(60_000);
    await gotoShowcaseList(page);

    const panel = await openViewManagePanel(page);
    await openTypePicker(page);

    // Click "Calendar" type
    const calendarBtn = panel.locator('.grid button').filter({ hasText: 'Calendar' });
    await calendarBtn.click();

    // Config step should appear
    const configTitle = panel.getByText(/Configure Calendar View/i);
    await expect(configTitle).toBeVisible({ timeout: 10_000 });

    // Verify "Date Field" required label
    await expect(panel.getByText('Date Field')).toBeVisible();

    // "Done" button should be disabled
    const doneBtn = panel.getByRole('button', { name: /^Done$/ });
    await expect(doneBtn).toBeDisabled();

    // Wait for model fields to load into the dropdown
    const dateSelect = panel.locator('select').first();
    await expect(dateSelect).toBeVisible();

    // Wait for options to populate
    await expect(async () => {
      const optCount = await dateSelect.locator('option').count();
      expect(optCount).toBeGreaterThan(1);
    }).toPass({ timeout: 10_000 });

    const options = dateSelect.locator('option');
    const optionCount = await options.count();

    if (optionCount <= 1) {
      // No date fields available in the model — skip with clear message
      test.skip(true, 'No date fields available in showcase model for calendar config');
      return;
    }

    await dateSelect.selectOption({ index: 1 });

    // Done should be enabled now
    await expect(doneBtn).toBeEnabled();
    const calendarNavigation = page.waitForURL(/(?:\?|&)view=/, { timeout: 10_000 });
    await doneBtn.click();
    await calendarNavigation;

    // Panel closes
    const panelLoc = page.locator('[role="dialog"][aria-modal="true"]');
    await panelLoc.waitFor({ state: 'hidden', timeout: 10_000 });

    // View name should mention "Calendar" — wait for loading to finish
    const viewBtn = page.locator('button[aria-haspopup="listbox"]').first();
    await expect.poll(async () => (await viewBtn.innerText()).trim(), { timeout: 10_000 }).not.toBe('Loading...');
    const viewName = (await viewBtn.innerText()).trim();
    expect(viewName).toMatch(/calendar/i);

    // Reload to ensure saved viewConfig is fetched
    await page.reload({ waitUntil: 'load' });
    await page.waitForLoadState('networkidle').catch(() => {});
    // Poll for either the FullCalendar root (.fc) or the not-configured
    // fallback so we don't race the client render. Bounded to 5s.
    const calendarContainer = page.locator('.fc');
    const notConfiguredCal = page.getByText('Calendar not configured');
    await expect
      .poll(
        async () => {
          if (await calendarContainer.isVisible().catch(() => false)) return 'rendered';
          if (await notConfiguredCal.isVisible().catch(() => false)) return 'not-configured';
          return 'pending';
        },
        { timeout: 5_000 },
      )
      .not.toBe('pending');

    // CRITICAL: Verify calendar renders — FullCalendar injects .fc class
    const isNotConfigured = await notConfiguredCal.isVisible().catch(() => false);

    if (isNotConfigured) {
      test.fail(true, 'BUG: Calendar shows "not configured" after saving date field in config step.');
      return;
    }

    const isCalendarVisible = await calendarContainer.isVisible().catch(() => false);
    if (isCalendarVisible) {
      // Calendar rendered — verify it has the grid structure
      const hasHeader = await page.locator('.fc-toolbar').isVisible().catch(() => false);
      const hasDayGrid = await page.locator('.fc-daygrid').isVisible().catch(() => false);
      expect(hasHeader || hasDayGrid).toBe(true);
    }
  });

  test('Create Gallery view — config step with optional fields', async ({ page }) => {
    test.setTimeout(60_000);
    await gotoShowcaseList(page);

    const panel = await openViewManagePanel(page);
    await openTypePicker(page);

    // Click "Gallery" type
    const galleryBtn = panel.locator('.grid button').filter({ hasText: 'Gallery' });
    await galleryBtn.click();

    // Config step should appear
    const configTitle = panel.getByText(/Configure Gallery View/i);
    await expect(configTitle).toBeVisible({ timeout: 10_000 });

    // Gallery has optional fields only (Image Field, Title Field)
    await expect(panel.getByText('Image Field')).toBeVisible();
    await expect(panel.getByText('Title Field')).toBeVisible();

    // "Done" should be enabled even without selecting (all fields optional)
    const doneBtn = panel.getByRole('button', { name: /^Done$/ });
    await expect(doneBtn).toBeEnabled();

    // Wait for model fields to load
    await expect(async () => {
      const selects = panel.locator('select');
      const selectCount = await selects.count();
      expect(selectCount).toBeGreaterThanOrEqual(1);
      const optCount = await selects.first().locator('option').count();
      expect(optCount).toBeGreaterThan(1);
    }).toPass({ timeout: 10_000 });

    // Select a title field if available
    const selects = panel.locator('select');
    const selectCount = await selects.count();
    if (selectCount >= 2) {
      const titleSelect = selects.nth(1); // Second select is Title Field
      const optCount = await titleSelect.locator('option').count();
      if (optCount > 1) {
        await titleSelect.selectOption({ index: 1 });
      }
    }

    const galleryNavigation = page.waitForURL(/(?:\?|&)view=/, { timeout: 10_000 }).catch(() => null);

    // Click Done
    await doneBtn.click();
    await galleryNavigation;

    // Panel closes
    const panelLoc = page.locator('[role="dialog"][aria-modal="true"]');
    await panelLoc.waitFor({ state: 'hidden', timeout: 10_000 });

    // View name should mention "Gallery"
    const viewBtn = page.locator('button[aria-haspopup="listbox"]').first();
    await expect.poll(async () => (await viewBtn.innerText()).trim(), { timeout: 10_000 }).not.toBe('Loading...');
    const viewName = (await viewBtn.innerText()).trim();
    expect(viewName).toMatch(/gallery|view|default/i);
  });

  test('Edit view — change name via inline edit form', async ({ page }) => {
    test.setTimeout(60_000);
    await gotoShowcaseList(page);

    const panel = await openViewManagePanel(page);

    // Find an edit button (pencil icon with title="Edit view")
    const editBtn = panel.locator('button[title="Edit view"]').first();
    await expect(editBtn).toBeVisible({ timeout: 5_000 });

    // Get the view name before editing
    const viewRow = editBtn.locator('xpath=ancestor::div[contains(@class, "rounded-md")]');
    const viewNameBefore = await viewRow.locator('span.truncate').first().innerText();

    // Click edit button
    await editBtn.click();

    // Inline edit form should appear with Name, Description, Scope fields
    const nameInput = panel.locator('input[type="text"]').first();
    await expect(nameInput).toBeVisible({ timeout: 5_000 });

    // Verify the Name label
    await expect(panel.getByText('Name', { exact: false })).toBeVisible();
    // Verify Description label
    await expect(panel.getByText('Description')).toBeVisible();
    // Verify Scope label
    await expect(panel.getByText('Scope')).toBeVisible();

    // Change the name
    const newName = `Edited ${UID}`;
    await nameInput.clear();
    await nameInput.fill(newName);

    // Click Save
    const saveBtn = panel.getByRole('button', { name: /^Save$/ });
    await expect(saveBtn).toBeVisible();
    await saveBtn.click();

    // After save, the inline form should disappear and the updated name should appear
    await expect(nameInput).not.toBeVisible({ timeout: 5_000 });

    // Verify the new name is visible in the panel
    await expect(panel.getByText(newName)).toBeVisible({ timeout: 5_000 });

    await closePanel(page);
  });

  test('Delete view — confirm dialog and removal from list', async ({ page }) => {
    test.setTimeout(60_000);
    await gotoShowcaseList(page);

    const panel = await openViewManagePanel(page);

    // Find the view we edited earlier (has our UID in name)
    const targetView = panel.getByText(`Edited ${UID}`);
    await expect(targetView).toBeVisible({ timeout: 5_000 });

    // Find the delete button in the same row
    // The delete button is title="Delete view" and it's a sibling in the action buttons area
    const viewRow = targetView.locator('xpath=ancestor::div[contains(@class, "mx-2")]');
    const deleteBtn = viewRow.locator('button[title="Delete view"]');
    await expect(deleteBtn).toBeVisible();

    // Click delete
    await deleteBtn.click();

    // Confirmation dialog should appear (uses confirmDialog utility)
    const confirmDialog = page.locator('[data-testid="confirm-dialog"]');
    await expect(confirmDialog).toBeVisible({ timeout: 5_000 });

    // Verify the confirm dialog mentions the view name
    const dialogText = await confirmDialog.innerText();
    expect(dialogText).toContain('delete');

    // Confirm deletion
    await page.locator('[data-testid="confirm-ok"]').click();

    // Wait for dialog to close
    await confirmDialog.waitFor({ state: 'hidden', timeout: 5_000 });

    // Verify the view is removed from the list
    await expect(panel.getByText(`Edited ${UID}`)).not.toBeVisible({ timeout: 5_000 });

    await closePanel(page);
  });

  test('Switch between views — clicking a view in the panel activates it', async ({ page }) => {
    test.setTimeout(60_000);
    await gotoShowcaseList(page);

    const panel = await openViewManagePanel(page);

    // Get all view name buttons in the panel (they have min-w-0 flex-1 text-left)
    const viewNameButtons = panel.locator('button.min-w-0');
    const count = await viewNameButtons.count();
    expect(count).toBeGreaterThanOrEqual(2);

    // Get the current view button text from the selector
    const viewSelector = page.locator('button[aria-haspopup="listbox"]').first();
    const currentName = (await viewSelector.innerText()).trim();

    // Find a different view to switch to
    let switchTarget: string | null = null;
    for (let i = 0; i < count; i++) {
      const name = (await viewNameButtons.nth(i).innerText()).trim();
      if (name && !currentName.includes(name) && name !== currentName) {
        switchTarget = name;
        await viewNameButtons.nth(i).click();
        break;
      }
    }

    // If we found and clicked a different view, panel should close and view should switch
    if (switchTarget) {
      // Panel may stay open after view selection — that's OK, we just verify the view switched
      // The view selector text should update
      // First close panel if still open
      const panelLoc = page.locator('[role="dialog"][aria-modal="true"]');
      const isPanelOpen = await panelLoc.isVisible();
      if (isPanelOpen) {
        await closePanel(page);
      }

      // Verify the view selector shows the new view name (may take a moment to update)
      await expect(viewSelector).toContainText(new RegExp(switchTarget.split(' ')[0], 'i'), { timeout: 10_000 });
    }
  });

  test('Config step Skip button — closes panel without saving config', async ({ page }) => {
    test.setTimeout(60_000);
    await gotoShowcaseList(page);

    const panel = await openViewManagePanel(page);
    await openTypePicker(page);

    // Create a Gantt view (requires start + end date)
    const ganttBtn = panel.locator('.grid button').filter({ hasText: 'Gantt' });
    await ganttBtn.click();

    // Config step should appear
    await expect(panel.getByText(/Configure Gantt View/i)).toBeVisible({ timeout: 10_000 });

    // Verify required fields
    await expect(panel.getByText('Start Date')).toBeVisible();
    await expect(panel.getByText('End Date')).toBeVisible();

    // Click "Skip" button
    const skipBtn = panel.getByRole('button', { name: /Skip/ });
    await expect(skipBtn).toBeVisible();
    await skipBtn.click();

    // Panel should close
    const panelLoc = page.locator('[role="dialog"][aria-modal="true"]');
    await panelLoc.waitFor({ state: 'hidden', timeout: 5_000 });
  });

  test('Panel close button works', async ({ page }) => {
    test.setTimeout(60_000);
    await gotoShowcaseList(page);

    const panel = await openViewManagePanel(page);

    // Click the close button (aria-label="Close panel")
    const closeBtn = panel.locator('button[aria-label="Close panel"]');
    await expect(closeBtn).toBeVisible();
    await closeBtn.click();

    // Panel should be hidden
    const panelLoc = page.locator('[role="dialog"][aria-modal="true"]');
    await panelLoc.waitFor({ state: 'hidden', timeout: 5_000 });
  });

  test('Set default view — star icon changes state', async ({ page }) => {
    test.setTimeout(60_000);
    await gotoShowcaseList(page);

    const panel = await openViewManagePanel(page);

    // Find a non-default view's "Set as default" button
    const setDefaultBtns = panel.locator('button[title="Set as default"]');
    const btnCount = await setDefaultBtns.count();

    if (btnCount === 0) {
      // All views are already default or no non-default views
      await closePanel(page);
      return;
    }

    // Click the first "Set as default" button
    await setDefaultBtns.first().click();

    // Wait for the operation to complete (loading spinner appears then disappears)
    // After setting default, the button title changes to "Default view"
    await expect(panel.locator('button[title="Default view"]')).toBeVisible({ timeout: 10_000 });

    // Verify the "Default" badge appears on the view
    await expect(panel.getByText('Default').first()).toBeVisible();

    await closePanel(page);
  });
});
