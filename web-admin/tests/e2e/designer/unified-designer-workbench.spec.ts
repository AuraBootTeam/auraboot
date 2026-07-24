/**
 * Unified Designer Workbench V3 E2E coverage.
 *
 * Focuses on a real authoring path against /api/pages:
 * load V3 page -> add model field -> edit inspector -> save -> reopen.
 */

import { expect, test } from '../../fixtures';
import { createCookieSessionStorage } from 'react-router';
import type { Browser, BrowserContext, Locator, Page, Request } from '@playwright/test';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';
import { uniqueId } from '../helpers';

// Wait for @dnd-kit to tear down the drag (overlay ghost removed) and let React
// flush the document update + canvas re-render before the next interaction.
async function settleAfterDrag(page: Page): Promise<void> {
  await page
    .locator('[data-testid="drag-overlay-ghost"]')
    .waitFor({ state: 'detached', timeout: 5000 })
    .catch(() => {});
  await page.evaluate(
    () => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))),
  );
}

/**
 * Drag a source onto a target using a real multi-step pointer gesture.
 *
 * Playwright `Locator.dragTo()` performs a single synchronous jump-move, which
 * does not give @dnd-kit time to run its asynchronous droppable measurement and
 * per-move collision detection. For short containers the stale start-rect still
 * covers the drop point, but a container that grows after first paint (e.g. a
 * table rendering preview rows) ends up with a stale short rect and the drop
 * silently resolves to the page root, where model fields are rejected. Real
 * users drag across many frames; this helper mirrors that with intermediate
 * moves so the gesture drives @dnd-kit the same way a human does.
 */
async function dndDragTo(
  page: Page,
  source: Locator,
  target: Locator,
  opts?: { targetPosition?: { x: number; y: number } },
): Promise<void> {
  const canvasBlocks = page.locator('[data-testid^="canvas-block-"]');
  const before = await canvasBlocks.count();

  const performGesture = async () => {
    // Clear any pointer/drag state a prior (failed) gesture may have left behind:
    // a held button or a stuck @dnd-kit drag would make this gesture a no-op.
    // mouse.move uses viewport coordinates, so both ends must be on-screen first;
    // a tall canvas (filters + toolbar + table) can push the drop target below the fold.
    await target.scrollIntoViewIfNeeded();
    await source.scrollIntoViewIfNeeded();
    const src = await source.boundingBox();
    const dst = await target.boundingBox();
    if (!src || !dst) throw new Error('dndDragTo: source or target has no bounding box');
    const sx = src.x + src.width / 2;
    const sy = src.y + src.height / 2;
    const tx = dst.x + (opts?.targetPosition?.x ?? dst.width / 2);
    const ty = dst.y + (opts?.targetPosition?.y ?? dst.height / 2);
    await page.mouse.move(sx, sy);
    await page.mouse.down();
    await page.mouse.move(sx + 12, sy + 12, { steps: 6 });
    await page.mouse.move(tx, ty, { steps: 18 });
    await page.mouse.move(tx + 2, ty + 2, { steps: 4 });
    await page.mouse.up();
    await settleAfterDrag(page);
  };

  // Every dndDragTo in this suite adds a block (palette block or bound field).
  // @dnd-kit's async measuring means a gesture occasionally doesn't register; the
  // settle above lets a successful add render before we check, so retrying only
  // fires when nothing was added (no double-add).
  await expect(async () => {
    await performGesture();
    expect(await canvasBlocks.count()).toBeGreaterThan(before);
  }).toPass({ timeout: 20000 });
}

/**
 * Switch the resource panel tab and confirm the switch took effect.
 *
 * Right after a drag + inspector edit, the resource panel re-renders; a single
 * tab `click()` is occasionally lost to that re-render (the panel stays on the
 * previous tab). Retry the click until the target tab reports active, so callers
 * can rely on the tab actually being shown.
 */
async function switchResourceTab(page: Page, tab: 'outline' | 'blocks' | 'fields'): Promise<void> {
  const button = page.getByTestId(`resource-tab-${tab}`);
  await expect(async () => {
    await button.click();
    await expect(button).toHaveAttribute('data-active', 'true', { timeout: 1000 });
  }).toPass({ timeout: 10000 });
}

/**
 * Toggle an inspector checkbox to a target state and confirm it sticks.
 *
 * A controlled checkbox edited right after a sibling field edit can be reverted
 * by the in-flight re-render (the click registers, then the prior edit's render
 * resets `checked`). Retry the toggle until the state holds.
 */
async function setCheckbox(page: Page, testId: string, checked: boolean): Promise<void> {
  const checkbox = page.getByTestId(testId);
  await expect(async () => {
    if ((await checkbox.isChecked()) !== checked) await checkbox.click();
    // The DOM toggles on click, but if the controlled onChange didn't commit to
    // the document, React reconciles the checkbox back on the next frame. Settle
    // before verifying so a non-committed toggle is detected and retried.
    await page.evaluate(
      () =>
        new Promise<void>((resolve) =>
          requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
        ),
    );
    expect(await checkbox.isChecked()).toBe(checked);
  }).toPass({ timeout: 10000 });
}

/**
 * Click a JSON field's "apply" button after letting the textarea's draft state
 * commit. The apply handler reads the draft from a render closure; under load the
 * preceding `.fill()`'s state update may not have flushed yet, so the click would
 * apply a stale (often empty) draft. A two-frame settle guarantees the draft is
 * current before the button reads it.
 */
async function applyJsonField(page: Page, applyTestId: string): Promise<void> {
  await page.evaluate(
    () => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))),
  );
  await page.getByTestId(applyTestId).click();
}

/**
 * Locate the interactive control inside a WYSIWYG runtime-preview form field.
 *
 * Model-backed `field` blocks render through the *real* platform control (true
 * WYSIWYG, introduced in #1204): `RuntimePlatformField` emits a
 * `runtime-field-<blockId>` wrapper containing a `ControlledFieldRenderer`
 * (`field-<fieldCode>`), and the concrete `<input>` / `<textarea>` / `<select>`
 * lives inside — it does NOT carry the legacy generic `runtime-input-<blockId>`
 * testid. Scoping by the block-id wrapper is stable across model fields; the
 * caller passes the leaf CSS selector for the component it configured.
 */
function runtimePreviewControl(
  page: Page,
  blockId: string,
  selector: 'input' | 'textarea' | 'select' = 'input',
): Locator {
  return page.getByTestId(`runtime-field-${blockId}`).locator(selector);
}

/**
 * Locate the validation error text of a WYSIWYG runtime-preview form field.
 *
 * The runtime form-context error is surfaced through the platform
 * `ControlledFieldRenderer`'s `<FieldError>` (an `ErrorText` `<p>` with the
 * `text-status-red` class), not the legacy `runtime-field-error-<blockId>` node.
 * The label's required asterisk is a `<span class="text-status-red">`, so the `p.`
 * prefix keeps this pinned to the error paragraph.
 *
 * Deliberately NOT `.first()`: the message must render exactly once. While the wrapper
 * shows a field error the control suppresses its own copy of it
 * (`FieldErrorOwnedByWrapperContext`), so a regression that paints the same message
 * twice (wrapper + the control's identical validationRules run) fails here on a
 * Playwright strict-mode violation instead of being hidden by `.first()`. Absent when
 * there is no error, so `toBeHidden()` still holds for the no-error case.
 */
function runtimePreviewFieldError(page: Page, blockId: string): Locator {
  return page.getByTestId(`runtime-field-${blockId}`).locator('p.text-status-red');
}

/**
 * Trigger of a WYSIWYG runtime-preview `select` control.
 *
 * Under true-WYSIWYG, `component: 'select'` renders the platform SmartSelect — a
 * Radix combobox (`<button role="combobox" data-testid="select-trigger-<code>">`)
 * whose options are portal `[role="option"]` nodes — not a native `<select>`. Open
 * the trigger, then `page.getByRole('option', { name })` to pick.
 */
function runtimePreviewSelectTrigger(page: Page, blockId: string): Locator {
  return page.getByTestId(`runtime-field-${blockId}`).getByRole('combobox');
}

/** Open a WYSIWYG runtime-preview `select` control and pick an option by its label. */
async function selectRuntimePreviewOption(
  page: Page,
  blockId: string,
  optionLabel: string,
): Promise<void> {
  await runtimePreviewSelectTrigger(page, blockId).click();
  await page.getByRole('option', { name: optionLabel }).click();
}

const ADMIN_STORAGE_STATE =
  process.env.PW_ADMIN_STORAGE_STATE ||
  (process.env.PW_STORAGE_DIR
    ? `${process.env.PW_STORAGE_DIR}/admin.json`
    : './tests/storage/admin.json');
const JWT_TOKEN_KEY = 'jwtToken';
const LOCAL_DESIGNER_STORAGE_KEY = 'auraboot.unified-designer.sample';
// Must be a permission the `e2et_operator` fixture role HAS and `e2et_viewer` LACKS,
// so the role matrix below actually discriminates. Source of truth:
// plugins/test-fixtures/config/roles.json — operator grants e2et.order.manage /
// e2et.customer.* / e2et.payment.manage; viewer grants only e2et.order.read.
// (The previous value `dashboard.manage` was never granted to either fixture role,
// so the operator "allowed" leg could never hold.)
const ROLE_MATRIX_PERMISSION_CODE = 'e2et.order.manage';
const authSessionStorage = createCookieSessionStorage({
  cookie: {
    name: '__session',
    httpOnly: true,
    path: '/',
    sameSite: 'lax',
    secrets: [process.env.SESSION_SECRET || 'dev-only-secret-do-not-use-in-production'],
    secure: (process.env.NODE_ENV ?? 'development') === 'production',
  },
});

interface PageSchemaDto {
  pid: string;
  pageKey: string;
  kind?: string;
  title?: unknown;
  modelCode?: string;
  layout?: Record<string, unknown>;
  extension?: Record<string, unknown>;
  blocks?: DslBlock[];
}

interface DslBlock {
  id?: string;
  blockType?: string;
  region?: string;
  field?: string;
  actionType?: string;
  dataSource?: Record<string, unknown>;
  layout?: Record<string, unknown>;
  props?: Record<string, unknown>;
  blocks?: DslBlock[];
}

