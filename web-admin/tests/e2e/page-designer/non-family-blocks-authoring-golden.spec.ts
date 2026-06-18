/**
 * Unified Designer — non-family blocks authoring golden (E2 batch).
 *
 * Adds visual authoring of the remaining ten non-family platform blocks to the
 * unified page designer. Each is backed by a platform meta-rendering renderer
 * wired into the runtime ui/schema-renderer/BlockRegistry (so the live /p/ page
 * renders the real, fully data-bound component):
 *   chart          ← ChartBlockRenderer        (SharedChartFactory, 28 types)
 *   rich-text      ← RichTextBlockRenderer      (sanitized HTML)
 *   divider        ← DividerBlockRenderer       (rule / labeled separator)
 *   toolbar        ← ToolbarBlockRenderer       (button group)
 *   form-buttons   ← FormButtonsBlockRenderer   (form footer buttons)
 *   filters        ← FiltersBlockRenderer       (filter field panel)
 *   form-wizard    ← FormWizardBlockRenderer     (multi-step form)
 *   trace-graph    ← TraceGraphBlockRenderer     (@xyflow lineage canvas)
 *   selection-info ← SelectionInfoBlockRenderer  (bound multi-select summary)
 *   gerber-viewer  ← GerberViewerBlockRenderer   (PCB board + CPL inspection)
 *
 * Architecture (same as the workbench + display families):
 *   - BlockRegistry + kindPolicy register/scope each block; InspectorSchemaRegistry
 *     keys every field at the EXACT bare path the live renderer reads (verified
 *     against renderer source — no invented fields); RecursiveBlockRenderer shows a
 *     config-driven REPRESENTATIVE preview inside the canvas (full data binding
 *     renders on the live /p/ page).
 *   - Backend DslRegistry.BlockType gains divider / rich-text / selection-info /
 *     gerber-viewer (chart / toolbar / form-buttons / filters / form-wizard /
 *     trace-graph already whitelisted) so the save + import validators accept them.
 *
 * Golden coverage:
 *   A* — detail-page authoring: chart / rich-text / divider / toolbar / trace-graph /
 *        selection-info / gerber-viewer. Edit inspector props (text / select / JSON),
 *        save (PUT), reload + readback inspector, assert designer preview, then GET
 *        /api/pages and `toMatchObject` (props persisted at the exact path).
 *   F* — form-page authoring: form-buttons / form-wizard.
 *   L* — list-page authoring: filters.
 *   SAD — invalid buttons JSON on toolbar shows a per-field error, NOT written back.
 *   LIVE — a published kind:list custom page renders the REAL platform chart /
 *        rich-text / divider through /p/c/<pageKey> (not the designer preview).
 *
 * Inspector testids (SchemaInspector.tsx): inspector-field-<path>;
 *   json apply / error: inspector-json-field-apply-<path> / inspector-json-field-error-<path>.
 * Preview testids (RecursiveBlockRenderer.tsx): runtime-<block>-*-<id>.
 *
 * @since 4.1.0
 */

import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers';

const ADMIN_STORAGE_STATE =
  process.env.PW_ADMIN_STORAGE_STATE ||
  (process.env.PW_STORAGE_DIR ? `${process.env.PW_STORAGE_DIR}/admin.json` : './tests/storage/admin.json');

const MODEL_CODE = 'ab_announcement';

interface DslBlock {
  id?: string;
  blockType?: string;
  title?: unknown;
  blocks?: DslBlock[];
  [key: string]: unknown;
}
interface PageSchemaDto {
  pid: string;
  pageKey: string;
  kind?: string;
  blocks?: DslBlock[];
}

function findBlockById(blocks: DslBlock[] | undefined, id: string): DslBlock | null {
  for (const block of blocks ?? []) {
    if (block.id === id) return block;
    const nested = findBlockById(block.blocks, id);
    if (nested) return nested;
  }
  return null;
}

async function readPage(page: Page, pid: string): Promise<PageSchemaDto> {
  const resp = await page.request.get(`/api/pages/${pid}`);
  expect(resp.ok(), `GET /api/pages/${pid} failed: ${resp.status()}`).toBeTruthy();
  const body = await resp.json();
  expect(body.code, 'read page API code').toBe('0');
  return body.data as PageSchemaDto;
}

