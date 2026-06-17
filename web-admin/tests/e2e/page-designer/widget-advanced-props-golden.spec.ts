/**
 * Unified Designer — dashboard widget advanced-property authoring golden.
 *
 * The dashboard `widget` inspector (InspectorSchemaRegistry.widgetFields) exposes
 * the advanced chart/card properties that the runtime renderer consumes
 * (RecursiveBlockRenderer.RuntimeWidget / RuntimeWidgetBody / runtimeExecution):
 *   - widgetType            (top-level field — number-card / bar-chart / line-chart / table / markdown)
 *   - props.format          (select — plain / number / currency / percent)
 *   - props.thresholds      (JSON)
 *   - props.series          (JSON — consumed by RuntimeBarChart / RuntimeLineChart)
 *   - props.columns         (JSON — table widget)
 *   - props.rows            (JSON — table widget, RuntimeWidgetTable)
 *   - props.markdown        (text — RuntimeMarkdownWidget)
 *   - props.drillDownTo     (text — runtime-widget-drilldown footer)
 *   - props.refreshInterval (number)
 *
 * These fields existed in the inspector schema and are read at runtime, but had
 * zero E2E coverage. This suite closes that gap with the same seed→save→readback
 * contract as inspector-authoring-golden.spec.ts: every inspector edit is paired
 * with a GET /api/pages readback `toMatchObject` so a save that silently drops a
 * widget prop fails here (not just a green UI). A sad-path case proves invalid
 * JSON surfaces a per-field error and is NOT written back.
 *
 * Pattern follows tests/e2e/page-designer/inspector-authoring-golden.spec.ts:
 *   seed a kind:'dashboard' page (schemaVersion 3) whose `dashboard` root holds
 *   number-card / bar-chart / line-chart / table widgets (BlockRegistry:
 *   dashboard.allowedChildren = ['widget']) -> open /unified-designer?pageId=<pid>
 *   -> select a widget via outline-item-<id> -> edit inspector-field-<path> /
 *   inspector JSON apply -> designer-save -> reload + GET readback assertions.
 *
 * Inspector data-testids verified against the live source (SchemaInspector.tsx):
 *   - top-level select widgetType:  inspector-field-widgetType
 *   - select props.format:          inspector-field-props.format
 *   - text  props.markdown/.drillDownTo: inspector-field-props.markdown / .drillDownTo
 *   - number props.refreshInterval: inspector-field-props.refreshInterval
 *   - json   props.thresholds/.series/.columns/.rows:
 *       inspector-field-props.<key> + inspector-json-field-apply-props.<key>
 *       (sad path error: inspector-json-field-error-props.<key>)
 *
 * @since 4.0.0
 */

import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers';

const ADMIN_STORAGE_STATE =
  process.env.PW_ADMIN_STORAGE_STATE ||
  (process.env.PW_STORAGE_DIR ? `${process.env.PW_STORAGE_DIR}/admin.json` : './tests/storage/admin.json');

// ab_announcement is a published platform meta-model present in every OSS stack.
// A dashboard-kind page only needs a real published modelCode for the root
// detail/dashboard contract; widget blocks here use static/preview data sources
// so the model just has to exist.
const MODEL_CODE = 'ab_announcement';

interface DslBlock {
  id?: string;
  blockType?: string;
  widgetType?: string;
  field?: string;
  title?: unknown;
  props?: Record<string, unknown>;
  layout?: Record<string, unknown>;
  dataSource?: Record<string, unknown>;
  blocks?: DslBlock[];
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
  // The route lazily compiles on the first hit and the workbench mounts only
  // after the page schema + model fields load, so allow generous first-paint time.
  await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 45_000 });
  await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存', { timeout: 15_000 });
}

async function selectBlock(page: Page, blockId: string): Promise<void> {
  await page.getByTestId(`outline-item-${blockId}`).click();
  await expect(page.getByTestId('inspector-selected-id')).toContainText(blockId);
}