test.describe.serial('Unified Designer Workbench V3', () => {
  // These flows perform several real multi-step pointer drags plus save/reopen
  // round-trips; the 15s global default is too tight for the @dnd-kit gestures.
  test.describe.configure({ timeout: 60_000 });
  const uid = uniqueId('udw');
  const formPageKey = `udw_v3_form_${uid}`;
  const listPageKey = `udw_v3_list_${uid}`;
  const detailPageKey = `udw_v3_detail_${uid}`;
  const dashboardPageKey = `udw_v3_dashboard_${uid}`;
  const formTitle = `UDW V3 Form ${uid}`;
  const listTitle = `UDW V3 List ${uid}`;
  const detailTitle = `UDW V3 Detail ${uid}`;
  const dashboardTitle = `UDW V3 Dashboard ${uid}`;
  const modelCode = 'page_schema';
  let pagePid = '';
  let listPagePid = '';
  let detailPagePid = '';
  let dashboardPagePid = '';
  let fieldCode = '';
  let fieldBlockId = '';
  let columnBlockId = '';
  let filterBlockId = '';
  let updatedLabel = '';
  let columnLabel = '';
  let filterLabel = '';
  let actionLabel = '';
  let actionRoute = '';
  let widgetTitle = '';
  let runtimeFormLabel = '';
  let runtimeColumnLabel = '';
  let runtimeActionLabel = '';
  let runtimeWidgetTitle = '';
  let paletteFieldLabel = '';
  let detailFieldLabel = '';
  let complexPlaceholder = '';
  let complexHelpText = '';
  let nestedSectionTitle = '';
  let nestedSectionDescription = '';
  let advancedWidgetTitle = '';
  let advancedWidgetSubtitle = '';
  let advancedWidgetMetric = '';
  let advancedWidgetValue = '';
  let advancedWidgetDrilldown = '';
  let advancedWidgetEmptyText = '';
  let chartWidgetTitle = '';
  let markdownWidgetText = '';
  let runtimeCommandLabel = '';
  let runtimeCommandFeedback = '';
  let runtimeDrawerLabel = '';
  let runtimeDrawerTitle = '';
  let runtimeDrawerPageKey = '';
  let liveWidgetTitle = '';
  let liveFormCommandLabel = '';
  let liveFormCommandCode = '';
  let liveCommandLabel = '';
  let liveCommandCode = '';
  let liveBulkCommandLabel = '';
  let liveBulkCommandCode = '';
  let liveRowCommandLabel = '';
  let liveRowCommandCode = '';
  let paletteRowActionLabel = '';
  let paletteRowCommandCode = '';
  let componentCheckboxLabel = '';
  let componentTextareaLabel = '';
  let componentTextareaPlaceholder = '';
  let componentPickerLabel = '';
  let componentPickerPlaceholder = '';
  let componentRichTextLabel = '';
  let componentRichTextPlaceholder = '';
  let componentUploadLabel = '';
  let componentUploadAccept = '';
  let validationCommandLabel = '';
  let validationCommandCode = '';
  let selectableRowId = '';
  let selectableRowName = '';
  let liveWorkflowKey = '';
  let liveWorkflowLabel = '';
  let liveWorkflowBusinessKey = '';
  let liveNamedQueryCode = '';
  let liveNamedQueryTitle = '';
  let liveHelperNamedQueryCode = '';
  let liveHelperNamedQueryTitle = '';

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({
      storageState: ADMIN_STORAGE_STATE,
    });
    const page = await ctx.newPage();

    const field = await pickModelField(page, modelCode, [
      'name',
      'page_key',
      'created_at',
      'updated_at',
      'created_by',
      'updated_by',
      'tenant_id',
    ]);
    fieldCode = field.code;
    fieldBlockId = stableBlockId('field', fieldCode);
    columnBlockId = stableBlockId('column', fieldCode);
    filterBlockId = stableBlockId('filter', fieldCode);
    updatedLabel = `Designer label ${uid}`;
    columnLabel = `Column label ${uid}`;
    filterLabel = `Filter label ${uid}`;
    actionLabel = `Navigate ${uid}`;
    actionRoute = `/p/mission?source=${uid}`;
    widgetTitle = `Widget title ${uid}`;
    runtimeFormLabel = `Runtime form ${uid}`;
    runtimeColumnLabel = `Runtime column ${uid}`;
    runtimeActionLabel = `Runtime action ${uid}`;
    runtimeWidgetTitle = `Runtime widget ${uid}`;
    paletteFieldLabel = `Palette field ${uid}`;
    detailFieldLabel = `Detail field ${uid}`;
    complexPlaceholder = `Enter mission ${uid}`;
    complexHelpText = `Complex help ${uid}`;
    nestedSectionTitle = `Nested section ${uid}`;
    nestedSectionDescription = `Nested description ${uid}`;
    advancedWidgetTitle = `Advanced widget ${uid}`;
    advancedWidgetSubtitle = `Pipeline health ${uid}`;
    advancedWidgetMetric = `count_open_${uid}`;
    advancedWidgetValue = `128 ${uid}`;
    advancedWidgetDrilldown = `/p/mission?dashboard=${uid}`;
    advancedWidgetEmptyText = `No dashboard data ${uid}`;
    chartWidgetTitle = `Chart widget ${uid}`;
    markdownWidgetText = `Pipeline review every Friday ${uid}`;
    runtimeCommandLabel = `Archive ${uid}`;
    runtimeCommandFeedback = `Archived ${uid}`;
    runtimeDrawerLabel = `Open drawer ${uid}`;
    runtimeDrawerTitle = `Mission drawer ${uid}`;
    runtimeDrawerPageKey = `mission_detail_${uid}`;
    liveWidgetTitle = `Live page rows ${uid}`;
    liveFormCommandLabel = `Live form command ${uid}`;
    liveFormCommandCode = `missing.form.command.${uid}`;
    liveCommandLabel = `Live command ${uid}`;
    liveCommandCode = `missing.command.${uid}`;
    liveBulkCommandLabel = `Live bulk command ${uid}`;
    liveBulkCommandCode = `missing.bulk.command.${uid}`;
    liveRowCommandLabel = `Live row command ${uid}`;
    liveRowCommandCode = `missing.row.command.${uid}`;
    paletteRowActionLabel = `Palette row action ${uid}`;
    paletteRowCommandCode = `missing.palette.row.command.${uid}`;
    componentCheckboxLabel = `Runtime checkbox ${uid}`;
    componentTextareaLabel = `Runtime textarea ${uid}`;
    componentTextareaPlaceholder = `Enter textarea ${uid}`;
    componentPickerLabel = `Runtime picker ${uid}`;
    componentPickerPlaceholder = `Select owner ${uid}`;
    componentRichTextLabel = `Runtime rich text ${uid}`;
    componentRichTextPlaceholder = `Write notes ${uid}`;
    componentUploadLabel = `Runtime upload ${uid}`;
    componentUploadAccept = '.pdf,.docx';
    validationCommandLabel = `Validated submit ${uid}`;
    validationCommandCode = `missing.validation.command.${uid}`;
    selectableRowId = `row_${uid}_001`;
    selectableRowName = `Selectable row ${uid}`;
    liveWorkflowKey = stableBlockId('udw_live_workflow', uid);
    liveWorkflowLabel = `Live workflow ${uid}`;
    liveWorkflowBusinessKey = `UDW-LIVE-WORKFLOW-${uid}`;
    liveNamedQueryCode = stableBlockId('udw_live_pages', uid);
    liveNamedQueryTitle = `Named query pages ${uid}`;
    liveHelperNamedQueryCode = stableBlockId('udw_live_helpers', uid);
    liveHelperNamedQueryTitle = `Named query helper data ${uid}`;

    const createWorkflowResp = await page.request.post('/api/bpm/process-definitions', {
      data: {
        processKey: liveWorkflowKey,
        processName: `UDW Live Workflow ${uid}`,
        description: 'Auto-generated for Unified Designer workflow live runtime E2E',
        category: 'e2e-test',
        bpmnContent: generateMinimalBpmn(liveWorkflowKey),
      },
    });
    expect(createWorkflowResp.ok(), await createWorkflowResp.text()).toBe(true);
    const createWorkflowBody = await createWorkflowResp.json();
    expect(createWorkflowBody.code).toBe('0');
    const liveWorkflowPid = String(createWorkflowBody.data?.pid ?? '');
    expect(liveWorkflowPid).toBeTruthy();

    const deployWorkflowResp = await page.request.post(
      `/api/bpm/process-definitions/${liveWorkflowPid}/deploy`,
    );
    expect(deployWorkflowResp.ok(), await deployWorkflowResp.text()).toBe(true);
    const deployWorkflowBody = await deployWorkflowResp.json();
    expect(deployWorkflowBody.code).toBe('0');

    await ensureNamedQuery(page, {
      code: liveNamedQueryCode,
      title: liveNamedQueryTitle,
      description: 'Auto-generated for Unified Designer named query widget E2E',
      fromSql: 'ab_page_schema p',
    });

    for (const field of [
      {
        fieldCode: 'name',
        columnExpr: 'p.name',
        dataType: 'string',
        displayName: 'Name',
        sortable: true,
        searchable: true,
        sortOrder: 1,
      },
      {
        fieldCode: 'page_key',
        columnExpr: 'p.page_key',
        dataType: 'string',
        displayName: 'Page key',
        sortable: true,
        searchable: true,
        sortOrder: 2,
      },
    ]) {
      await ensureNamedQueryField(page, liveNamedQueryCode, field);
    }

    await ensureNamedQuery(page, {
      code: liveHelperNamedQueryCode,
      title: liveHelperNamedQueryTitle,
      description: 'Auto-generated for Unified Designer helper block live data E2E',
      fromSql: 'ab_page_schema p',
    });

    for (const field of [
      {
        fieldCode: 'field',
        columnExpr: 'p.page_key',
        dataType: 'string',
        displayName: 'Suggested field',
        sortable: false,
        searchable: true,
        sortOrder: 1,
      },
      {
        fieldCode: 'label',
        columnExpr: 'p.name',
        dataType: 'string',
        displayName: 'Suggested label',
        sortable: false,
        searchable: true,
        sortOrder: 2,
      },
      {
        fieldCode: 'value',
        columnExpr: 'p.page_key',
        dataType: 'string',
        displayName: 'Suggested value',
        sortable: false,
        searchable: true,
        sortOrder: 3,
      },
      {
        fieldCode: 'feedback',
        columnExpr: `'Live helper suggestions applied ${uid}'`,
        dataType: 'string',
        displayName: 'AI feedback',
        sortable: false,
        searchable: false,
        sortOrder: 4,
      },
      {
        fieldCode: 'status',
        columnExpr: "'pending'",
        dataType: 'string',
        displayName: 'Workflow status',
        sortable: false,
        searchable: false,
        sortOrder: 5,
      },
      {
        fieldCode: 'assignee',
        columnExpr: 'p.name',
        dataType: 'string',
        displayName: 'Assignee',
        sortable: false,
        searchable: true,
        sortOrder: 6,
      },
      {
        fieldCode: 'dueAt',
        columnExpr: "'2026-05-21'",
        dataType: 'string',
        displayName: 'Due at',
        sortable: false,
        searchable: false,
        sortOrder: 7,
      },
      {
        fieldCode: 'actionLabel',
        columnExpr: `'Approve live helper ${uid}'`,
        dataType: 'string',
        displayName: 'Action label',
        sortable: false,
        searchable: false,
        sortOrder: 8,
      },
      {
        fieldCode: 'actionType',
        columnExpr: "'approve'",
        dataType: 'string',
        displayName: 'Action type',
        sortable: false,
        searchable: false,
        sortOrder: 9,
      },
      {
        fieldCode: 'actor',
        columnExpr: 'p.name',
        dataType: 'string',
        displayName: 'Activity actor',
        sortable: false,
        searchable: true,
        sortOrder: 10,
      },
      {
        fieldCode: 'action',
        columnExpr: `'Loaded live helper activity ${uid}'`,
        dataType: 'string',
        displayName: 'Activity action',
        sortable: false,
        searchable: false,
        sortOrder: 11,
      },
      {
        fieldCode: 'time',
        columnExpr: "'2026-05-20 12:00'",
        dataType: 'string',
        displayName: 'Activity time',
        sortable: false,
        searchable: false,
        sortOrder: 12,
      },
      {
        fieldCode: 'description',
        columnExpr: 'p.page_key',
        dataType: 'string',
        displayName: 'Activity description',
        sortable: false,
        searchable: true,
        sortOrder: 13,
      },
      {
        fieldCode: 'from',
        columnExpr: "'draft'",
        dataType: 'string',
        displayName: 'History from',
        sortable: false,
        searchable: false,
        sortOrder: 14,
      },
      {
        fieldCode: 'to',
        columnExpr: "'approved'",
        dataType: 'string',
        displayName: 'History to',
        sortable: false,
        searchable: false,
        sortOrder: 15,
      },
      {
        fieldCode: 'changedBy',
        columnExpr: 'p.name',
        dataType: 'string',
        displayName: 'History changed by',
        sortable: false,
        searchable: true,
        sortOrder: 16,
      },
    ]) {
      await ensureNamedQueryField(page, liveHelperNamedQueryCode, field);
    }

    const createResp = await page.request.post('/api/pages', {
      data: {
        name: formTitle,
        pageKey: formPageKey,
        title: formTitle,
        kind: 'form',
        modelCode,
        schemaVersion: 3,
        blocks: [
          {
            id: 'form_root',
            blockType: 'form',
            title: 'Designer E2E Form',
            dataSource: { model: modelCode },
            layout: { span: 12 },
            blocks: [
              {
                id: 'section_basic',
                blockType: 'form-section',
                title: 'Basic',
                layout: { columns: 12 },
                blocks: [
                  {
                    id: 'field_seed_title',
                    blockType: 'field',
                    field: 'name',
                    layout: { span: 6 },
                    props: { label: 'Seed title', component: 'input' },
                  },
                  {
                    id: 'field_seed_secondary',
                    blockType: 'field',
                    field: 'page_key',
                    layout: { span: 6 },
                    props: { label: 'Seed secondary', component: 'input' },
                  },
                ],
              },
              {
                id: 'form_actions',
                blockType: 'action-bar',
                region: 'footer',
                blocks: [
                  {
                    id: 'action_seed_submit',
                    blockType: 'action',
                    actionType: 'command',
                    props: { label: 'Submit form', command: 'page_schema:submit' },
                  },
                ],
              },
            ],
          },
        ],
        extension: { e2e: true, scenario: 'unified-designer-workbench-v3' },
      },
    });
    expect(createResp.ok(), await createResp.text()).toBe(true);
    const createBody = await createResp.json();
    expect(createBody.code).toBe('0');
    pagePid = String(createBody.data?.pid ?? '');
    expect(pagePid).toBeTruthy();

    const createListResp = await page.request.post('/api/pages', {
      data: {
        name: listTitle,
        pageKey: listPageKey,
        title: listTitle,
        kind: 'list',
        modelCode,
        schemaVersion: 3,
        blocks: [
          {
            id: 'list_root',
            blockType: 'list',
            title: 'Designer E2E List',
            dataSource: { model: modelCode },
            layout: { span: 12 },
            blocks: [
              {
                id: 'list_filters',
                blockType: 'filter-bar',
                region: 'filters',
                blocks: [
                  {
                    id: 'filter_seed_title',
                    blockType: 'filter-field',
                    field: 'name',
                    props: { label: 'Seed title filter', component: 'input', operator: 'contains' },
                  },
                ],
              },
              {
                id: 'list_toolbar',
                blockType: 'action-bar',
                region: 'toolbar',
                blocks: [
                  {
                    id: 'action_seed_create',
                    blockType: 'action',
                    actionType: 'create',
                    props: { label: 'Create mission', openMode: 'drawer' },
                  },
                  {
                    id: 'action_seed_export',
                    blockType: 'action',
                    actionType: 'command',
                    props: { label: 'Export mission' },
                  },
                  {
                    id: 'action_seed_bulk',
                    blockType: 'action',
                    actionType: 'command',
                    props: { label: 'Bulk mission' },
                  },
                ],
              },
              {
                id: 'list_table',
                blockType: 'table',
                layout: { span: 12 },
                props: {
                  // Runtime row identity is pid-only (see getRuntimeRowId:
                  // `row.pid ?? row.key ?? row._id ?? index`, hardened by the
                  // "enforce pid-only public record contracts" change). Real list
                  // rows expose `pid` as their public id, so seed the preview
                  // fixture the same way — otherwise selection/current.rowId fall
                  // back to the row index.
                  rows: [
                    { pid: selectableRowId, name: selectableRowName, page_key: listPageKey },
                    {
                      pid: `row_${uid}_002`,
                      name: `Other row ${uid}`,
                      page_key: `${listPageKey}_2`,
                    },
                  ],
                },
                blocks: [
                  {
                    id: 'column_seed_title',
                    blockType: 'column',
                    field: 'name',
                    layout: { width: 220 },
                    props: { label: 'Seed title column' },
                  },
                  {
                    id: 'column_seed_secondary',
                    blockType: 'column',
                    field: 'page_key',
                    layout: { width: 180 },
                    props: { label: 'Seed secondary column' },
                  },
                  {
                    id: 'action_seed_row_open',
                    blockType: 'action',
                    region: 'row-actions',
                    actionType: 'command',
                    props: { label: 'Open row', command: 'page_schema:open_row' },
                  },
                ],
              },
            ],
          },
        ],
        extension: { e2e: true, scenario: 'unified-designer-workbench-v3-list' },
      },
    });
    expect(createListResp.ok(), await createListResp.text()).toBe(true);
    const createListBody = await createListResp.json();
    expect(createListBody.code).toBe('0');
    listPagePid = String(createListBody.data?.pid ?? '');
    expect(listPagePid).toBeTruthy();

    const createDetailResp = await page.request.post('/api/pages', {
      data: {
        name: detailTitle,
        pageKey: detailPageKey,
        title: detailTitle,
        kind: 'detail',
        modelCode,
        schemaVersion: 3,
        blocks: [
          {
            id: 'detail_root',
            blockType: 'detail',
            title: 'Designer E2E Detail',
            dataSource: { model: modelCode },
            layout: { span: 12 },
            blocks: [
              {
                id: 'detail_section_summary',
                blockType: 'detail-section',
                title: 'Summary',
                layout: { columns: 12 },
                blocks: [
                  {
                    id: 'detail_field_title',
                    blockType: 'field',
                    field: 'name',
                    layout: { span: 6 },
                    props: { label: 'Detail title', component: 'input' },
                  },
                ],
              },
            ],
          },
        ],
        extension: { e2e: true, scenario: 'unified-designer-workbench-v3-detail' },
      },
    });
    expect(createDetailResp.ok(), await createDetailResp.text()).toBe(true);
    const createDetailBody = await createDetailResp.json();
    expect(createDetailBody.code).toBe('0');
    detailPagePid = String(createDetailBody.data?.pid ?? '');
    expect(detailPagePid).toBeTruthy();

    const createDashboardResp = await page.request.post('/api/pages', {
      data: {
        name: dashboardTitle,
        pageKey: dashboardPageKey,
        title: dashboardTitle,
        kind: 'detail',
        modelCode,
        schemaVersion: 3,
        blocks: [
          {
            id: 'dashboard_root',
            blockType: 'dashboard',
            title: 'Designer E2E Dashboard',
            layout: { span: 12, type: 'dashboard-grid', cols: 12, rowHeight: 80, gap: 16 },
            blocks: [
              {
                id: 'widget_pipeline',
                blockType: 'widget',
                widgetType: 'number-card',
                layout: { x: 0, y: 0, w: 4, h: 2, span: 4 },
                props: { title: 'Pipeline' },
              },
              {
                id: 'widget_health',
                blockType: 'widget',
                widgetType: 'bar-chart',
                layout: { x: 7, y: 0, w: 3, h: 2, span: 3 },
                props: { title: 'Health' },
              },
            ],
          },
        ],
        extension: { e2e: true, scenario: 'unified-designer-workbench-v3-dashboard' },
      },
    });
    expect(createDashboardResp.ok(), await createDashboardResp.text()).toBe(true);
    const createDashboardBody = await createDashboardResp.json();
    expect(createDashboardBody.code).toBe('0');
    dashboardPagePid = String(createDashboardBody.data?.pid ?? '');
    expect(dashboardPagePid).toBeTruthy();

    await ctx.close();
  });

  test('UDW-001: adds a model field, edits inspector, saves, and reopens V3 blocks', async ({
    page,
  }) => {
    expect(pagePid).toBeTruthy();
    expect(fieldCode).toBeTruthy();

    await page.goto(`/unified-designer?pageId=${pagePid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-section_basic').click();
    await expect(page.getByTestId('inspector-selected-id')).toContainText('section_basic');

    await switchResourceTab(page, 'fields');
    await page.getByTestId('field-palette-search').fill(fieldCode);
    const modelFieldButton = page.getByTestId(`model-field-${fieldCode}`);
    await expect(modelFieldButton).toBeVisible({ timeout: 10000 });
    await expect(modelFieldButton).toBeEnabled();
    await dndDragTo(page, modelFieldButton, page.getByTestId('canvas-block-section_basic'));

    await expect(page.getByTestId('inspector-selected-id')).toContainText(fieldBlockId);
    await expect(page.getByTestId(`canvas-block-${fieldBlockId}`)).toBeVisible();

    const labelInput = page.getByTestId('inspector-field-props.label');
    await expect(labelInput).toBeVisible();
    await labelInput.fill(updatedLabel);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    const saveRespPromise = page.waitForResponse(
      (response) =>
        response.url().includes(`/api/pages/${pagePid}`) && response.request().method() === 'PUT',
      { timeout: 15000 },
    );
    await page.getByTestId('designer-save').click();
    const saveResp = await saveRespPromise;
    expect(saveResp.status()).toBe(200);
    const saveBody = await saveResp.json();
    expect(saveBody.code).toBe('0');
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId(`outline-item-${fieldBlockId}`).click();
    await expect(page.getByTestId('inspector-field-props.label')).toHaveValue(updatedLabel);

    const persisted = await readPage(page, pagePid);
    const persistedBlock = findBlockById(persisted.blocks ?? [], fieldBlockId);
    expect(persistedBlock).toMatchObject({
      blockType: 'field',
      field: fieldCode,
      props: expect.objectContaining({ label: updatedLabel }),
    });
  });

  test('UDW-002: drags a model field into list table and filter blocks, then saves and reopens', async ({
    page,
  }) => {
    expect(listPagePid).toBeTruthy();
    expect(fieldCode).toBeTruthy();

    await page.goto(`/unified-designer?pageId=${listPagePid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-list_table').click();
    await expect(page.getByTestId('inspector-selected-id')).toContainText('list_table');

    await switchResourceTab(page, 'fields');
    await page.getByTestId('field-palette-search').fill(fieldCode);
    const tableFieldButton = page.getByTestId(`model-field-${fieldCode}`);
    await expect(tableFieldButton).toBeVisible({ timeout: 10000 });
    await expect(tableFieldButton).toBeEnabled();
    await dndDragTo(page, tableFieldButton, page.getByTestId('canvas-block-list_table'));

    await expect(page.getByTestId('inspector-selected-id')).toContainText(columnBlockId);
    await expect(page.getByTestId(`canvas-block-${columnBlockId}`)).toBeVisible();
    await page.getByTestId('inspector-field-props.label').fill(columnLabel);

    await switchResourceTab(page, 'outline');
    await page.getByTestId('outline-item-list_filters').click();
    await expect(page.getByTestId('inspector-selected-id')).toContainText('list_filters');

    await switchResourceTab(page, 'fields');
    await page.getByTestId('field-palette-search').fill(fieldCode);
    const filterFieldButton = page.getByTestId(`model-field-${fieldCode}`);
    await expect(filterFieldButton).toBeVisible({ timeout: 10000 });
    await expect(filterFieldButton).toBeEnabled();
    await dndDragTo(page, filterFieldButton, page.getByTestId('canvas-block-list_filters'));

    await expect(page.getByTestId('inspector-selected-id')).toContainText(filterBlockId);
    await expect(page.getByTestId(`canvas-block-${filterBlockId}`)).toBeVisible();
    await page.getByTestId('inspector-field-props.label').fill(filterLabel);
    await page.getByTestId('inspector-field-props.operator').selectOption('contains');
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, listPagePid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });

    await page.getByTestId(`outline-item-${columnBlockId}`).click();
    await expect(page.getByTestId('inspector-field-props.label')).toHaveValue(columnLabel);

    await page.getByTestId(`outline-item-${filterBlockId}`).click();
    await expect(page.getByTestId('inspector-field-props.label')).toHaveValue(filterLabel);
    await expect(page.getByTestId('inspector-field-props.operator')).toHaveValue('contains');

    const persisted = await readPage(page, listPagePid);
    const persistedColumn = findBlockById(persisted.blocks ?? [], columnBlockId);
    const persistedFilter = findBlockById(persisted.blocks ?? [], filterBlockId);
    expect(persistedColumn).toMatchObject({
      blockType: 'column',
      field: fieldCode,
      props: expect.objectContaining({ label: columnLabel }),
    });
    expect(persistedFilter).toMatchObject({
      blockType: 'filter-field',
      field: fieldCode,
      props: expect.objectContaining({ label: filterLabel, operator: 'contains' }),
    });
  });

  test('UDW-003: edits a toolbar action as an attached block and persists action settings', async ({
    page,
  }) => {
    expect(listPagePid).toBeTruthy();

    await page.goto(`/unified-designer?pageId=${listPagePid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-action_seed_create').click();
    await expect(page.getByTestId('inspector-selected-id')).toContainText('action_seed_create');

    await page.getByTestId('inspector-field-actionType').selectOption('navigate');
    await page.getByTestId('inspector-field-props.label').fill(actionLabel);
    await page.getByTestId('inspector-field-props.to').fill(actionRoute);
    await page.getByTestId('inspector-field-props.target').selectOption('self');
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, listPagePid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('outline-item-action_seed_create').click();

    await expect(page.getByTestId('inspector-field-actionType')).toHaveValue('navigate');
    await expect(page.getByTestId('inspector-field-props.label')).toHaveValue(actionLabel);
    await expect(page.getByTestId('inspector-field-props.to')).toHaveValue(actionRoute);
    await expect(page.getByTestId('inspector-field-props.target')).toHaveValue('self');

    const persisted = await readPage(page, listPagePid);
    const persistedAction = findBlockById(persisted.blocks ?? [], 'action_seed_create');
    expect(persistedAction).toMatchObject({
      blockType: 'action',
      actionType: 'navigate',
      props: expect.objectContaining({
        label: actionLabel,
        to: actionRoute,
        target: 'self',
      }),
    });
  });

  test('UDW-004: resizes a dashboard widget in layout mode and persists grid layout', async ({
    page,
  }) => {
    expect(dashboardPagePid).toBeTruthy();

    await page.goto(`/unified-designer?pageId=${dashboardPagePid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-widget_pipeline').click();
    await expect(page.getByTestId('inspector-selected-id')).toContainText('widget_pipeline');
    await expect(page.getByTestId('inspector-field-layout.w')).toHaveValue('4');
    await expect(page.getByTestId('inspector-field-layout.h')).toHaveValue('2');

    await page.getByTestId('designer-mode-layout').click();
    await resizeWidgetByMouse(page, 'widget_pipeline', 160, 64);
    await page.getByTestId('outline-item-widget_pipeline').click();

    await expect(page.getByTestId('inspector-field-layout.w')).toHaveValue('6');
    await expect(page.getByTestId('inspector-field-layout.h')).toHaveValue('3');
    await page.getByTestId('inspector-field-props.title').fill(widgetTitle);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, dashboardPagePid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('outline-item-widget_pipeline').click();

    await expect(page.getByTestId('inspector-field-layout.w')).toHaveValue('6');
    await expect(page.getByTestId('inspector-field-layout.h')).toHaveValue('3');
    await expect(page.getByTestId('inspector-field-props.title')).toHaveValue(widgetTitle);

    const persisted = await readPage(page, dashboardPagePid);
    const persistedWidget = findBlockById(persisted.blocks ?? [], 'widget_pipeline');
    expect(persistedWidget).toMatchObject({
      blockType: 'widget',
      widgetType: 'number-card',
      layout: expect.objectContaining({ w: 6, h: 3, span: 6 }),
      props: expect.objectContaining({ title: widgetTitle }),
    });
  });

  test('UDW-005: renders saved V3 form, list, and dashboard blocks in runtime preview', async ({
    page,
  }) => {
    expect(pagePid).toBeTruthy();
    expect(listPagePid).toBeTruthy();
    expect(dashboardPagePid).toBeTruthy();

    await page.goto(`/unified-designer?pageId=${pagePid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('outline-item-field_seed_title').click();
    await page.getByTestId('inspector-field-props.label').fill(runtimeFormLabel);
    await saveDesignerPage(page, pagePid);
    await page.getByTestId('designer-mode-preview').click();
    await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();
    await expect(page.getByTestId(`runtime-page-${formPageKey}`)).toBeVisible();
    await expect(page.getByTestId('runtime-block-form_root')).toBeVisible();
    await expect(page.getByTestId('runtime-field-field_seed_title')).toContainText(
      runtimeFormLabel,
    );

    await page.goto(`/unified-designer?pageId=${listPagePid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('outline-item-column_seed_title').click();
    await page.getByTestId('inspector-field-props.label').fill(runtimeColumnLabel);
    await page.getByTestId('outline-item-action_seed_create').click();
    await page.getByTestId('inspector-field-props.label').fill(runtimeActionLabel);
    await saveDesignerPage(page, listPagePid);
    await page.getByTestId('designer-mode-preview').click();
    await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();
    await expect(page.getByTestId(`runtime-page-${listPageKey}`)).toBeVisible();
    await expect(page.getByTestId('runtime-block-list_root')).toBeVisible();
    await expect(page.getByTestId('runtime-field-filter_seed_title')).toBeVisible();
    await expect(page.getByTestId('runtime-column-column_seed_title')).toContainText(
      runtimeColumnLabel,
    );
    await expect(page.getByTestId('runtime-action-action_seed_create')).toContainText(
      runtimeActionLabel,
    );

    await page.goto(`/unified-designer?pageId=${dashboardPagePid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('outline-item-widget_health').click();
    await page.getByTestId('inspector-field-props.title').fill(runtimeWidgetTitle);
    await saveDesignerPage(page, dashboardPagePid);
    await page.getByTestId('designer-mode-preview').click();
    await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();
    await expect(page.getByTestId(`runtime-page-${dashboardPageKey}`)).toBeVisible();
    await expect(page.getByTestId('runtime-block-dashboard_root')).toBeVisible();
    await expect(page.getByTestId('runtime-widget-widget_pipeline')).toBeVisible();
    await expect(page.getByTestId('runtime-widget-widget_health')).toContainText(
      runtimeWidgetTitle,
    );
  });

  test('UDW-006: drags a palette block onto the canvas and persists it', async ({ page }) => {
    expect(pagePid).toBeTruthy();

    await page.goto(`/unified-designer?pageId=${pagePid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-section_basic').click();
    await expect(page.getByTestId('inspector-selected-id')).toContainText('section_basic');
    // A custom (unbound) field is added from the Fields tab escape hatch; the bare
    // `field` leaf was removed from the Blocks palette (fields bind from the library).
    await switchResourceTab(page, 'fields');

    const paletteField = page.getByTestId('field-palette-add-field');
    await expect(paletteField).toBeVisible();
    await expect(paletteField).toBeEnabled();
    await paletteField.click();

    await expect(page.getByTestId('inspector-selected-id')).toContainText('field_new_field');
    await expect(page.getByTestId('canvas-block-field_new_field')).toBeVisible();
    await page.getByTestId('inspector-field-props.label').fill(paletteFieldLabel);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, pagePid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('outline-item-field_new_field').click();
    await expect(page.getByTestId('inspector-field-props.label')).toHaveValue(paletteFieldLabel);

    const persisted = await readPage(page, pagePid);
    const persistedPaletteField = findBlockById(persisted.blocks ?? [], 'field_new_field');
    expect(persistedPaletteField).toMatchObject({
      blockType: 'field',
      field: 'new_field',
      props: expect.objectContaining({ label: paletteFieldLabel }),
    });
  });

  test('UDW-007: reorders form fields, list columns, and toolbar actions on the canvas', async ({
    page,
  }) => {
    expect(pagePid).toBeTruthy();
    expect(listPagePid).toBeTruthy();

    await page.goto(`/unified-designer?pageId=${pagePid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('designer-mode-layout').click();
    await expectBlockBefore(page, 'field_seed_title', 'field_seed_secondary');
    await page.getByTestId('block-move-up-field_seed_secondary').click();
    await expectBlockBefore(page, 'field_seed_secondary', 'field_seed_title');
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');
    await saveDesignerPage(page, pagePid);

    let persisted = await readPage(page, pagePid);
    expectChildOrder(persisted.blocks ?? [], 'section_basic', [
      'field_seed_secondary',
      'field_seed_title',
    ]);

    await page.goto(`/unified-designer?pageId=${listPagePid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('designer-mode-layout').click();

    await expectBlockBefore(page, 'column_seed_title', 'column_seed_secondary');
    await moveBlockUpUntilBefore(page, 'column_seed_secondary', 'column_seed_title');
    await expectBlockBefore(page, 'column_seed_secondary', 'column_seed_title');

    await expectBlockBefore(page, 'action_seed_create', 'action_seed_export');
    await page.getByTestId('block-move-up-action_seed_export').click();
    await expectBlockBefore(page, 'action_seed_export', 'action_seed_create');
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');
    await saveDesignerPage(page, listPagePid);

    persisted = await readPage(page, listPagePid);
    expectChildOrder(persisted.blocks ?? [], 'list_table', [
      'column_seed_secondary',
      'column_seed_title',
    ]);
    expectChildOrder(persisted.blocks ?? [], 'list_toolbar', [
      'action_seed_export',
      'action_seed_create',
    ]);
  });

  test('UDW-008: designs a detail page with a model field and verifies runtime preview', async ({
    page,
  }) => {
    expect(detailPagePid).toBeTruthy();
    expect(fieldCode).toBeTruthy();

    await page.goto(`/unified-designer?pageId=${detailPagePid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-detail_section_summary').click();
    await expect(page.getByTestId('inspector-selected-id')).toContainText('detail_section_summary');

    await switchResourceTab(page, 'fields');
    await page.getByTestId('field-palette-search').fill(fieldCode);
    const detailFieldButton = page.getByTestId(`model-field-${fieldCode}`);
    await expect(detailFieldButton).toBeVisible({ timeout: 10000 });
    await expect(detailFieldButton).toBeEnabled();
    await dndDragTo(page, detailFieldButton, page.getByTestId('canvas-block-detail_section_summary'));

    await expect(page.getByTestId('inspector-selected-id')).toContainText(fieldBlockId);
    await expect(page.getByTestId(`canvas-block-${fieldBlockId}`)).toBeVisible();
    await page.getByTestId('inspector-field-props.label').fill(detailFieldLabel);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, detailPagePid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId(`outline-item-${fieldBlockId}`).click();
    await expect(page.getByTestId('inspector-field-props.label')).toHaveValue(detailFieldLabel);

    const persisted = await readPage(page, detailPagePid);
    const persistedDetailField = findBlockById(persisted.blocks ?? [], fieldBlockId);
    expect(persistedDetailField).toMatchObject({
      blockType: 'field',
      field: fieldCode,
      props: expect.objectContaining({ label: detailFieldLabel }),
    });

    await page.getByTestId('designer-mode-preview').click();
    await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();
    await expect(page.getByTestId(`runtime-page-${detailPageKey}`)).toBeVisible();
    await expect(page.getByTestId('runtime-block-detail_root')).toBeVisible();
    await expect(page.getByTestId(`runtime-field-${fieldBlockId}`)).toContainText(detailFieldLabel);
  });

  test('UDW-009: edits complex form layout and field rules through schema-driven inspector', async ({
    page,
  }) => {
    expect(pagePid).toBeTruthy();

    const visibleWhen = { field: 'mission_status', operator: 'equals', value: 'open' };
    const fieldVisibleWhen = { field: 'mission_type', operator: 'notEmpty' };
    const validationRules = [
      { type: 'minLength', value: 3, message: 'Too short' },
      { type: 'pattern', value: '^[A-Z].*', message: 'Start with uppercase' },
    ];

    await page.goto(`/unified-designer?pageId=${pagePid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-section_basic').click();
    await switchResourceTab(page, 'blocks');
    const nestedSection = page.getByTestId('palette-add-form-section');
    await expect(nestedSection).toBeEnabled();
    await dndDragTo(page, nestedSection, page.getByTestId('canvas-block-section_basic'));

    await expect(page.getByTestId('inspector-selected-id')).toContainText(
      'form_section_new_section',
    );
    await page.getByTestId('inspector-field-title').fill(nestedSectionTitle);
    await page.getByTestId('inspector-field-props.description').fill(nestedSectionDescription);
    await setCheckbox(page, 'inspector-field-props.collapsible', true);
    await page.getByTestId('inspector-field-props.visibleWhen').fill(JSON.stringify(visibleWhen));
    await applyJsonField(page, 'inspector-json-field-apply-props.visibleWhen');

    await switchResourceTab(page, 'outline');
    await page.getByTestId('outline-item-field_seed_title').click();
    await page.getByTestId('inspector-field-props.placeholder').fill(complexPlaceholder);
    await page.getByTestId('inspector-field-props.helpText').fill(complexHelpText);
    await setCheckbox(page, 'inspector-field-props.readOnly', true);
    await page
      .getByTestId('inspector-field-props.visibleWhen')
      .fill(JSON.stringify(fieldVisibleWhen));
    await applyJsonField(page, 'inspector-json-field-apply-props.visibleWhen');
    await page
      .getByTestId('inspector-field-props.validationRules')
      .fill(JSON.stringify(validationRules));
    await applyJsonField(page, 'inspector-json-field-apply-props.validationRules');
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, pagePid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });

    await page.getByTestId('outline-item-form_section_new_section').click();
    await expect(page.getByTestId('inspector-field-title')).toHaveValue(nestedSectionTitle);
    await expect(page.getByTestId('inspector-field-props.description')).toHaveValue(
      nestedSectionDescription,
    );
    await expect(page.getByTestId('inspector-field-props.collapsible')).toBeChecked();

    await page.getByTestId('outline-item-field_seed_title').click();
    await expect(page.getByTestId('inspector-field-props.placeholder')).toHaveValue(
      complexPlaceholder,
    );
    await expect(page.getByTestId('inspector-field-props.helpText')).toHaveValue(complexHelpText);
    await expect(page.getByTestId('inspector-field-props.readOnly')).toBeChecked();

    const persisted = await readPage(page, pagePid);
    const persistedSection = findBlockById(persisted.blocks ?? [], 'form_section_new_section');
    const persistedField = findBlockById(persisted.blocks ?? [], 'field_seed_title');

    expect(persistedSection).toMatchObject({
      blockType: 'form-section',
      title: nestedSectionTitle,
      props: expect.objectContaining({
        description: nestedSectionDescription,
        collapsible: true,
        visibleWhen,
      }),
    });
    expect(persistedField).toMatchObject({
      blockType: 'field',
      props: expect.objectContaining({
        placeholder: complexPlaceholder,
        helpText: complexHelpText,
        readOnly: true,
        visibleWhen: fieldVisibleWhen,
        validationRules,
      }),
    });
  });

  test('UDW-010: edits advanced dashboard widget settings and verifies runtime preview', async ({
    page,
  }) => {
    expect(dashboardPagePid).toBeTruthy();

    const query = { status: ['open', 'in_progress'], groupBy: 'owner' };
    const thresholds = [
      { min: 0, color: 'slate' },
      { min: 100, color: 'green' },
    ];

    await page.goto(`/unified-designer?pageId=${dashboardPagePid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-widget_pipeline').click();
    await expect(page.getByTestId('inspector-selected-id')).toContainText('widget_pipeline');
    await page.getByTestId('inspector-field-widgetType').selectOption('line-chart');
    await page.getByTestId('inspector-field-props.title').fill(advancedWidgetTitle);
    await page.getByTestId('inspector-field-props.subtitle').fill(advancedWidgetSubtitle);
    // The model inspector field is now a 'model' picker (a <select> from the
    // published meta-model list) plus a free-text manual-entry fallback. Bind the
    // model code through the manual input (the intended free-text path), which
    // also avoids racing the async model-options load.
    await page.getByTestId('inspector-field-dataSource.model-manual').fill(modelCode);
    await page.getByTestId('inspector-field-dataSource.metric').fill(advancedWidgetMetric);
    await page.getByTestId('inspector-field-dataSource.query').fill(JSON.stringify(query));
    await applyJsonField(page, 'inspector-json-field-apply-dataSource.query');
    await page.getByTestId('inspector-field-props.value').fill(advancedWidgetValue);
    await page.getByTestId('inspector-field-props.format').selectOption('number');
    await page.getByTestId('inspector-field-props.emptyText').fill(advancedWidgetEmptyText);
    await page.getByTestId('inspector-field-props.drillDownTo').fill(advancedWidgetDrilldown);
    await page.getByTestId('inspector-field-props.thresholds').fill(JSON.stringify(thresholds));
    await applyJsonField(page, 'inspector-json-field-apply-props.thresholds');
    await page.getByTestId('inspector-field-props.refreshInterval').fill('60');

    await page.getByTestId('outline-item-widget_health').click();
    await page.getByTestId('inspector-field-props.emptyText').fill(advancedWidgetEmptyText);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, dashboardPagePid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('outline-item-widget_pipeline').click();

    await expect(page.getByTestId('inspector-field-widgetType')).toHaveValue('line-chart');
    await expect(page.getByTestId('inspector-field-props.title')).toHaveValue(advancedWidgetTitle);
    await expect(page.getByTestId('inspector-field-props.subtitle')).toHaveValue(
      advancedWidgetSubtitle,
    );
    await expect(page.getByTestId('inspector-field-dataSource.model')).toHaveValue(modelCode);
    await expect(page.getByTestId('inspector-field-dataSource.metric')).toHaveValue(
      advancedWidgetMetric,
    );
    await expect(page.getByTestId('inspector-field-props.value')).toHaveValue(advancedWidgetValue);
    await expect(page.getByTestId('inspector-field-props.format')).toHaveValue('number');
    await expect(page.getByTestId('inspector-field-props.drillDownTo')).toHaveValue(
      advancedWidgetDrilldown,
    );
    await expect(page.getByTestId('inspector-field-props.refreshInterval')).toHaveValue('60');

    const persisted = await readPage(page, dashboardPagePid);
    const persistedPipeline = findBlockById(persisted.blocks ?? [], 'widget_pipeline');
    const persistedHealth = findBlockById(persisted.blocks ?? [], 'widget_health');
    expect(persistedPipeline).toMatchObject({
      blockType: 'widget',
      widgetType: 'line-chart',
      dataSource: expect.objectContaining({
        model: modelCode,
        metric: advancedWidgetMetric,
        query,
      }),
      props: expect.objectContaining({
        title: advancedWidgetTitle,
        subtitle: advancedWidgetSubtitle,
        value: advancedWidgetValue,
        format: 'number',
        emptyText: advancedWidgetEmptyText,
        drillDownTo: advancedWidgetDrilldown,
        thresholds,
        refreshInterval: 60,
      }),
    });
    expect(persistedHealth).toMatchObject({
      blockType: 'widget',
      props: expect.objectContaining({ emptyText: advancedWidgetEmptyText }),
    });

    await page.getByTestId('designer-mode-preview').click();
    await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();
    await expect(page.getByTestId('runtime-widget-widget_pipeline')).toContainText(
      advancedWidgetTitle,
    );
    await expect(page.getByTestId('runtime-widget-subtitle-widget_pipeline')).toContainText(
      advancedWidgetSubtitle,
    );
    // A line-chart is a body widget: the E1 chart-parity renderer suppresses the
    // number-card value box (props.value) in favour of the chart body / empty
    // state, so with no live data the configured empty text renders here instead
    // of the value. (props.value persistence is still asserted above.)
    await expect(page.getByTestId('runtime-widget-value-widget_pipeline')).toHaveCount(0);
    await expect(page.getByTestId('runtime-widget-empty-widget_pipeline')).toContainText(
      advancedWidgetEmptyText,
    );
    await expect(page.getByTestId('runtime-widget-meta-widget_pipeline')).toContainText(
      `${modelCode} / ${advancedWidgetMetric}`,
    );
    await expect(page.getByTestId('runtime-widget-drilldown-widget_pipeline')).toContainText(
      advancedWidgetDrilldown,
    );
    await expect(page.getByTestId('runtime-widget-empty-widget_health')).toContainText(
      advancedWidgetEmptyText,
    );
  });

  test('UDW-011: renders configured dashboard chart data and markdown content in preview', async ({
    page,
  }) => {
    expect(dashboardPagePid).toBeTruthy();

    const chartSeries = [
      { label: 'Open', value: 3 },
      { label: 'Won', value: 7 },
    ];

    await page.goto(`/unified-designer?pageId=${dashboardPagePid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-widget_health').click();
    await page.getByTestId('inspector-field-widgetType').selectOption('bar-chart');
    await page.getByTestId('inspector-field-props.title').fill(chartWidgetTitle);
    await page.getByTestId('inspector-field-props.emptyText').fill('');
    await page.getByTestId('inspector-field-props.series').fill(JSON.stringify(chartSeries));
    await applyJsonField(page, 'inspector-json-field-apply-props.series');

    await page.getByTestId('outline-item-widget_pipeline').click();
    await page.getByTestId('inspector-field-widgetType').selectOption('markdown');
    await page.getByTestId('inspector-field-props.value').fill('');
    await page.getByTestId('inspector-field-props.emptyText').fill('');
    await page.getByTestId('inspector-field-props.markdown').fill(markdownWidgetText);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, dashboardPagePid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('outline-item-widget_health').click();
    await expect(page.getByTestId('inspector-field-widgetType')).toHaveValue('bar-chart');
    await expect(page.getByTestId('inspector-field-props.title')).toHaveValue(chartWidgetTitle);
    await expect(page.getByTestId('inspector-field-props.series')).toHaveValue(/Open/);

    await page.getByTestId('outline-item-widget_pipeline').click();
    await expect(page.getByTestId('inspector-field-widgetType')).toHaveValue('markdown');
    await expect(page.getByTestId('inspector-field-props.markdown')).toHaveValue(
      markdownWidgetText,
    );

    const persisted = await readPage(page, dashboardPagePid);
    const persistedHealth = findBlockById(persisted.blocks ?? [], 'widget_health');
    const persistedPipeline = findBlockById(persisted.blocks ?? [], 'widget_pipeline');
    expect(persistedHealth).toMatchObject({
      blockType: 'widget',
      widgetType: 'bar-chart',
      props: expect.objectContaining({
        title: chartWidgetTitle,
        series: chartSeries,
      }),
    });
    expect(persistedPipeline).toMatchObject({
      blockType: 'widget',
      widgetType: 'markdown',
      props: expect.objectContaining({
        markdown: markdownWidgetText,
      }),
    });

    await page.getByTestId('designer-mode-preview').click();
    await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();
    await expect(page.getByTestId('runtime-widget-bar-widget_health-0')).toContainText('Open');
    await expect(page.getByTestId('runtime-widget-bar-widget_health-0')).toHaveAttribute(
      'data-value',
      '3',
    );
    await expect(page.getByTestId('runtime-widget-bar-widget_health-1')).toContainText('Won');
    await expect(page.getByTestId('runtime-widget-markdown-widget_pipeline')).toContainText(
      markdownWidgetText,
    );
  });

  test('UDW-012: executes configured action runtime feedback and drawer overlay in preview', async ({
    page,
  }) => {
    expect(listPagePid).toBeTruthy();

    await page.goto(`/unified-designer?pageId=${listPagePid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-action_seed_export').click();
    await expect(page.getByTestId('inspector-selected-id')).toContainText('action_seed_export');
    await page.getByTestId('inspector-field-actionType').selectOption('command');
    await page.getByTestId('inspector-field-props.label').fill(runtimeCommandLabel);
    // props.command is a `command-select` inspector field (#800 D2 rich selectors):
    // it renders a <select> over the live /api/meta/commands registry PLUS a
    // manual-entry <input> (testid `<field>-manual`) for arbitrary/preview codes
    // not in the registry. `mission.archive` (and the other codes below) are
    // preview/synthetic commands, so drive the manual input rather than the
    // <select> — `.fill()` on the <select> throws "not an <input>".
    await page.getByTestId('inspector-field-props.command-manual').fill('mission.archive');
    await setCheckbox(page, 'inspector-field-props.confirm', true);
    await page.getByTestId('inspector-field-props.feedback').fill(runtimeCommandFeedback);

    await page.getByTestId('outline-item-action_seed_create').click();
    await page.getByTestId('inspector-field-actionType').selectOption('drawer');
    await page.getByTestId('inspector-field-props.label').fill(runtimeDrawerLabel);
    await page.getByTestId('inspector-field-props.pageKey').fill(runtimeDrawerPageKey);
    await page.getByTestId('inspector-field-props.title').fill(runtimeDrawerTitle);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, listPagePid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('outline-item-action_seed_export').click();
    await expect(page.getByTestId('inspector-field-actionType')).toHaveValue('command');
    await expect(page.getByTestId('inspector-field-props.confirm')).toBeChecked();
    await expect(page.getByTestId('inspector-field-props.feedback')).toHaveValue(
      runtimeCommandFeedback,
    );

    await page.getByTestId('outline-item-action_seed_create').click();
    await expect(page.getByTestId('inspector-field-actionType')).toHaveValue('drawer');
    await expect(page.getByTestId('inspector-field-props.pageKey')).toHaveValue(
      runtimeDrawerPageKey,
    );
    await expect(page.getByTestId('inspector-field-props.title')).toHaveValue(runtimeDrawerTitle);

    const persisted = await readPage(page, listPagePid);
    const persistedCommand = findBlockById(persisted.blocks ?? [], 'action_seed_export');
    const persistedDrawer = findBlockById(persisted.blocks ?? [], 'action_seed_create');
    expect(persistedCommand).toMatchObject({
      blockType: 'action',
      actionType: 'command',
      props: expect.objectContaining({
        label: runtimeCommandLabel,
        command: 'mission.archive',
        confirm: true,
        feedback: runtimeCommandFeedback,
      }),
    });
    expect(persistedDrawer).toMatchObject({
      blockType: 'action',
      actionType: 'drawer',
      props: expect.objectContaining({
        label: runtimeDrawerLabel,
        pageKey: runtimeDrawerPageKey,
        title: runtimeDrawerTitle,
      }),
    });

    await page.getByTestId('designer-mode-preview').click();
    await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();
    await page.getByTestId('runtime-action-action_seed_export').click();
    await expect(page.getByTestId('runtime-action-confirm-action_seed_export')).toContainText(
      'Click again to confirm',
    );
    await expect(page.getByTestId('runtime-action-status-action_seed_export')).toHaveCount(0);
    await page.getByTestId('runtime-action-action_seed_export').click();
    await expect(page.getByTestId('runtime-action-status-action_seed_export')).toContainText(
      runtimeCommandFeedback,
    );

    await page.getByTestId('runtime-action-action_seed_create').click();
    await expect(page.getByTestId('runtime-action-overlay-action_seed_create')).toContainText(
      runtimeDrawerTitle,
    );
    await expect(page.getByTestId('runtime-action-overlay-action_seed_create')).toHaveAttribute(
      'data-overlay-kind',
      'drawer',
    );
    await expect(page.getByTestId('runtime-action-status-action_seed_create')).toContainText(
      'Drawer opened',
    );
  });

  test('UDW-013: executes dashboard widget query JSON through query-builder in runtime preview', async ({
    page,
  }) => {
    expect(dashboardPagePid).toBeTruthy();

    const query = {
      modelCode,
      fields: ['name', 'page_key'],
      limit: 20,
    };

    await page.goto(`/unified-designer?pageId=${dashboardPagePid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-widget_pipeline').click();
    await page.getByTestId('inspector-field-widgetType').selectOption('table');
    await page.getByTestId('inspector-field-props.title').fill(liveWidgetTitle);
    await page.getByTestId('inspector-field-dataSource.model-manual').fill(modelCode);
    await page.getByTestId('inspector-field-dataSource.executionMode').selectOption('live');
    await page.getByTestId('inspector-field-dataSource.query').fill(JSON.stringify(query));
    await applyJsonField(page, 'inspector-json-field-apply-dataSource.query');
    await page.getByTestId('inspector-field-props.value').fill('');
    await page.getByTestId('inspector-field-props.emptyText').fill('');
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, dashboardPagePid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('outline-item-widget_pipeline').click();
    await expect(page.getByTestId('inspector-field-widgetType')).toHaveValue('table');
    await expect(page.getByTestId('inspector-field-dataSource.executionMode')).toHaveValue('live');

    const queryRespPromise = page.waitForResponse(
      (response) =>
        response.url().includes('/api/query-builder/execute') &&
        response.request().method() === 'POST',
      { timeout: 15000 },
    );
    await page.getByTestId('designer-mode-preview').click();
    const queryResp = await queryRespPromise;
    expect(queryResp.status()).toBe(200);
    const queryBody = await queryResp.json();
    expect(queryBody.code).toBe('0');

    await expect(page.getByTestId('runtime-widget-widget_pipeline')).toContainText(liveWidgetTitle);
    await expect(page.getByTestId('runtime-widget-meta-widget_pipeline')).toContainText(
      `query-builder / ${modelCode}`,
    );
    await expect(page.getByTestId('runtime-widget-table-widget_pipeline')).toBeVisible();
    await expect(page.getByTestId('runtime-widget-table-widget_pipeline')).toContainText('name');
    await expect(
      page.locator('[data-testid="runtime-widget-table-widget_pipeline"] tbody tr'),
    ).toHaveCount(3);

    const persisted = await readPage(page, dashboardPagePid);
    const persistedWidget = findBlockById(persisted.blocks ?? [], 'widget_pipeline');
    expect(persistedWidget).toMatchObject({
      blockType: 'widget',
      widgetType: 'table',
      dataSource: expect.objectContaining({
        model: modelCode,
        executionMode: 'live',
        query,
      }),
      props: expect.objectContaining({ title: liveWidgetTitle }),
    });
  });

  test('UDW-014: executes live command actions through backend and shows inline errors', async ({
    page,
  }) => {
    expect(listPagePid).toBeTruthy();

    await page.goto(`/unified-designer?pageId=${listPagePid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-action_seed_export').click();
    await page.getByTestId('inspector-field-actionType').selectOption('command');
    await page.getByTestId('inspector-field-props.label').fill(liveCommandLabel);
    await page.getByTestId('inspector-field-props.command-manual').fill(liveCommandCode);
    await page.getByTestId('inspector-field-props.executionMode').selectOption('live');
    await page.getByTestId('inspector-field-props.payload').fill(
      JSON.stringify({
        source: 'unified-designer-workbench',
        uid,
        pageId: '{{page.id}}',
        pageKind: '{{page.kind}}',
        routePagePid: '{{route.query.pageId}}',
        routeSummary: '{{route.query.pageId}}/{{page.id}}',
        schemaVersion: '{{schema.version}}',
        actionSummary: '{{page.kind}}/{{action.type}}/{{block.id}}',
        nested: {
          blockType: '{{block.type}}',
          blockPath: '{{block.path}}',
          unknown: '{{record.id}}',
          __auditContext: { source: 'spoofed-nested-client' },
        },
        entries: ['{{block.id}}', '{{schema.version}}'],
        __auditContext: { source: 'spoofed-client' },
      }),
    );
    await applyJsonField(page, 'inspector-json-field-apply-props.payload');
    await page.getByTestId('inspector-field-props.feedback').fill('');
    const confirmInput = page.getByTestId('inspector-field-props.confirm');
    if (await confirmInput.isChecked()) {
      await confirmInput.uncheck();
    }
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, listPagePid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('outline-item-action_seed_export').click();
    await expect(page.getByTestId('inspector-field-props.executionMode')).toHaveValue('live');

    await page.getByTestId('designer-mode-preview').click();
    await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();
    const commandRespPromise = page.waitForResponse(
      (response) =>
        response.url().includes('/api/meta/commands/execute/') &&
        response.request().method() === 'POST',
      { timeout: 15000 },
    );
    await expect(page.getByTestId('runtime-action-action_seed_export')).toHaveAttribute(
      'data-live-execution',
      'true',
    );
    await page.getByTestId('runtime-action-action_seed_export').click();
    const commandResp = await commandRespPromise;
    expect([200, 400, 403, 404, 422, 500]).toContain(commandResp.status());
    const commandRequestBody = commandResp.request().postDataJSON() as {
      payload?: Record<string, unknown>;
      auditContext?: Record<string, unknown>;
    };
    expect(commandRequestBody.payload).toMatchObject({
      source: 'unified-designer-workbench',
      uid,
      pageId: listPageKey,
      pageKind: 'list',
      routePagePid: listPagePid,
      routeSummary: `${listPagePid}/${listPageKey}`,
      schemaVersion: 3,
      actionSummary: 'list/command/action_seed_export',
      nested: {
        blockType: 'action',
        blockPath: ['list_root', 'list_toolbar', 'action_seed_export'],
        unknown: '{{record.id}}',
      },
      entries: ['action_seed_export', 3],
    });
    expect(commandRequestBody.payload ?? {}).not.toHaveProperty('__auditContext');
    expect(commandRequestBody.payload?.nested as Record<string, unknown>).not.toHaveProperty(
      '__auditContext',
    );
    expect(commandRequestBody.auditContext).toMatchObject({
      source: 'unified-designer-runtime-preview',
      pageKind: 'list',
      schemaVersion: 3,
      blockId: 'action_seed_export',
      blockType: 'action',
      actionType: 'command',
      blockPath: ['list_root', 'list_toolbar', 'action_seed_export'],
    });
    const commandError = page.getByTestId('runtime-action-error-action_seed_export');
    await expect(commandError).toBeVisible();
    await expect(commandError).toHaveAttribute(
      'data-error-kind',
      /not-found|validation|permission|server/,
    );
    await expect(commandError).not.toContainText('no response');

    const persisted = await readPage(page, listPagePid);
    const persistedCommand = findBlockById(persisted.blocks ?? [], 'action_seed_export');
    expect(persistedCommand).toMatchObject({
      blockType: 'action',
      actionType: 'command',
      props: expect.objectContaining({
        label: liveCommandLabel,
        command: liveCommandCode,
        executionMode: 'live',
      }),
    });
  });

  test('UDW-015: executes live workflow actions through backend and shows process instance feedback', async ({
    page,
  }) => {
    expect(listPagePid).toBeTruthy();
    expect(liveWorkflowKey).toBeTruthy();

    const workflowBusinessKeyTemplate = `${liveWorkflowBusinessKey}-{{page.id}}-{{block.id}}-{{route.query.pageId}}`;
    const payload = {
      source: 'unified-designer-workbench',
      uid,
      pageId: '{{page.id}}',
      pageKind: '{{page.kind}}',
      routePagePid: '{{route.query.pageId}}',
      routeSummary: '{{route.query.pageId}}/{{page.id}}',
      schemaVersion: '{{schema.version}}',
      actionSummary: '{{page.kind}}/{{action.type}}/{{block.id}}',
      nested: {
        blockType: '{{block.type}}',
        blockPath: '{{block.path}}',
        unknown: '{{record.id}}',
        __auditContext: { source: 'spoofed-nested-client' },
      },
      entries: ['{{action.type}}', '{{block.type}}'],
      __auditContext: { source: 'spoofed-client' },
    };

    await page.goto(`/unified-designer?pageId=${listPagePid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-action_seed_export').click();
    await page.getByTestId('inspector-field-actionType').selectOption('workflow');
    await page.getByTestId('inspector-field-props.label').fill(liveWorkflowLabel);
    await page.getByTestId('inspector-field-props.workflowKey').fill(liveWorkflowKey);
    await page.getByTestId('inspector-field-props.businessKey').fill(workflowBusinessKeyTemplate);
    await page.getByTestId('inspector-field-props.payload').fill(JSON.stringify(payload));
    await applyJsonField(page, 'inspector-json-field-apply-props.payload');
    await page.getByTestId('inspector-field-props.executionMode').selectOption('live');
    await page.getByTestId('inspector-field-props.feedback').fill('');
    const confirmInput = page.getByTestId('inspector-field-props.confirm');
    if (await confirmInput.isChecked()) {
      await confirmInput.uncheck();
    }
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, listPagePid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('outline-item-action_seed_export').click();
    await expect(page.getByTestId('inspector-field-actionType')).toHaveValue('workflow');
    await expect(page.getByTestId('inspector-field-props.workflowKey')).toHaveValue(
      liveWorkflowKey,
    );
    await expect(page.getByTestId('inspector-field-props.businessKey')).toHaveValue(
      workflowBusinessKeyTemplate,
    );
    await expect(page.getByTestId('inspector-field-props.executionMode')).toHaveValue('live');

    await page.getByTestId('designer-mode-preview').click();
    await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();
    const workflowRespPromise = page.waitForResponse(
      (response) =>
        response.url().includes('/api/bpm/process-instances') &&
        response.request().method() === 'POST',
      { timeout: 15000 },
    );
    await expect(page.getByTestId('runtime-action-action_seed_export')).toHaveAttribute(
      'data-live-execution',
      'true',
    );
    await page.getByTestId('runtime-action-action_seed_export').click();
    const workflowResp = await workflowRespPromise;
    expect(workflowResp.status()).toBe(200);
    const workflowRequestBody = workflowResp.request().postDataJSON() as {
      processDefinitionId?: string;
      businessKey?: string;
      variables?: Record<string, unknown>;
    };
    expect(workflowRequestBody).toMatchObject({
      processDefinitionId: liveWorkflowKey,
      businessKey: `${liveWorkflowBusinessKey}-${listPageKey}-action_seed_export-${listPagePid}`,
      variables: {
        source: 'unified-designer-workbench',
        uid,
        pageId: listPageKey,
        pageKind: 'list',
        routePagePid: listPagePid,
        routeSummary: `${listPagePid}/${listPageKey}`,
        schemaVersion: 3,
        actionSummary: 'list/workflow/action_seed_export',
        nested: {
          blockType: 'action',
          blockPath: ['list_root', 'list_toolbar', 'action_seed_export'],
          unknown: '{{record.id}}',
        },
        entries: ['workflow', 'action'],
      },
    });
    expect(workflowRequestBody.variables ?? {}).not.toHaveProperty('__auditContext');
    expect(workflowRequestBody.variables?.nested as Record<string, unknown>).not.toHaveProperty(
      '__auditContext',
    );
    const workflowBody = await workflowResp.json();
    expect(workflowBody.code).toBe('0');
    const processInstanceId = String(workflowBody.data?.instanceId ?? '');
    expect(processInstanceId).toBeTruthy();
    await expect(page.getByTestId('runtime-action-status-action_seed_export')).toContainText(
      `Workflow started: ${processInstanceId}`,
    );

    const instanceResp = await page.request.get(`/api/bpm/process-instances/${processInstanceId}`);
    expect(instanceResp.ok(), await instanceResp.text()).toBe(true);
    const instanceBody = await instanceResp.json();
    expect(instanceBody.code).toBe('0');
    expect(instanceBody.data?.instanceId).toBe(processInstanceId);

    const persisted = await readPage(page, listPagePid);
    const persistedWorkflow = findBlockById(persisted.blocks ?? [], 'action_seed_export');
    expect(persistedWorkflow).toMatchObject({
      blockType: 'action',
      actionType: 'workflow',
      props: expect.objectContaining({
        label: liveWorkflowLabel,
        workflowKey: liveWorkflowKey,
        businessKey: workflowBusinessKeyTemplate,
        executionMode: 'live',
        payload,
      }),
    });
  });

  test('UDW-016: executes dashboard widget named query through runtime preview', async ({
    page,
  }) => {
    expect(dashboardPagePid).toBeTruthy();
    expect(liveNamedQueryCode).toBeTruthy();

    await page.goto(`/unified-designer?pageId=${dashboardPagePid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-widget_pipeline').click();
    await page.getByTestId('inspector-field-widgetType').selectOption('table');
    await page.getByTestId('inspector-field-props.title').fill(liveNamedQueryTitle);
    await page.getByTestId('inspector-field-dataSource.type').selectOption('namedQuery');
    await page.getByTestId('inspector-field-dataSource.executionMode').selectOption('live');
    await page.getByTestId('inspector-field-dataSource.queryCode-manual').fill(liveNamedQueryCode);
    await page.getByTestId('inspector-field-dataSource.parameters').fill(JSON.stringify({}));
    await applyJsonField(page, 'inspector-json-field-apply-dataSource.parameters');
    await page.getByTestId('inspector-field-dataSource.page').fill('1');
    await page.getByTestId('inspector-field-dataSource.size').fill('20');
    await page.getByTestId('inspector-field-props.value').fill('');
    await page.getByTestId('inspector-field-props.emptyText').fill('');
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, dashboardPagePid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('outline-item-widget_pipeline').click();
    await expect(page.getByTestId('inspector-field-dataSource.type')).toHaveValue('namedQuery');
    await expect(page.getByTestId('inspector-field-dataSource.executionMode')).toHaveValue('live');
    await expect(page.getByTestId('inspector-field-dataSource.queryCode')).toHaveValue(
      liveNamedQueryCode,
    );

    const namedQueryRespPromise = page.waitForResponse(
      (response) =>
        response.url().includes(`/api/meta/named-queries/${liveNamedQueryCode}/execute`) &&
        response.request().method() === 'POST',
      { timeout: 15000 },
    );
    await page.getByTestId('designer-mode-preview').click();
    const namedQueryResp = await namedQueryRespPromise;
    expect(namedQueryResp.status()).toBe(200);
    const namedQueryBody = await namedQueryResp.json();
    expect(namedQueryBody.code).toBe('0');

    await expect(page.getByTestId('runtime-widget-widget_pipeline')).toContainText(
      liveNamedQueryTitle,
    );
    await expect(page.getByTestId('runtime-widget-meta-widget_pipeline')).toContainText(
      'named-query',
    );
    await expect(page.getByTestId('runtime-widget-table-widget_pipeline')).toBeVisible();
    await expect(page.getByTestId('runtime-widget-table-widget_pipeline')).toContainText('name');
    const rowCount = await page
      .locator('[data-testid="runtime-widget-table-widget_pipeline"] tbody tr')
      .count();
    expect(rowCount).toBeGreaterThan(0);

    const persisted = await readPage(page, dashboardPagePid);
    const persistedWidget = findBlockById(persisted.blocks ?? [], 'widget_pipeline');
    expect(persistedWidget).toMatchObject({
      blockType: 'widget',
      widgetType: 'table',
      dataSource: expect.objectContaining({
        type: 'namedQuery',
        executionMode: 'live',
        queryCode: liveNamedQueryCode,
        parameters: {},
        page: 1,
        size: 20,
      }),
      props: expect.objectContaining({ title: liveNamedQueryTitle }),
    });
  });

  test('UDW-017: binds runtime form values into live command payload templates', async ({
    page,
  }) => {
    expect(pagePid).toBeTruthy();

    const submittedName = `Submitted name ${uid}`;
    const submittedPageKey = `submitted_${uid}`;
    const payload = {
      source: 'unified-designer-workbench',
      uid,
      submittedName: '{{form.values.name}}',
      submittedPageKey: '{{form.values.page_key}}',
      formSummary: '{{form.values.name}}/{{form.values.page_key}}',
      missingFormValue: '{{form.values.missing}}',
    };

    await page.goto(`/unified-designer?pageId=${pagePid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-field_seed_title').click();
    const readOnlyInput = page.getByTestId('inspector-field-props.readOnly');
    if (await readOnlyInput.isChecked()) {
      await readOnlyInput.uncheck();
    }
    await page.getByTestId('inspector-field-props.visibleWhen').fill('');
    await applyJsonField(page, 'inspector-json-field-apply-props.visibleWhen');

    const addedFieldOutlineItem = page.getByTestId(`outline-item-${fieldBlockId}`);
    if ((await addedFieldOutlineItem.count()) > 0) {
      await addedFieldOutlineItem.click();
      const addedFieldRequiredInput = page.getByTestId('inspector-field-props.required');
      if (await addedFieldRequiredInput.isChecked()) {
        await addedFieldRequiredInput.uncheck();
      }
    }

    await page.getByTestId('outline-item-action_seed_submit').click();
    await page.getByTestId('inspector-field-actionType').selectOption('command');
    await page.getByTestId('inspector-field-props.label').fill(liveFormCommandLabel);
    await page.getByTestId('inspector-field-props.command-manual').fill(liveFormCommandCode);
    await page.getByTestId('inspector-field-props.executionMode').selectOption('live');
    await page.getByTestId('inspector-field-props.payload').fill(JSON.stringify(payload));
    await applyJsonField(page, 'inspector-json-field-apply-props.payload');
    await page.getByTestId('inspector-field-props.feedback').fill('');
    const confirmInput = page.getByTestId('inspector-field-props.confirm');
    if (await confirmInput.isChecked()) {
      await confirmInput.uncheck();
    }
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');
    await saveDesignerPage(page, pagePid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('outline-item-action_seed_submit').click();
    await expect(page.getByTestId('inspector-field-props.executionMode')).toHaveValue('live');

    await page.getByTestId('designer-mode-preview').click();
    await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();
    await runtimePreviewControl(page, 'field_seed_title').fill(submittedName);
    await runtimePreviewControl(page, 'field_seed_secondary').fill(submittedPageKey);

    const commandRespPromise = page.waitForResponse(
      (response) =>
        response.url().includes('/api/meta/commands/execute/') &&
        response.request().method() === 'POST',
      { timeout: 15000 },
    );
    await expect(page.getByTestId('runtime-action-action_seed_submit')).toHaveAttribute(
      'data-live-execution',
      'true',
    );
    await page.getByTestId('runtime-action-action_seed_submit').click();
    const commandResp = await commandRespPromise;
    expect([200, 400, 403, 404, 422, 500]).toContain(commandResp.status());
    const commandRequestBody = commandResp.request().postDataJSON() as {
      payload?: Record<string, unknown>;
      auditContext?: Record<string, unknown>;
    };
    expect(commandRequestBody.payload).toMatchObject({
      source: 'unified-designer-workbench',
      uid,
      submittedName,
      submittedPageKey,
      formSummary: `${submittedName}/${submittedPageKey}`,
      missingFormValue: '{{form.values.missing}}',
    });
    expect(commandRequestBody.auditContext).toMatchObject({
      source: 'unified-designer-runtime-preview',
      pageKind: 'form',
      schemaVersion: 3,
      blockId: 'action_seed_submit',
      blockType: 'action',
      actionType: 'command',
      blockPath: ['form_root', 'form_actions', 'action_seed_submit'],
    });

    const commandError = page.getByTestId('runtime-action-error-action_seed_submit');
    await expect(commandError).toBeVisible();
    await expect(commandError).toHaveAttribute(
      'data-error-kind',
      /not-found|validation|permission|server/,
    );

    const persisted = await readPage(page, pagePid);
    const persistedCommand = findBlockById(persisted.blocks ?? [], 'action_seed_submit');
    expect(persistedCommand).toMatchObject({
      blockType: 'action',
      actionType: 'command',
      props: expect.objectContaining({
        label: liveFormCommandLabel,
        command: liveFormCommandCode,
        executionMode: 'live',
        payload,
      }),
    });
  });

  test('UDW-018: binds selected list rows into live command payload templates', async ({
    page,
  }) => {
    expect(listPagePid).toBeTruthy();

    const payload = {
      source: 'unified-designer-workbench',
      uid,
      selectedRows: '{{selected.rows}}',
      selectedRowIds: '{{selected.rowIds}}',
      selectedCount: '{{selected.count}}',
    };

    await page.goto(`/unified-designer?pageId=${listPagePid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-action_seed_bulk').click();
    await page.getByTestId('inspector-field-actionType').selectOption('command');
    await page.getByTestId('inspector-field-props.label').fill(liveBulkCommandLabel);
    await page.getByTestId('inspector-field-props.command-manual').fill(liveBulkCommandCode);
    await page.getByTestId('inspector-field-props.executionMode').selectOption('live');
    await page.getByTestId('inspector-field-props.payload').fill(JSON.stringify(payload));
    await applyJsonField(page, 'inspector-json-field-apply-props.payload');
    await page.getByTestId('inspector-field-props.feedback').fill('');
    const confirmInput = page.getByTestId('inspector-field-props.confirm');
    if (await confirmInput.isChecked()) {
      await confirmInput.uncheck();
    }
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');
    await saveDesignerPage(page, listPagePid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('outline-item-action_seed_bulk').click();
    await expect(page.getByTestId('inspector-field-props.executionMode')).toHaveValue('live');

    await page.getByTestId('designer-mode-preview').click();
    await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();
    await expect(page.getByTestId('runtime-table-row-list_table-0')).toContainText(
      selectableRowName,
    );
    await setCheckbox(page, 'runtime-row-select-list_table-0', true);

    const commandRespPromise = page.waitForResponse(
      (response) =>
        response.url().includes('/api/meta/commands/execute/') &&
        response.request().method() === 'POST',
      { timeout: 15000 },
    );
    await expect(page.getByTestId('runtime-action-action_seed_bulk')).toHaveAttribute(
      'data-live-execution',
      'true',
    );
    await page.getByTestId('runtime-action-action_seed_bulk').click();
    const commandResp = await commandRespPromise;
    expect([200, 400, 403, 404, 422, 500]).toContain(commandResp.status());
    const commandRequestBody = commandResp.request().postDataJSON() as {
      payload?: Record<string, unknown>;
      auditContext?: Record<string, unknown>;
    };
    expect(commandRequestBody.payload).toMatchObject({
      source: 'unified-designer-workbench',
      uid,
      selectedRows: [{ pid: selectableRowId, name: selectableRowName, page_key: listPageKey }],
      selectedRowIds: [selectableRowId],
      selectedCount: 1,
    });
    expect(commandRequestBody.auditContext).toMatchObject({
      source: 'unified-designer-runtime-preview',
      pageKind: 'list',
      schemaVersion: 3,
      blockId: 'action_seed_bulk',
      blockType: 'action',
      actionType: 'command',
      blockPath: ['list_root', 'list_toolbar', 'action_seed_bulk'],
    });

    const commandError = page.getByTestId('runtime-action-error-action_seed_bulk');
    await expect(commandError).toBeVisible();
    await expect(commandError).toHaveAttribute(
      'data-error-kind',
      /not-found|validation|permission|server/,
    );

    const persisted = await readPage(page, listPagePid);
    const persistedCommand = findBlockById(persisted.blocks ?? [], 'action_seed_bulk');
    expect(persistedCommand).toMatchObject({
      blockType: 'action',
      actionType: 'command',
      props: expect.objectContaining({
        label: liveBulkCommandLabel,
        command: liveBulkCommandCode,
        executionMode: 'live',
        payload,
      }),
    });
  });

  test('UDW-019: binds the clicked table row into live row action payload templates', async ({
    page,
  }) => {
    expect(listPagePid).toBeTruthy();

    const payload = {
      source: 'unified-designer-workbench',
      uid,
      currentRow: '{{current.row}}',
      currentRowId: '{{current.rowId}}',
      currentName: '{{current.row.name}}',
      currentPageKey: '{{current.row.page_key}}',
      currentSummary: '{{current.row.name}}/{{current.row.page_key}}',
      missingCurrent: '{{current.row.missing}}',
    };

    await page.goto(`/unified-designer?pageId=${listPagePid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-action_seed_row_open').click();
    await page.getByTestId('inspector-field-actionType').selectOption('command');
    await page.getByTestId('inspector-field-props.label').fill(liveRowCommandLabel);
    await page.getByTestId('inspector-field-props.command-manual').fill(liveRowCommandCode);
    await page.getByTestId('inspector-field-props.executionMode').selectOption('live');
    await page.getByTestId('inspector-field-props.payload').fill(JSON.stringify(payload));
    await applyJsonField(page, 'inspector-json-field-apply-props.payload');
    await page.getByTestId('inspector-field-props.feedback').fill('');
    const confirmInput = page.getByTestId('inspector-field-props.confirm');
    if (await confirmInput.isChecked()) {
      await confirmInput.uncheck();
    }
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');
    await saveDesignerPage(page, listPagePid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('outline-item-action_seed_row_open').click();
    await expect(page.getByTestId('inspector-field-props.executionMode')).toHaveValue('live');

    await page.getByTestId('designer-mode-preview').click();
    await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();
    await expect(page.getByTestId('runtime-table-row-list_table-0')).toContainText(
      selectableRowName,
    );

    const commandRespPromise = page.waitForResponse(
      (response) =>
        response.url().includes('/api/meta/commands/execute/') &&
        response.request().method() === 'POST',
      { timeout: 15000 },
    );
    await expect(
      page.getByTestId('runtime-row-action-list_table-action_seed_row_open-0'),
    ).toHaveAttribute('data-live-execution', 'true');
    await page.getByTestId('runtime-row-action-list_table-action_seed_row_open-0').click();
    const commandResp = await commandRespPromise;
    expect([200, 400, 403, 404, 422, 500]).toContain(commandResp.status());
    const commandRequestBody = commandResp.request().postDataJSON() as {
      payload?: Record<string, unknown>;
      auditContext?: Record<string, unknown>;
    };
    expect(commandRequestBody.payload).toMatchObject({
      source: 'unified-designer-workbench',
      uid,
      currentRow: { pid: selectableRowId, name: selectableRowName, page_key: listPageKey },
      currentRowId: selectableRowId,
      currentName: selectableRowName,
      currentPageKey: listPageKey,
      currentSummary: `${selectableRowName}/${listPageKey}`,
      missingCurrent: '{{current.row.missing}}',
    });
    expect(commandRequestBody.auditContext).toMatchObject({
      source: 'unified-designer-runtime-preview',
      pageKind: 'list',
      schemaVersion: 3,
      blockId: 'action_seed_row_open',
      blockType: 'action',
      actionType: 'command',
      blockPath: ['list_root', 'list_table', 'action_seed_row_open'],
    });
    expect(commandRequestBody.auditContext ?? {}).not.toHaveProperty('currentRow');
    expect(commandRequestBody.auditContext ?? {}).not.toHaveProperty('currentRowId');

    const commandError = page.getByTestId(
      'runtime-row-action-error-list_table-action_seed_row_open-0',
    );
    await expect(commandError).toBeVisible();
    await expect(commandError).toHaveAttribute(
      'data-error-kind',
      /not-found|validation|permission|server/,
    );

    const persisted = await readPage(page, listPagePid);
    const persistedCommand = findBlockById(persisted.blocks ?? [], 'action_seed_row_open');
    expect(persistedCommand).toMatchObject({
      blockType: 'action',
      region: 'row-actions',
      actionType: 'command',
      props: expect.objectContaining({
        label: liveRowCommandLabel,
        command: liveRowCommandCode,
        executionMode: 'live',
        payload,
      }),
    });
  });

  test('UDW-020: drags an action palette block into a table as a persisted row action', async ({
    page,
  }) => {
    expect(listPagePid).toBeTruthy();

    const paletteRowActionBlockId = 'action_new_action';
    const payload = {
      source: 'unified-designer-workbench',
      uid,
      actionSource: 'blocks-palette',
      currentRowId: '{{current.rowId}}',
      currentName: '{{current.row.name}}',
    };

    await page.goto(`/unified-designer?pageId=${listPagePid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-list_table').click();
    await switchResourceTab(page, 'blocks');
    const paletteAction = page.getByTestId('palette-add-action');
    await expect(paletteAction).toBeVisible();
    await expect(paletteAction).toBeEnabled();
    await expect(paletteAction).toHaveAttribute('aria-roledescription', 'draggable');
    await dndDragTo(page, paletteAction, page.getByTestId('canvas-block-list_table'));

    await expect(page.getByTestId('inspector-selected-id')).toContainText(paletteRowActionBlockId);
    await expect(page.getByTestId(`canvas-block-${paletteRowActionBlockId}`)).toBeVisible();
    await page.getByTestId('inspector-field-actionType').selectOption('command');
    await page.getByTestId('inspector-field-props.label').fill(paletteRowActionLabel);
    await page.getByTestId('inspector-field-props.command-manual').fill(paletteRowCommandCode);
    await page.getByTestId('inspector-field-props.executionMode').selectOption('live');
    await page.getByTestId('inspector-field-props.payload').fill(JSON.stringify(payload));
    await applyJsonField(page, 'inspector-json-field-apply-props.payload');
    const confirmInput = page.getByTestId('inspector-field-props.confirm');
    if (await confirmInput.isChecked()) {
      await confirmInput.uncheck();
    }
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');
    await saveDesignerPage(page, listPagePid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId(`outline-item-${paletteRowActionBlockId}`).click();
    await expect(page.getByTestId('inspector-field-props.executionMode')).toHaveValue('live');
    await expect(page.getByTestId('inspector-field-props.label')).toHaveValue(
      paletteRowActionLabel,
    );

    const persisted = await readPage(page, listPagePid);
    const persistedAction = findBlockById(persisted.blocks ?? [], paletteRowActionBlockId);
    expect(persistedAction).toMatchObject({
      blockType: 'action',
      region: 'row-actions',
      actionType: 'command',
      props: expect.objectContaining({
        label: paletteRowActionLabel,
        command: paletteRowCommandCode,
        executionMode: 'live',
        payload,
      }),
    });

    await page.getByTestId('designer-mode-preview').click();
    await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();
    await expect(page.getByTestId('runtime-table-row-list_table-0')).toContainText(
      selectableRowName,
    );
    await expect(
      page.getByTestId(`runtime-row-action-list_table-${paletteRowActionBlockId}-0`),
    ).toHaveAttribute('data-live-execution', 'true');

    const commandRespPromise = page.waitForResponse(
      (response) =>
        response.url().includes('/api/meta/commands/execute/') &&
        response.request().method() === 'POST',
      { timeout: 15000 },
    );
    await page.getByTestId(`runtime-row-action-list_table-${paletteRowActionBlockId}-0`).click();
    const commandResp = await commandRespPromise;
    expect([200, 400, 403, 404, 422, 500]).toContain(commandResp.status());
    const commandRequestBody = commandResp.request().postDataJSON() as {
      payload?: Record<string, unknown>;
      auditContext?: Record<string, unknown>;
    };
    expect(commandRequestBody.payload).toMatchObject({
      source: 'unified-designer-workbench',
      uid,
      actionSource: 'blocks-palette',
      currentRowId: selectableRowId,
      currentName: selectableRowName,
    });
    expect(commandRequestBody.auditContext).toMatchObject({
      source: 'unified-designer-runtime-preview',
      pageKind: 'list',
      schemaVersion: 3,
      blockId: paletteRowActionBlockId,
      blockType: 'action',
      actionType: 'command',
      blockPath: ['list_root', 'list_table', paletteRowActionBlockId],
    });
  });

  test('UDW-021: renders configured form field components in runtime preview', async ({ page }) => {
    expect(pagePid).toBeTruthy();

    await page.goto(`/unified-designer?pageId=${pagePid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-field_seed_title').click();
    await page.getByTestId('inspector-field-props.label').fill(componentCheckboxLabel);
    await page.getByTestId('inspector-field-props.component').selectOption('checkbox');

    await page.getByTestId('outline-item-field_seed_secondary').click();
    await page.getByTestId('inspector-field-props.label').fill(componentTextareaLabel);
    await page.getByTestId('inspector-field-props.component').selectOption('textarea');
    await page.getByTestId('inspector-field-props.placeholder').fill(componentTextareaPlaceholder);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');
    await saveDesignerPage(page, pagePid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('outline-item-field_seed_title').click();
    await expect(page.getByTestId('inspector-field-props.component')).toHaveValue('checkbox');
    await page.getByTestId('outline-item-field_seed_secondary').click();
    await expect(page.getByTestId('inspector-field-props.component')).toHaveValue('textarea');
    await expect(page.getByTestId('inspector-field-props.placeholder')).toHaveValue(
      componentTextareaPlaceholder,
    );

    await page.getByTestId('designer-mode-preview').click();
    await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();
    const checkboxControl = runtimePreviewControl(page, 'field_seed_title');
    await expect(checkboxControl).toBeVisible();
    await checkboxControl.click();
    await expect(checkboxControl).toBeChecked();
    const textareaControl = runtimePreviewControl(page, 'field_seed_secondary', 'textarea');
    await expect(textareaControl).toBeVisible();
    await expect(textareaControl).toHaveAttribute('placeholder', componentTextareaPlaceholder);
    await textareaControl.fill('Textarea preview value');
    await expect(textareaControl).toHaveValue('Textarea preview value');

    const persisted = await readPage(page, pagePid);
    const checkboxField = findBlockById(persisted.blocks ?? [], 'field_seed_title');
    const textareaField = findBlockById(persisted.blocks ?? [], 'field_seed_secondary');
    expect(checkboxField).toMatchObject({
      blockType: 'field',
      props: expect.objectContaining({
        label: componentCheckboxLabel,
        component: 'checkbox',
      }),
    });
    expect(textareaField).toMatchObject({
      blockType: 'field',
      props: expect.objectContaining({
        label: componentTextareaLabel,
        component: 'textarea',
        placeholder: componentTextareaPlaceholder,
      }),
    });
  });

  // Two different WYSIWYG outcomes in one test, by design:
  //  - `picker` stays on the designer's own picker renderer (`runtime-picker-*`). It is a
  //    data-source component — its options come from `pickerSource` / `pickerDataSource` /
  //    `options` executed by the designer runtime — and a platform FieldConfig cannot
  //    express that, so handing it to the platform control would drop the authored source
  //    (and the platform registry has no generic `picker`; that is what used to render
  //    "Unknown component: picker"). See isDesignerRuntimeOnlyComponent in
  //    RecursiveBlockRenderer.
  //  - `rich-text` DOES resolve to a real platform control (SmartRichTextEditor, a TipTap
  //    contenteditable), so it is asserted through the platform DOM (`.ProseMirror` +
  //    `data-placeholder`) rather than the legacy `runtime-rich-text-*` textarea.
  test('UDW-027: configures picker and rich text form controls through the inspector', async ({
    page,
  }) => {
    expect(pagePid).toBeTruthy();

    const pickerOptions = [
      { label: `Owner Alice ${uid}`, value: `alice_${uid}` },
      { label: `Owner Bob ${uid}`, value: `bob_${uid}` },
    ];

    await page.goto(`/unified-designer?pageId=${pagePid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-field_seed_title').click();
    await page.getByTestId('inspector-field-props.label').fill(componentPickerLabel);
    await page.getByTestId('inspector-field-props.component').selectOption('picker');
    await expect(page.getByTestId('inspector-field-props.pickerSource')).toBeVisible();
    await page.getByTestId('inspector-field-props.placeholder').fill(componentPickerPlaceholder);
    await page.getByTestId('inspector-field-props.pickerSource').fill('user');
    await page.getByTestId('inspector-field-props.valueField').fill('id');
    await page.getByTestId('inspector-field-props.displayField').fill('name');
    await page.getByTestId('inspector-field-props.options').fill(JSON.stringify(pickerOptions));
    await applyJsonField(page, 'inspector-json-field-apply-props.options');

    await page.getByTestId('outline-item-field_seed_secondary').click();
    await page.getByTestId('inspector-field-props.label').fill(componentRichTextLabel);
    await page.getByTestId('inspector-field-props.component').selectOption('rich-text');
    await expect(page.getByTestId('inspector-field-props.richTextToolbar')).toBeVisible();
    await expect(page.getByTestId('inspector-field-props.pickerSource')).toHaveCount(0);
    await page.getByTestId('inspector-field-props.placeholder').fill(componentRichTextPlaceholder);
    await page
      .getByTestId('inspector-field-props.richTextToolbar')
      .fill(JSON.stringify(['bold', 'italic', 'link']));
    await applyJsonField(page, 'inspector-json-field-apply-props.richTextToolbar');
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, pagePid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('outline-item-field_seed_title').click();
    await expect(page.getByTestId('inspector-field-props.component')).toHaveValue('picker');
    await expect(page.getByTestId('inspector-field-props.pickerSource')).toHaveValue('user');
    await page.getByTestId('outline-item-field_seed_secondary').click();
    await expect(page.getByTestId('inspector-field-props.component')).toHaveValue('rich-text');
    await expect(page.getByTestId('inspector-field-props.richTextToolbar')).toHaveValue(/bold/);

    await page.getByTestId('designer-mode-preview').click();
    await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();
    await expect(page.getByTestId('runtime-picker-field_seed_title')).toBeVisible();
    await expect(page.getByTestId('runtime-picker-meta-field_seed_title')).toContainText(
      'user / name / id',
    );
    await page.getByTestId('runtime-picker-field_seed_title').selectOption(`bob_${uid}`);
    await expect(page.getByTestId('runtime-picker-field_seed_title')).toHaveValue(`bob_${uid}`);

    // rich-text renders the real platform editor (TipTap): the editable surface is a
    // `.ProseMirror` contenteditable, and the configured placeholder is exposed as
    // `data-placeholder` on its empty first paragraph.
    const richTextEditor = page
      .getByTestId('runtime-field-field_seed_secondary')
      .locator('.ProseMirror');
    await expect(richTextEditor).toBeVisible();
    await expect(richTextEditor.locator('[data-placeholder]').first()).toHaveAttribute(
      'data-placeholder',
      componentRichTextPlaceholder,
    );
    await richTextEditor.click();
    await page.keyboard.type(`Formatted notes ${uid}`);
    await expect(richTextEditor).toContainText(`Formatted notes ${uid}`);

    const persisted = await readPage(page, pagePid);
    const pickerField = findBlockById(persisted.blocks ?? [], 'field_seed_title');
    const richTextField = findBlockById(persisted.blocks ?? [], 'field_seed_secondary');
    expect(pickerField).toMatchObject({
      blockType: 'field',
      props: expect.objectContaining({
        label: componentPickerLabel,
        component: 'picker',
        pickerSource: 'user',
        valueField: 'id',
        displayField: 'name',
        options: pickerOptions,
      }),
    });
    expect(richTextField).toMatchObject({
      blockType: 'field',
      props: expect.objectContaining({
        label: componentRichTextLabel,
        component: 'rich-text',
        placeholder: componentRichTextPlaceholder,
        richTextToolbar: ['bold', 'italic', 'link'],
      }),
    });
  });

  test('UDW-053: drags a relation model field as a preconfigured picker and persists it', async ({
    page,
  }) => {
    await page.goto('/unified-designer', { waitUntil: 'domcontentloaded' });
    await page.evaluate((key) => window.localStorage.removeItem(key), LOCAL_DESIGNER_STORAGE_KEY);
    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-section_basic').click();
    await switchResourceTab(page, 'fields');
    await page.getByTestId('field-palette-search').fill('owner');
    await expect(page.getByTestId('model-field-type-owner')).toHaveText('relation');
    const relationField = page.getByTestId('model-field-owner');
    await expect(relationField).toBeVisible();
    await expect(relationField).toBeEnabled();

    await dndDragTo(page, relationField, page.getByTestId('canvas-block-section_basic'));

    await expect(page.getByTestId('canvas-block-field_owner')).toBeVisible();
    await expect(page.getByTestId('inspector-selected-id')).toContainText('field_owner');
    await expect(page.getByTestId('inspector-field-props.component')).toHaveValue('picker');
    await expect(page.getByTestId('inspector-field-props.pickerDataSource')).toHaveValue('model');
    await expect(page.getByTestId('inspector-field-props.pickerSource')).toHaveValue('user');
    await expect(page.getByTestId('inspector-field-props.valueField')).toHaveValue('pid');
    await expect(page.getByTestId('inspector-field-props.displayField')).toHaveValue('displayName');

    await page.getByTestId('inspector-field-props.displayField').fill('fullName');
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');
    await page.getByTestId('designer-save').click();
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    const localDocument = (await page.evaluate((key) => {
      const raw = window.localStorage.getItem(key);
      return raw ? JSON.parse(raw) : null;
    }, LOCAL_DESIGNER_STORAGE_KEY)) as PageSchemaDto | null;
    const persistedOwner = findBlockById(localDocument?.blocks ?? [], 'field_owner');
    expect(persistedOwner).toMatchObject({
      blockType: 'field',
      field: 'owner',
      props: expect.objectContaining({
        component: 'picker',
        pickerDataSource: 'model',
        pickerSource: 'user',
        valueField: 'pid',
        displayField: 'fullName',
      }),
    });

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('outline-item-field_owner').click();
    await expect(page.getByTestId('inspector-field-props.component')).toHaveValue('picker');
    await expect(page.getByTestId('inspector-field-props.displayField')).toHaveValue('fullName');
  });

  test('UDW-054: drags a relation model field into list filters and filters preview rows', async ({
    page,
  }) => {
    const visibleTitle = `Visible relation row ${uid}`;
    const hiddenTitle = `Hidden relation row ${uid}`;

    await page.goto('/unified-designer', { waitUntil: 'domcontentloaded' });
    await page.evaluate((key) => window.localStorage.removeItem(key), LOCAL_DESIGNER_STORAGE_KEY);
    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });

    await page.getByTestId('outline-item-list_filters').click();
    await switchResourceTab(page, 'fields');
    await page.getByTestId('field-palette-search').fill('owner');
    const relationField = page.getByTestId('model-field-owner');
    await expect(relationField).toBeVisible();
    await dndDragTo(page, relationField, page.getByTestId('canvas-block-list_filters'));

    await expect(page.getByTestId('canvas-block-filter_owner')).toBeVisible();
    await expect(page.getByTestId('inspector-selected-id')).toContainText('filter_owner');
    await expect(page.getByTestId('inspector-field-props.component')).toHaveValue('picker');
    await expect(page.getByTestId('inspector-field-props.pickerDataSource')).toHaveValue('model');
    await expect(page.getByTestId('inspector-field-props.pickerSource')).toHaveValue('user');
    await expect(page.getByTestId('inspector-field-props.valueField')).toHaveValue('pid');
    await expect(page.getByTestId('inspector-field-props.displayField')).toHaveValue('displayName');

    await page.getByTestId('inspector-field-props.pickerDataSource').selectOption('static');
    await page
      .getByTestId('inspector-field-props.options')
      .fill(JSON.stringify([{ label: formTitle, value: formPageKey }]));
    await applyJsonField(page, 'inspector-json-field-apply-props.options');

    await page.getByTestId('designer-save').click();
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    const localDocument = (await page.evaluate((key) => {
      const raw = window.localStorage.getItem(key);
      return raw ? JSON.parse(raw) : null;
    }, LOCAL_DESIGNER_STORAGE_KEY)) as PageSchemaDto | null;
    expect(findBlockById(localDocument?.blocks ?? [], 'filter_owner')).toMatchObject({
      blockType: 'filter-field',
      field: 'owner',
      props: expect.objectContaining({
        component: 'picker',
        pickerDataSource: 'static',
        options: [{ label: formTitle, value: formPageKey }],
      }),
    });

    await page.evaluate(
      ({ hiddenTitle, key, ownerValue, visibleTitle }) => {
        const raw = window.localStorage.getItem(key);
        if (!raw) throw new Error('Missing local designer document');
        const document = JSON.parse(raw) as PageSchemaDto;
        const find = (blocks: DslBlock[] | undefined, blockId: string): DslBlock | null => {
          for (const block of blocks ?? []) {
            if (block.id === blockId) return block;
            const child = find(block.blocks, blockId);
            if (child) return child;
          }
          return null;
        };
        const table = find(document.blocks, 'table_customers');
        if (!table) throw new Error('Missing sample table block');
        table.props = {
          ...(table.props ?? {}),
          rows: [
            { title: visibleTitle, status: 'active', owner: ownerValue },
            { title: hiddenTitle, status: 'inactive', owner: 'not_matching_owner' },
          ],
        };
        window.localStorage.setItem(key, JSON.stringify(document));
      },
      {
        hiddenTitle,
        key: LOCAL_DESIGNER_STORAGE_KEY,
        ownerValue: formPageKey,
        visibleTitle,
      },
    );

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('designer-mode-preview').click();
    await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();
    const runtimeTable = page.getByTestId('runtime-table-table_customers');
    await expect(runtimeTable).toContainText(visibleTitle);
    await expect(runtimeTable).toContainText(hiddenTitle);
    const ownerPicker = page.getByTestId('runtime-picker-filter_owner');
    await expect(ownerPicker).toContainText(formTitle);
    await ownerPicker.selectOption(formPageKey);
    await expect(runtimeTable).toContainText(visibleTitle);
    await expect(runtimeTable).not.toContainText(hiddenTitle);
  });

  test('UDW-055: configures field, filter, and column permission codes and gates runtime data', async ({
    page,
  }) => {
    const missingPermissionCode = `meta.unified-designer.field-missing.${uid}`;
    const visibleTitle = `Permission visible row ${uid}`;
    const secretStatus = `Permission secret status ${uid}`;

    await page.goto('/unified-designer', { waitUntil: 'domcontentloaded' });
    await page.evaluate((key) => window.localStorage.removeItem(key), LOCAL_DESIGNER_STORAGE_KEY);
    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-field_customer_phone').click();
    await expect(page.getByTestId('inspector-field-props.permissionCode')).toBeVisible();
    await page.getByTestId('inspector-field-props.permissionCode-manual').fill(missingPermissionCode);

    await page.getByTestId('outline-item-filter_status').click();
    await expect(page.getByTestId('inspector-field-props.permissionCode')).toBeVisible();
    await page.getByTestId('inspector-field-props.permissionCode-manual').fill(missingPermissionCode);

    await page.getByTestId('outline-item-column_status').click();
    await expect(page.getByTestId('inspector-field-props.permissionCode')).toBeVisible();
    await page.getByTestId('inspector-field-props.permissionCode-manual').fill(missingPermissionCode);

    await page.getByTestId('outline-item-table_customers').click();
    await page
      .getByTestId('inspector-field-props.rows')
      .fill(JSON.stringify([{ title: visibleTitle, status: secretStatus }]));
    await applyJsonField(page, 'inspector-json-field-apply-props.rows');
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await page.getByTestId('designer-save').click();
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    const localDocument = (await page.evaluate((key) => {
      const raw = window.localStorage.getItem(key);
      return raw ? JSON.parse(raw) : null;
    }, LOCAL_DESIGNER_STORAGE_KEY)) as PageSchemaDto | null;
    expect(findBlockById(localDocument?.blocks ?? [], 'field_customer_phone')).toMatchObject({
      blockType: 'field',
      props: expect.objectContaining({ permissionCode: missingPermissionCode }),
    });
    expect(findBlockById(localDocument?.blocks ?? [], 'filter_status')).toMatchObject({
      blockType: 'filter-field',
      props: expect.objectContaining({ permissionCode: missingPermissionCode }),
    });
    expect(findBlockById(localDocument?.blocks ?? [], 'column_status')).toMatchObject({
      blockType: 'column',
      props: expect.objectContaining({ permissionCode: missingPermissionCode }),
    });

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('designer-mode-preview').click();
    await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();

    await expect(page.getByTestId('runtime-input-field_customer_name')).toBeVisible();
    await expect(page.getByTestId('runtime-field-field_customer_phone')).toHaveAttribute(
      'data-permission-code',
      missingPermissionCode,
    );
    await expect(page.getByTestId('runtime-field-permission-field_customer_phone')).toContainText(
      `Requires permission: ${missingPermissionCode}`,
    );
    await expect(page.getByTestId('runtime-input-field_customer_phone')).toHaveCount(0);
    await expect(page.getByTestId('runtime-field-permission-filter_status')).toContainText(
      `Requires permission: ${missingPermissionCode}`,
    );
    await expect(page.getByTestId('runtime-filter-input-filter_status')).toHaveCount(0);

    const runtimeTable = page.getByTestId('runtime-table-table_customers');
    await expect(page.getByTestId('runtime-column-column_title')).toBeVisible();
    await expect(page.getByTestId('runtime-column-column_status')).toHaveCount(0);
    await expect(runtimeTable).toContainText(visibleTitle);
    await expect(runtimeTable).not.toContainText(secretStatus);
    await expect(page.getByTestId('runtime-table-cell-table_customers-0-status')).toHaveCount(0);
  });

  test('UDW-056: configures row action visible and disabled rules from current row data', async ({
    page,
  }) => {
    expect(listPagePid).toBeTruthy();
    const conditionalActionLabel = `Conditional row action ${uid}`;
    const visibleWhen = { field: 'page_key', operator: 'equals', value: listPageKey };
    const disabledWhen = {
      field: 'current.rowId',
      operator: 'equals',
      value: selectableRowId,
    };

    await page.goto(`/unified-designer?pageId=${listPagePid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-action_seed_row_open').click();
    await page.getByTestId('inspector-field-actionType').selectOption('command');
    await page.getByTestId('inspector-field-props.label').fill(conditionalActionLabel);
    await expect(page.getByTestId('inspector-field-props.visibleWhen')).toBeVisible();
    await page.getByTestId('inspector-field-props.visibleWhen').fill(JSON.stringify(visibleWhen));
    await applyJsonField(page, 'inspector-json-field-apply-props.visibleWhen');
    await expect(page.getByTestId('inspector-field-props.disabledWhen')).toBeVisible();
    await page.getByTestId('inspector-field-props.disabledWhen').fill(JSON.stringify(disabledWhen));
    await applyJsonField(page, 'inspector-json-field-apply-props.disabledWhen');
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, listPagePid);
    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('outline-item-action_seed_row_open').click();
    expect(JSON.parse(await page.getByTestId('inspector-field-props.visibleWhen').inputValue())).toEqual(
      visibleWhen,
    );
    expect(JSON.parse(await page.getByTestId('inspector-field-props.disabledWhen').inputValue())).toEqual(
      disabledWhen,
    );

    const persisted = await readPage(page, listPagePid);
    const persistedAction = findBlockById(persisted.blocks ?? [], 'action_seed_row_open');
    expect(persistedAction).toMatchObject({
      blockType: 'action',
      region: 'row-actions',
      actionType: 'command',
      props: expect.objectContaining({
        label: conditionalActionLabel,
        visibleWhen,
        disabledWhen,
      }),
    });

    await page.getByTestId('designer-mode-preview').click();
    await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();
    const firstRowAction = page.getByTestId('runtime-row-action-list_table-action_seed_row_open-0');
    await expect(firstRowAction).toBeDisabled();
    await expect(firstRowAction).toHaveAttribute('data-condition-disabled', 'true');
    await expect(firstRowAction).toContainText(conditionalActionLabel);
    await expect(page.getByTestId('runtime-row-action-list_table-action_seed_row_open-1')).toHaveCount(
      0,
    );
  });

  test('UDW-057: configures form action visible and disabled rules from form values', async ({
    page,
  }) => {
    expect(pagePid).toBeTruthy();
    const conditionalActionLabel = `Conditional form action ${uid}`;
    const visibleWhen = { field: 'name', operator: 'notEmpty' };
    const disabledWhen = { field: 'name', operator: 'equals', value: `Blocked ${uid}` };

    await page.goto(`/unified-designer?pageId=${pagePid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-field_seed_title').click();
    await page.getByTestId('inspector-field-props.component').selectOption('input');

    await page.getByTestId('outline-item-action_seed_submit').click();
    await page.getByTestId('inspector-field-actionType').selectOption('command');
    await page.getByTestId('inspector-field-props.label').fill(conditionalActionLabel);
    await expect(page.getByTestId('inspector-field-props.visibleWhen')).toBeVisible();
    await page.getByTestId('inspector-field-props.visibleWhen').fill(JSON.stringify(visibleWhen));
    await applyJsonField(page, 'inspector-json-field-apply-props.visibleWhen');
    await expect(page.getByTestId('inspector-field-props.disabledWhen')).toBeVisible();
    await page.getByTestId('inspector-field-props.disabledWhen').fill(JSON.stringify(disabledWhen));
    await applyJsonField(page, 'inspector-json-field-apply-props.disabledWhen');
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, pagePid);
    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('outline-item-action_seed_submit').click();
    expect(JSON.parse(await page.getByTestId('inspector-field-props.visibleWhen').inputValue())).toEqual(
      visibleWhen,
    );
    expect(JSON.parse(await page.getByTestId('inspector-field-props.disabledWhen').inputValue())).toEqual(
      disabledWhen,
    );

    const persisted = await readPage(page, pagePid);
    const persistedAction = findBlockById(persisted.blocks ?? [], 'action_seed_submit');
    expect(persistedAction).toMatchObject({
      blockType: 'action',
      actionType: 'command',
      props: expect.objectContaining({
        label: conditionalActionLabel,
        visibleWhen,
        disabledWhen,
      }),
    });

    await page.getByTestId('designer-mode-preview').click();
    await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();
    await expect(page.getByTestId('runtime-action-action_seed_submit')).toHaveCount(0);

    const titleControl = runtimePreviewControl(page, 'field_seed_title');
    await titleControl.fill(`Ready ${uid}`);
    const submitAction = page.getByTestId('runtime-action-action_seed_submit');
    await expect(submitAction).toBeVisible();
    await expect(submitAction).toBeEnabled();
    await expect(submitAction).toContainText(conditionalActionLabel);

    await titleControl.fill(disabledWhen.value);
    await expect(submitAction).toBeDisabled();
    await expect(submitAction).toHaveAttribute('data-condition-disabled', 'true');
  });

  test('UDW-022: validates configured form field rules before live action execution', async ({
    page,
  }) => {
    expect(pagePid).toBeTruthy();

    const validationRules = [
      { type: 'minLength', value: 5, message: 'Validation value is too short' },
      { type: 'pattern', value: '^OK', message: 'Validation value must start with OK' },
    ];
    const validValue = `OK ${uid}`;
    const payload = {
      source: 'unified-designer-workbench',
      uid,
      validatedValue: '{{form.values.page_key}}',
    };

    await page.goto(`/unified-designer?pageId=${pagePid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-field_seed_secondary').click();
    await page.getByTestId('inspector-field-props.component').selectOption('textarea');
    const requiredInput = page.getByTestId('inspector-field-props.required');
    if (!(await requiredInput.isChecked())) {
      await requiredInput.check();
    }
    await page
      .getByTestId('inspector-field-props.validationRules')
      .fill(JSON.stringify(validationRules));
    await applyJsonField(page, 'inspector-json-field-apply-props.validationRules');

    await page.getByTestId('outline-item-action_seed_submit').click();
    await page.getByTestId('inspector-field-actionType').selectOption('command');
    await page.getByTestId('inspector-field-props.label').fill(validationCommandLabel);
    await page.getByTestId('inspector-field-props.command-manual').fill(validationCommandCode);
    await page.getByTestId('inspector-field-props.executionMode').selectOption('live');
    await page.getByTestId('inspector-field-props.payload').fill(JSON.stringify(payload));
    await applyJsonField(page, 'inspector-json-field-apply-props.payload');
    await page.getByTestId('inspector-field-props.visibleWhen').fill('');
    await applyJsonField(page, 'inspector-json-field-apply-props.visibleWhen');
    await page.getByTestId('inspector-field-props.disabledWhen').fill('');
    await applyJsonField(page, 'inspector-json-field-apply-props.disabledWhen');
    await page.getByTestId('inspector-field-props.feedback').fill('');
    const confirmInput = page.getByTestId('inspector-field-props.confirm');
    if (await confirmInput.isChecked()) {
      await confirmInput.uncheck();
    }
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');
    await saveDesignerPage(page, pagePid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('designer-mode-preview').click();
    await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();

    let commandRequestCount = 0;
    page.on('request', (request) => {
      if (request.method() === 'POST' && request.url().includes('/api/meta/commands/execute/')) {
        commandRequestCount += 1;
      }
    });

    // Under WYSIWYG the model-backed field renders the platform control; the
    // runtime form-context validation error is surfaced via ControlledFieldRenderer's
    // <FieldError> (ErrorText <p class="text-status-red">), not the legacy
    // `runtime-field-error-<blockId>` node. Scope to the field wrapper + error <p>
    // (the label asterisk is a <span>, so `p.` disambiguates).
    const secondaryError = runtimePreviewFieldError(page, 'field_seed_secondary');
    const secondaryTextarea = runtimePreviewControl(page, 'field_seed_secondary', 'textarea');

    await page.getByTestId('runtime-action-action_seed_submit').click();
    await expect(secondaryError).toHaveText('Required');
    // Rendered once — the control suppresses its own identical validationRules message
    // while the wrapper is showing the form-context error.
    await expect(secondaryError).toHaveCount(1);
    expect(commandRequestCount).toBe(0);

    await secondaryTextarea.fill('Bad');
    await page.getByTestId('runtime-action-action_seed_submit').click();
    await expect(secondaryError).toHaveText('Validation value is too short');
    expect(commandRequestCount).toBe(0);

    await secondaryTextarea.fill('Bad value');
    await page.getByTestId('runtime-action-action_seed_submit').click();
    await expect(secondaryError).toHaveText('Validation value must start with OK');
    expect(commandRequestCount).toBe(0);

    await secondaryTextarea.fill(validValue);
    const commandRespPromise = page.waitForResponse(
      (response) =>
        response.url().includes('/api/meta/commands/execute/') &&
        response.request().method() === 'POST',
      { timeout: 15000 },
    );
    await page.getByTestId('runtime-action-action_seed_submit').click();
    const commandResp = await commandRespPromise;
    expect([200, 400, 403, 404, 422, 500]).toContain(commandResp.status());
    const commandRequestBody = commandResp.request().postDataJSON() as {
      payload?: Record<string, unknown>;
    };
    expect(commandRequestBody.payload).toMatchObject({
      source: 'unified-designer-workbench',
      uid,
      validatedValue: validValue,
    });
    await expect(secondaryError).toBeHidden();

    const persisted = await readPage(page, pagePid);
    const validatedField = findBlockById(persisted.blocks ?? [], 'field_seed_secondary');
    expect(validatedField).toMatchObject({
      blockType: 'field',
      props: expect.objectContaining({
        required: true,
        validationRules,
      }),
    });
  });

  test('UDW-061: validates repeater and subform row fields before form action execution', async ({
    page,
  }) => {
    const nestedFeedback = `Nested form submitted ${uid}`;
    const nestedValidationPid = await createPageResource(page, {
      name: `UDW V3 Nested Validation ${uid}`,
      pageKey: `udw_v3_nested_validation_${uid}`,
      title: `UDW V3 Nested Validation ${uid}`,
      kind: 'form',
      modelCode,
      schemaVersion: 3,
      blocks: [
        {
          id: 'form_root',
          blockType: 'form',
          title: 'Nested validation form',
          dataSource: { model: modelCode },
          blocks: [
            {
              id: 'repeater_contacts',
              blockType: 'repeater',
              field: 'contacts',
              title: 'Contacts',
              props: {
                rows: [{ email: '' }],
              },
              blocks: [
                {
                  id: 'field_contact_email',
                  blockType: 'field',
                  field: 'email',
                  props: {
                    label: 'Email',
                    required: true,
                    component: 'input',
                  },
                },
              ],
            },
            {
              id: 'subform_tasks',
              blockType: 'subform',
              field: 'tasks',
              title: 'Tasks',
              props: {
                rows: [{ title: '' }],
              },
              blocks: [
                {
                  id: 'task_section',
                  blockType: 'form-section',
                  title: 'Task section',
                  blocks: [
                    {
                      id: 'field_task_title',
                      blockType: 'field',
                      field: 'title',
                      props: {
                        label: 'Task title',
                        required: true,
                        component: 'input',
                      },
                    },
                  ],
                },
              ],
            },
            {
              id: 'action_bar_nested_validation',
              blockType: 'action-bar',
              blocks: [
                {
                  id: 'action_submit_nested_validation',
                  blockType: 'action',
                  actionType: 'command',
                  props: {
                    label: 'Submit nested',
                    feedback: nestedFeedback,
                  },
                },
              ],
            },
          ],
        },
      ],
      extension: { e2e: true, scenario: 'unified-designer-workbench-v3-nested-validation' },
    });

    await page.goto(`/unified-designer?pageId=${nestedValidationPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('designer-mode-preview').click();
    await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();

    await page.getByTestId('runtime-action-action_submit_nested_validation').click();
    await expect(
      page.getByTestId('runtime-repeater-input-error-repeater_contacts-0-field_contact_email'),
    ).toHaveText('Required');
    await expect(
      page.getByTestId('runtime-subform-input-error-subform_tasks-0-field_task_title'),
    ).toHaveText('Required');
    await expect(
      page.getByTestId('runtime-action-status-action_submit_nested_validation'),
    ).toHaveCount(0);

    await page
      .getByTestId('runtime-repeater-input-repeater_contacts-0-field_contact_email')
      .fill(`ada.${uid}@example.com`);
    await page
      .getByTestId('runtime-subform-input-subform_tasks-0-field_task_title')
      .fill(`Prepare nested validation ${uid}`);
    await expect(
      page.getByTestId('runtime-repeater-input-error-repeater_contacts-0-field_contact_email'),
    ).toHaveCount(0);
    await expect(
      page.getByTestId('runtime-subform-input-error-subform_tasks-0-field_task_title'),
    ).toHaveCount(0);

    await page.getByTestId('runtime-action-action_submit_nested_validation').click();
    await expect(
      page.getByTestId('runtime-action-status-action_submit_nested_validation'),
    ).toHaveText(nestedFeedback);

    const persisted = await readPage(page, nestedValidationPid);
    expect(findBlockById(persisted.blocks ?? [], 'repeater_contacts')).toMatchObject({
      blockType: 'repeater',
      field: 'contacts',
    });
    expect(findBlockById(persisted.blocks ?? [], 'subform_tasks')).toMatchObject({
      blockType: 'subform',
      field: 'tasks',
    });
  });

  test('UDW-023: adds a form sub-table, configures columns, and renders preview rows', async ({
    page,
  }) => {
    expect(pagePid).toBeTruthy();
    expect(fieldCode).toBeTruthy();

    const subTableTitle = `Line items ${uid}`;
    const subTableParentField = 'pid';
    const subTableChildField = 'page_schema_id';
    const subTablePreviewValue = `Nested value ${uid}`;
    const subTableRows = [{ [fieldCode]: subTablePreviewValue }];

    await page.goto(`/unified-designer?pageId=${pagePid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-section_basic').click();
    await switchResourceTab(page, 'blocks');
    await page.getByTestId('palette-add-sub-table').click();

    await expect(page.getByTestId('inspector-selected-id')).toContainText(
      'sub_table_new_sub_table',
    );
    await page.getByTestId('inspector-field-title').fill(subTableTitle);
    await page.getByTestId('inspector-field-dataSource.model-manual').fill(modelCode);
    await page.getByTestId('inspector-field-dataSource.parentField').fill(subTableParentField);
    await page.getByTestId('inspector-field-dataSource.childField').fill(subTableChildField);
    await page.getByTestId('inspector-field-props.rows').fill(JSON.stringify(subTableRows));
    await applyJsonField(page, 'inspector-json-field-apply-props.rows');

    await switchResourceTab(page, 'fields');
    await page.getByTestId('field-palette-search').fill(fieldCode);
    await page.getByTestId(`model-field-${fieldCode}`).click();
    await expect(page.getByTestId(`canvas-block-${columnBlockId}`)).toBeVisible();
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, pagePid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('outline-item-sub_table_new_sub_table').click();
    await expect(page.getByTestId('inspector-field-title')).toHaveValue(subTableTitle);
    await expect(page.getByTestId('inspector-field-dataSource.model')).toHaveValue(modelCode);

    await page.getByTestId('designer-mode-preview').click();
    await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();
    await expect(page.getByTestId('runtime-block-sub_table_new_sub_table')).toContainText(
      subTableTitle,
    );
    await expect(
      page.getByTestId(`runtime-table-cell-sub_table_new_sub_table-0-${fieldCode}`),
    ).toHaveText(subTablePreviewValue);

    const persisted = await readPage(page, pagePid);
    const persistedSubTable = findBlockById(persisted.blocks ?? [], 'sub_table_new_sub_table');
    expect(persistedSubTable).toMatchObject({
      blockType: 'sub-table',
      title: subTableTitle,
      dataSource: expect.objectContaining({
        model: modelCode,
        parentField: subTableParentField,
        childField: subTableChildField,
      }),
      props: expect.objectContaining({
        rows: subTableRows,
      }),
    });
    expect(persistedSubTable?.blocks?.[0]).toMatchObject({
      blockType: 'column',
      field: fieldCode,
    });
  });

  test('UDW-024: configures select field options through schema-driven inspector', async ({
    page,
  }) => {
    expect(pagePid).toBeTruthy();

    const selectOptions = [
      { label: `Option A ${uid}`, value: `option_a_${uid}` },
      { label: `Option B ${uid}`, value: `option_b_${uid}` },
    ];

    await page.goto(`/unified-designer?pageId=${pagePid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-field_seed_secondary').click();
    await page.getByTestId('inspector-field-props.component').selectOption('select');
    await page.getByTestId('inspector-field-props.options').fill(JSON.stringify(selectOptions));
    await applyJsonField(page, 'inspector-json-field-apply-props.options');
    const requiredInput = page.getByTestId('inspector-field-props.required');
    if (await requiredInput.isChecked()) {
      await requiredInput.uncheck();
    }
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, pagePid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('outline-item-field_seed_secondary').click();
    await expect(page.getByTestId('inspector-field-props.component')).toHaveValue('select');
    await expect(page.getByTestId('inspector-field-props.options')).toHaveValue(
      new RegExp(selectOptions[0].label),
    );

    await page.getByTestId('designer-mode-preview').click();
    await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();
    // WYSIWYG renders the platform SmartSelect (a Radix combobox), not a native
    // <select>: the trigger is `select-trigger-<fieldCode>` and options are portal
    // `[role="option"]` nodes, so open + click the option rather than selectOption().
    const selectTrigger = runtimePreviewSelectTrigger(page, 'field_seed_secondary');
    await selectTrigger.click();
    await expect(page.getByRole('option', { name: selectOptions[0].label })).toBeVisible();
    await page.getByRole('option', { name: selectOptions[1].label }).click();
    await expect(selectTrigger).toContainText(selectOptions[1].label);

    const persisted = await readPage(page, pagePid);
    const selectField = findBlockById(persisted.blocks ?? [], 'field_seed_secondary');
    expect(selectField).toMatchObject({
      blockType: 'field',
      props: expect.objectContaining({
        component: 'select',
        options: selectOptions,
      }),
    });
  });

  test('UDW-025: swaps form field positions by dragging canvas blocks in layout mode', async ({
    page,
  }) => {
    expect(pagePid).toBeTruthy();

    await page.goto(`/unified-designer?pageId=${pagePid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('designer-mode-layout').click();
    const [movingBlockId, targetBlockId] = await swapCanvasBlocksByPointerDrag(
      page,
      'field_seed_title',
      'field_seed_secondary',
    );
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, pagePid);

    const persisted = await readPage(page, pagePid);
    expectChildOrder(persisted.blocks ?? [], 'section_basic', [movingBlockId, targetBlockId]);
  });

  test('UDW-058: changes form field span with layout quick controls and persists preview grid', async ({
    page,
  }) => {
    expect(pagePid).toBeTruthy();

    await page.goto(`/unified-designer?pageId=${pagePid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-field_seed_title').click();
    await page.getByTestId('inspector-field-props.visibleWhen').fill('');
    await applyJsonField(page, 'inspector-json-field-apply-props.visibleWhen');

    await page.getByTestId('designer-mode-layout').click();
    await expect(page.getByTestId('field-span-controls-field_seed_title')).toBeVisible();
    await page.getByTestId('field-span-field_seed_title-12').click();
    await expect(page.getByTestId('canvas-block-field_seed_title')).toHaveAttribute(
      'data-layout-span',
      '12',
    );
    await expect(page.getByTestId('inspector-field-layout.span')).toHaveValue('12');
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, pagePid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('outline-item-field_seed_title').click();
    await expect(page.getByTestId('inspector-field-layout.span')).toHaveValue('12');

    const persisted = await readPage(page, pagePid);
    const resizedField = findBlockById(persisted.blocks ?? [], 'field_seed_title');
    expect(resizedField).toMatchObject({
      blockType: 'field',
      layout: expect.objectContaining({ span: 12 }),
    });

    await page.getByTestId('designer-mode-preview').click();
    await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();
    const runtimeGridColumn = await page
      .getByTestId('runtime-field-field_seed_title')
      .evaluate((element) => getComputedStyle(element).gridColumn);
    expect(runtimeGridColumn).toContain('span 12');
  });

  test('UDW-059: applies AI fill suggestions into runtime form fields', async ({ page }) => {
    const aiValue = `AI generated title ${uid}`;
    const aiFeedback = `AI copied values ${uid}`;
    const aiFillPid = await createPageResource(page, {
      name: `UDW V3 AI Fill Form ${uid}`,
      pageKey: `udw_v3_ai_fill_form_${uid}`,
      title: `UDW V3 AI Fill Form ${uid}`,
      kind: 'form',
      modelCode,
      schemaVersion: 3,
      blocks: [
        {
          id: 'form_root',
          blockType: 'form',
          title: 'AI fill form',
          dataSource: { model: modelCode },
          layout: { span: 12 },
          blocks: [
            {
              id: 'ai_form_helper',
              blockType: 'ai-fill-banner',
              title: `AI form helper ${uid}`,
              props: {
                description: `Generated values for form ${uid}`,
                feedback: aiFeedback,
                suggestedFields: [{ field: 'name', label: 'Name', value: aiValue }],
              },
            },
            {
              id: 'field_ai_target',
              blockType: 'field',
              field: 'name',
              layout: { span: 12 },
              props: { label: `AI target ${uid}`, component: 'input' },
            },
          ],
        },
      ],
      extension: { e2e: true, scenario: 'unified-designer-workbench-v3-ai-fill-form' },
    });

    await page.goto(`/unified-designer?pageId=${aiFillPid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('outline-item-ai_form_helper').click();
    await expect(page.getByTestId('inspector-field-props.suggestedFields')).toHaveValue(
      new RegExp(aiValue),
    );

    await page.getByTestId('designer-mode-preview').click();
    await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();
    const aiTargetControl = runtimePreviewControl(page, 'field_ai_target');
    await expect(aiTargetControl).toHaveValue('');
    await page.getByTestId('runtime-ai-fill-apply-ai_form_helper').click();
    await expect(aiTargetControl).toHaveValue(aiValue);
    await expect(page.getByTestId('runtime-ai-fill-status-ai_form_helper')).toHaveText(aiFeedback);

    const persisted = await readPage(page, aiFillPid);
    expect(findBlockById(persisted.blocks ?? [], 'ai_form_helper')).toMatchObject({
      blockType: 'ai-fill-banner',
      props: expect.objectContaining({
        suggestedFields: [{ field: 'name', label: 'Name', value: aiValue }],
      }),
    });
  });

  test('UDW-060: applies live named-query AI fill suggestions into runtime form fields', async ({
    page,
  }) => {
    const liveAiQueryCode = stableBlockId('udw_live_ai_form', uid);
    const liveAiValue = `Live AI named query value ${uid}`;
    const liveAiFeedback = `Live named query values copied ${uid}`;
    const liveAiDataSource = {
      type: 'namedQuery',
      executionMode: 'live',
      queryCode: liveAiQueryCode,
      page: 1,
      size: 1,
    };

    await ensureNamedQuery(page, {
      code: liveAiQueryCode,
      title: `UDW live AI form ${uid}`,
      description: 'Auto-generated for Unified Designer live AI form E2E',
      fromSql: 'ab_page_schema p',
    });

    for (const field of [
      {
        fieldCode: 'field',
        columnExpr: "'page_key'",
        dataType: 'string',
        displayName: 'Suggested field',
        sortable: false,
        searchable: false,
        sortOrder: 1,
      },
      {
        fieldCode: 'label',
        columnExpr: "'Page key'",
        dataType: 'string',
        displayName: 'Suggested label',
        sortable: false,
        searchable: false,
        sortOrder: 2,
      },
      {
        fieldCode: 'value',
        columnExpr: `'${liveAiValue}'`,
        dataType: 'string',
        displayName: 'Suggested value',
        sortable: false,
        searchable: false,
        sortOrder: 3,
      },
      {
        fieldCode: 'feedback',
        columnExpr: `'${liveAiFeedback}'`,
        dataType: 'string',
        displayName: 'AI feedback',
        sortable: false,
        searchable: false,
        sortOrder: 4,
      },
    ]) {
      await ensureNamedQueryField(page, liveAiQueryCode, field);
    }

    const aiLiveFillPid = await createPageResource(page, {
      name: `UDW V3 AI Live Fill Form ${uid}`,
      pageKey: `udw_v3_ai_live_fill_form_${uid}`,
      title: `UDW V3 AI Live Fill Form ${uid}`,
      kind: 'form',
      modelCode,
      schemaVersion: 3,
      blocks: [
        {
          id: 'form_root',
          blockType: 'form',
          title: 'AI live fill form',
          dataSource: { model: modelCode },
          layout: { span: 12 },
          blocks: [
            {
              id: 'ai_live_form_helper',
              blockType: 'ai-fill-banner',
              title: `Live AI form helper ${uid}`,
              dataSource: liveAiDataSource,
              props: {
                description: 'Live AI suggestions are loaded from a named query',
                feedback: 'Static feedback should be replaced by live data',
              },
            },
            {
              id: 'field_ai_live_target',
              blockType: 'field',
              field: 'page_key',
              layout: { span: 12 },
              props: { label: `AI live target ${uid}`, component: 'input' },
            },
          ],
        },
      ],
      extension: { e2e: true, scenario: 'unified-designer-workbench-v3-ai-live-fill-form' },
    });

    await page.goto(`/unified-designer?pageId=${aiLiveFillPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('outline-item-ai_live_form_helper').click();
    await expect(page.getByTestId('inspector-field-dataSource.type')).toHaveValue('namedQuery');
    await expect(page.getByTestId('inspector-field-dataSource.executionMode')).toHaveValue('live');
    await expect(page.getByTestId('inspector-field-dataSource.queryCode')).toHaveValue(
      liveAiQueryCode,
    );

    const helperRespPromise = page.waitForResponse(
      (response) =>
        response.url().includes(`/api/meta/named-queries/${liveAiQueryCode}/execute`) &&
        response.request().method() === 'POST',
      { timeout: 15000 },
    );
    await page.getByTestId('designer-mode-preview').click();
    const helperResp = await helperRespPromise;
    expect(helperResp.status()).toBe(200);
    const helperRequestBody = helperResp.request().postDataJSON() as {
      page?: number;
      size?: number;
      executeQuery?: boolean;
    };
    expect(helperRequestBody).toMatchObject({ page: 1, size: 1, executeQuery: true });
    const helperBody = await helperResp.json();
    expect(helperBody.code).toBe('0');
    const rows = (helperBody.data?.records ?? []) as Array<Record<string, unknown>>;
    expect(rows.length).toBeGreaterThan(0);
    const firstRow = rows[0] ?? {};
    expect(firstRow).toMatchObject({
      field: 'page_key',
      label: 'Page key',
      value: liveAiValue,
      feedback: liveAiFeedback,
    });

    await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();
    await expect(page.getByTestId('runtime-helper-source-ai_live_form_helper')).toHaveText(
      'named-query',
    );
    await expect(page.getByTestId('runtime-ai-fill-field-ai_live_form_helper-0')).toContainText(
      liveAiValue,
    );
    const aiLiveTargetControl = runtimePreviewControl(page, 'field_ai_live_target');
    await expect(aiLiveTargetControl).toHaveValue('');
    await page.getByTestId('runtime-ai-fill-apply-ai_live_form_helper').click();
    await expect(aiLiveTargetControl).toHaveValue(liveAiValue);
    await expect(page.getByTestId('runtime-ai-fill-status-ai_live_form_helper')).toHaveText(
      liveAiFeedback,
    );

    const persisted = await readPage(page, aiLiveFillPid);
    expect(findBlockById(persisted.blocks ?? [], 'ai_live_form_helper')).toMatchObject({
      blockType: 'ai-fill-banner',
      dataSource: expect.objectContaining(liveAiDataSource),
    });
  });

  test('UDW-026: swaps list columns and toolbar actions by dragging canvas blocks in layout mode', async ({
    page,
  }) => {
    expect(listPagePid).toBeTruthy();

    await page.goto(`/unified-designer?pageId=${listPagePid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('designer-mode-layout').click();
    const columnOrder = await swapCanvasBlocksByPointerDrag(
      page,
      'column_seed_title',
      'column_seed_secondary',
    );
    const actionOrder = await swapCanvasBlocksByPointerDrag(
      page,
      'action_seed_create',
      'action_seed_export',
    );
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, listPagePid);

    const persisted = await readPage(page, listPagePid);
    expectChildOrder(persisted.blocks ?? [], 'list_table', columnOrder);
    expectChildOrder(persisted.blocks ?? [], 'list_toolbar', actionOrder);
  });

  // Under true-WYSIWYG the model-backed `upload` field renders the platform SmartUpload:
  // a hidden `upload-input-<fieldCode>` inside the block wrapper, one
  // `upload-file-<fieldCode>` row per accepted file, and an "已上传 x/y" counter — the
  // legacy `runtime-upload-*` / `runtime-upload-files-*` nodes are gone.
  // `buildPreviewFieldConfig` translates the designer's `maxFiles` into the platform
  // uploader's `maxCount`, so the configured limit is what actually gates the selection
  // (before the translation the uploader kept its default limit of 1: "已上传 1/1").
  test('UDW-028: configures upload constraints and shows selected file feedback in preview', async ({
    page,
  }) => {
    expect(pagePid).toBeTruthy();

    await page.goto(`/unified-designer?pageId=${pagePid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-field_seed_title').click();
    await page.getByTestId('inspector-field-props.label').fill(componentUploadLabel);
    await page.getByTestId('inspector-field-props.component').selectOption('upload');
    await expect(page.getByTestId('inspector-field-props.accept')).toBeVisible();
    await page.getByTestId('inspector-field-props.accept').fill(componentUploadAccept);
    await setCheckbox(page, 'inspector-field-props.multiple', true);
    await page.getByTestId('inspector-field-props.maxFiles').fill('2');
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, pagePid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('outline-item-field_seed_title').click();
    await expect(page.getByTestId('inspector-field-props.component')).toHaveValue('upload');
    await expect(page.getByTestId('inspector-field-props.accept')).toHaveValue(
      componentUploadAccept,
    );
    await expect(page.getByTestId('inspector-field-props.multiple')).toBeChecked();
    await expect(page.getByTestId('inspector-field-props.maxFiles')).toHaveValue('2');

    await page.getByTestId('designer-mode-preview').click();
    await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();
    // `field_seed_title` binds the model field `name`, and SmartUpload keys its testids on
    // the field code — scope by the block wrapper so the selector stays unambiguous.
    const uploadBlock = page.getByTestId('runtime-field-field_seed_title');
    const uploadInput = uploadBlock.locator('[data-testid="upload-input-name"]');
    await expect(uploadInput).toHaveAttribute('accept', componentUploadAccept);
    await expect(uploadInput).toHaveJSProperty('multiple', true);
    // The configured limit (not the default 1) drives the counter.
    await expect(uploadBlock).toContainText('已上传 0/2');
    // All three are `.pdf`, so `accept` cannot be what rejects the third one — only the
    // translated maxFiles → maxCount limit can.
    await uploadInput.setInputFiles([
      { name: `first-${uid}.pdf`, mimeType: 'application/pdf', buffer: Buffer.from('first') },
      { name: `second-${uid}.pdf`, mimeType: 'application/pdf', buffer: Buffer.from('second') },
      { name: `third-${uid}.pdf`, mimeType: 'application/pdf', buffer: Buffer.from('third') },
    ]);
    await expect(uploadBlock.getByTestId('upload-file-name')).toHaveCount(2);
    await expect(uploadBlock).toContainText(`first-${uid}.pdf`);
    await expect(uploadBlock).toContainText(`second-${uid}.pdf`);
    await expect(uploadBlock).not.toContainText(`third-${uid}.pdf`);
    await expect(uploadBlock).toContainText('已上传 2/2');

    const persisted = await readPage(page, pagePid);
    const uploadField = findBlockById(persisted.blocks ?? [], 'field_seed_title');
    expect(uploadField).toMatchObject({
      blockType: 'field',
      props: expect.objectContaining({
        label: componentUploadLabel,
        component: 'upload',
        accept: componentUploadAccept,
        multiple: true,
        maxFiles: 2,
      }),
    });

    // Restore `field_seed_title` before leaving: this suite is `describe.serial` and the
    // two seed fields are shared, so `props.multiple: true` would survive into every
    // later test. It is not inert leftover — a field switched to `select` later still
    // carries it, and SmartSelect's multiple mode renders a native <select multiple>
    // (role=listbox) instead of the Radix combobox, which silently breaks any downstream
    // select interaction (UDW-029). It also cannot be cleaned up downstream: the
    // `props.multiple` inspector control only exists while the component is `upload`
    // (uploadFieldFields in InspectorSchemaRegistry), so the checkbox is gone the moment
    // another test picks a different component. Uncheck it while the control is still
    // rendered, then put the component back to the seed's `input`.
    await page.getByTestId('designer-mode-edit').click();
    await page.getByTestId('outline-item-field_seed_title').click();
    await setCheckbox(page, 'inspector-field-props.multiple', false);
    await page.getByTestId('inspector-field-props.component').selectOption('input');
    await saveDesignerPage(page, pagePid);

    // Assert the restore actually persisted — a silently no-op cleanup would poison the
    // rest of the chain exactly like no cleanup at all.
    const restored = await readPage(page, pagePid);
    expect(findBlockById(restored.blocks ?? [], 'field_seed_title')).toMatchObject({
      blockType: 'field',
      props: expect.objectContaining({ component: 'input', multiple: false }),
    });
  });

  test('UDW-029: applies field visibleWhen rules from runtime form values', async ({ page }) => {
    expect(pagePid).toBeTruthy();

    const controllerOptions = [
      { label: `Hide dependent ${uid}`, value: 'hide' },
      { label: `Show dependent ${uid}`, value: 'show' },
    ];
    const dependentVisibleWhen = { field: 'name', operator: 'equals', value: 'show' };
    const controllerLabel = `Visibility controller ${uid}`;
    const dependentLabel = `Conditional dependent ${uid}`;

    await page.goto(`/unified-designer?pageId=${pagePid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-field_seed_title').click();
    await page.getByTestId('inspector-field-props.label').fill(controllerLabel);
    await page.getByTestId('inspector-field-props.component').selectOption('select');
    await page.getByTestId('inspector-field-props.options').fill(JSON.stringify(controllerOptions));
    await applyJsonField(page, 'inspector-json-field-apply-props.options');

    await page.getByTestId('outline-item-field_seed_secondary').click();
    await page.getByTestId('inspector-field-props.label').fill(dependentLabel);
    await page.getByTestId('inspector-field-props.component').selectOption('input');
    await page
      .getByTestId('inspector-field-props.visibleWhen')
      .fill(JSON.stringify(dependentVisibleWhen));
    await applyJsonField(page, 'inspector-json-field-apply-props.visibleWhen');
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, pagePid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('outline-item-field_seed_secondary').click();
    await expect(page.getByTestId('inspector-field-props.visibleWhen')).toContainText('equals');

    await page.getByTestId('designer-mode-preview').click();
    await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();
    // WYSIWYG: the controller renders a SmartSelect (Radix combobox). Pick options
    // by label; selecting drives formContext.values['name'], which gates the
    // dependent field's visibleWhen.
    await expect(runtimePreviewSelectTrigger(page, 'field_seed_title')).toBeVisible();
    await expect(page.getByTestId('runtime-field-field_seed_secondary')).toHaveCount(0);

    await selectRuntimePreviewOption(page, 'field_seed_title', controllerOptions[0].label);
    await expect(page.getByTestId('runtime-field-field_seed_secondary')).toHaveCount(0);

    await selectRuntimePreviewOption(page, 'field_seed_title', controllerOptions[1].label);
    await expect(page.getByTestId('runtime-field-field_seed_secondary')).toBeVisible();
    await expect(page.getByTestId('runtime-field-field_seed_secondary')).toContainText(
      dependentLabel,
    );

    const persisted = await readPage(page, pagePid);
    const controllerField = findBlockById(persisted.blocks ?? [], 'field_seed_title');
    const dependentField = findBlockById(persisted.blocks ?? [], 'field_seed_secondary');
    expect(controllerField).toMatchObject({
      blockType: 'field',
      props: expect.objectContaining({
        label: controllerLabel,
        component: 'select',
        options: controllerOptions,
      }),
    });
    expect(dependentField).toMatchObject({
      blockType: 'field',
      props: expect.objectContaining({
        label: dependentLabel,
        component: 'input',
        visibleWhen: dependentVisibleWhen,
      }),
    });
  });

  // `picker` keeps the designer's own renderer under true-WYSIWYG (see UDW-027): the
  // authored `pickerDataSource: model` option source is executed by the designer runtime
  // through /api/query-builder/execute, which a platform FieldConfig cannot express.
  test('UDW-030: configures a model-backed picker and reloads dynamic options in preview', async ({
    page,
  }) => {
    expect(pagePid).toBeTruthy();

    const dynamicPickerLabel = `Dynamic page picker ${uid}`;
    const dynamicPickerPlaceholder = `Choose page ${uid}`;

    await page.goto(`/unified-designer?pageId=${pagePid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-field_seed_title').click();
    await page.getByTestId('inspector-field-props.label').fill(dynamicPickerLabel);
    await page.getByTestId('inspector-field-props.component').selectOption('picker');
    await expect(page.getByTestId('inspector-field-props.pickerDataSource')).toBeVisible();
    await page.getByTestId('inspector-field-props.placeholder').fill(dynamicPickerPlaceholder);
    await page.getByTestId('inspector-field-props.pickerDataSource').selectOption('model');
    await page.getByTestId('inspector-field-props.pickerSource').fill(modelCode);
    await page.getByTestId('inspector-field-props.valueField').fill('page_key');
    await page.getByTestId('inspector-field-props.displayField').fill('name');
    await page.getByTestId('inspector-field-props.pageSize').fill('1000');
    await page.getByTestId('inspector-field-props.options').fill('[]');
    await applyJsonField(page, 'inspector-json-field-apply-props.options');
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, pagePid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('outline-item-field_seed_title').click();
    await expect(page.getByTestId('inspector-field-props.component')).toHaveValue('picker');
    await expect(page.getByTestId('inspector-field-props.pickerDataSource')).toHaveValue('model');
    await expect(page.getByTestId('inspector-field-props.pickerSource')).toHaveValue(modelCode);
    await expect(page.getByTestId('inspector-field-props.valueField')).toHaveValue('page_key');
    await expect(page.getByTestId('inspector-field-props.displayField')).toHaveValue('name');
    await expect(page.getByTestId('inspector-field-props.pageSize')).toHaveValue('1000');

    const pickerOptionsRespPromise = page.waitForResponse(
      (response) =>
        response.url().includes('/api/query-builder/execute') &&
        response.request().method() === 'POST',
      { timeout: 15000 },
    );
    await page.getByTestId('designer-mode-preview').click();
    await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();
    const pickerOptionsResp = await pickerOptionsRespPromise;
    expect(pickerOptionsResp.status()).toBe(200);
    const pickerRequestBody = pickerOptionsResp.request().postDataJSON() as {
      modelCode?: string;
      fields?: string[];
      limit?: number;
    };
    expect(pickerRequestBody).toMatchObject({
      modelCode,
      fields: ['page_key', 'name'],
      limit: 1000,
    });
    const pickerOptionsBody = await pickerOptionsResp.json();
    expect(pickerOptionsBody.code).toBe('0');
    const pickerRows = (pickerOptionsBody.data ?? []) as Array<Record<string, unknown>>;
    const formPickerRow = pickerRows.find((row) => row.page_key === formPageKey) ?? pickerRows[0];
    expect(formPickerRow).toBeTruthy();
    const expectedPickerValue = String(formPickerRow?.page_key ?? '');
    expect(expectedPickerValue).toBeTruthy();
    const expectedPickerLabel = String(formPickerRow?.name ?? expectedPickerValue);

    const picker = page.getByTestId('runtime-picker-field_seed_title');
    await expect(picker).toBeVisible();
    await expect(picker.locator('option', { hasText: expectedPickerLabel })).toHaveCount(1);
    await expect(page.getByTestId('runtime-picker-meta-field_seed_title')).toContainText(
      'model / page_schema / name / page_key',
    );
    await picker.selectOption(expectedPickerValue);
    await expect(picker).toHaveValue(expectedPickerValue);

    const persisted = await readPage(page, pagePid);
    const pickerField = findBlockById(persisted.blocks ?? [], 'field_seed_title');
    expect(pickerField).toMatchObject({
      blockType: 'field',
      props: expect.objectContaining({
        label: dynamicPickerLabel,
        component: 'picker',
        pickerDataSource: 'model',
        pickerSource: modelCode,
        valueField: 'page_key',
        displayField: 'name',
        pageSize: 1000,
        options: [],
      }),
    });
  });

  // `picker` keeps the designer's own renderer under true-WYSIWYG (see UDW-027); the
  // authored named-query option source has no platform FieldConfig equivalent.
  test('UDW-031: configures a named-query picker and reloads dynamic options in preview', async ({
    page,
  }) => {
    expect(pagePid).toBeTruthy();
    expect(liveNamedQueryCode).toBeTruthy();

    const namedQueryPickerLabel = `Named query page picker ${uid}`;
    const namedQueryPickerPlaceholder = `Choose named query page ${uid}`;

    await page.goto(`/unified-designer?pageId=${pagePid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-field_seed_secondary').click();
    await page.getByTestId('inspector-field-props.label').fill(namedQueryPickerLabel);
    await page.getByTestId('inspector-field-props.component').selectOption('picker');
    await expect(page.getByTestId('inspector-field-props.pickerDataSource')).toBeVisible();
    await page.getByTestId('inspector-field-props.placeholder').fill(namedQueryPickerPlaceholder);
    await page.getByTestId('inspector-field-props.pickerDataSource').selectOption('named-query');
    await page.getByTestId('inspector-field-props.pickerQueryCode').fill(liveNamedQueryCode);
    await page.getByTestId('inspector-field-props.valueField').fill('page_key');
    await page.getByTestId('inspector-field-props.displayField').fill('name');
    await page.getByTestId('inspector-field-props.pageSize').fill('1000');
    await page.getByTestId('inspector-field-props.pickerParameters').fill(JSON.stringify({}));
    await applyJsonField(page, 'inspector-json-field-apply-props.pickerParameters');
    await page.getByTestId('inspector-field-props.options').fill('[]');
    await applyJsonField(page, 'inspector-json-field-apply-props.options');
    await page.getByTestId('inspector-field-props.visibleWhen').fill('');
    await applyJsonField(page, 'inspector-json-field-apply-props.visibleWhen');
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, pagePid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('outline-item-field_seed_secondary').click();
    await expect(page.getByTestId('inspector-field-props.component')).toHaveValue('picker');
    await expect(page.getByTestId('inspector-field-props.pickerDataSource')).toHaveValue(
      'named-query',
    );
    await expect(page.getByTestId('inspector-field-props.pickerQueryCode')).toHaveValue(
      liveNamedQueryCode,
    );
    await expect(page.getByTestId('inspector-field-props.valueField')).toHaveValue('page_key');
    await expect(page.getByTestId('inspector-field-props.displayField')).toHaveValue('name');
    await expect(page.getByTestId('inspector-field-props.pageSize')).toHaveValue('1000');

    const pickerOptionsRespPromise = page.waitForResponse(
      (response) =>
        response.url().includes(`/api/meta/named-queries/${liveNamedQueryCode}/execute`) &&
        response.request().method() === 'POST',
      { timeout: 15000 },
    );
    await page.getByTestId('designer-mode-preview').click();
    await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();
    const pickerOptionsResp = await pickerOptionsRespPromise;
    expect(pickerOptionsResp.status()).toBe(200);
    const pickerRequestBody = pickerOptionsResp.request().postDataJSON() as {
      page?: number;
      size?: number;
      executeQuery?: boolean;
      parameters?: Record<string, unknown>;
    };
    expect(pickerRequestBody).toMatchObject({
      page: 1,
      size: 1000,
      executeQuery: true,
      parameters: {},
    });
    const pickerOptionsBody = await pickerOptionsResp.json();
    expect(pickerOptionsBody.code).toBe('0');
    const pickerRows = (pickerOptionsBody.data?.records ?? []) as Array<Record<string, unknown>>;
    const namedQueryPickerRow = pickerRows.find((row) => typeof row.page_key === 'string');
    expect(namedQueryPickerRow).toBeTruthy();
    const expectedPickerValue = String(namedQueryPickerRow?.page_key);
    const expectedPickerLabel = String(namedQueryPickerRow?.name ?? expectedPickerValue);

    const picker = page.getByTestId('runtime-picker-field_seed_secondary');
    await expect(picker).toBeVisible();
    await expect(picker.locator('option', { hasText: expectedPickerLabel })).toHaveCount(1);
    await expect(page.getByTestId('runtime-picker-meta-field_seed_secondary')).toContainText(
      `named-query / ${liveNamedQueryCode} / name / page_key`,
    );
    await picker.selectOption(expectedPickerValue);
    await expect(picker).toHaveValue(expectedPickerValue);

    const persisted = await readPage(page, pagePid);
    const pickerField = findBlockById(persisted.blocks ?? [], 'field_seed_secondary');
    expect(pickerField).toMatchObject({
      blockType: 'field',
      props: expect.objectContaining({
        label: namedQueryPickerLabel,
        component: 'picker',
        pickerDataSource: 'named-query',
        pickerQueryCode: liveNamedQueryCode,
        valueField: 'page_key',
        displayField: 'name',
        pageSize: 1000,
        pickerParameters: {},
        options: [],
      }),
    });
  });

  // `picker` keeps the designer's own renderer under true-WYSIWYG (see UDW-027), so the
  // server-side `runtime-picker-search-*` contract stays live for model fields — the
  // platform's select only filters already-loaded options client-side.
  test('UDW-032: configures a searchable model picker and sends preview search filters', async ({
    page,
  }) => {
    expect(pagePid).toBeTruthy();

    const searchablePickerLabel = `Searchable page picker ${uid}`;
    const searchablePickerPlaceholder = `Search pages ${uid}`;
    const pickerPlaceholder = `Choose searchable page ${uid}`;
    const searchKeyword = formPageKey;

    await page.goto(`/unified-designer?pageId=${pagePid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-field_seed_title').click();
    await page.getByTestId('inspector-field-props.label').fill(searchablePickerLabel);
    await page.getByTestId('inspector-field-props.component').selectOption('picker');
    await expect(page.getByTestId('inspector-field-props.pickerDataSource')).toBeVisible();
    await page.getByTestId('inspector-field-props.placeholder').fill(pickerPlaceholder);
    await page.getByTestId('inspector-field-props.pickerDataSource').selectOption('model');
    await page.getByTestId('inspector-field-props.pickerSource').fill(modelCode);
    await page.getByTestId('inspector-field-props.valueField').fill('page_key');
    await page.getByTestId('inspector-field-props.displayField').fill('name');
    await setCheckbox(page, 'inspector-field-props.searchable', true);
    await page
      .getByTestId('inspector-field-props.searchPlaceholder')
      .fill(searchablePickerPlaceholder);
    await page.getByTestId('inspector-field-props.searchField').fill('page_key');
    await page.getByTestId('inspector-field-props.searchParameter').fill('keyword');
    await page.getByTestId('inspector-field-props.pageSize').fill('1000');
    await page.getByTestId('inspector-field-props.options').fill('[]');
    await applyJsonField(page, 'inspector-json-field-apply-props.options');
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, pagePid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('outline-item-field_seed_title').click();
    await expect(page.getByTestId('inspector-field-props.component')).toHaveValue('picker');
    await expect(page.getByTestId('inspector-field-props.pickerDataSource')).toHaveValue('model');
    await expect(page.getByTestId('inspector-field-props.searchable')).toBeChecked();
    await expect(page.getByTestId('inspector-field-props.searchPlaceholder')).toHaveValue(
      searchablePickerPlaceholder,
    );
    await expect(page.getByTestId('inspector-field-props.searchField')).toHaveValue('page_key');
    await expect(page.getByTestId('inspector-field-props.searchParameter')).toHaveValue('keyword');

    await page.getByTestId('designer-mode-preview').click();
    await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();
    await expect(page.getByTestId('runtime-picker-search-field_seed_title')).toHaveAttribute(
      'placeholder',
      searchablePickerPlaceholder,
    );

    const searchRespPromise = page.waitForResponse(
      (response) => {
        if (
          !response.url().includes('/api/query-builder/execute') ||
          response.request().method() !== 'POST'
        ) {
          return false;
        }
        const requestBody = response.request().postDataJSON() as {
          modelCode?: string;
          filters?: Array<{ fieldName?: string; operator?: string; value?: string }>;
        };
        return (
          requestBody.modelCode === modelCode &&
          Array.isArray(requestBody.filters) &&
          requestBody.filters.some(
            (filter) =>
              filter.fieldName === 'page_key' &&
              filter.operator === 'LIKE' &&
              filter.value === searchKeyword,
          )
        );
      },
      { timeout: 15000 },
    );
    await page.getByTestId('runtime-picker-search-field_seed_title').fill(searchKeyword);
    const searchResp = await searchRespPromise;
    expect(searchResp.status()).toBe(200);
    const searchRequestBody = searchResp.request().postDataJSON() as {
      modelCode?: string;
      fields?: string[];
      filters?: Array<{ fieldName?: string; operator?: string; value?: string }>;
      limit?: number;
    };
    expect(searchRequestBody).toMatchObject({
      modelCode,
      fields: ['page_key', 'name'],
      filters: [{ fieldName: 'page_key', operator: 'LIKE', value: searchKeyword }],
      limit: 1000,
    });
    const searchBody = await searchResp.json();
    expect(searchBody.code).toBe('0');
    const pickerRows = (searchBody.data ?? []) as Array<Record<string, unknown>>;
    const formPickerRow = pickerRows.find((row) => row.page_key === formPageKey);
    expect(formPickerRow).toBeTruthy();
    const expectedPickerLabel = String(formPickerRow?.name ?? formPageKey);

    const picker = page.getByTestId('runtime-picker-field_seed_title');
    await expect(picker.locator('option', { hasText: expectedPickerLabel })).toHaveCount(1);
    await picker.selectOption(formPageKey);
    await expect(picker).toHaveValue(formPageKey);

    const persisted = await readPage(page, pagePid);
    const pickerField = findBlockById(persisted.blocks ?? [], 'field_seed_title');
    expect(pickerField).toMatchObject({
      blockType: 'field',
      props: expect.objectContaining({
        label: searchablePickerLabel,
        component: 'picker',
        pickerDataSource: 'model',
        pickerSource: modelCode,
        valueField: 'page_key',
        displayField: 'name',
        searchable: true,
        searchPlaceholder: searchablePickerPlaceholder,
        searchField: 'page_key',
        searchParameter: 'keyword',
        pageSize: 1000,
        options: [],
      }),
    });
  });

  // `picker` keeps the designer's own renderer under true-WYSIWYG (see UDW-027), so the
  // named-query where-condition search contract stays live for model fields.
  test('UDW-033: configures a searchable named-query picker and sends preview where conditions', async ({
    page,
  }) => {
    expect(pagePid).toBeTruthy();
    expect(liveNamedQueryCode).toBeTruthy();

    const namedQuerySearchableLabel = `Searchable named query picker ${uid}`;
    const namedQuerySearchPlaceholder = `Search named query pages ${uid}`;
    const pickerPlaceholder = `Choose named query search result ${uid}`;
    const searchKeyword = formPageKey;

    await page.goto(`/unified-designer?pageId=${pagePid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-field_seed_secondary').click();
    await page.getByTestId('inspector-field-props.label').fill(namedQuerySearchableLabel);
    await page.getByTestId('inspector-field-props.component').selectOption('picker');
    await expect(page.getByTestId('inspector-field-props.pickerDataSource')).toBeVisible();
    await page.getByTestId('inspector-field-props.placeholder').fill(pickerPlaceholder);
    await page.getByTestId('inspector-field-props.pickerDataSource').selectOption('named-query');
    await page.getByTestId('inspector-field-props.pickerQueryCode').fill(liveNamedQueryCode);
    await page.getByTestId('inspector-field-props.valueField').fill('page_key');
    await page.getByTestId('inspector-field-props.displayField').fill('name');
    await setCheckbox(page, 'inspector-field-props.searchable', true);
    await page
      .getByTestId('inspector-field-props.searchPlaceholder')
      .fill(namedQuerySearchPlaceholder);
    await page.getByTestId('inspector-field-props.searchField').fill('page_key');
    await page.getByTestId('inspector-field-props.searchParameter').fill('keyword');
    await page.getByTestId('inspector-field-props.pageSize').fill('1000');
    await page.getByTestId('inspector-field-props.pickerParameters').fill(JSON.stringify({}));
    await applyJsonField(page, 'inspector-json-field-apply-props.pickerParameters');
    await page.getByTestId('inspector-field-props.options').fill('[]');
    await applyJsonField(page, 'inspector-json-field-apply-props.options');
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, pagePid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('outline-item-field_seed_secondary').click();
    await expect(page.getByTestId('inspector-field-props.component')).toHaveValue('picker');
    await expect(page.getByTestId('inspector-field-props.pickerDataSource')).toHaveValue(
      'named-query',
    );
    await expect(page.getByTestId('inspector-field-props.searchable')).toBeChecked();
    await expect(page.getByTestId('inspector-field-props.searchPlaceholder')).toHaveValue(
      namedQuerySearchPlaceholder,
    );
    await expect(page.getByTestId('inspector-field-props.searchField')).toHaveValue('page_key');
    await expect(page.getByTestId('inspector-field-props.searchParameter')).toHaveValue('keyword');

    await page.getByTestId('designer-mode-preview').click();
    await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();
    await expect(page.getByTestId('runtime-picker-search-field_seed_secondary')).toHaveAttribute(
      'placeholder',
      namedQuerySearchPlaceholder,
    );

    const searchRespPromise = page.waitForResponse(
      (response) => {
        if (
          !response.url().includes(`/api/meta/named-queries/${liveNamedQueryCode}/execute`) ||
          response.request().method() !== 'POST'
        ) {
          return false;
        }
        const requestBody = response.request().postDataJSON() as {
          parameters?: Record<string, unknown>;
          whereConditions?: Array<{ field?: string; operator?: string; value?: string }>;
        };
        return (
          requestBody.parameters?.keyword === searchKeyword &&
          Array.isArray(requestBody.whereConditions) &&
          requestBody.whereConditions.some(
            (condition) =>
              condition.field === 'page_key' &&
              condition.operator === 'contains' &&
              condition.value === searchKeyword,
          )
        );
      },
      { timeout: 15000 },
    );
    await page.getByTestId('runtime-picker-search-field_seed_secondary').fill(searchKeyword);
    const searchResp = await searchRespPromise;
    expect(searchResp.status()).toBe(200);
    const searchRequestBody = searchResp.request().postDataJSON() as {
      page?: number;
      size?: number;
      executeQuery?: boolean;
      parameters?: Record<string, unknown>;
      whereConditions?: Array<{ field?: string; operator?: string; value?: string }>;
    };
    expect(searchRequestBody).toMatchObject({
      page: 1,
      size: 1000,
      executeQuery: true,
      parameters: { keyword: searchKeyword },
      whereConditions: [{ field: 'page_key', operator: 'contains', value: searchKeyword }],
    });
    const searchBody = await searchResp.json();
    expect(searchBody.code).toBe('0');
    const pickerRows = (searchBody.data?.records ?? []) as Array<Record<string, unknown>>;
    const formPickerRow = pickerRows.find((row) => row.page_key === formPageKey);
    expect(formPickerRow).toBeTruthy();
    const expectedPickerLabel = String(formPickerRow?.name ?? formPageKey);

    const picker = page.getByTestId('runtime-picker-field_seed_secondary');
    await expect(picker.locator('option', { hasText: expectedPickerLabel })).toHaveCount(1);
    await picker.selectOption(formPageKey);
    await expect(picker).toHaveValue(formPageKey);

    const persisted = await readPage(page, pagePid);
    const pickerField = findBlockById(persisted.blocks ?? [], 'field_seed_secondary');
    expect(pickerField).toMatchObject({
      blockType: 'field',
      props: expect.objectContaining({
        label: namedQuerySearchableLabel,
        component: 'picker',
        pickerDataSource: 'named-query',
        pickerQueryCode: liveNamedQueryCode,
        valueField: 'page_key',
        displayField: 'name',
        searchable: true,
        searchPlaceholder: namedQuerySearchPlaceholder,
        searchField: 'page_key',
        searchParameter: 'keyword',
        pageSize: 1000,
        pickerParameters: {},
        options: [],
      }),
    });
  });

  test('UDW-034: configures action permission code and blocks preview execution when missing', async ({
    page,
  }) => {
    expect(listPagePid).toBeTruthy();

    const permissionActionLabel = `Permission blocked action ${uid}`;
    const missingPermissionCode = `meta.unified-designer.missing.${uid}`;

    await page.goto(`/unified-designer?pageId=${listPagePid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-action_seed_export').click();
    await page.getByTestId('inspector-field-actionType').selectOption('command');
    await page.getByTestId('inspector-field-props.label').fill(permissionActionLabel);
    await page.getByTestId('inspector-field-props.command-manual').fill(liveCommandCode);
    await page.getByTestId('inspector-field-props.executionMode').selectOption('live');
    await expect(page.getByTestId('inspector-field-props.permissionCode')).toBeVisible();
    await page.getByTestId('inspector-field-props.permissionCode-manual').fill(missingPermissionCode);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, listPagePid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('outline-item-action_seed_export').click();
    await expect(page.getByTestId('inspector-field-props.permissionCode')).toHaveValue(
      missingPermissionCode,
    );

    await page.getByTestId('designer-mode-preview').click();
    await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();
    const action = page.getByTestId('runtime-action-action_seed_export');
    await expect(action).toBeDisabled();
    await expect(action).toHaveAttribute('data-permission-code', missingPermissionCode);
    await expect(action).toHaveAttribute('data-permission-allowed', 'false');
    await expect(page.getByTestId('runtime-action-permission-action_seed_export')).toContainText(
      `Requires permission: ${missingPermissionCode}`,
    );
    await expect(page.getByTestId('runtime-action-error-action_seed_export')).toHaveCount(0);

    const persisted = await readPage(page, listPagePid);
    const persistedCommand = findBlockById(persisted.blocks ?? [], 'action_seed_export');
    expect(persistedCommand).toMatchObject({
      blockType: 'action',
      actionType: 'command',
      props: expect.objectContaining({
        label: permissionActionLabel,
        command: liveCommandCode,
        executionMode: 'live',
        permissionCode: missingPermissionCode,
      }),
    });
  });

  test('UDW-035: keeps permission-protected action executable when current admin has the permission', async ({
    page,
  }) => {
    expect(listPagePid).toBeTruthy();

    const allowedPermissionCode = await pickCurrentPermissionCode(page);
    const permissionActionLabel = `Permission allowed action ${uid}`;

    await page.goto(`/unified-designer?pageId=${listPagePid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-action_seed_export').click();
    await page.getByTestId('inspector-field-actionType').selectOption('command');
    await page.getByTestId('inspector-field-props.label').fill(permissionActionLabel);
    await page.getByTestId('inspector-field-props.command-manual').fill(liveCommandCode);
    await page.getByTestId('inspector-field-props.executionMode').selectOption('live');
    await page.getByTestId('inspector-field-props.permissionCode-manual').fill(allowedPermissionCode);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, listPagePid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('outline-item-action_seed_export').click();
    await expect(page.getByTestId('inspector-field-props.permissionCode')).toHaveValue(
      allowedPermissionCode,
    );

    await page.getByTestId('designer-mode-preview').click();
    await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();
    const action = page.getByTestId('runtime-action-action_seed_export');
    await expect(action).toBeEnabled();
    await expect(action).toHaveAttribute('data-permission-code', allowedPermissionCode);
    await expect(action).toHaveAttribute('data-permission-allowed', 'true');
    await expect(page.getByTestId('runtime-action-permission-action_seed_export')).toContainText(
      `Permission: ${allowedPermissionCode}`,
    );

    const commandRespPromise = page.waitForResponse(
      (response) =>
        response.url().includes('/api/meta/commands/execute/') &&
        response.request().method() === 'POST',
      { timeout: 15000 },
    );
    await action.click();
    const commandResp = await commandRespPromise;
    expect([200, 400, 403, 404, 422, 500]).toContain(commandResp.status());
    const commandRequestBody = commandResp.request().postDataJSON() as {
      auditContext?: Record<string, unknown>;
    };
    expect(commandRequestBody.auditContext).toMatchObject({
      source: 'unified-designer-runtime-preview',
      pageKind: 'list',
      schemaVersion: 3,
      blockId: 'action_seed_export',
      blockType: 'action',
      actionType: 'command',
      permissionCode: allowedPermissionCode,
    });

    const persisted = await readPage(page, listPagePid);
    const persistedCommand = findBlockById(persisted.blocks ?? [], 'action_seed_export');
    expect(persistedCommand).toMatchObject({
      blockType: 'action',
      actionType: 'command',
      props: expect.objectContaining({
        label: permissionActionLabel,
        command: liveCommandCode,
        executionMode: 'live',
        permissionCode: allowedPermissionCode,
      }),
    });
  });

  test('UDW-036: applies action permission code across operator and viewer roles', async ({
    page,
    browser,
    baseURL,
  }) => {
    test.setTimeout(60_000);
    expect(listPagePid).toBeTruthy();
    const permissionActionLabel = `Role matrix action ${uid}`;

    await page.goto(`/unified-designer?pageId=${listPagePid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-action_seed_export').click();
    await page.getByTestId('inspector-field-actionType').selectOption('command');
    await page.getByTestId('inspector-field-props.label').fill(permissionActionLabel);
    await page.getByTestId('inspector-field-props.command-manual').fill(liveCommandCode);
    await page.getByTestId('inspector-field-props.executionMode').selectOption('live');
    await page
      .getByTestId('inspector-field-props.permissionCode-manual')
      .fill(ROLE_MATRIX_PERMISSION_CODE);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');
    await saveDesignerPage(page, listPagePid);

    const persisted = await readPage(page, listPagePid);
    const persistedDocument = toLocalDesignerDocument(persisted, 'list');
    const persistedCommand = findBlockById(persisted.blocks ?? [], 'action_seed_export');
    expect(persistedCommand).toMatchObject({
      blockType: 'action',
      actionType: 'command',
      props: expect.objectContaining({
        label: permissionActionLabel,
        command: liveCommandCode,
        executionMode: 'live',
        permissionCode: ROLE_MATRIX_PERMISSION_CODE,
      }),
    });

    const appBaseUrl = baseURL ?? new URL(page.url()).origin;
    const operatorContext = await createAuthenticatedRoleContext(browser, appBaseUrl, 'operator');
    const viewerContext = await createAuthenticatedRoleContext(browser, appBaseUrl, 'viewer');

    try {
      const operatorPage = await operatorContext.newPage();
      await expectCurrentUserPermission(operatorPage, ROLE_MATRIX_PERMISSION_CODE, true);
      await expectRoleActionPermissionState({
        page: operatorPage,
        document: persistedDocument,
        permissionCode: ROLE_MATRIX_PERMISSION_CODE,
        allowed: true,
      });

      const viewerPage = await viewerContext.newPage();
      await expectCurrentUserPermission(viewerPage, ROLE_MATRIX_PERMISSION_CODE, false);
      await expectRoleActionPermissionState({
        page: viewerPage,
        document: persistedDocument,
        permissionCode: ROLE_MATRIX_PERMISSION_CODE,
        allowed: false,
      });
    } finally {
      await operatorContext.close();
      await viewerContext.close();
    }
  });

  test('UDW-037: configures a radio field and selects an option in runtime preview', async ({
    page,
  }) => {
    expect(pagePid).toBeTruthy();
    const radioLabel = `Runtime radio ${uid}`;
    const radioOptions = [
      { label: `Low priority ${uid}`, value: `low_${uid}` },
      { label: `High priority ${uid}`, value: `high_${uid}` },
    ];

    await page.goto(`/unified-designer?pageId=${pagePid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-field_seed_title').click();
    await page.getByTestId('inspector-field-props.label').fill(radioLabel);
    await page.getByTestId('inspector-field-props.component').selectOption('radio');
    await page.getByTestId('inspector-field-props.options').fill(JSON.stringify(radioOptions));
    await applyJsonField(page, 'inspector-json-field-apply-props.options');
    const requiredInput = page.getByTestId('inspector-field-props.required');
    if (await requiredInput.isChecked()) {
      await requiredInput.uncheck();
    }
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');
    await saveDesignerPage(page, pagePid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('outline-item-field_seed_title').click();
    await expect(page.getByTestId('inspector-field-props.component')).toHaveValue('radio');
    await expect(page.getByTestId('inspector-field-props.options')).toHaveValue(
      new RegExp(radioOptions[1].label),
    );

    await page.getByTestId('designer-mode-preview').click();
    await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();
    // WYSIWYG renders the platform Radio group: each option is a
    // <label><input type="radio" value="<optionValue>" name="<fieldCode>">…</label>
    // inside the field wrapper (no per-option testid). Match radios by value.
    const radioGroup = page.getByTestId('runtime-field-field_seed_title');
    await expect(radioGroup).toContainText(radioOptions[0].label);
    await expect(radioGroup).toContainText(radioOptions[1].label);
    const highRadio = radioGroup.locator(`input[type="radio"][value="${radioOptions[1].value}"]`);
    await highRadio.click();
    await expect(highRadio).toBeChecked();

    const persisted = await readPage(page, pagePid);
    const radioField = findBlockById(persisted.blocks ?? [], 'field_seed_title');
    expect(radioField).toMatchObject({
      blockType: 'field',
      props: expect.objectContaining({
        label: radioLabel,
        component: 'radio',
        options: radioOptions,
      }),
    });
  });

  test('UDW-038: drags a repeater into a form and submits edited rows from runtime preview', async ({
    page,
  }) => {
    expect(pagePid).toBeTruthy();
    expect(fieldCode).toBeTruthy();

    const repeaterId = 'repeater_new_repeater';
    const repeaterTitle = `Editable lines ${uid}`;
    const initialLineValue = `Initial line ${uid}`;
    const firstRuntimeValue = `Updated line ${uid}`;
    const secondRuntimeValue = `Second line ${uid}`;
    const repeaterCommandCode = `missing.repeater.command.${uid}`;
    const repeaterPayload = {
      source: 'unified-designer-workbench',
      uid,
      lineItems: `{{form.values.${repeaterId}}}`,
    };

    await page.goto(`/unified-designer?pageId=${pagePid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-section_basic').click();
    await switchResourceTab(page, 'blocks');
    const repeaterPaletteItem = page.getByTestId('palette-add-repeater');
    const sectionCanvasBlock = page.getByTestId('canvas-block-section_basic');
    await expect(repeaterPaletteItem).toHaveAttribute('aria-roledescription', 'draggable');
    await sectionCanvasBlock.scrollIntoViewIfNeeded();
    await dndDragTo(page, repeaterPaletteItem, sectionCanvasBlock, {
      targetPosition: { x: 24, y: 24 },
    });
    await expect(page.getByTestId(`canvas-block-${repeaterId}`)).toBeVisible();

    await expect(page.getByTestId('inspector-selected-id')).toContainText(repeaterId);
    await page.getByTestId('inspector-field-title').fill(repeaterTitle);
    await page
      .getByTestId('inspector-field-props.rows')
      .fill(JSON.stringify([{ [fieldCode]: initialLineValue }]));
    await applyJsonField(page, 'inspector-json-field-apply-props.rows');

    await switchResourceTab(page, 'fields');
    await page.getByTestId('field-palette-search').fill(fieldCode);
    await dndDragTo(page, page
      .getByTestId(`model-field-${fieldCode}`), page.getByTestId(`canvas-block-${repeaterId}`));
    await expect(page.getByTestId('inspector-selected-id')).toContainText(fieldBlockId);
    await page.getByTestId('inspector-field-props.component').selectOption('input');

    await switchResourceTab(page, 'outline');
    await page.getByTestId('outline-item-action_seed_submit').click();
    await page.getByTestId('inspector-field-actionType').selectOption('command');
    await page.getByTestId('inspector-field-props.label').fill(`Submit repeater ${uid}`);
    await page.getByTestId('inspector-field-props.command-manual').fill(repeaterCommandCode);
    await page.getByTestId('inspector-field-props.executionMode').selectOption('live');
    await setCheckbox(page, 'inspector-field-props.validateForm', false);
    await page.getByTestId('inspector-field-props.payload').fill(JSON.stringify(repeaterPayload));
    await applyJsonField(page, 'inspector-json-field-apply-props.payload');
    const confirmInput = page.getByTestId('inspector-field-props.confirm');
    if (await confirmInput.isChecked()) {
      await confirmInput.uncheck();
    }
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');
    await saveDesignerPage(page, pagePid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId(`outline-item-${repeaterId}`).click();
    await expect(page.getByTestId('inspector-field-title')).toHaveValue(repeaterTitle);
    await expect(page.getByTestId('inspector-field-props.rows')).toHaveValue(
      new RegExp(initialLineValue),
    );

    await page.getByTestId('designer-mode-preview').click();
    await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();
    await expect(page.getByTestId(`runtime-repeater-${repeaterId}`)).toContainText(repeaterTitle);

    const firstLineInput = page
      .locator(`[data-testid^="runtime-repeater-input-${repeaterId}-0-"]`)
      .first();
    await expect(firstLineInput).toBeVisible();
    await firstLineInput.fill(firstRuntimeValue);
    await page.getByTestId(`runtime-repeater-add-${repeaterId}`).click();
    const secondLineInput = page
      .locator(`[data-testid^="runtime-repeater-input-${repeaterId}-1-"]`)
      .first();
    await expect(secondLineInput).toBeVisible();
    await secondLineInput.fill(secondRuntimeValue);

    const commandRespPromise = page.waitForResponse(
      (response) =>
        response.url().includes('/api/meta/commands/execute/') &&
        response.request().method() === 'POST',
      { timeout: 15000 },
    );
    await page.getByTestId('runtime-action-action_seed_submit').click();
    const commandResp = await commandRespPromise;
    expect([200, 400, 403, 404, 422, 500]).toContain(commandResp.status());
    const commandRequestBody = commandResp.request().postDataJSON() as {
      payload?: Record<string, unknown>;
    };
    expect(commandRequestBody.payload).toMatchObject({
      source: 'unified-designer-workbench',
      uid,
      lineItems: [{ [fieldCode]: firstRuntimeValue }, { [fieldCode]: secondRuntimeValue }],
    });

    const persisted = await readPage(page, pagePid);
    const persistedRepeater = findBlockById(persisted.blocks ?? [], repeaterId);
    expect(persistedRepeater).toMatchObject({
      blockType: 'repeater',
      title: repeaterTitle,
      props: expect.objectContaining({
        rows: [{ [fieldCode]: initialLineValue }],
      }),
    });
    expect(persistedRepeater?.blocks?.[0]).toMatchObject({
      blockType: 'field',
      field: fieldCode,
      props: expect.objectContaining({
        component: 'input',
      }),
    });
  });

  test('UDW-039: drags a nested subform into a form and submits row editor values', async ({
    page,
  }) => {
    expect(pagePid).toBeTruthy();
    expect(fieldCode).toBeTruthy();

    const subformId = 'subform_new_subform';
    const subformTitle = `Nested team ${uid}`;
    const initialMemberValue = `Initial member ${uid}`;
    const firstRuntimeValue = `Updated member ${uid}`;
    const secondRuntimeValue = `Second member ${uid}`;
    const subformCommandCode = `missing.subform.command.${uid}`;
    const subformPayload = {
      source: 'unified-designer-workbench',
      uid,
      teamMembers: `{{form.values.${subformId}}}`,
    };

    await page.goto(`/unified-designer?pageId=${pagePid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-section_basic').click();
    await switchResourceTab(page, 'blocks');
    const subformPaletteItem = page.getByTestId('palette-add-subform');
    const sectionCanvasBlock = page.getByTestId('canvas-block-section_basic');
    await expect(subformPaletteItem).toHaveAttribute('aria-roledescription', 'draggable');
    await sectionCanvasBlock.scrollIntoViewIfNeeded();
    await dndDragTo(page, subformPaletteItem, sectionCanvasBlock, {
      targetPosition: { x: 24, y: 24 },
    });
    await expect(page.getByTestId(`canvas-block-${subformId}`)).toBeVisible();

    await expect(page.getByTestId('inspector-selected-id')).toContainText(subformId);
    await page.getByTestId('inspector-field-title').fill(subformTitle);
    await page
      .getByTestId('inspector-field-props.rows')
      .fill(JSON.stringify([{ [fieldCode]: initialMemberValue }]));
    await applyJsonField(page, 'inspector-json-field-apply-props.rows');

    await switchResourceTab(page, 'fields');
    await page.getByTestId('field-palette-search').fill(fieldCode);
    await dndDragTo(page, page
      .getByTestId(`model-field-${fieldCode}`), page.getByTestId(`canvas-block-${subformId}`));
    await expect(page.getByTestId('inspector-selected-id')).toContainText('field_');
    await page.getByTestId('inspector-field-props.component').selectOption('input');

    await switchResourceTab(page, 'outline');
    await page.getByTestId('outline-item-action_seed_submit').click();
    await page.getByTestId('inspector-field-actionType').selectOption('command');
    await page.getByTestId('inspector-field-props.label').fill(`Submit subform ${uid}`);
    await page.getByTestId('inspector-field-props.command-manual').fill(subformCommandCode);
    await page.getByTestId('inspector-field-props.executionMode').selectOption('live');
    const validateFormInput = page.getByTestId('inspector-field-props.validateForm');
    if (await validateFormInput.isChecked()) {
      await validateFormInput.uncheck();
    }
    await page.getByTestId('inspector-field-props.payload').fill(JSON.stringify(subformPayload));
    await applyJsonField(page, 'inspector-json-field-apply-props.payload');
    const confirmInput = page.getByTestId('inspector-field-props.confirm');
    if (await confirmInput.isChecked()) {
      await confirmInput.uncheck();
    }
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');
    await saveDesignerPage(page, pagePid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId(`outline-item-${subformId}`).click();
    await expect(page.getByTestId('inspector-field-title')).toHaveValue(subformTitle);
    await expect(page.getByTestId('inspector-field-props.rows')).toHaveValue(
      new RegExp(initialMemberValue),
    );

    await page.getByTestId('designer-mode-preview').click();
    await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();
    await expect(page.getByTestId(`runtime-subform-${subformId}`)).toContainText(subformTitle);

    const firstMemberInput = page
      .locator(`[data-testid^="runtime-subform-input-${subformId}-0-"]`)
      .first();
    await expect(firstMemberInput).toBeVisible();
    await firstMemberInput.fill(firstRuntimeValue);
    await page.getByTestId(`runtime-subform-add-${subformId}`).click();
    const secondMemberInput = page
      .locator(`[data-testid^="runtime-subform-input-${subformId}-1-"]`)
      .first();
    await expect(secondMemberInput).toBeVisible();
    await secondMemberInput.fill(secondRuntimeValue);

    const commandRespPromise = page.waitForResponse(
      (response) =>
        response.url().includes('/api/meta/commands/execute/') &&
        response.request().method() === 'POST',
      { timeout: 15000 },
    );
    await page.getByTestId('runtime-action-action_seed_submit').click();
    const commandResp = await commandRespPromise;
    expect([200, 400, 403, 404, 422, 500]).toContain(commandResp.status());
    const commandRequestBody = commandResp.request().postDataJSON() as {
      payload?: Record<string, unknown>;
    };
    expect(commandRequestBody.payload).toMatchObject({
      source: 'unified-designer-workbench',
      uid,
      teamMembers: [{ [fieldCode]: firstRuntimeValue }, { [fieldCode]: secondRuntimeValue }],
    });

    const persisted = await readPage(page, pagePid);
    const persistedSubform = findBlockById(persisted.blocks ?? [], subformId);
    expect(persistedSubform).toMatchObject({
      blockType: 'subform',
      title: subformTitle,
      props: expect.objectContaining({
        rows: [{ [fieldCode]: initialMemberValue }],
      }),
    });
    expect(persistedSubform?.blocks?.[0]).toMatchObject({
      blockType: 'field',
      field: fieldCode,
      props: expect.objectContaining({
        component: 'input',
      }),
    });
  });

  test('UDW-040: authors form tabs from palette blocks and renders them after save', async ({
    page,
  }) => {
    expect(pagePid).toBeTruthy();

    const tabsId = 'tabs_new_tabs';
    const tabId = 'tab_new_tab';
    const tabsTitle = `Form tabs ${uid}`;
    const tabTitle = `Primary tab ${uid}`;
    const sectionTitle = `Tabbed section ${uid}`;
    const freeFieldLabel = `Free-form field ${uid}`;

    await page.goto(`/unified-designer?pageId=${pagePid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-form_root').click();
    await switchResourceTab(page, 'blocks');
    const tabsPaletteItem = page.getByTestId('palette-add-tabs');
    await expect(tabsPaletteItem).toBeVisible();
    await expect(tabsPaletteItem).toBeEnabled();
    await expect(tabsPaletteItem).toHaveAttribute('aria-roledescription', 'draggable');
    // Add the tabs container via the palette's click-to-add affordance (it targets
    // the current selection — form_root, selected above) rather than a drag.
    //
    // By this point in the serial suite `form_root` is ~1650px tall. The designer
    // resolves a drop by taking `pointerWithin` hits PLUS the top `closestCenter`
    // candidate and then picking the SMALLEST-area droppable
    // (prioritizeNestedDropCollisions). For a container that large, closestCenter
    // always contributes some small descendant, which then wins on area — so a drop
    // aimed at form_root (even on its own header) resolves to a descendant that
    // rejects a `tabs` block and the gesture is a silent no-op. See the audit notes:
    // this silent no-op drop onto a tall container is a real UX weakness, tracked
    // separately. Drag coverage for palette→canvas is retained by UDW-006 and by the
    // tab / form-section drags below (small, freshly-created containers).
    await tabsPaletteItem.click();
    await expect(page.getByTestId(`canvas-block-${tabsId}`)).toBeVisible();
    await expect(page.getByTestId('inspector-selected-id')).toContainText(tabsId);
    await page.getByTestId('inspector-field-title').fill(tabsTitle);

    const tabPaletteItem = page.getByTestId('palette-add-tab');
    await expect(tabPaletteItem).toBeEnabled();
    await expect(tabPaletteItem).toHaveAttribute('aria-roledescription', 'draggable');
    await dndDragTo(page, tabPaletteItem, page.getByTestId(`canvas-block-${tabsId}`));
    await expect(page.getByTestId(`canvas-block-${tabId}`)).toBeVisible();
    await expect(page.getByTestId('inspector-selected-id')).toContainText(tabId);
    await page.getByTestId('inspector-field-title').fill(tabTitle);

    const sectionPaletteItem = page.getByTestId('palette-add-form-section');
    await expect(sectionPaletteItem).toBeEnabled();
    await dndDragTo(page, sectionPaletteItem, page.getByTestId(`canvas-block-${tabId}`));
    const sectionId = (await page.getByTestId('inspector-selected-id').textContent())?.trim() ?? '';
    expect(sectionId).toMatch(/^form_section_new_section(_\d+)?$/);
    await expect(page.getByTestId(`canvas-block-${sectionId}`)).toBeVisible();
    await page.getByTestId('inspector-field-title').fill(sectionTitle);

    await switchResourceTab(page, 'fields');
    const fieldPaletteItem = page.getByTestId('field-palette-add-field');
    await expect(fieldPaletteItem).toBeEnabled();
    await fieldPaletteItem.click();
    const freeFieldId =
      (await page.getByTestId('inspector-selected-id').textContent())?.trim() ?? '';
    expect(freeFieldId).toMatch(/^field_new_field(_\d+)?$/);
    await expect(page.getByTestId(`canvas-block-${freeFieldId}`)).toBeVisible();
    await page.getByTestId('inspector-field-props.label').fill(freeFieldLabel);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, pagePid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId(`outline-item-${tabsId}`).click();
    await expect(page.getByTestId('inspector-field-title')).toHaveValue(tabsTitle);
    await page.getByTestId(`outline-item-${tabId}`).click();
    await expect(page.getByTestId('inspector-field-title')).toHaveValue(tabTitle);
    await page.getByTestId(`outline-item-${freeFieldId}`).click();
    await expect(page.getByTestId('inspector-field-props.label')).toHaveValue(freeFieldLabel);

    const persisted = await readPage(page, pagePid);
    const persistedTabs = findBlockById(persisted.blocks ?? [], tabsId);
    const persistedTab = findBlockById(persisted.blocks ?? [], tabId);
    const persistedSection = findBlockById(persisted.blocks ?? [], sectionId);
    const persistedFreeField = findBlockById(persisted.blocks ?? [], freeFieldId);
    expect(persistedTabs).toMatchObject({
      blockType: 'tabs',
      title: tabsTitle,
    });
    expect(persistedTab).toMatchObject({
      blockType: 'tab',
      title: tabTitle,
    });
    expect(persistedSection).toMatchObject({
      blockType: 'form-section',
      title: sectionTitle,
    });
    expect(persistedFreeField).toMatchObject({
      blockType: 'field',
      field: 'new_field',
      props: expect.objectContaining({ label: freeFieldLabel }),
    });

    await page.getByTestId('designer-mode-preview').click();
    await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();
    await expect(page.getByTestId(`runtime-block-${tabsId}`)).toContainText(tabsTitle);
    await expect(page.getByTestId(`runtime-block-${tabId}`)).toContainText(tabTitle);
    await expect(page.getByTestId(`runtime-field-${freeFieldId}`)).toContainText(freeFieldLabel);
  });

  test('UDW-042: designs detail complex blocks and verifies saved runtime output', async ({
    page,
  }) => {
    expect(detailPagePid).toBeTruthy();
    expect(fieldCode).toBeTruthy();

    const subTableId = 'sub_table_new_sub_table';
    const repeaterId = 'repeater_new_repeater';
    const subformId = 'subform_new_subform';
    const actionBarId = 'action_bar_new_action_bar';
    const actionId = 'action_new_action';
    const widgetId = 'widget_new_widget';
    const detailSubTableTitle = `Detail child rows ${uid}`;
    const detailRepeaterTitle = `Detail repeater ${uid}`;
    const detailSubformTitle = `Detail subform ${uid}`;
    const detailActionLabel = `Detail command ${uid}`;
    const detailCommandCode = `missing.detail.command.${uid}`;
    const detailWidgetTitle = `Detail metric ${uid}`;
    const detailWidgetValue = `42 ${uid}`;
    const subTablePreviewValue = `Detail child value ${uid}`;
    const repeaterPreviewValue = `Detail repeater value ${uid}`;
    const subformPreviewValue = `Detail subform value ${uid}`;

    await page.goto(`/unified-designer?pageId=${detailPagePid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-detail_root').click();
    await switchResourceTab(page, 'blocks');

    const actionBarPaletteItem = page.getByTestId('palette-add-action-bar');
    await expect(actionBarPaletteItem).toBeEnabled();
    await dndDragTo(page, actionBarPaletteItem, page.getByTestId('canvas-block-detail_root'));
    await expect(page.getByTestId(`canvas-block-${actionBarId}`)).toBeVisible();
    await page.getByTestId('inspector-field-region').fill('footer');

    const actionPaletteItem = page.getByTestId('palette-add-action');
    await expect(actionPaletteItem).toBeEnabled();
    await dndDragTo(page, actionPaletteItem, page.getByTestId(`canvas-block-${actionBarId}`));
    await expect(page.getByTestId(`canvas-block-${actionId}`)).toBeVisible();
    await page.getByTestId('inspector-field-actionType').selectOption('command');
    await page.getByTestId('inspector-field-props.label').fill(detailActionLabel);
    await page.getByTestId('inspector-field-props.command-manual').fill(detailCommandCode);
    await page.getByTestId('inspector-field-props.executionMode').selectOption('live');

    await switchResourceTab(page, 'outline');
    await page.getByTestId('outline-item-detail_root').click();
    await switchResourceTab(page, 'blocks');
    const widgetPaletteItem = page.getByTestId('palette-add-widget');
    await expect(widgetPaletteItem).toBeEnabled();
    // Click-to-add against the current selection (detail_root, selected above) —
    // by this point detail_root also holds the action-bar added above and a
    // WYSIWYG-rendered summary section, so a drop aimed at it resolves to a smaller
    // descendant that rejects a `widget` (see the UDW-040 note on
    // prioritizeNestedDropCollisions) and the gesture is a silent no-op. The
    // action-bar / action drags above still exercise the drag path in this test.
    await widgetPaletteItem.click();
    await expect(page.getByTestId(`canvas-block-${widgetId}`)).toBeVisible();
    await page.getByTestId('inspector-field-widgetType').selectOption('number-card');
    await page.getByTestId('inspector-field-props.title').fill(detailWidgetTitle);
    await page.getByTestId('inspector-field-props.value').fill(detailWidgetValue);

    await switchResourceTab(page, 'outline');
    await page.getByTestId('outline-item-detail_section_summary').click();
    await switchResourceTab(page, 'blocks');

    const subTablePaletteItem = page.getByTestId('palette-add-sub-table');
    await expect(subTablePaletteItem).toBeEnabled();
    await dndDragTo(page, subTablePaletteItem, page.getByTestId('canvas-block-detail_section_summary'));
    await expect(page.getByTestId(`canvas-block-${subTableId}`)).toBeVisible();
    await page.getByTestId('inspector-field-title').fill(detailSubTableTitle);
    await page.getByTestId('inspector-field-dataSource.model-manual').fill(modelCode);
    await page
      .getByTestId('inspector-field-props.rows')
      .fill(JSON.stringify([{ [fieldCode]: subTablePreviewValue }]));
    await applyJsonField(page, 'inspector-json-field-apply-props.rows');

    await switchResourceTab(page, 'fields');
    await page.getByTestId('field-palette-search').fill(fieldCode);
    await dndDragTo(page, page
      .getByTestId(`model-field-${fieldCode}`), page.getByTestId(`canvas-block-${subTableId}`));
    await expect(page.getByTestId(`canvas-block-${columnBlockId}`)).toBeVisible();

    await switchResourceTab(page, 'outline');
    await page.getByTestId('outline-item-detail_section_summary').click();
    await switchResourceTab(page, 'blocks');
    const repeaterPaletteItem = page.getByTestId('palette-add-repeater');
    await expect(repeaterPaletteItem).toBeEnabled();
    // The section already holds a sub-table; drop into its top band so the gesture
    // targets the section itself, not the nested sub-table/column.
    await dndDragTo(page, repeaterPaletteItem, page.getByTestId('canvas-block-detail_section_summary'), {
      targetPosition: { x: 24, y: 16 },
    });
    await expect(page.getByTestId(`canvas-block-${repeaterId}`)).toBeVisible();
    await page.getByTestId('inspector-field-title').fill(detailRepeaterTitle);
    await page
      .getByTestId('inspector-field-props.rows')
      .fill(JSON.stringify([{ [fieldCode]: repeaterPreviewValue }]));
    await applyJsonField(page, 'inspector-json-field-apply-props.rows');

    await switchResourceTab(page, 'fields');
    await page.getByTestId('field-palette-search').fill(fieldCode);
    await dndDragTo(page, page
      .getByTestId(`model-field-${fieldCode}`), page.getByTestId(`canvas-block-${repeaterId}`));
    const repeaterFieldId =
      (await page.getByTestId('inspector-selected-id').textContent())?.trim() ?? '';
    expect(repeaterFieldId).toMatch(/^field_/);
    await page.getByTestId('inspector-field-props.component').selectOption('input');

    await switchResourceTab(page, 'outline');
    await page.getByTestId('outline-item-detail_section_summary').click();
    await switchResourceTab(page, 'blocks');
    const subformPaletteItem = page.getByTestId('palette-add-subform');
    await expect(subformPaletteItem).toBeEnabled();
    await dndDragTo(page, subformPaletteItem, page.getByTestId('canvas-block-detail_section_summary'));
    await expect(page.getByTestId(`canvas-block-${subformId}`)).toBeVisible();
    await page.getByTestId('inspector-field-title').fill(detailSubformTitle);
    await page
      .getByTestId('inspector-field-props.rows')
      .fill(JSON.stringify([{ [fieldCode]: subformPreviewValue }]));
    await applyJsonField(page, 'inspector-json-field-apply-props.rows');

    await switchResourceTab(page, 'fields');
    await page.getByTestId('field-palette-search').fill(fieldCode);
    await dndDragTo(page, page
      .getByTestId(`model-field-${fieldCode}`), page.getByTestId(`canvas-block-${subformId}`));
    const subformFieldId =
      (await page.getByTestId('inspector-selected-id').textContent())?.trim() ?? '';
    expect(subformFieldId).toMatch(/^field_/);
    await page.getByTestId('inspector-field-props.component').selectOption('input');
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, detailPagePid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId(`outline-item-${subTableId}`).click();
    await expect(page.getByTestId('inspector-field-title')).toHaveValue(detailSubTableTitle);
    await page.getByTestId(`outline-item-${repeaterId}`).click();
    await expect(page.getByTestId('inspector-field-title')).toHaveValue(detailRepeaterTitle);
    await page.getByTestId(`outline-item-${subformId}`).click();
    await expect(page.getByTestId('inspector-field-title')).toHaveValue(detailSubformTitle);
    await page.getByTestId(`outline-item-${actionId}`).click();
    await expect(page.getByTestId('inspector-field-props.label')).toHaveValue(detailActionLabel);
    await page.getByTestId(`outline-item-${widgetId}`).click();
    await expect(page.getByTestId('inspector-field-props.title')).toHaveValue(detailWidgetTitle);

    const persisted = await readPage(page, detailPagePid);
    expect(findBlockById(persisted.blocks ?? [], subTableId)).toMatchObject({
      blockType: 'sub-table',
      title: detailSubTableTitle,
      props: expect.objectContaining({ rows: [{ [fieldCode]: subTablePreviewValue }] }),
    });
    expect(findBlockById(persisted.blocks ?? [], repeaterId)).toMatchObject({
      blockType: 'repeater',
      title: detailRepeaterTitle,
      props: expect.objectContaining({ rows: [{ [fieldCode]: repeaterPreviewValue }] }),
    });
    expect(findBlockById(persisted.blocks ?? [], subformId)).toMatchObject({
      blockType: 'subform',
      title: detailSubformTitle,
      props: expect.objectContaining({ rows: [{ [fieldCode]: subformPreviewValue }] }),
    });
    expect(findBlockById(persisted.blocks ?? [], actionId)).toMatchObject({
      blockType: 'action',
      actionType: 'command',
      props: expect.objectContaining({
        label: detailActionLabel,
        command: detailCommandCode,
      }),
    });
    expect(findBlockById(persisted.blocks ?? [], widgetId)).toMatchObject({
      blockType: 'widget',
      widgetType: 'number-card',
      props: expect.objectContaining({
        title: detailWidgetTitle,
        value: detailWidgetValue,
      }),
    });

    await page.getByTestId('designer-mode-preview').click();
    await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();
    await expect(page.getByTestId(`runtime-block-${subTableId}`)).toContainText(
      detailSubTableTitle,
    );
    await expect(page.getByTestId(`runtime-table-cell-${subTableId}-0-${fieldCode}`)).toHaveText(
      subTablePreviewValue,
    );
    await expect(page.getByTestId(`runtime-repeater-${repeaterId}`)).toContainText(
      detailRepeaterTitle,
    );
    await expect(
      page.getByTestId(`runtime-repeater-input-${repeaterId}-0-${repeaterFieldId}`),
    ).toHaveValue(repeaterPreviewValue);
    await expect(page.getByTestId(`runtime-subform-${subformId}`)).toContainText(
      detailSubformTitle,
    );
    await expect(
      page.getByTestId(`runtime-subform-input-${subformId}-0-${subformFieldId}`),
    ).toHaveValue(subformPreviewValue);
    await expect(page.getByTestId(`runtime-widget-${widgetId}`)).toContainText(detailWidgetTitle);

    const commandRespPromise = page.waitForResponse(
      (response) =>
        response.url().includes('/api/meta/commands/execute/') &&
        response.request().method() === 'POST',
      { timeout: 15000 },
    );
    await page.getByTestId(`runtime-action-${actionId}`).click();
    const commandResp = await commandRespPromise;
    expect([200, 400, 403, 404, 422, 500]).toContain(commandResp.status());
    const commandRequestBody = commandResp.request().postDataJSON() as {
      auditContext?: Record<string, unknown>;
    };
    expect(commandRequestBody.auditContext).toMatchObject({
      source: 'unified-designer-runtime-preview',
      pageKind: 'detail',
      schemaVersion: 3,
      blockId: actionId,
      blockType: 'action',
      actionType: 'command',
    });
  });

  test('UDW-043: drags a new widget onto a dashboard and persists runtime rendering', async ({
    page,
  }) => {
    expect(dashboardPagePid).toBeTruthy();

    const newWidgetId = 'widget_new_widget';
    const newWidgetTitle = `Palette widget ${uid}`;
    const newWidgetMarkdown = `Dashboard palette widget ${uid}`;

    await page.goto(`/unified-designer?pageId=${dashboardPagePid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-dashboard_root').click();
    await switchResourceTab(page, 'blocks');
    const widgetPaletteItem = page.getByTestId('palette-add-widget');
    await expect(widgetPaletteItem).toBeVisible();
    await expect(widgetPaletteItem).toBeEnabled();
    await expect(widgetPaletteItem).toHaveAttribute('aria-roledescription', 'draggable');
    await dndDragTo(page, widgetPaletteItem, page.getByTestId('canvas-block-dashboard_root'));

    await expect(page.getByTestId(`canvas-block-${newWidgetId}`)).toBeVisible();
    await expect(page.getByTestId('inspector-selected-id')).toContainText(newWidgetId);
    await page.getByTestId('inspector-field-widgetType').selectOption('markdown');
    await page.getByTestId('inspector-field-props.title').fill(newWidgetTitle);
    await page.getByTestId('inspector-field-props.markdown').fill(newWidgetMarkdown);
    await page.getByTestId('inspector-field-layout.x').fill('4');
    await page.getByTestId('inspector-field-layout.y').fill('2');
    await page.getByTestId('inspector-field-layout.w').fill('4');
    await page.getByTestId('inspector-field-layout.h').fill('2');
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, dashboardPagePid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId(`outline-item-${newWidgetId}`).click();
    await expect(page.getByTestId('inspector-field-widgetType')).toHaveValue('markdown');
    await expect(page.getByTestId('inspector-field-props.title')).toHaveValue(newWidgetTitle);
    await expect(page.getByTestId('inspector-field-props.markdown')).toHaveValue(newWidgetMarkdown);
    await expect(page.getByTestId('inspector-field-layout.x')).toHaveValue('4');
    await expect(page.getByTestId('inspector-field-layout.y')).toHaveValue('2');
    await expect(page.getByTestId('inspector-field-layout.w')).toHaveValue('4');
    await expect(page.getByTestId('inspector-field-layout.h')).toHaveValue('2');

    const persisted = await readPage(page, dashboardPagePid);
    const persistedWidget = findBlockById(persisted.blocks ?? [], newWidgetId);
    expect(persistedWidget).toMatchObject({
      blockType: 'widget',
      widgetType: 'markdown',
      layout: expect.objectContaining({ x: 4, y: 2, w: 4, h: 2 }),
      props: expect.objectContaining({
        title: newWidgetTitle,
        markdown: newWidgetMarkdown,
      }),
    });

    await page.getByTestId('designer-mode-preview').click();
    await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();
    await expect(page.getByTestId(`runtime-widget-${newWidgetId}`)).toContainText(newWidgetTitle);
    await expect(page.getByTestId(`runtime-widget-markdown-${newWidgetId}`)).toContainText(
      newWidgetMarkdown,
    );
  });

  test('UDW-041: authors list tabs with filter, table, action, and runtime filtering', async ({
    page,
  }) => {
    const tabbedListPageKey = `udw_v3_list_tabs_${uid}`;
    const tabbedListPid = await createPageResource(page, {
      name: `UDW V3 List Tabs ${uid}`,
      pageKey: tabbedListPageKey,
      title: `UDW V3 List Tabs ${uid}`,
      kind: 'list',
      modelCode,
      schemaVersion: 3,
      blocks: [
        {
          id: 'list_root',
          blockType: 'list',
          title: 'Tabbed List',
          dataSource: { model: modelCode },
          layout: { span: 12 },
          blocks: [],
        },
      ],
      extension: { e2e: true, scenario: 'unified-designer-workbench-v3-list-tabs' },
    });
    const tabsTitle = `List tabs ${uid}`;
    const tabTitle = `Review tab ${uid}`;
    const filterLabel = `List tab filter ${uid}`;
    const columnLabel = `List tab column ${uid}`;
    const tableTitle = `List tab table ${uid}`;
    const actionLabel = `List tab action ${uid}`;
    const actionTitle = `List tab modal ${uid}`;
    const matchingValue = `Needle row ${uid}`;
    const hiddenValue = `Other row ${uid}`;

    await page.goto(`/unified-designer?pageId=${tabbedListPid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-list_root').click();
    await switchResourceTab(page, 'blocks');
    await dndDragTo(page, page.getByTestId('palette-add-tabs'), page.getByTestId('canvas-block-list_root'));
    const tabsId = (await page.getByTestId('inspector-selected-id').textContent())?.trim() ?? '';
    expect(tabsId).toMatch(/^tabs_new_tabs(_\d+)?$/);
    await page.getByTestId('inspector-field-title').fill(tabsTitle);

    await dndDragTo(page, page.getByTestId('palette-add-tab'), page.getByTestId(`canvas-block-${tabsId}`));
    const tabId = (await page.getByTestId('inspector-selected-id').textContent())?.trim() ?? '';
    expect(tabId).toMatch(/^tab_new_tab(_\d+)?$/);
    await page.getByTestId('inspector-field-title').fill(tabTitle);

    await dndDragTo(page, page
      .getByTestId('palette-add-filter-bar'), page.getByTestId(`canvas-block-${tabId}`));
    const filterBarId =
      (await page.getByTestId('inspector-selected-id').textContent())?.trim() ?? '';
    expect(filterBarId).toMatch(/^filter_bar_new_filter_bar(_\d+)?$/);

    await switchResourceTab(page, 'fields');
    await page.getByTestId('field-palette-search').fill(fieldCode);
    await dndDragTo(page, page
      .getByTestId(`model-field-${fieldCode}`), page.getByTestId(`canvas-block-${filterBarId}`));
    const filterId = (await page.getByTestId('inspector-selected-id').textContent())?.trim() ?? '';
    expect(filterId).toMatch(new RegExp(`^filter_${fieldCode}(_\\d+)?$`));
    await page.getByTestId('inspector-field-props.label').fill(filterLabel);
    await page.getByTestId('inspector-field-props.component').selectOption('input');
    await page.getByTestId('inspector-field-props.operator').selectOption('contains');

    await switchResourceTab(page, 'outline');
    await page.getByTestId(`outline-item-${tabId}`).click();
    await switchResourceTab(page, 'blocks');
    await dndDragTo(page, page
      .getByTestId('palette-add-table'), page.getByTestId(`canvas-block-${tabId}`), { targetPosition: { x: 20, y: 20 } });
    const tableId = (await page.getByTestId('inspector-selected-id').textContent())?.trim() ?? '';
    expect(tableId).toMatch(/^table_new_table(_\d+)?$/);
    await page.getByTestId('inspector-field-title').fill(tableTitle);
    await page
      .getByTestId('inspector-field-props.rows')
      .fill(JSON.stringify([{ [fieldCode]: matchingValue }, { [fieldCode]: hiddenValue }]));
    await applyJsonField(page, 'inspector-json-field-apply-props.rows');

    await switchResourceTab(page, 'fields');
    await page.getByTestId('field-palette-search').fill(fieldCode);
    await dndDragTo(page, page
      .getByTestId(`model-field-${fieldCode}`), page.getByTestId(`canvas-block-${tableId}`));
    const columnId = (await page.getByTestId('inspector-selected-id').textContent())?.trim() ?? '';
    expect(columnId).toMatch(new RegExp(`^column_${fieldCode}(_\\d+)?$`));
    await page.getByTestId('inspector-field-props.label').fill(columnLabel);

    await switchResourceTab(page, 'outline');
    await page.getByTestId(`outline-item-${tabId}`).click();
    await switchResourceTab(page, 'blocks');
    // Click-to-add against the current selection (the tab, selected above). The tab
    // now holds a filter-bar plus a table with a column, so a drop aimed at it
    // resolves to a smaller descendant that rejects an `action-bar` (see the UDW-040
    // note on prioritizeNestedDropCollisions) and the gesture is a silent no-op.
    // The action drag below still exercises the drag path.
    await page.getByTestId('palette-add-action-bar').click();
    const actionBarId =
      (await page.getByTestId('inspector-selected-id').textContent())?.trim() ?? '';
    expect(actionBarId).toMatch(/^action_bar_new_action_bar(_\d+)?$/);
    await dndDragTo(page, page
      .getByTestId('palette-add-action'), page.getByTestId(`canvas-block-${actionBarId}`));
    const actionId = (await page.getByTestId('inspector-selected-id').textContent())?.trim() ?? '';
    expect(actionId).toMatch(/^action_new_action(_\d+)?$/);
    await page.getByTestId('inspector-field-actionType').selectOption('modal');
    await page.getByTestId('inspector-field-props.label').fill(actionLabel);
    await page.getByTestId('inspector-field-props.title').fill(actionTitle);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, tabbedListPid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId(`outline-item-${tabsId}`).click();
    await expect(page.getByTestId('inspector-field-title')).toHaveValue(tabsTitle);
    await page.getByTestId(`outline-item-${filterId}`).click();
    await expect(page.getByTestId('inspector-field-props.operator')).toHaveValue('contains');
    await page.getByTestId(`outline-item-${tableId}`).click();
    await expect(page.getByTestId('inspector-field-props.rows')).toHaveValue(/Needle row/);

    const persisted = await readPage(page, tabbedListPid);
    expect(findBlockById(persisted.blocks ?? [], tabsId)).toMatchObject({ blockType: 'tabs' });
    expect(findBlockById(persisted.blocks ?? [], filterId)).toMatchObject({
      blockType: 'filter-field',
      field: fieldCode,
      props: expect.objectContaining({ label: filterLabel, operator: 'contains' }),
    });
    expect(findBlockById(persisted.blocks ?? [], tableId)).toMatchObject({
      blockType: 'table',
      props: expect.objectContaining({
        rows: [{ [fieldCode]: matchingValue }, { [fieldCode]: hiddenValue }],
      }),
    });

    await page.getByTestId('designer-mode-preview').click();
    await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();
    await expect(page.getByTestId(`runtime-tab-trigger-${tabId}`)).toContainText(tabTitle);
    await expect(page.getByTestId(`runtime-filter-input-${filterId}`)).toBeVisible();
    await expect(page.getByTestId(`runtime-table-${tableId}`)).toContainText(matchingValue);
    await expect(page.getByTestId(`runtime-table-${tableId}`)).toContainText(hiddenValue);
    await page.getByTestId(`runtime-filter-input-${filterId}`).fill('Needle');
    await expect(page.getByTestId(`runtime-table-${tableId}`)).toContainText(matchingValue);
    await expect(page.getByTestId(`runtime-table-${tableId}`)).not.toContainText(hiddenValue);
    await page.getByTestId(`runtime-action-${actionId}`).click();
    await expect(page.getByTestId(`runtime-action-overlay-${actionId}`)).toContainText(actionTitle);
  });

  test('UDW-044: moves dashboard widgets by browser drag and rejects overlap', async ({ page }) => {
    const movePageKey = `udw_v3_dashboard_move_${uid}`;
    const moveDashboardPid = await createPageResource(page, {
      name: `UDW V3 Dashboard Move ${uid}`,
      pageKey: movePageKey,
      title: `UDW V3 Dashboard Move ${uid}`,
      kind: 'detail',
      modelCode,
      schemaVersion: 3,
      blocks: [
        {
          id: 'dashboard_root',
          blockType: 'dashboard',
          title: 'Move Dashboard',
          layout: { span: 12, type: 'dashboard-grid', cols: 12, rowHeight: 80, gap: 16 },
          blocks: [
            {
              id: 'widget_alpha',
              blockType: 'widget',
              widgetType: 'number-card',
              layout: { x: 0, y: 0, w: 3, h: 2, span: 3 },
              props: { title: 'Alpha' },
            },
            {
              id: 'widget_beta',
              blockType: 'widget',
              widgetType: 'number-card',
              layout: { x: 4, y: 0, w: 3, h: 2, span: 3 },
              props: { title: 'Beta' },
            },
          ],
        },
      ],
      extension: { e2e: true, scenario: 'unified-designer-workbench-v3-dashboard-move' },
    });

    await page.goto(`/unified-designer?pageId=${moveDashboardPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('designer-mode-layout').click();

    await dragWidgetByMouse(page, 'widget_alpha', 0, 160);
    await page.getByTestId('outline-item-widget_alpha').click();
    await expect(page.getByTestId('inspector-field-layout.x')).toHaveValue('0');
    await expect(page.getByTestId('inspector-field-layout.y')).toHaveValue('2');

    await dragWidgetByMouse(page, 'widget_beta', -320, 160, 1);
    await page.getByTestId('outline-item-widget_beta').click();
    await expect(page.getByTestId('inspector-field-layout.x')).toHaveValue('4');
    await expect(page.getByTestId('inspector-field-layout.y')).toHaveValue('0');
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, moveDashboardPid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('outline-item-widget_alpha').click();
    await expect(page.getByTestId('inspector-field-layout.y')).toHaveValue('2');
    await page.getByTestId('outline-item-widget_beta').click();
    await expect(page.getByTestId('inspector-field-layout.x')).toHaveValue('4');
    await expect(page.getByTestId('inspector-field-layout.y')).toHaveValue('0');

    const persisted = await readPage(page, moveDashboardPid);
    expect(findBlockById(persisted.blocks ?? [], 'widget_alpha')).toMatchObject({
      layout: expect.objectContaining({ x: 0, y: 2 }),
    });
    expect(findBlockById(persisted.blocks ?? [], 'widget_beta')).toMatchObject({
      layout: expect.objectContaining({ x: 4, y: 0 }),
    });
  });

  test('UDW-045: configures date, number, and switch form controls in browser preview', async ({
    page,
  }) => {
    const componentPageKey = `udw_v3_form_components_${uid}`;
    const componentFormPid = await createPageResource(page, {
      name: `UDW V3 Form Components ${uid}`,
      pageKey: componentPageKey,
      title: `UDW V3 Form Components ${uid}`,
      kind: 'form',
      modelCode,
      schemaVersion: 3,
      blocks: [
        {
          id: 'form_root',
          blockType: 'form',
          title: 'Component Form',
          dataSource: { model: modelCode },
          layout: { span: 12 },
          blocks: [
            {
              id: 'section_components',
              blockType: 'form-section',
              title: 'Components',
              layout: { span: 12, columns: 12 },
              blocks: [],
            },
          ],
        },
      ],
      extension: { e2e: true, scenario: 'unified-designer-workbench-v3-form-components' },
    });
    const dateLabel = `Runtime date ${uid}`;
    const numberLabel = `Runtime number ${uid}`;
    const switchLabel = `Runtime switch ${uid}`;

    await page.goto(`/unified-designer?pageId=${componentFormPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('outline-item-section_components').click();
    await switchResourceTab(page, 'fields');
    await page.getByTestId('field-palette-add-field').click();
    const dateFieldId =
      (await page.getByTestId('inspector-selected-id').textContent())?.trim() ?? '';
    await page.getByTestId('inspector-field-field').selectOption('created_at');
    await page.getByTestId('inspector-field-props.label').fill(dateLabel);
    await page.getByTestId('inspector-field-props.component').selectOption('date');
    await page.getByTestId('inspector-field-props.placeholder').fill('YYYY-MM-DD');

    await switchResourceTab(page, 'outline');
    await page.getByTestId('outline-item-section_components').click();
    await switchResourceTab(page, 'fields');
    await page.getByTestId('field-palette-add-field').click();
    const numberFieldId =
      (await page.getByTestId('inspector-selected-id').textContent())?.trim() ?? '';
    await page.getByTestId('inspector-field-field').selectOption('page_key');
    await page.getByTestId('inspector-field-props.label').fill(numberLabel);
    await page.getByTestId('inspector-field-props.component').selectOption('number');
    await page.getByTestId('inspector-field-props.helpText').fill(`Number help ${uid}`);

    await switchResourceTab(page, 'outline');
    await page.getByTestId('outline-item-section_components').click();
    await switchResourceTab(page, 'fields');
    await page.getByTestId('field-palette-add-field').click();
    const switchFieldId =
      (await page.getByTestId('inspector-selected-id').textContent())?.trim() ?? '';
    await page.getByTestId('inspector-field-field').selectOption('name');
    await page.getByTestId('inspector-field-props.label').fill(switchLabel);
    await page.getByTestId('inspector-field-props.component').selectOption('switch');
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, componentFormPid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId(`outline-item-${dateFieldId}`).click();
    await expect(page.getByTestId('inspector-field-props.component')).toHaveValue('date');
    await page.getByTestId(`outline-item-${numberFieldId}`).click();
    await expect(page.getByTestId('inspector-field-props.component')).toHaveValue('number');
    await page.getByTestId(`outline-item-${switchFieldId}`).click();
    await expect(page.getByTestId('inspector-field-props.component')).toHaveValue('switch');

    const persisted = await readPage(page, componentFormPid);
    expect(findBlockById(persisted.blocks ?? [], dateFieldId)).toMatchObject({
      field: 'created_at',
      props: expect.objectContaining({ label: dateLabel, component: 'date' }),
    });
    expect(findBlockById(persisted.blocks ?? [], numberFieldId)).toMatchObject({
      field: 'page_key',
      props: expect.objectContaining({ label: numberLabel, component: 'number' }),
    });
    expect(findBlockById(persisted.blocks ?? [], switchFieldId)).toMatchObject({
      field: 'name',
      props: expect.objectContaining({ label: switchLabel, component: 'switch' }),
    });

    await page.getByTestId('designer-mode-preview').click();
    await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();
    // WYSIWYG renders the real platform controls: date → SmartDatePicker (native
    // <input type="date"> carrying the `date-picker-input-<fieldCode>` testid), number →
    // SmartNumberInput (a stepper <input type="text" inputMode="decimal">, not a native
    // number input), switch → SmartSwitch (<button role="switch">).
    // The designer's `date` component id is translated to SmartDatePicker in
    // buildPreviewFieldConfig; the same-named legacy platform control is a bare date
    // input that forwards the raw change *event* to onChange, so the bound value became
    // `[object Object]` and the input read back empty. Assert the string round-trip so a
    // regression to that control fails here.
    const dateControl = runtimePreviewControl(page, dateFieldId);
    await expect(dateControl).toBeVisible();
    await expect(dateControl).toHaveAttribute('type', 'date');
    await expect(dateControl).toHaveAttribute('data-testid', 'date-picker-input-created_at');
    await expect(dateControl).toBeEditable();
    await dateControl.fill('2026-03-18');
    await expect(dateControl).toHaveValue('2026-03-18');
    const numberControl = runtimePreviewControl(page, numberFieldId);
    await expect(numberControl).toHaveAttribute('inputmode', 'decimal');
    await numberControl.fill('42');
    await expect(numberControl).toHaveValue('42');
    // helpText is rendered by ControlledFieldRenderer for every control type, not just
    // the smart Input (SmartNumberInput never forwarded a plain helpText prop).
    await expect(page.getByTestId(`runtime-field-${numberFieldId}`)).toContainText(
      `Number help ${uid}`,
    );
    const switchControl = page.getByTestId(`runtime-field-${switchFieldId}`).locator('[role="switch"]');
    await switchControl.click();
    await expect(switchControl).toBeChecked();
  });

  test('UDW-046: adds and renders a list-level widget with table properties', async ({ page }) => {
    const widgetListPageKey = `udw_v3_list_widget_${uid}`;
    const widgetListPid = await createPageResource(page, {
      name: `UDW V3 List Widget ${uid}`,
      pageKey: widgetListPageKey,
      title: `UDW V3 List Widget ${uid}`,
      kind: 'list',
      modelCode,
      schemaVersion: 3,
      blocks: [
        {
          id: 'list_root',
          blockType: 'list',
          title: 'Widget List',
          dataSource: { model: modelCode },
          layout: { span: 12 },
          blocks: [],
        },
      ],
      extension: { e2e: true, scenario: 'unified-designer-workbench-v3-list-widget' },
    });
    const listWidgetTitle = `List widget table ${uid}`;
    const listWidgetSubtitle = `List widget subtitle ${uid}`;
    const listWidgetRows = [
      ['Open', `12 ${uid}`],
      ['Closed', `5 ${uid}`],
    ];

    await page.goto(`/unified-designer?pageId=${widgetListPid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('outline-item-list_root').click();
    await switchResourceTab(page, 'blocks');
    await dndDragTo(page, page.getByTestId('palette-add-widget'), page.getByTestId('canvas-block-list_root'));
    const widgetId = (await page.getByTestId('inspector-selected-id').textContent())?.trim() ?? '';
    expect(widgetId).toMatch(/^widget_new_widget(_\d+)?$/);
    await page.getByTestId('inspector-field-widgetType').selectOption('table');
    await page.getByTestId('inspector-field-props.title').fill(listWidgetTitle);
    await page.getByTestId('inspector-field-props.subtitle').fill(listWidgetSubtitle);
    await page
      .getByTestId('inspector-field-props.columns')
      .fill(JSON.stringify(['Status', 'Count']));
    await applyJsonField(page, 'inspector-json-field-apply-props.columns');
    await page.getByTestId('inspector-field-props.rows').fill(JSON.stringify(listWidgetRows));
    await applyJsonField(page, 'inspector-json-field-apply-props.rows');
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, widgetListPid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId(`outline-item-${widgetId}`).click();
    await expect(page.getByTestId('inspector-field-widgetType')).toHaveValue('table');
    await expect(page.getByTestId('inspector-field-props.title')).toHaveValue(listWidgetTitle);
    await expect(page.getByTestId('inspector-field-props.rows')).toHaveValue(/Closed/);

    const persisted = await readPage(page, widgetListPid);
    expect(findBlockById(persisted.blocks ?? [], widgetId)).toMatchObject({
      blockType: 'widget',
      widgetType: 'table',
      props: expect.objectContaining({
        title: listWidgetTitle,
        subtitle: listWidgetSubtitle,
        columns: ['Status', 'Count'],
        rows: listWidgetRows,
      }),
    });

    await page.getByTestId('designer-mode-preview').click();
    await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();
    await expect(page.getByTestId(`runtime-widget-${widgetId}`)).toContainText(listWidgetTitle);
    await expect(page.getByTestId(`runtime-widget-subtitle-${widgetId}`)).toContainText(
      listWidgetSubtitle,
    );
    await expect(page.getByTestId(`runtime-widget-table-${widgetId}`)).toContainText('Status');
    await expect(page.getByTestId(`runtime-widget-table-${widgetId}`)).toContainText(`12 ${uid}`);
  });

  test('UDW-047: authors detail tabs with helper blocks and persists runtime panels', async ({
    page,
  }) => {
    const detailTabsPageKey = `udw_v3_detail_tabs_${uid}`;
    const detailTabsPid = await createPageResource(page, {
      name: `UDW V3 Detail Tabs ${uid}`,
      pageKey: detailTabsPageKey,
      title: `UDW V3 Detail Tabs ${uid}`,
      kind: 'detail',
      modelCode,
      schemaVersion: 3,
      blocks: [
        {
          id: 'detail_root',
          blockType: 'detail',
          title: 'Tabbed Detail',
          dataSource: { model: modelCode },
          layout: { span: 12 },
          blocks: [],
        },
      ],
      extension: { e2e: true, scenario: 'unified-designer-workbench-v3-detail-tabs' },
    });
    const tabsTitle = `Detail tabs ${uid}`;
    const tabTitle = `Workflow tab ${uid}`;
    const sectionTitle = `Detail tab section ${uid}`;
    const fieldLabel = `Detail tab field ${uid}`;
    const aiTitle = `AI helper ${uid}`;
    const bpmTitle = `BPM helper ${uid}`;
    const timelineTitle = `Timeline helper ${uid}`;
    const historyTitle = `History helper ${uid}`;
    const aiDescription = `Generated helper suggestions ${uid}`;
    const aiFeedback = `AI suggestions applied ${uid}`;
    const aiSuggestedFields = [{ field: fieldCode, label: fieldLabel, value: `Suggested ${uid}` }];
    const bpmDescription = `Approval state ${uid}`;
    const bpmAssignee = `Approver ${uid}`;
    const bpmDueAt = '2026-05-21';
    const bpmActions = [{ label: `Approve ${uid}`, actionType: 'approve' }];
    const helperDataSourceParams = { recordId: `record-${uid}` };
    const timelineItems = [
      {
        actor: `Operator ${uid}`,
        action: `Updated record ${uid}`,
        time: '2026-05-20 10:00',
        description: `Activity detail ${uid}`,
      },
    ];
    const historyEntries = [
      {
        field: fieldCode,
        from: 'draft',
        to: `review ${uid}`,
        changedBy: `Reviewer ${uid}`,
      },
    ];

    await page.goto(`/unified-designer?pageId=${detailTabsPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-detail_root').click();
    await switchResourceTab(page, 'blocks');
    await dndDragTo(page, page.getByTestId('palette-add-tabs'), page.getByTestId('canvas-block-detail_root'));
    const tabsId = (await page.getByTestId('inspector-selected-id').textContent())?.trim() ?? '';
    expect(tabsId).toMatch(/^tabs_new_tabs(_\d+)?$/);
    await page.getByTestId('inspector-field-title').fill(tabsTitle);

    await dndDragTo(page, page.getByTestId('palette-add-tab'), page.getByTestId(`canvas-block-${tabsId}`));
    const tabId = (await page.getByTestId('inspector-selected-id').textContent())?.trim() ?? '';
    expect(tabId).toMatch(/^tab_new_tab(_\d+)?$/);
    await page.getByTestId('inspector-field-title').fill(tabTitle);

    await dndDragTo(page, page
      .getByTestId('palette-add-detail-section'), page.getByTestId(`canvas-block-${tabId}`));
    const sectionId = (await page.getByTestId('inspector-selected-id').textContent())?.trim() ?? '';
    expect(sectionId).toMatch(/^detail_section_new_detail_section(_\d+)?$/);
    await page.getByTestId('inspector-field-title').fill(sectionTitle);

    await switchResourceTab(page, 'fields');
    await page.getByTestId('field-palette-search').fill(fieldCode);
    await dndDragTo(page, page
      .getByTestId(`model-field-${fieldCode}`), page.getByTestId(`canvas-block-${sectionId}`));
    const fieldId = (await page.getByTestId('inspector-selected-id').textContent())?.trim() ?? '';
    expect(fieldId).toMatch(new RegExp(`^field_${fieldCode}(_\\d+)?$`));
    await page.getByTestId('inspector-field-props.label').fill(fieldLabel);

    const helperBlocks = [
      { paletteId: 'palette-add-ai-fill-banner', blockType: 'ai-fill-banner', title: aiTitle },
      { paletteId: 'palette-add-bpm-panel', blockType: 'bpm-panel', title: bpmTitle },
      {
        paletteId: 'palette-add-activity-timeline',
        blockType: 'activity-timeline',
        title: timelineTitle,
      },
      { paletteId: 'palette-add-field-history', blockType: 'field-history', title: historyTitle },
    ];
    const helperIds: string[] = [];
    const helperIdsByType: Record<string, string> = {};
    for (const helper of helperBlocks) {
      await switchResourceTab(page, 'outline');
      await page.getByTestId(`outline-item-${tabId}`).click();
      await switchResourceTab(page, 'blocks');
      await expect(page.getByTestId(helper.paletteId)).toBeEnabled();
      // Click-to-add against the current selection (the tab, re-selected each
      // iteration). The tab grows with every helper appended, so a drop aimed at it
      // resolves to a smaller descendant that rejects the helper block (see the
      // UDW-040 note on prioritizeNestedDropCollisions) and silently adds nothing.
      await page.getByTestId(helper.paletteId).click();
      const helperId =
        (await page.getByTestId('inspector-selected-id').textContent())?.trim() ?? '';
      expect(helperId).toContain(helper.blockType.replaceAll('-', '_'));
      await page.getByTestId('inspector-field-title').fill(helper.title);
      await page.getByTestId('inspector-field-dataSource.type').selectOption('namedQuery');
      await page.getByTestId('inspector-field-dataSource.executionMode').selectOption('preview');
      await page
        .getByTestId('inspector-field-dataSource.queryCode-manual')
        .fill(`udw_${helper.blockType.replaceAll('-', '_')}_${uid}`);
      await page
        .getByTestId('inspector-field-dataSource.parameters')
        .fill(JSON.stringify({ ...helperDataSourceParams, helper: helper.blockType }));
      await applyJsonField(page, 'inspector-json-field-apply-dataSource.parameters');
      if (helper.blockType === 'ai-fill-banner') {
        await page.getByTestId('inspector-field-props.description').fill(aiDescription);
        await page.getByTestId('inspector-field-props.feedback').fill(aiFeedback);
        await page
          .getByTestId('inspector-field-props.suggestedFields')
          .fill(JSON.stringify(aiSuggestedFields));
        await applyJsonField(page, 'inspector-json-field-apply-props.suggestedFields');
      }
      if (helper.blockType === 'bpm-panel') {
        await page.getByTestId('inspector-field-props.description').fill(bpmDescription);
        await page.getByTestId('inspector-field-props.status').selectOption('pending');
        await page.getByTestId('inspector-field-props.assignee').fill(bpmAssignee);
        await page.getByTestId('inspector-field-props.dueAt').fill(bpmDueAt);
        await page.getByTestId('inspector-field-props.actions').fill(JSON.stringify(bpmActions));
        await applyJsonField(page, 'inspector-json-field-apply-props.actions');
      }
      if (helper.blockType === 'activity-timeline') {
        await page.getByTestId('inspector-field-props.items').fill(JSON.stringify(timelineItems));
        await applyJsonField(page, 'inspector-json-field-apply-props.items');
      }
      if (helper.blockType === 'field-history') {
        await page
          .getByTestId('inspector-field-props.entries')
          .fill(JSON.stringify(historyEntries));
        await applyJsonField(page, 'inspector-json-field-apply-props.entries');
      }
      helperIds.push(helperId);
      helperIdsByType[helper.blockType] = helperId;
    }
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, detailTabsPid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId(`outline-item-${tabsId}`).click();
    await expect(page.getByTestId('inspector-field-title')).toHaveValue(tabsTitle);
    await page.getByTestId(`outline-item-${tabId}`).click();
    await expect(page.getByTestId('inspector-field-title')).toHaveValue(tabTitle);
    await page.getByTestId(`outline-item-${fieldId}`).click();
    await expect(page.getByTestId('inspector-field-props.label')).toHaveValue(fieldLabel);
    for (let index = 0; index < helperIds.length; index += 1) {
      await page.getByTestId(`outline-item-${helperIds[index]}`).click();
      await expect(page.getByTestId('inspector-field-title')).toHaveValue(
        helperBlocks[index].title,
      );
    }

    const persisted = await readPage(page, detailTabsPid);
    expect(findBlockById(persisted.blocks ?? [], tabsId)).toMatchObject({
      blockType: 'tabs',
      title: tabsTitle,
    });
    expect(findBlockById(persisted.blocks ?? [], tabId)).toMatchObject({
      blockType: 'tab',
      title: tabTitle,
    });
    expect(findBlockById(persisted.blocks ?? [], sectionId)).toMatchObject({
      blockType: 'detail-section',
      title: sectionTitle,
    });
    expect(findBlockById(persisted.blocks ?? [], fieldId)).toMatchObject({
      blockType: 'field',
      field: fieldCode,
      props: expect.objectContaining({ label: fieldLabel }),
    });
    helperBlocks.forEach((helper, index) => {
      expect(findBlockById(persisted.blocks ?? [], helperIds[index])).toMatchObject({
        blockType: helper.blockType,
        title: helper.title,
        dataSource: expect.objectContaining({
          type: 'namedQuery',
          executionMode: 'preview',
          queryCode: `udw_${helper.blockType.replaceAll('-', '_')}_${uid}`,
          parameters: { ...helperDataSourceParams, helper: helper.blockType },
        }),
      });
    });
    expect(findBlockById(persisted.blocks ?? [], helperIdsByType['ai-fill-banner'])).toMatchObject({
      props: expect.objectContaining({
        description: aiDescription,
        feedback: aiFeedback,
        suggestedFields: aiSuggestedFields,
      }),
    });
    expect(findBlockById(persisted.blocks ?? [], helperIdsByType['bpm-panel'])).toMatchObject({
      props: expect.objectContaining({
        description: bpmDescription,
        status: 'pending',
        assignee: bpmAssignee,
        dueAt: bpmDueAt,
        actions: bpmActions,
      }),
    });
    expect(
      findBlockById(persisted.blocks ?? [], helperIdsByType['activity-timeline']),
    ).toMatchObject({
      props: expect.objectContaining({ items: timelineItems }),
    });
    expect(findBlockById(persisted.blocks ?? [], helperIdsByType['field-history'])).toMatchObject({
      props: expect.objectContaining({ entries: historyEntries }),
    });

    await page.getByTestId('designer-mode-preview').click();
    await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();
    await expect(page.getByTestId(`runtime-tab-trigger-${tabId}`)).toContainText(tabTitle);
    await expect(page.getByTestId(`runtime-tab-panel-${tabId}`)).toContainText(sectionTitle);
    await expect(page.getByTestId(`runtime-field-${fieldId}`)).toContainText(fieldLabel);
    await expect(
      page.getByTestId(`runtime-ai-fill-banner-${helperIdsByType['ai-fill-banner']}`),
    ).toContainText(aiDescription);
    await expect(
      page.getByTestId(`runtime-ai-fill-field-${helperIdsByType['ai-fill-banner']}-0`),
    ).toContainText(`Suggested ${uid}`);
    await page.getByTestId(`runtime-ai-fill-apply-${helperIdsByType['ai-fill-banner']}`).click();
    await expect(
      page.getByTestId(`runtime-ai-fill-status-${helperIdsByType['ai-fill-banner']}`),
    ).toContainText(aiFeedback);
    await expect(page.getByTestId(`runtime-bpm-status-${helperIdsByType['bpm-panel']}`)).toHaveText(
      'pending',
    );
    await expect(
      page.getByTestId(`runtime-bpm-assignee-${helperIdsByType['bpm-panel']}`),
    ).toContainText(bpmAssignee);
    await expect(
      page.getByTestId(`runtime-bpm-action-${helperIdsByType['bpm-panel']}-0`),
    ).toContainText(`Approve ${uid}`);
    await expect(
      page.getByTestId(`runtime-activity-item-${helperIdsByType['activity-timeline']}-0`),
    ).toContainText(`Updated record ${uid}`);
    await expect(
      page.getByTestId(`runtime-field-history-entry-${helperIdsByType['field-history']}-0`),
    ).toContainText(`review ${uid}`);
  });

  test('UDW-048: edits dashboard root grid properties and renders persisted grid styles', async ({
    page,
  }) => {
    const gridPageKey = `udw_v3_dashboard_grid_${uid}`;
    const gridDashboardPid = await createPageResource(page, {
      name: `UDW V3 Dashboard Grid ${uid}`,
      pageKey: gridPageKey,
      title: `UDW V3 Dashboard Grid ${uid}`,
      kind: 'detail',
      modelCode,
      schemaVersion: 3,
      blocks: [
        {
          id: 'dashboard_root',
          blockType: 'dashboard',
          title: 'Grid Dashboard',
          layout: { span: 12, type: 'dashboard-grid', cols: 12, rowHeight: 80, gap: 16 },
          blocks: [
            {
              id: 'widget_grid_probe',
              blockType: 'widget',
              widgetType: 'number-card',
              layout: { x: 0, y: 0, w: 2, h: 2, span: 2 },
              props: { title: `Grid probe ${uid}`, value: '7' },
            },
          ],
        },
      ],
      extension: { e2e: true, scenario: 'unified-designer-workbench-v3-dashboard-grid' },
    });

    await page.goto(`/unified-designer?pageId=${gridDashboardPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');
    await page.getByTestId('outline-item-dashboard_root').click();
    await page.getByTestId('inspector-field-layout.cols').fill('6');
    await page.getByTestId('inspector-field-layout.rowHeight').fill('96');
    await page.getByTestId('inspector-field-layout.gap').fill('24');
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, gridDashboardPid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('outline-item-dashboard_root').click();
    await expect(page.getByTestId('inspector-field-layout.cols')).toHaveValue('6');
    await expect(page.getByTestId('inspector-field-layout.rowHeight')).toHaveValue('96');
    await expect(page.getByTestId('inspector-field-layout.gap')).toHaveValue('24');

    const persisted = await readPage(page, gridDashboardPid);
    expect(findBlockById(persisted.blocks ?? [], 'dashboard_root')).toMatchObject({
      blockType: 'dashboard',
      layout: expect.objectContaining({ cols: 6, rowHeight: 96, gap: 24 }),
    });

    await page.getByTestId('designer-mode-preview').click();
    await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();
    const runtimeGrid = page.getByTestId('runtime-dashboard-grid-dashboard_root');
    await expect(runtimeGrid).toHaveCSS('gap', '24px');
    await expect(runtimeGrid).toHaveCSS('grid-auto-rows', '96px');
    await expect(runtimeGrid).toHaveAttribute('style', /grid-template-columns: repeat\(6,/);
    await expect(page.getByTestId('runtime-widget-widget_grid_probe')).toContainText(
      `Grid probe ${uid}`,
    );
  });

  test('UDW-049: renders helper blocks from live named-query data sources in preview', async ({
    page,
  }) => {
    expect(liveHelperNamedQueryCode).toBeTruthy();
    const helperPageKey = `udw_v3_helper_live_${uid}`;
    const helperDataSource = {
      type: 'namedQuery',
      executionMode: 'live',
      queryCode: liveHelperNamedQueryCode,
      page: 1,
      size: 3,
    };
    const helperLivePid = await createPageResource(page, {
      name: `UDW V3 Helper Live ${uid}`,
      pageKey: helperPageKey,
      title: `UDW V3 Helper Live ${uid}`,
      kind: 'detail',
      modelCode,
      schemaVersion: 3,
      blocks: [
        {
          id: 'detail_root',
          blockType: 'detail',
          title: 'Live helper detail',
          dataSource: { model: modelCode },
          layout: { span: 12 },
          blocks: [
            {
              id: 'ai_live_helper',
              blockType: 'ai-fill-banner',
              title: `Live AI helper ${uid}`,
              dataSource: helperDataSource,
              props: {
                description: 'Static AI fallback should be replaced by live data',
                feedback: 'Static feedback',
              },
            },
            {
              id: 'bpm_live_helper',
              blockType: 'bpm-panel',
              title: `Live BPM helper ${uid}`,
              dataSource: helperDataSource,
              props: {
                status: 'draft',
                assignee: 'Static assignee',
              },
            },
            {
              id: 'timeline_live_helper',
              blockType: 'activity-timeline',
              title: `Live timeline helper ${uid}`,
              dataSource: helperDataSource,
              props: {
                emptyText: 'No static timeline',
              },
            },
            {
              id: 'history_live_helper',
              blockType: 'field-history',
              title: `Live history helper ${uid}`,
              dataSource: helperDataSource,
              props: {
                emptyText: 'No static history',
              },
            },
          ],
        },
      ],
      extension: { e2e: true, scenario: 'unified-designer-workbench-v3-helper-live' },
    });

    await page.goto(`/unified-designer?pageId=${helperLivePid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');
    await page.getByTestId('outline-item-ai_live_helper').click();
    await expect(page.getByTestId('inspector-field-dataSource.type')).toHaveValue('namedQuery');
    await expect(page.getByTestId('inspector-field-dataSource.executionMode')).toHaveValue('live');
    await expect(page.getByTestId('inspector-field-dataSource.queryCode')).toHaveValue(
      liveHelperNamedQueryCode,
    );

    const helperRespPromise = page.waitForResponse(
      (response) =>
        response.url().includes(`/api/meta/named-queries/${liveHelperNamedQueryCode}/execute`) &&
        response.request().method() === 'POST',
      { timeout: 15000 },
    );
    await page.getByTestId('designer-mode-preview').click();
    const helperResp = await helperRespPromise;
    expect(helperResp.status()).toBe(200);
    const helperRequestBody = helperResp.request().postDataJSON() as {
      page?: number;
      size?: number;
      executeQuery?: boolean;
    };
    expect(helperRequestBody).toMatchObject({ page: 1, size: 3, executeQuery: true });
    const helperBody = await helperResp.json();
    expect(helperBody.code).toBe('0');
    const rows = (helperBody.data?.records ?? []) as Array<Record<string, unknown>>;
    expect(rows.length).toBeGreaterThan(0);
    const firstRow = rows[0] ?? {};
    const suggestedValue = String(firstRow.value ?? '');
    const assignee = String(firstRow.assignee ?? '');
    const actionLabel = String(firstRow.actionLabel ?? '');

    await expect(page.getByTestId('runtime-helper-source-ai_live_helper')).toHaveText(
      'named-query',
    );
    await expect(page.getByTestId('runtime-ai-fill-field-ai_live_helper-0')).toContainText(
      suggestedValue,
    );
    await page.getByTestId('runtime-ai-fill-apply-ai_live_helper').click();
    await expect(page.getByTestId('runtime-ai-fill-status-ai_live_helper')).toContainText(
      `Live helper suggestions applied ${uid}`,
    );
    await expect(page.getByTestId('runtime-bpm-status-bpm_live_helper')).toHaveText('pending');
    await expect(page.getByTestId('runtime-bpm-assignee-bpm_live_helper')).toContainText(assignee);
    await expect(page.getByTestId('runtime-bpm-due-bpm_live_helper')).toContainText('2026-05-21');
    await expect(page.getByTestId('runtime-bpm-action-bpm_live_helper-0')).toContainText(
      actionLabel,
    );
    await expect(page.getByTestId('runtime-activity-item-timeline_live_helper-0')).toContainText(
      `Loaded live helper activity ${uid}`,
    );
    await expect(
      page.getByTestId('runtime-field-history-entry-history_live_helper-0'),
    ).toContainText('approved');

    const persisted = await readPage(page, helperLivePid);
    for (const blockId of [
      'ai_live_helper',
      'bpm_live_helper',
      'timeline_live_helper',
      'history_live_helper',
    ]) {
      expect(findBlockById(persisted.blocks ?? [], blockId)).toMatchObject({
        dataSource: expect.objectContaining(helperDataSource),
      });
    }
  });

  test('UDW-050: configures and swaps detail fields with saved runtime order', async ({ page }) => {
    expect(fieldCode).toBeTruthy();
    const detailFieldPid = await createPageResource(page, {
      name: `UDW V3 Detail Field Layout ${uid}`,
      pageKey: `udw_v3_detail_field_layout_${uid}`,
      title: `UDW V3 Detail Field Layout ${uid}`,
      kind: 'detail',
      modelCode,
      schemaVersion: 3,
      blocks: [
        {
          id: 'detail_field_layout_root',
          blockType: 'detail',
          title: 'Detail field layout',
          dataSource: { model: modelCode },
          blocks: [
            {
              id: 'detail_field_layout_section',
              blockType: 'detail-section',
              title: 'Field layout section',
              layout: { columns: 12 },
              blocks: [
                {
                  id: 'detail_field_layout_seed',
                  blockType: 'field',
                  field: 'name',
                  layout: { span: 6 },
                  props: {
                    label: 'Seed detail field',
                    component: 'input',
                  },
                },
              ],
            },
          ],
        },
      ],
      extension: { e2e: true, scenario: 'unified-designer-workbench-v3-detail-field-layout' },
    });
    const detailFieldBlockId = fieldBlockId;
    const detailFieldLabel = `Detail reordered field ${uid}`;
    const detailFieldHelp = `Detail field help ${uid}`;

    await page.goto(`/unified-designer?pageId=${detailFieldPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-detail_field_layout_section').click();
    await switchResourceTab(page, 'fields');
    await page.getByTestId('field-palette-search').fill(fieldCode);
    await dndDragTo(page, page
      .getByTestId(`model-field-${fieldCode}`), page.getByTestId('canvas-block-detail_field_layout_section'));
    await expect(page.getByTestId(`canvas-block-${detailFieldBlockId}`)).toBeVisible();
    await expect(page.getByTestId('inspector-selected-id')).toContainText(detailFieldBlockId);
    await page.getByTestId('inspector-field-props.label').fill(detailFieldLabel);
    await page.getByTestId('inspector-field-props.helpText').fill(detailFieldHelp);
    await page.getByTestId('inspector-field-props.component').selectOption('input');

    await page.getByTestId('designer-mode-layout').click();
    const detailFieldOrder = await swapCanvasBlocksByPointerDrag(
      page,
      'detail_field_layout_seed',
      detailFieldBlockId,
    );
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, detailFieldPid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await page.getByTestId(`outline-item-${detailFieldBlockId}`).click();
    await expect(page.getByTestId('inspector-field-props.label')).toHaveValue(detailFieldLabel);
    await expect(page.getByTestId('inspector-field-props.helpText')).toHaveValue(detailFieldHelp);

    const persisted = await readPage(page, detailFieldPid);
    expectChildOrder(persisted.blocks ?? [], 'detail_field_layout_section', detailFieldOrder);
    expect(findBlockById(persisted.blocks ?? [], detailFieldBlockId)).toMatchObject({
      blockType: 'field',
      field: fieldCode,
      props: expect.objectContaining({
        label: detailFieldLabel,
        helpText: detailFieldHelp,
        component: 'input',
      }),
    });

    await page.getByTestId('designer-mode-preview').click();
    await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();
    await expect(page.getByTestId(`runtime-field-${detailFieldBlockId}`)).toContainText(
      detailFieldLabel,
    );
    // `fieldCode` resolves to a dict-backed enum field (`kind`) that the platform renders
    // as a SmartSelect. Help text is owned by `ControlledFieldRenderer` (like the label),
    // so it renders for non-input controls too — it used to be left to each smart control
    // and only the smart *Input* read it, silently dropping configured help everywhere
    // else ("shared renderer silently ignores DSL config"). The legacy
    // `runtime-field-help-<blockId>` node is gone; assert the text inside the block.
    await expect(page.getByTestId(`runtime-field-${detailFieldBlockId}`)).toContainText(
      detailFieldHelp,
    );
    await expect
      .poll(
        async () => {
          const runtimeOrder = await page.evaluate(() =>
            Array.from(document.querySelectorAll('[data-testid^="runtime-field-"]')).flatMap(
              (node) => {
                const testId = node.getAttribute('data-testid');
                return testId ? [testId.replace(/^runtime-field-/, '')] : [];
              },
            ),
          );
          return isOrderBefore(runtimeOrder, detailFieldOrder[0], detailFieldOrder[1]);
        },
        { timeout: 5000 },
      )
      .toBe(true);
  });

  test('UDW-051: renders helper empty and error states from live data sources', async ({
    page,
  }) => {
    expect(liveHelperNamedQueryCode).toBeTruthy();
    const helperEmptyPid = await createPageResource(page, {
      name: `UDW V3 Helper States ${uid}`,
      pageKey: `udw_v3_helper_states_${uid}`,
      title: `UDW V3 Helper States ${uid}`,
      kind: 'detail',
      modelCode,
      schemaVersion: 3,
      blocks: [
        {
          id: 'helper_state_root',
          blockType: 'detail',
          title: 'Helper states',
          dataSource: { model: modelCode },
          blocks: [
            {
              id: 'ai_empty_helper',
              blockType: 'ai-fill-banner',
              title: `Empty AI ${uid}`,
              dataSource: {
                type: 'namedQuery',
                executionMode: 'live',
                queryCode: liveHelperNamedQueryCode,
                page: 999999,
                size: 3,
              },
              props: {
                emptyText: `No AI suggestions ${uid}`,
              },
            },
            {
              id: 'bpm_empty_helper',
              blockType: 'bpm-panel',
              title: `Empty BPM ${uid}`,
              dataSource: {
                type: 'namedQuery',
                executionMode: 'live',
                queryCode: liveHelperNamedQueryCode,
                page: 999999,
                size: 3,
              },
              props: {
                emptyText: `No workflow tasks ${uid}`,
              },
            },
            {
              id: 'timeline_empty_helper',
              blockType: 'activity-timeline',
              title: `Empty timeline ${uid}`,
              dataSource: {
                type: 'namedQuery',
                executionMode: 'live',
                queryCode: liveHelperNamedQueryCode,
                page: 999999,
                size: 3,
              },
              props: {
                emptyText: `No activity ${uid}`,
              },
            },
            {
              id: 'history_empty_helper',
              blockType: 'field-history',
              title: `Empty history ${uid}`,
              dataSource: {
                type: 'namedQuery',
                executionMode: 'live',
                queryCode: liveHelperNamedQueryCode,
                page: 999999,
                size: 3,
              },
              props: {
                emptyText: `No field changes ${uid}`,
              },
            },
            {
              id: 'ai_error_helper',
              blockType: 'ai-fill-banner',
              title: `Error AI ${uid}`,
              dataSource: {
                type: 'namedQuery',
                executionMode: 'live',
                queryCode: `missing_helper_query_${uid}`,
                page: 1,
                size: 3,
              },
            },
          ],
        },
      ],
      extension: { e2e: true, scenario: 'unified-designer-workbench-v3-helper-states' },
    });
    const updatedAiEmptyText = `No generated suggestions ${uid}`;

    await page.goto(`/unified-designer?pageId=${helperEmptyPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-ai_empty_helper').click();
    await expect(page.getByTestId('inspector-field-props.emptyText')).toHaveValue(
      `No AI suggestions ${uid}`,
    );
    await page.getByTestId('inspector-field-props.emptyText').fill(updatedAiEmptyText);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    const emptyRespPromise = page.waitForResponse(
      (response) =>
        response.url().includes(`/api/meta/named-queries/${liveHelperNamedQueryCode}/execute`) &&
        response.request().method() === 'POST' &&
        (response.request().postDataJSON() as { page?: number }).page === 999999,
      { timeout: 15000 },
    );
    const errorRespPromise = page.waitForResponse(
      (response) =>
        response.url().includes(`/api/meta/named-queries/missing_helper_query_${uid}/execute`) &&
        response.request().method() === 'POST',
      { timeout: 15000 },
    );
    await page.getByTestId('designer-mode-preview').click();
    const emptyResp = await emptyRespPromise;
    expect(emptyResp.status()).toBe(200);
    const emptyBody = await emptyResp.json();
    expect(emptyBody.code).toBe('0');
    expect(emptyBody.data?.records ?? []).toEqual([]);
    await errorRespPromise;

    await expect(page.getByTestId('runtime-ai-fill-empty-ai_empty_helper')).toContainText(
      updatedAiEmptyText,
    );
    await expect(page.getByTestId('runtime-bpm-empty-bpm_empty_helper')).toContainText(
      `No workflow tasks ${uid}`,
    );
    await expect(page.getByTestId('runtime-activity-empty-timeline_empty_helper')).toContainText(
      `No activity ${uid}`,
    );
    await expect(
      page.getByTestId('runtime-field-history-empty-history_empty_helper'),
    ).toContainText(`No field changes ${uid}`);
    const errorState = page.getByTestId('runtime-helper-error-ai_error_helper');
    await expect(errorState).toBeVisible();
    await expect(errorState).not.toHaveText('');

    await page.getByTestId('designer-mode-edit').click();
    await page.getByTestId('outline-item-ai_empty_helper').click();
    await expect(page.getByTestId('inspector-field-props.emptyText')).toHaveValue(
      updatedAiEmptyText,
    );
  });

  test('UDW-052: configures helper permission code and gates live helper data loading', async ({
    page,
  }) => {
    expect(liveHelperNamedQueryCode).toBeTruthy();
    const allowedPermissionCode = await pickCurrentPermissionCode(page);
    const missingPermissionCode = `meta.unified-designer.helper-missing.${uid}`;
    const blockedQueryCode = `missing_helper_permission_blocked_${uid}`;
    const helperPermissionPid = await createPageResource(page, {
      name: `UDW V3 Helper Permission ${uid}`,
      pageKey: `udw_v3_helper_permission_${uid}`,
      title: `UDW V3 Helper Permission ${uid}`,
      kind: 'detail',
      modelCode,
      schemaVersion: 3,
      blocks: [
        {
          id: 'helper_permission_root',
          blockType: 'detail',
          title: 'Helper permission detail',
          dataSource: { model: modelCode },
          blocks: [
            {
              id: 'ai_permission_denied_helper',
              blockType: 'ai-fill-banner',
              title: `Permission denied AI ${uid}`,
              dataSource: {
                type: 'namedQuery',
                executionMode: 'live',
                queryCode: blockedQueryCode,
                page: 1,
                size: 3,
              },
              props: {
                emptyText: `Permission denied helper stays private ${uid}`,
              },
            },
            {
              id: 'ai_permission_allowed_helper',
              blockType: 'ai-fill-banner',
              title: `Permission allowed AI ${uid}`,
              dataSource: {
                type: 'namedQuery',
                executionMode: 'live',
                queryCode: liveHelperNamedQueryCode,
                page: 1,
                size: 3,
              },
              props: {
                permissionCode: allowedPermissionCode,
                emptyText: `Allowed helper fallback ${uid}`,
              },
            },
          ],
        },
      ],
      extension: { e2e: true, scenario: 'unified-designer-workbench-v3-helper-permission' },
    });

    await page.goto(`/unified-designer?pageId=${helperPermissionPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await page.getByTestId('outline-item-ai_permission_denied_helper').click();
    await expect(page.getByTestId('inspector-field-props.permissionCode')).toBeVisible();
    await page.getByTestId('inspector-field-props.permissionCode-manual').fill(missingPermissionCode);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await page.getByTestId('outline-item-ai_permission_allowed_helper').click();
    await expect(page.getByTestId('inspector-field-props.permissionCode')).toHaveValue(
      allowedPermissionCode,
    );

    const blockedRequests: string[] = [];
    const blockedRequestListener = (request: Request) => {
      if (request.url().includes(`/api/meta/named-queries/${blockedQueryCode}/execute`)) {
        blockedRequests.push(request.url());
      }
    };
    page.on('request', blockedRequestListener);
    const allowedRespPromise = page.waitForResponse(
      (response) =>
        response.url().includes(`/api/meta/named-queries/${liveHelperNamedQueryCode}/execute`) &&
        response.request().method() === 'POST',
      { timeout: 15000 },
    );
    await page.getByTestId('designer-mode-preview').click();
    const allowedResp = await allowedRespPromise;
    expect(allowedResp.status()).toBe(200);
    page.off('request', blockedRequestListener);

    const deniedPermission = page.getByTestId(
      'runtime-helper-permission-ai_permission_denied_helper',
    );
    await expect(deniedPermission).toContainText(`Requires permission: ${missingPermissionCode}`);
    await expect(deniedPermission).toHaveAttribute('data-permission-code', missingPermissionCode);
    await expect(deniedPermission).toHaveAttribute('data-permission-allowed', 'false');
    await expect(page.getByTestId('runtime-helper-source-ai_permission_denied_helper')).toHaveCount(
      0,
    );
    await expect(page.getByTestId('runtime-helper-error-ai_permission_denied_helper')).toHaveCount(
      0,
    );
    await expect(
      page.getByTestId('runtime-ai-fill-field-ai_permission_denied_helper-0'),
    ).toHaveCount(0);
    await expect(
      page.getByTestId('runtime-ai-fill-empty-ai_permission_denied_helper'),
    ).toContainText(`Permission denied helper stays private ${uid}`);

    const allowedPermission = page.getByTestId(
      'runtime-helper-permission-ai_permission_allowed_helper',
    );
    await expect(allowedPermission).toContainText(`Permission: ${allowedPermissionCode}`);
    await expect(allowedPermission).toHaveAttribute('data-permission-code', allowedPermissionCode);
    await expect(allowedPermission).toHaveAttribute('data-permission-allowed', 'true');
    await expect(page.getByTestId('runtime-helper-source-ai_permission_allowed_helper')).toHaveText(
      'named-query',
    );
    await expect(
      page.getByTestId('runtime-ai-fill-field-ai_permission_allowed_helper-0'),
    ).toBeVisible();
    expect(blockedRequests).toEqual([]);
  });
});

async function saveDesignerPage(page: Page, pid: string): Promise<void> {
  // A save click issued right after an inspector input edit can be lost to the
  // blur/re-render that the click itself triggers, so the PUT never fires. Retry
  // the click until the save round-trip actually happens.
  await expect(async () => {
    const saveRespPromise = page.waitForResponse(
      (response) =>
        response.url().includes(`/api/pages/${pid}`) && response.request().method() === 'PUT',
      { timeout: 5000 },
    );
    await page.getByTestId('designer-save').click();
    const saveResp = await saveRespPromise;
    expect(saveResp.status()).toBe(200);
    const saveBody = await saveResp.json();
    expect(saveBody.code).toBe('0');
  }).toPass({ timeout: 30000 });
  await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');
}

async function resizeWidgetByMouse(
  page: Page,
  blockId: string,
  deltaX: number,
  deltaY: number,
): Promise<void> {
  const handle = page.getByTestId(`widget-resize-${blockId}`);
  await expect(handle).toBeVisible();
  const box = await handle.boundingBox();
  expect(box).not.toBeNull();
  const startX = box!.x + box!.width / 2;
  const startY = box!.y + box!.height / 2;
  await page.mouse.move(startX, startY);
  await page.mouse.down();
  await page.mouse.move(startX + deltaX, startY + deltaY);
  await page.mouse.up();
}

async function dragWidgetByMouse(
  page: Page,
  blockId: string,
  deltaX: number,
  deltaY: number,
  steps = 8,
): Promise<void> {
  const widget = page.getByTestId(`canvas-block-${blockId}`);
  await expect(widget).toBeVisible();
  const box = await widget.boundingBox();
  expect(box).not.toBeNull();
  const startX = box!.x + box!.width / 2;
  const startY = box!.y + Math.min(42, box!.height / 2);
  await page.mouse.move(startX, startY);
  await page.mouse.down();
  await page.mouse.move(startX + deltaX, startY + deltaY, { steps });
  await page.mouse.up();
}

async function dragCanvasBlockBefore(
  page: Page,
  movingBlockId: string,
  targetBlockId: string,
): Promise<void> {
  // @dnd-kit attaches the drag activators to the block's grip handle, not the
  // block body, so the gesture must start on the handle. Drop onto the target
  // block; the workbench resolves a canvas-block drop onto another block as a
  // move-before. Use a multi-step pointer move so @dnd-kit measures + collides.
  const handle = page.getByTestId(`block-drag-handle-${movingBlockId}`);
  const targetBlock = page.getByTestId(`canvas-block-${targetBlockId}`);
  await expect(handle).toBeVisible();
  await expect(targetBlock).toBeVisible();
  await targetBlock.scrollIntoViewIfNeeded();
  await handle.scrollIntoViewIfNeeded();

  const handleBox = await handle.boundingBox();
  const targetBox = await targetBlock.boundingBox();
  expect(handleBox).not.toBeNull();
  expect(targetBox).not.toBeNull();

  const startX = handleBox!.x + handleBox!.width / 2;
  const startY = handleBox!.y + handleBox!.height / 2;
  const endX = targetBox!.x + targetBox!.width / 2;
  const endY = targetBox!.y + Math.min(12, targetBox!.height / 4);

  await page.mouse.move(startX, startY);
  await page.mouse.down();
  await page.mouse.move(startX + 8, startY + 8, { steps: 6 });
  await page.mouse.move(endX, endY, { steps: 16 });
  await page.mouse.move(endX + 2, endY + 2, { steps: 4 });
  await page.mouse.up();
  await page
    .locator('[data-testid="drag-overlay-ghost"]')
    .waitFor({ state: 'detached', timeout: 5000 })
    .catch(() => {});
  await page.evaluate(
    () => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))),
  );
}

async function swapCanvasBlocksByPointerDrag(
  page: Page,
  firstBlockId: string,
  secondBlockId: string,
): Promise<[string, string]> {
  const firstWasBeforeSecond = await isCanvasBlockBefore(page, firstBlockId, secondBlockId);
  const movingBlockId = firstWasBeforeSecond ? secondBlockId : firstBlockId;
  const targetBlockId = firstWasBeforeSecond ? firstBlockId : secondBlockId;

  await expectBlockBefore(page, targetBlockId, movingBlockId);
  await dragCanvasBlockBefore(page, movingBlockId, targetBlockId);
  await expectBlockBefore(page, movingBlockId, targetBlockId);

  return [movingBlockId, targetBlockId];
}

async function pickModelField(
  page: Page,
  modelCode: string,
  excludedCodes: string[],
): Promise<{ code: string }> {
  const modelResp = await page.request.get(`/api/meta/models/code/${modelCode}`);
  expect(modelResp.ok(), await modelResp.text()).toBe(true);
  const modelBody = await modelResp.json();
  const modelPid = String(modelBody.data?.pid ?? '');
  expect(modelPid).toBeTruthy();

  const fieldsResp = await page.request.get(`/api/meta/models/${modelPid}/fields`);
  expect(fieldsResp.ok(), await fieldsResp.text()).toBe(true);
  const fieldsBody = await fieldsResp.json();
  const field = (fieldsBody.data ?? []).find(
    (item: { code?: string }) => item.code && !excludedCodes.includes(item.code),
  );
  expect(field?.code).toBeTruthy();
  return { code: String(field.code) };
}

async function readPage(page: Page, pid: string): Promise<PageSchemaDto> {
  const resp = await page.request.get(`/api/pages/${pid}`);
  expect(resp.ok(), await resp.text()).toBe(true);
  const body = await resp.json();
  expect(body.code).toBe('0');
  return body.data as PageSchemaDto;
}

async function createPageResource(page: Page, data: Record<string, unknown>): Promise<string> {
  const resp = await page.request.post('/api/pages', { data });
  expect(resp.ok(), await resp.text()).toBe(true);
  const body = await resp.json();
  expect(body.code).toBe('0');
  const pid = String(body.data?.pid ?? '');
  expect(pid).toBeTruthy();
  return pid;
}

async function ensureNamedQuery(
  page: Page,
  request: {
    code: string;
    title: string;
    description: string;
    fromSql: string;
  },
): Promise<void> {
  const createResp = await page.request.post('/api/meta/named-queries', { data: request });
  if (createResp.ok()) {
    const createBody = await createResp.json();
    expect(createBody.code).toBe('0');
    return;
  }

  const createText = await createResp.text();
  const lookupResp = await page.request.get(
    `/api/meta/named-queries/by-code/${encodeURIComponent(request.code)}`,
  );
  if (lookupResp.ok()) {
    const lookupBody = await lookupResp.json();
    if (lookupBody.code === '0' && lookupBody.data?.code === request.code) return;
  }

  throw new Error(`Failed to create or reuse named query ${request.code}: ${createText}`);
}

async function ensureNamedQueryField(
  page: Page,
  queryCode: string,
  field: {
    fieldCode: string;
    columnExpr: string;
    dataType: string;
    displayName: string;
    sortable: boolean;
    searchable: boolean;
    sortOrder: number;
  },
): Promise<void> {
  const encodedQueryCode = encodeURIComponent(queryCode);
  const encodedFieldCode = encodeURIComponent(field.fieldCode);
  const addResp = await page.request.post(`/api/meta/named-queries/${encodedQueryCode}/fields`, {
    data: field,
  });
  if (addResp.ok()) {
    const addBody = await addResp.json();
    expect(addBody.code).toBe('0');
    return;
  }

  const addText = await addResp.text();

  const fieldsResp = await page.request.get(`/api/meta/named-queries/${encodedQueryCode}/fields`);
  if (fieldsResp.ok()) {
    const fieldsBody = await fieldsResp.json();
    const fields = Array.isArray(fieldsBody.data) ? fieldsBody.data : [];
    if (fieldsBody.code === '0') {
      const existing = fields.find(
        (item: { fieldCode?: unknown }) => item.fieldCode === field.fieldCode,
      ) as
        | {
            fieldCode?: unknown;
            columnExpr?: unknown;
            dataType?: unknown;
            displayName?: unknown;
            sortable?: unknown;
            searchable?: unknown;
            sortOrder?: unknown;
          }
        | undefined;
      if (existing) {
        expect(existing).toMatchObject(field);
        return;
      }
    }
  }

  throw new Error(
    `Failed to create or reuse matching named query field ${queryCode}.${field.fieldCode}: ${addText}`,
  );
}

async function pickCurrentPermissionCode(page: Page): Promise<string> {
  const resp = await page.request.get('/api/auth/me');
  expect(resp.ok(), await resp.text()).toBe(true);
  const body = await resp.json();
  expect(body.code).toBe('0');
  const permissions = body.data?.permissions ?? {};
  const objectCodes = Array.isArray(permissions.permissions)
    ? permissions.permissions
        .map((permission: { code?: unknown }) => permission.code)
        .filter((code: unknown): code is string => typeof code === 'string' && code.length > 0)
    : [];
  const codes = [
    ...(Array.isArray(permissions.permissionCodes) ? permissions.permissionCodes : []),
    ...objectCodes,
  ].filter((code: unknown): code is string => typeof code === 'string' && code.length > 0);
  expect(codes.length).toBeGreaterThan(0);
  return codes[0];
}

async function expectCurrentUserPermission(
  page: Page,
  permissionCode: string,
  expected: boolean,
): Promise<void> {
  const resp = await page.request.get('/api/auth/me');
  expect(resp.ok(), await resp.text()).toBe(true);
  const body = await resp.json();
  expect(body.code).toBe('0');
  const permissions = body.data?.permissions ?? {};
  const codes = collectPermissionCodes(permissions);
  expect(codes.includes(permissionCode)).toBe(expected);
}

async function expectRoleActionPermissionState({
  page,
  document,
  permissionCode,
  allowed,
}: {
  page: Page;
  document: Record<string, unknown>;
  permissionCode: string;
  allowed: boolean;
}): Promise<void> {
  await page.addInitScript(
    ({ key, value }) => {
      window.localStorage.setItem(key, JSON.stringify(value));
    },
    { key: LOCAL_DESIGNER_STORAGE_KEY, value: document },
  );
  await page.goto('/unified-designer', { waitUntil: 'domcontentloaded' });
  await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
  await page.getByTestId('designer-mode-preview').click();
  await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();

  const action = page.getByTestId('runtime-action-action_seed_export');
  await expect(action).toHaveAttribute('data-permission-code', permissionCode);
  await expect(action).toHaveAttribute('data-permission-allowed', String(allowed));
  await expect(page.getByTestId('runtime-action-permission-action_seed_export')).toContainText(
    `${allowed ? 'Permission' : 'Requires permission'}: ${permissionCode}`,
  );

  if (!allowed) {
    await expect(action).toBeDisabled();
    await expect(page.getByTestId('runtime-action-error-action_seed_export')).toHaveCount(0);
    return;
  }

  await expect(action).toBeEnabled();
  const commandRespPromise = page.waitForResponse(
    (response) =>
      response.url().includes('/api/meta/commands/execute/') &&
      response.request().method() === 'POST',
    { timeout: 15000 },
  );
  await action.click();
  const commandResp = await commandRespPromise;
  expect([200, 400, 403, 404, 422, 500]).toContain(commandResp.status());
  const commandRequestBody = commandResp.request().postDataJSON() as {
    auditContext?: Record<string, unknown>;
  };
  expect(commandRequestBody.auditContext).toMatchObject({
    source: 'unified-designer-runtime-preview',
    pageKind: 'list',
    schemaVersion: 3,
    blockId: 'action_seed_export',
    blockType: 'action',
    actionType: 'command',
    permissionCode,
  });
}

function toLocalDesignerDocument(
  dto: PageSchemaDto,
  fallbackKind: string,
): Record<string, unknown> {
  return {
    schemaVersion: 3,
    kind: dto.kind || fallbackKind,
    id: dto.pageKey || dto.pid,
    pageKey: dto.pageKey,
    modelCode: dto.modelCode,
    title: dto.title,
    layout: dto.layout,
    blocks: dto.blocks ?? [],
    extension: dto.extension,
  };
}

async function createAuthenticatedRoleContext(
  browser: Browser,
  baseURL: string,
  role: 'operator' | 'viewer',
): Promise<BrowserContext> {
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] } });
  const page = await context.newPage();
  const email = `e2e-${role}@test.com`;
  const loginResp = await page.request.post(`${baseURL}/api/auth/login`, {
    data: { email, password: DEFAULT_TEST_ACCOUNT.password },
  });
  expect(loginResp.ok(), await loginResp.text()).toBe(true);
  const loginBody = await loginResp.json();
  expect(loginBody.code).toBe('0');
  const jwt = String(loginBody.data?.jwt ?? '');
  expect(jwt).toBeTruthy();
  await addSessionCookie(context, baseURL, jwt);
  if (!loginBody.data?.tenantId) {
    await selectBusinessSpace(page, context, baseURL);
  }
  await page.close();
  return context;
}

async function selectBusinessSpace(
  page: Page,
  context: BrowserContext,
  baseURL: string,
): Promise<void> {
  const spacesResp = await page.request.get(`${baseURL}/api/tenant-selection/my-spaces`);
  expect(spacesResp.ok(), await spacesResp.text()).toBe(true);
  const spacesBody = await spacesResp.json();
  const spaces = Array.isArray(spacesBody.data) ? spacesBody.data : [];
  const businessSpace =
    spaces.find(
      (space: { spaceType?: string; tenantName?: string }) =>
        space.spaceType === 'business' && space.tenantName === 'AuraBoot Demo',
    ) ?? spaces.find((space: { spaceType?: string }) => space.spaceType === 'business');
  expect(businessSpace?.tenantId).toBeTruthy();

  const selectResp = await page.request.post(`${baseURL}/api/tenant-selection/process`, {
    data: { action: 'select', tenantId: businessSpace.tenantId },
  });
  expect(selectResp.ok(), await selectResp.text()).toBe(true);
  const selectBody = await selectResp.json();
  expect(selectBody.code).toBe('0');
  const jwt = String(selectBody.data?.jwt ?? '');
  expect(jwt).toBeTruthy();
  await addSessionCookie(context, baseURL, jwt);
}

async function addSessionCookie(
  context: BrowserContext,
  baseURL: string,
  jwt: string,
): Promise<void> {
  const session = await authSessionStorage.getSession();
  session.set(JWT_TOKEN_KEY, jwt);
  const setCookie = await authSessionStorage.commitSession(session, {
    maxAge: 60 * 60 * 24 * 7,
  });
  const cookieValue = setCookie.match(/__session=([^;]+)/)?.[1] ?? '';
  expect(cookieValue).toBeTruthy();
  await context.addCookies([
    {
      name: '__session',
      value: cookieValue,
      url: baseURL,
      httpOnly: true,
      sameSite: 'Lax',
      expires: Math.floor(Date.now() / 1000) + 60 * 60 * 24 * 7,
    },
  ]);
}

function collectPermissionCodes(permissions: Record<string, unknown>): string[] {
  const objectCodes = Array.isArray(permissions.permissions)
    ? permissions.permissions
        .map((permission: { code?: unknown }) => permission.code)
        .filter((code: unknown): code is string => typeof code === 'string' && code.length > 0)
    : [];
  return [
    ...(Array.isArray(permissions.permissionCodes) ? permissions.permissionCodes : []),
    ...objectCodes,
  ].filter((code: unknown): code is string => typeof code === 'string' && code.length > 0);
}

function findBlockById(blocks: DslBlock[], id: string): DslBlock | null {
  for (const block of blocks) {
    if (block.id === id) return block;
    const child = block.blocks ? findBlockById(block.blocks, id) : null;
    if (child) return child;
  }
  return null;
}

async function expectBlockBefore(
  page: Page,
  firstBlockId: string,
  secondBlockId: string,
): Promise<void> {
  await expect(page.getByTestId(`canvas-block-${firstBlockId}`)).toBeVisible();
  await expect(page.getByTestId(`canvas-block-${secondBlockId}`)).toBeVisible();
  await expect
    .poll(() => isCanvasBlockBefore(page, firstBlockId, secondBlockId), { timeout: 5000 })
    .toBe(true);
}

async function moveBlockUpUntilBefore(
  page: Page,
  movingBlockId: string,
  targetBlockId: string,
): Promise<void> {
  for (let attempt = 0; attempt < 6; attempt += 1) {
    if (await isCanvasBlockBefore(page, movingBlockId, targetBlockId)) return;
    const previousOrder = await getCanvasBlockOrder(page);
    const moveUpButton = page.getByTestId(`block-move-up-${movingBlockId}`);
    await expect(moveUpButton).toBeEnabled();
    await moveUpButton.click();
    await expect
      .poll(
        async () => {
          const nextOrder = await getCanvasBlockOrder(page);
          return (
            isOrderBefore(nextOrder, movingBlockId, targetBlockId) ||
            nextOrder.join('|') !== previousOrder.join('|')
          );
        },
        { timeout: 5000 },
      )
      .toBe(true);
  }
  await expectBlockBefore(page, movingBlockId, targetBlockId);
}

async function isCanvasBlockBefore(
  page: Page,
  firstBlockId: string,
  secondBlockId: string,
): Promise<boolean> {
  const order = await getCanvasBlockOrder(page);
  return isOrderBefore(order, firstBlockId, secondBlockId);
}

async function getCanvasBlockOrder(page: Page): Promise<string[]> {
  return page.evaluate(() => {
    const host = document.querySelector('[data-testid="unified-canvas-host"]');
    return Array.from(host?.querySelectorAll('[data-testid^="canvas-block-"]') ?? []).flatMap(
      (node) => {
        const testId = node.getAttribute('data-testid');
        return testId ? [testId.replace(/^canvas-block-/, '')] : [];
      },
    );
  });
}

function isOrderBefore(order: string[], firstBlockId: string, secondBlockId: string): boolean {
  const firstIndex = order.indexOf(firstBlockId);
  const secondIndex = order.indexOf(secondBlockId);
  return firstIndex > -1 && secondIndex > -1 && firstIndex < secondIndex;
}

function expectChildOrder(blocks: DslBlock[], parentId: string, orderedChildIds: string[]): void {
  const parent = findBlockById(blocks, parentId);
  expect(parent).toBeTruthy();
  const childIds = parent?.blocks?.map((block) => block.id ?? '') ?? [];
  for (const childId of orderedChildIds) {
    expect(childIds).toContain(childId);
  }
  const indexes = orderedChildIds.map((childId) => childIds.indexOf(childId));
  expect(indexes).toEqual([...indexes].sort((left, right) => left - right));
}

function stableBlockId(...parts: Array<string | number | undefined | null>): string {
  return parts
    .filter((part) => part !== undefined && part !== null && String(part).trim() !== '')
    .map((part) =>
      String(part)
        .trim()
        .replace(/([a-z0-9])([A-Z])/g, '$1_$2')
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, '_')
        .replace(/^_+|_+$/g, ''),
    )
    .filter(Boolean)
    .join('_');
}

function generateMinimalBpmn(processKey: string): string {
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             targetNamespace="http://auraboot.com/bpm"
             id="definitions_${processKey}">
  <process id="${processKey}" name="Unified Designer Live Workflow" isExecutable="true">
    <startEvent id="start"/>
    <userTask id="userTask1" name="Unified Designer Review"/>
    <endEvent id="end"/>
    <sequenceFlow id="flow1" sourceRef="start" targetRef="userTask1"/>
    <sequenceFlow id="flow2" sourceRef="userTask1" targetRef="end"/>
  </process>
</definitions>`;
}