async function openDesigner(page: Page, pid: string): Promise<void> {
  await page.goto(`/unified-designer?pageId=${pid}`, { waitUntil: 'domcontentloaded' });
  await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 45_000 });
  await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存', { timeout: 15_000 });
}

async function selectBlock(page: Page, blockId: string): Promise<void> {
  await page.getByTestId(`outline-item-${blockId}`).click();
  await expect(page.getByTestId('inspector-selected-id')).toContainText(blockId);
}

async function enterPreviewMode(page: Page): Promise<void> {
  await page.getByTestId('designer-mode-preview').click();
  await expect(page.getByTestId('unified-runtime-preview')).toBeVisible({ timeout: 10_000 });
}
async function enterEditMode(page: Page): Promise<void> {
  await page.getByTestId('designer-mode-edit').click();
  await expect(page.getByTestId('unified-canvas-host')).toBeVisible({ timeout: 10_000 });
}

async function saveDesigner(page: Page, pid: string): Promise<void> {
  const saveButton = page.getByTestId('designer-save');
  await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');
  await expect(saveButton).toBeEnabled({ timeout: 10_000 });
  await expect(async () => {
    const saveResp = page.waitForResponse(
      (r) => r.url().includes(`/api/pages/${pid}`) && r.request().method() === 'PUT',
      { timeout: 5_000 },
    );
    await saveButton.click();
    const resp = await saveResp;
    expect(resp.status()).toBe(200);
    expect((await resp.json()).code).toBe('0');
  }).toPass({ timeout: 30_000 });
  await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');
}

async function fillTextField(page: Page, path: string, value: string): Promise<void> {
  const field = page.getByTestId(`inspector-field-${path}`);
  await expect(field).toBeVisible({ timeout: 5_000 });
  await field.fill(value);
}
async function selectField(page: Page, path: string, value: string): Promise<void> {
  const field = page.getByTestId(`inspector-field-${path}`);
  await expect(field).toBeVisible({ timeout: 5_000 });
  await field.selectOption(value);
}
async function applyJsonField(page: Page, path: string, value: unknown): Promise<void> {
  const textarea = page.getByTestId(`inspector-field-${path}`);
  await expect(textarea).toBeVisible({ timeout: 5_000 });
  await textarea.fill(JSON.stringify(value, null, 2));
  await page.evaluate(
    () => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))),
  );
  await page.getByTestId(`inspector-json-field-apply-${path}`).click();
  await page.evaluate(
    () => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))),
  );
}

// ── Detail page (cockpit / display / graph blocks + toolbar + divider) ────────
const CHART = 'pd_nf_chart';
const RICH_TEXT = 'pd_nf_rich_text';
const DIVIDER = 'pd_nf_divider';
const TOOLBAR = 'pd_nf_toolbar';
const TRACE_GRAPH = 'pd_nf_trace_graph';
const SELECTION_INFO = 'pd_nf_selection_info';
const GERBER_VIEWER = 'pd_nf_gerber_viewer';
// Form page
const FORM_BUTTONS = 'pd_nf_form_buttons';
const FORM_WIZARD = 'pd_nf_form_wizard';
// List page
const FILTERS = 'pd_nf_filters';