/**
 * Save and wait for the real PUT to land (mirrors saveDesigner in the inspector
 * authoring golden). The save button is disabled while the document is clean /
 * saving / invalid, so wait for dirty + enabled, then retry click + PUT.
 */
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
    const body = await resp.json();
    expect(body.code).toBe('0');
  }).toPass({ timeout: 30_000 });
  await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');
}

/** Apply a basic-tab JSON inspector field (inspector-json-field-apply-<path>). */
async function applyJsonField(page: Page, path: string, value: unknown): Promise<void> {
  const textarea = page.getByTestId(`inspector-field-${path}`);
  await expect(textarea).toBeVisible({ timeout: 5_000 });
  await textarea.fill(JSON.stringify(value, null, 2));
  // Let the draft state commit before the apply handler reads it.
  await page.evaluate(
    () => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))),
  );
  await page.getByTestId(`inspector-json-field-apply-${path}`).click();
  // Let the apply commit to the document before the next interaction.
  await page.evaluate(
    () => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))),
  );
}

const NUMBER_CARD = 'pd_widget_number_card';
const BAR_CHART = 'pd_widget_bar_chart';
const LINE_CHART = 'pd_widget_line_chart';
const TABLE_WIDGET = 'pd_widget_table';

test.describe.serial('Unified Designer widget advanced props golden', () => {
  // Several real save/reopen round-trips per test; the 15s default is tight.
  test.describe.configure({ timeout: 120_000 });

  const uid = uniqueId('pdwidget');
  let pid = '';

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: ADMIN_STORAGE_STATE });
    const page = await ctx.newPage();

    const resp = await page.request.post('/api/pages', {
      data: {
        name: `Widget advanced props ${uid}`,
        pageKey: `pd_widget_${uid}`.replace(/-/g, '_'),
        title: `Widget advanced props ${uid}`,
        kind: 'dashboard',
        modelCode: MODEL_CODE,
        // The unified designer loads/saves a V3 document; its client validator
        // (validatePageSchemaV3) requires schemaVersion 3, matching the UDW
        // reference suite seed. A schemaVersion 4 seed loads but fails client
        // save validation, so the save PUT never fires.
        schemaVersion: 3,
        blocks: [
          {
            // dashboard kind: single `dashboard` root container whose only
            // allowedChildren is `widget` (BlockRegistry + kindPolicy.dashboard).
            id: 'dashboard_root',
            blockType: 'dashboard',
            title: 'Widget advanced props root',
            layout: { cols: 12, rowHeight: 60, gap: 8, span: 12 },
            blocks: [
              {
                id: NUMBER_CARD,
                blockType: 'widget',
                widgetType: 'number-card',
                title: 'KPI card',
                props: { value: '0' },
                layout: { x: 0, y: 0, w: 3, h: 2 },
              },
              {
                id: BAR_CHART,
                blockType: 'widget',
                widgetType: 'bar-chart',
                title: 'Bar chart',
                props: {},
                layout: { x: 3, y: 0, w: 4, h: 3 },
              },
              {
                id: LINE_CHART,
                blockType: 'widget',
                widgetType: 'line-chart',
                title: 'Line chart',
                props: {},
                layout: { x: 7, y: 0, w: 4, h: 3 },
              },
              {
                id: TABLE_WIDGET,
                blockType: 'widget',
                widgetType: 'table',
                title: 'Table widget',
                props: {},
                layout: { x: 0, y: 3, w: 6, h: 3 },
              },
            ],
          },
        ],
        extension: { e2e: true, scenario: 'widget-advanced-props-golden' },
      },
    });
    expect(resp.ok(), `seed page failed: ${resp.status()} ${await resp.text()}`).toBeTruthy();
    const body = await resp.json();
    expect(body.code, 'seed page API code').toBe('0');
    pid = String(body.data?.pid ?? '');
    expect(pid, 'seeded pid').toBeTruthy();

    await ctx.close();
  });

  test('W1: number-card widget — thresholds JSON + format select + drillDownTo + refreshInterval persist and reload', async ({
    page,
  }, testInfo) => {
    const thresholds = [
      { value: 80, color: 'green', label: 'On track' },
      { value: 50, color: 'amber', label: 'At risk' },
      { value: 0, color: 'red', label: 'Critical' },
    ];
    const drillDownTo = `/p/c/announcement_detail?from=${uid}`;
    const refreshInterval = 45;

    await openDesigner(page, pid);
    await selectBlock(page, NUMBER_CARD);
    await testInfo.attach('w1-number-card-selected', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });

    // props.format is a select (plain / number / currency / percent).
    await page.getByTestId('inspector-field-props.format').selectOption('currency');
    // props.thresholds is a basic-tab JSON field.
    await applyJsonField(page, 'props.thresholds', thresholds);
    // props.drillDownTo is a text field; props.refreshInterval is a number field.
    await page.getByTestId('inspector-field-props.drillDownTo').fill(drillDownTo);
    await page.getByTestId('inspector-field-props.refreshInterval').fill(String(refreshInterval));
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');
    await testInfo.attach('w1-number-card-authored', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });

    await saveDesigner(page, pid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 30_000 });
    await selectBlock(page, NUMBER_CARD);
    await expect(page.getByTestId('inspector-field-props.format')).toHaveValue('currency');
    await expect(page.getByTestId('inspector-field-props.thresholds')).toContainText('At risk');
    await expect(page.getByTestId('inspector-field-props.drillDownTo')).toHaveValue(drillDownTo);
    await expect(page.getByTestId('inspector-field-props.refreshInterval')).toHaveValue(
      String(refreshInterval),
    );
    await testInfo.attach('w1-number-card-reloaded', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });

    const persisted = await readPage(page, pid);
    const block = findBlockById(persisted.blocks, NUMBER_CARD);
    expect(block).toMatchObject({
      blockType: 'widget',
      widgetType: 'number-card',
      props: expect.objectContaining({
        format: 'currency',
        thresholds,
        drillDownTo,
        refreshInterval,
      }),
    });
  });

  test('W2: bar-chart + line-chart widgets — series JSON persists per widget', async ({
    page,
  }, testInfo) => {
    const barSeries = [
      { label: 'Q1', value: 120 },
      { label: 'Q2', value: 180 },
      { label: 'Q3', value: 150 },
    ];
    const lineSeries = [
      { label: 'Mon', value: 12 },
      { label: 'Tue', value: 19 },
      { label: 'Wed', value: 7 },
    ];

    await openDesigner(page, pid);

    await selectBlock(page, BAR_CHART);
    await applyJsonField(page, 'props.series', barSeries);

    await selectBlock(page, LINE_CHART);
    await applyJsonField(page, 'props.series', lineSeries);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');
    await testInfo.attach('w2-charts-authored', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });

    await saveDesigner(page, pid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 30_000 });
    await selectBlock(page, BAR_CHART);
    await expect(page.getByTestId('inspector-field-props.series')).toContainText('Q2');
    await selectBlock(page, LINE_CHART);
    await expect(page.getByTestId('inspector-field-props.series')).toContainText('Tue');
    await testInfo.attach('w2-charts-reloaded', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });

    const persisted = await readPage(page, pid);
    expect(findBlockById(persisted.blocks, BAR_CHART)).toMatchObject({
      blockType: 'widget',
      widgetType: 'bar-chart',
      props: expect.objectContaining({ series: barSeries }),
    });
    expect(findBlockById(persisted.blocks, LINE_CHART)).toMatchObject({
      blockType: 'widget',
      widgetType: 'line-chart',
      props: expect.objectContaining({ series: lineSeries }),
    });
  });

  test('W3: table widget — columns JSON + rows JSON persist and reload', async ({
    page,
  }, testInfo) => {
    const columns = [
      { key: 'name', label: 'Name', align: 'left' },
      { key: 'amount', label: 'Amount', align: 'right' },
    ];
    const rows = [
      { name: `Acme ${uid}`, amount: 1200 },
      { name: `Globex ${uid}`, amount: 980 },
    ];

    await openDesigner(page, pid);
    await selectBlock(page, TABLE_WIDGET);

    await applyJsonField(page, 'props.columns', columns);
    await applyJsonField(page, 'props.rows', rows);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');
    await testInfo.attach('w3-table-authored', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });

    await saveDesigner(page, pid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 30_000 });
    await selectBlock(page, TABLE_WIDGET);
    await expect(page.getByTestId('inspector-field-props.columns')).toContainText('Amount');
    await expect(page.getByTestId('inspector-field-props.rows')).toContainText('Globex');
    await testInfo.attach('w3-table-reloaded', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });

    const persisted = await readPage(page, pid);
    expect(findBlockById(persisted.blocks, TABLE_WIDGET)).toMatchObject({
      blockType: 'widget',
      widgetType: 'table',
      props: expect.objectContaining({ columns, rows }),
    });
  });

  test('W4: markdown widget — widgetType switch to markdown + props.markdown text persist', async ({
    page,
  }, testInfo) => {
    // props.markdown is declared as a single-line text inspector field
    // (InspectorSchemaRegistry: { key: 'props.markdown', type: 'text' }), so the
    // authored value must be single-line — a multiline string would be collapsed
    // to spaces by the <input type="text"> and never round-trips verbatim.
    const markdown = `# Release ${uid} — ready, shipped`;

    await openDesigner(page, pid);
    // Switch an existing widget to the markdown type via the top-level widgetType
    // select, then author the markdown text the RuntimeMarkdownWidget consumes.
    await selectBlock(page, LINE_CHART);
    await page.getByTestId('inspector-field-widgetType').selectOption('markdown');
    await page.getByTestId('inspector-field-props.markdown').fill(markdown);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');
    await testInfo.attach('w4-markdown-authored', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });

    await saveDesigner(page, pid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 30_000 });
    await selectBlock(page, LINE_CHART);
    await expect(page.getByTestId('inspector-field-widgetType')).toHaveValue('markdown');
    await expect(page.getByTestId('inspector-field-props.markdown')).toHaveValue(markdown);
    await testInfo.attach('w4-markdown-reloaded', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });

    const persisted = await readPage(page, pid);
    const block = findBlockById(persisted.blocks, LINE_CHART);
    expect(block).toMatchObject({
      blockType: 'widget',
      widgetType: 'markdown',
      props: expect.objectContaining({ markdown }),
    });
  });

  test('W5 (sad path): invalid thresholds JSON shows a per-field error and is NOT written back', async ({
    page,
  }, testInfo) => {
    await openDesigner(page, pid);
    await selectBlock(page, NUMBER_CARD);

    // Capture the currently-persisted thresholds (set in W1) so we can prove the
    // invalid apply does not overwrite them.
    const before = await readPage(page, pid);
    const beforeThresholds = findBlockById(before.blocks, NUMBER_CARD)?.props?.thresholds;
    expect(Array.isArray(beforeThresholds), 'W1 thresholds present before sad path').toBeTruthy();

    // Type invalid JSON into the thresholds field and apply → per-field error.
    const thresholdsField = page.getByTestId('inspector-field-props.thresholds');
    await expect(thresholdsField).toBeVisible({ timeout: 5_000 });
    await thresholdsField.fill('[ { value: 80, ');
    await page.evaluate(
      () => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))),
    );
    await page.getByTestId('inspector-json-field-apply-props.thresholds').click();
    await expect(page.getByTestId('inspector-json-field-error-props.thresholds')).toBeVisible({
      timeout: 5_000,
    });
    await testInfo.attach('w5-invalid-json-error', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });

    // The invalid apply must not have mutated the persisted thresholds. There is
    // no PUT to wait for (apply was rejected), so re-read and assert unchanged.
    const after = await readPage(page, pid);
    const afterThresholds = findBlockById(after.blocks, NUMBER_CARD)?.props?.thresholds;
    expect(afterThresholds).toEqual(beforeThresholds);
  });
});