test.describe.serial('Unified Designer non-family-blocks authoring golden', () => {
  test.describe.configure({ timeout: 180_000 });

  const uid = uniqueId('pdnf');
  let detailPid = '';
  let formPid = '';
  let listPid = '';

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: ADMIN_STORAGE_STATE });
    const page = await ctx.newPage();

    async function seed(kind: string, suffix: string, children: DslBlock[]): Promise<string> {
      const resp = await page.request.post('/api/pages', {
        data: {
          name: `Non-family ${suffix} ${uid}`,
          pageKey: `pd_nf_${suffix}_${uid}`.replace(/-/g, '_'),
          title: `Non-family ${suffix} ${uid}`,
          kind,
          modelCode: MODEL_CODE,
          schemaVersion: 3,
          blocks: [
            {
              id: `${suffix}_root`,
              blockType: kind,
              title: `${suffix} root`,
              layout: { span: 12 },
              blocks: children,
            },
          ],
          extension: { e2e: true, scenario: 'non-family-blocks-authoring-golden' },
        },
      });
      expect(resp.ok(), `seed ${suffix} failed: ${resp.status()} ${await resp.text()}`).toBeTruthy();
      const body = await resp.json();
      expect(body.code, `seed ${suffix} API code`).toBe('0');
      const pid = String(body.data?.pid ?? '');
      expect(pid, `seeded ${suffix} pid`).toBeTruthy();
      return pid;
    }

    detailPid = await seed('detail', 'detail', [
      { id: CHART, blockType: 'chart', title: 'Revenue', layout: { span: 12 } },
      { id: RICH_TEXT, blockType: 'rich-text', title: 'Notes', layout: { span: 12 } },
      { id: DIVIDER, blockType: 'divider', title: 'Section', layout: { span: 12 } },
      { id: TOOLBAR, blockType: 'toolbar', title: 'Actions', layout: { span: 12 } },
      { id: TRACE_GRAPH, blockType: 'trace-graph', title: 'Lineage', layout: { span: 12 } },
      { id: SELECTION_INFO, blockType: 'selection-info', title: 'Selection', layout: { span: 12 } },
      { id: GERBER_VIEWER, blockType: 'gerber-viewer', title: 'Board', layout: { span: 12 } },
    ]);
    formPid = await seed('form', 'form', [
      { id: FORM_BUTTONS, blockType: 'form-buttons', title: 'Footer', layout: { span: 12 } },
      { id: FORM_WIZARD, blockType: 'form-wizard', title: 'Wizard', layout: { span: 12 } },
    ]);
    listPid = await seed('list', 'list', [
      { id: FILTERS, blockType: 'filters', title: 'Filters', layout: { span: 12 } },
    ]);

    await ctx.close();
  });

  test('A1: chart — chartType select + dataSource + chartConfig JSON persist and preview shows the type', async ({
    page,
  }, testInfo) => {
    const dataSource = `ds_rev_${uid}`;
    const chartConfig = { xField: 'month', yField: 'amount' };
    await openDesigner(page, detailPid);
    await selectBlock(page, CHART);
    await selectField(page, 'chartType', 'line');
    await fillTextField(page, 'dataSource', dataSource);
    await applyJsonField(page, 'chartConfig', chartConfig);
    await saveDesigner(page, detailPid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 30_000 });
    await selectBlock(page, CHART);
    await expect(page.getByTestId('inspector-field-chartType')).toHaveValue('line');
    await expect(page.getByTestId('inspector-field-dataSource')).toHaveValue(dataSource);

    await enterPreviewMode(page);
    await expect(page.getByTestId(`runtime-chart-type-${CHART}`)).toContainText('line');
    await expect(page.getByTestId(`runtime-chart-binding-${CHART}`)).toContainText(dataSource);
    await testInfo.attach('a1-chart-preview', { body: await page.screenshot(), contentType: 'image/png' });
    await enterEditMode(page);

    expect(findBlockById((await readPage(page, detailPid)).blocks, CHART)).toMatchObject({
      blockType: 'chart',
      chartType: 'line',
      dataSource,
      chartConfig,
    });
  });

  test('A2: rich-text — content persists at the bare block.content path and preview shows the text', async ({
    page,
  }, testInfo) => {
    const content = '<p>Read before submitting</p>';
    await openDesigner(page, detailPid);
    await selectBlock(page, RICH_TEXT);
    await fillTextField(page, 'content', content);
    await saveDesigner(page, detailPid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 30_000 });
    await selectBlock(page, RICH_TEXT);
    await expect(page.getByTestId('inspector-field-content')).toHaveValue(content);

    await enterPreviewMode(page);
    await expect(page.getByTestId(`runtime-rich-text-content-${RICH_TEXT}`)).toContainText('Read before submitting');
    await testInfo.attach('a2-rich-text-preview', { body: await page.screenshot(), contentType: 'image/png' });
    await enterEditMode(page);

    expect(findBlockById((await readPage(page, detailPid)).blocks, RICH_TEXT)).toMatchObject({
      blockType: 'rich-text',
      content,
    });
  });

  test('A3: divider — title label persists and preview shows the labeled separator', async ({ page }, testInfo) => {
    const title = 'Shipping details';
    await openDesigner(page, detailPid);
    await selectBlock(page, DIVIDER);
    await fillTextField(page, 'title', title);
    await saveDesigner(page, detailPid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 30_000 });
    await selectBlock(page, DIVIDER);
    await expect(page.getByTestId('inspector-field-title')).toHaveValue(title);

    await enterPreviewMode(page);
    await expect(page.getByTestId(`runtime-divider-label-${DIVIDER}`)).toContainText(title);
    await testInfo.attach('a3-divider-preview', { body: await page.screenshot(), contentType: 'image/png' });
    await enterEditMode(page);

    expect(findBlockById((await readPage(page, detailPid)).blocks, DIVIDER)).toMatchObject({
      blockType: 'divider',
      title,
    });
  });

  test('A4: toolbar — buttons JSON persists at the block top level and preview shows the buttons', async ({
    page,
  }, testInfo) => {
    const buttons = [
      { code: 'export', label: 'Export', variant: 'primary' },
      { code: 'archive', label: 'Archive' },
    ];
    await openDesigner(page, detailPid);
    await selectBlock(page, TOOLBAR);
    await applyJsonField(page, 'buttons', buttons);
    await saveDesigner(page, detailPid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 30_000 });
    await selectBlock(page, TOOLBAR);
    await expect(page.getByTestId('inspector-field-buttons')).toContainText('export');

    await enterPreviewMode(page);
    await expect(page.getByTestId('runtime-toolbar-button-export')).toContainText('Export');
    await expect(page.getByTestId('runtime-toolbar-button-archive')).toContainText('Archive');
    await testInfo.attach('a4-toolbar-preview', { body: await page.screenshot(), contentType: 'image/png' });
    await enterEditMode(page);

    expect(findBlockById((await readPage(page, detailPid)).blocks, TOOLBAR)).toMatchObject({
      blockType: 'toolbar',
      buttons,
    });
  });

  test('A5: trace-graph — dataSource + mode persist and preview shows the binding', async ({ page }, testInfo) => {
    const dataSource = 'pe_consumption_trace_by_lot';
    await openDesigner(page, detailPid);
    await selectBlock(page, TRACE_GRAPH);
    await fillTextField(page, 'dataSource', dataSource);
    await selectField(page, 'mode', 'consumption');
    await saveDesigner(page, detailPid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 30_000 });
    await selectBlock(page, TRACE_GRAPH);
    await expect(page.getByTestId('inspector-field-dataSource')).toHaveValue(dataSource);
    await expect(page.getByTestId('inspector-field-mode')).toHaveValue('consumption');

    await enterPreviewMode(page);
    await expect(page.getByTestId(`runtime-trace-graph-mode-${TRACE_GRAPH}`)).toContainText('consumption');
    await expect(page.getByTestId(`runtime-trace-graph-binding-${TRACE_GRAPH}`)).toContainText(dataSource);
    await testInfo.attach('a5-trace-graph-preview', { body: await page.screenshot(), contentType: 'image/png' });
    await enterEditMode(page);

    expect(findBlockById((await readPage(page, detailPid)).blocks, TRACE_GRAPH)).toMatchObject({
      blockType: 'trace-graph',
      dataSource,
      mode: 'consumption',
    });
  });

  test('A6: selection-info — title + bind persist and preview shows the bound state key', async ({ page }, testInfo) => {
    const title = 'Picked rows';
    const bind = 'selectedOrders';
    await openDesigner(page, detailPid);
    await selectBlock(page, SELECTION_INFO);
    await fillTextField(page, 'title', title);
    await fillTextField(page, 'bind', bind);
    await saveDesigner(page, detailPid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 30_000 });
    await selectBlock(page, SELECTION_INFO);
    await expect(page.getByTestId('inspector-field-bind')).toHaveValue(bind);

    await enterPreviewMode(page);
    await expect(page.getByTestId(`runtime-selection-info-bind-${SELECTION_INFO}`)).toContainText(bind);
    await testInfo.attach('a6-selection-info-preview', { body: await page.screenshot(), contentType: 'image/png' });
    await enterEditMode(page);

    expect(findBlockById((await readPage(page, detailPid)).blocks, SELECTION_INFO)).toMatchObject({
      blockType: 'selection-info',
      title,
      bind,
    });
  });

  test('A7: gerber-viewer — dataSource + line field persist and preview shows the board binding', async ({
    page,
  }, testInfo) => {
    const dataSource = 'ds_pcb_inspection';
    const lineInspectionField = 'line_result';
    await openDesigner(page, detailPid);
    await selectBlock(page, GERBER_VIEWER);
    await fillTextField(page, 'dataSource', dataSource);
    await fillTextField(page, 'lineInspectionField', lineInspectionField);
    await saveDesigner(page, detailPid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 30_000 });
    await selectBlock(page, GERBER_VIEWER);
    await expect(page.getByTestId('inspector-field-dataSource')).toHaveValue(dataSource);

    await enterPreviewMode(page);
    await expect(page.getByTestId(`runtime-gerber-viewer-binding-${GERBER_VIEWER}`)).toContainText(dataSource);
    await testInfo.attach('a7-gerber-viewer-preview', { body: await page.screenshot(), contentType: 'image/png' });
    await enterEditMode(page);

    expect(findBlockById((await readPage(page, detailPid)).blocks, GERBER_VIEWER)).toMatchObject({
      blockType: 'gerber-viewer',
      dataSource,
      lineInspectionField,
    });
  });

  test('F1: form-buttons — buttons JSON persists and preview shows the buttons', async ({ page }, testInfo) => {
    const buttons = [
      { code: 'submit', content: 'Submit', primary: true },
      { code: 'cancel', content: 'Cancel' },
    ];
    await openDesigner(page, formPid);
    await selectBlock(page, FORM_BUTTONS);
    await applyJsonField(page, 'buttons', buttons);
    await saveDesigner(page, formPid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 30_000 });
    await selectBlock(page, FORM_BUTTONS);
    await expect(page.getByTestId('inspector-field-buttons')).toContainText('submit');

    await enterPreviewMode(page);
    await expect(page.getByTestId('runtime-form-buttons-button-submit')).toContainText('Submit');
    await expect(page.getByTestId('runtime-form-buttons-button-cancel')).toContainText('Cancel');
    await testInfo.attach('f1-form-buttons-preview', { body: await page.screenshot(), contentType: 'image/png' });
    await enterEditMode(page);

    expect(findBlockById((await readPage(page, formPid)).blocks, FORM_BUTTONS)).toMatchObject({
      blockType: 'form-buttons',
      buttons,
    });
  });

  test('F2: form-wizard — steps JSON persists and preview shows the step rail', async ({ page }, testInfo) => {
    const steps = [
      { key: 'base', label: 'Basics', blocks: [] },
      { key: 'review', label: 'Review', blocks: [] },
    ];
    await openDesigner(page, formPid);
    await selectBlock(page, FORM_WIZARD);
    await applyJsonField(page, 'steps', steps);
    await saveDesigner(page, formPid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 30_000 });
    await selectBlock(page, FORM_WIZARD);
    await expect(page.getByTestId('inspector-field-steps')).toContainText('review');

    await enterPreviewMode(page);
    await expect(page.getByTestId('runtime-form-wizard-step-base')).toContainText('Basics');
    await expect(page.getByTestId('runtime-form-wizard-step-review')).toContainText('Review');
    await testInfo.attach('f2-form-wizard-preview', { body: await page.screenshot(), contentType: 'image/png' });
    await enterEditMode(page);

    expect(findBlockById((await readPage(page, formPid)).blocks, FORM_WIZARD)).toMatchObject({
      blockType: 'form-wizard',
      steps,
    });
  });

  test('L1: filters — fields JSON persists and preview shows the filter-field chips', async ({ page }, testInfo) => {
    const fields = [
      { field: 'status', label: 'Status' },
      { field: 'owner', label: 'Owner' },
    ];
    await openDesigner(page, listPid);
    await selectBlock(page, FILTERS);
    await applyJsonField(page, 'fields', fields);
    await saveDesigner(page, listPid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 30_000 });
    await selectBlock(page, FILTERS);
    await expect(page.getByTestId('inspector-field-fields')).toContainText('status');

    await enterPreviewMode(page);
    await expect(page.getByTestId('runtime-filters-field-status')).toContainText('Status');
    await expect(page.getByTestId('runtime-filters-field-owner')).toContainText('Owner');
    await testInfo.attach('l1-filters-preview', { body: await page.screenshot(), contentType: 'image/png' });
    await enterEditMode(page);

    expect(findBlockById((await readPage(page, listPid)).blocks, FILTERS)).toMatchObject({
      blockType: 'filters',
      fields,
    });
  });

  test('SAD: invalid buttons JSON on toolbar shows a per-field error and is NOT written back', async ({
    page,
  }, testInfo) => {
    await openDesigner(page, detailPid);
    await selectBlock(page, TOOLBAR);
    const before = findBlockById((await readPage(page, detailPid)).blocks, TOOLBAR)?.buttons;
    expect(Array.isArray(before), 'A4 buttons present before sad path').toBeTruthy();

    const field = page.getByTestId('inspector-field-buttons');
    await expect(field).toBeVisible({ timeout: 5_000 });
    await field.fill('[ { code: export, ');
    await page.evaluate(
      () => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))),
    );
    await page.getByTestId('inspector-json-field-apply-buttons').click();
    await expect(page.getByTestId('inspector-json-field-error-buttons')).toBeVisible({ timeout: 5_000 });
    await testInfo.attach('sad-invalid-buttons-json', { body: await page.screenshot(), contentType: 'image/png' });

    const after = findBlockById((await readPage(page, detailPid)).blocks, TOOLBAR)?.buttons;
    expect(after).toEqual(before);
  });

  test('LIVE: published custom page renders the REAL platform chart / rich-text / divider', async ({
    page,
  }, testInfo) => {
    const id = uniqueId('pdnf_live');
    const pageKey = id.replace(/-/g, '_');
    const blocks = [
      {
        id: 'live_chart',
        blockType: 'chart',
        title: 'Monthly revenue',
        chartType: 'bar',
        dataSource: 'ds_rev',
        chartConfig: { xField: 'month', yField: 'amount' },
      },
      {
        id: 'live_divider',
        blockType: 'divider',
        title: 'Notes',
      },
      {
        id: 'live_rt',
        blockType: 'rich-text',
        content: '<p>Review the open orders before closing the shift.</p>',
      },
    ];
    const createResp = await page.request.post('/api/pages', {
      data: {
        name: `Non-family live ${id}`,
        pageKey,
        title: `Non-family live ${id}`,
        kind: 'list',
        modelCode: 'tenant',
        profile: 'admin',
        layout: { type: 'stack', gap: 12 },
        blocks,
        dataSources: {
          ds_rev: {
            type: 'static',
            adaptor: 'records',
            data: [
              { month: 'Jan', amount: 120 },
              { month: 'Feb', amount: 180 },
            ],
          },
        },
        schemaVersion: 4,
        metaInfo: { componentCount: blocks.length, runtimeE2E: true },
        semver: '0.1.0',
        extension: {
          customOnly: true,
          skipListData: true,
          skipFieldMeta: true,
          miscBlocksPosition: 'beforeTable',
          hideQuickFilters: true,
          hideSort: true,
          hideColumnSettings: true,
          hideRowHeight: true,
          hideFilterChips: true,
        },
      },
    });
    expect(createResp.ok(), `create live failed: ${createResp.status()} ${await createResp.text()}`).toBeTruthy();
    const createBody = await createResp.json();
    expect(createBody.code, 'create live API code').toBe('0');
    const livePid = String(createBody.data?.pid || '');
    expect(livePid, 'live pid').toBeTruthy();

    const publishResp = await page.request.post(`/api/pages/${livePid}/publish`);
    expect(publishResp.ok(), `publish failed: ${publishResp.status()}`).toBeTruthy();
    expect((await publishResp.json()).data?.status, 'published status').toBe('published');

    await page.goto(`/p/c/${pageKey}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('dynamic-list')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId('list-misc-blocks')).toBeVisible();

    // Real DividerBlockRenderer (labeled separator) + RichTextBlockRenderer (HTML).
    await expect(page.locator('[data-block-type="divider"]').first()).toBeVisible();
    await expect(page.locator('.rich-text-block').first()).toContainText(
      'Review the open orders before closing the shift.',
    );
    // Real ChartBlockRenderer mounts the SharedChartFactory bar chart (Suspense
    // boundary resolves to a chart container, not the designer placeholder).
    await expect(page.getByTestId('list-misc-blocks')).toContainText('Monthly revenue');

    await testInfo.attach('live-render', { body: await page.screenshot(), contentType: 'image/png' });
  });
});
