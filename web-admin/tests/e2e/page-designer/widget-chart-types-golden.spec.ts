/**
 * Unified Designer — new dashboard widget chart types golden (pie / area / progress).
 *
 * Extends the widget renderer beyond the original 5 widget types
 * (number-card / bar-chart / line-chart / table / markdown) with three new
 * chart types that have BOTH an inspector option AND a real runtime
 * mini-renderer (RecursiveBlockRenderer.RuntimeWidgetBody):
 *   - pie-chart   → RuntimePieChart   (SVG slices, data-testid runtime-widget-pie-<id>)
 *   - area-chart  → RuntimeAreaChart  (SVG filled area, runtime-widget-area-<id>)
 *   - progress    → RuntimeProgressWidget (percentage bar, runtime-widget-progress-<id>)
 *
 * Why this suite exists: an inspector option without a runtime renderer is a
 * "fake option" (gate-gap — §2.2): the author picks the type, the page saves,
 * but the runtime shows nothing. This golden proves each new type all the way
 * through:
 *   inspector widgetType select  → author series/value props
 *   → designer-save (real PUT)   → GET /api/pages readback toMatchObject
 *   → switch to runtime preview  → assert the real chart DOM renders (non-empty:
 *                                  pie slices / area fill path / progress bar).
 *
 * Pattern mirrors widget-advanced-props-golden.spec.ts (seed→edit→save→readback)
 * plus a runtime-preview assertion (designer-mode-preview → unified-runtime-preview
 * mounts RecursiveBlockRenderer). Inspector data-testids verified against the live
 * source (SchemaInspector.tsx / InspectorSchemaRegistry.widgetFields):
 *   - top-level select widgetType:  inspector-field-widgetType
 *   - json props.series:            inspector-field-props.series + inspector-json-field-apply-props.series
 *   - text props.value:             inspector-field-props.value
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
// dashboard contract; widget blocks here use static/preview data sources so the
// model just has to exist.
const MODEL_CODE = 'ab_announcement';

interface DslBlock {
  id?: string;
  blockType?: string;
  widgetType?: string;
  props?: Record<string, unknown>;
  layout?: Record<string, unknown>;
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
  await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 45_000 });
  await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存', { timeout: 15_000 });
}

async function selectBlock(page: Page, blockId: string): Promise<void> {
  await page.getByTestId(`outline-item-${blockId}`).click();
  await expect(page.getByTestId('inspector-selected-id')).toContainText(blockId);
}

/** Save and wait for the real PUT to land (mirrors widget-advanced-props-golden). */
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
  // Let the draft state commit before the apply handler reads it. Switching the
  // widgetType select just before this can leave the inspector mid-rerender, so
  // wait for the apply button to be stable and retry the click (auto-retry handles
  // the brief detach/reattach that otherwise stalls a single click mid-scroll).
  await page.evaluate(
    () => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))),
  );
  const applyButton = page.getByTestId(`inspector-json-field-apply-${path}`);
  await expect(applyButton).toBeEnabled({ timeout: 5_000 });
  await applyButton.click({ timeout: 10_000 });
  await page.evaluate(
    () => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))),
  );
}

/** Switch the designer to runtime preview mode and wait for the runtime mount. */
async function enterPreview(page: Page): Promise<void> {
  await page.getByTestId('designer-mode-preview').click();
  await expect(page.getByTestId('unified-runtime-preview')).toBeVisible({ timeout: 15_000 });
}

const PIE_WIDGET = 'pd_widget_pie';
const AREA_WIDGET = 'pd_widget_area';
const PROGRESS_WIDGET = 'pd_widget_progress';

test.describe.serial('Unified Designer widget chart types golden (pie / area / progress)', () => {
  // Several real save/reopen round-trips per test; the 15s default is tight.
  test.describe.configure({ timeout: 120_000 });

  const uid = uniqueId('pdwchart');
  let pid = '';

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: ADMIN_STORAGE_STATE });
    const page = await ctx.newPage();

    const resp = await page.request.post('/api/pages', {
      data: {
        name: `Widget chart types ${uid}`,
        pageKey: `pd_wchart_${uid}`.replace(/-/g, '_'),
        title: `Widget chart types ${uid}`,
        kind: 'dashboard',
        modelCode: MODEL_CODE,
        // The unified designer loads/saves a V3 document; its client validator
        // requires schemaVersion 3 (a schemaVersion 4 seed loads but fails the
        // client save validation so the save PUT never fires).
        schemaVersion: 3,
        blocks: [
          {
            id: 'dashboard_root',
            blockType: 'dashboard',
            title: 'Widget chart types root',
            layout: { cols: 12, rowHeight: 60, gap: 8, span: 12 },
            blocks: [
              {
                id: PIE_WIDGET,
                blockType: 'widget',
                // Seed as number-card; the test switches widgetType via the
                // inspector select (proves the new option is selectable + persists).
                widgetType: 'number-card',
                title: 'Pie chart',
                props: {},
                layout: { x: 0, y: 0, w: 4, h: 3 },
              },
              {
                id: AREA_WIDGET,
                blockType: 'widget',
                widgetType: 'number-card',
                title: 'Area chart',
                props: {},
                layout: { x: 4, y: 0, w: 4, h: 3 },
              },
              {
                id: PROGRESS_WIDGET,
                blockType: 'widget',
                widgetType: 'number-card',
                title: 'Progress',
                props: {},
                layout: { x: 8, y: 0, w: 4, h: 2 },
              },
            ],
          },
        ],
        extension: { e2e: true, scenario: 'widget-chart-types-golden' },
      },
    });
    expect(resp.ok(), `seed page failed: ${resp.status()} ${await resp.text()}`).toBeTruthy();
    const body = await resp.json();
    expect(body.code, 'seed page API code').toBe('0');
    pid = String(body.data?.pid ?? '');
    expect(pid, 'seeded pid').toBeTruthy();

    await ctx.close();
  });

  test('C1: pie-chart — widgetType + series JSON persist and the runtime renders SVG slices', async ({
    page,
  }, testInfo) => {
    const series = [
      { label: 'Done', value: 60 },
      { label: 'In progress', value: 30 },
      { label: 'Blocked', value: 10 },
    ];

    await openDesigner(page, pid);
    await selectBlock(page, PIE_WIDGET);
    // The new option is selectable in the inspector widgetType dropdown.
    await page.getByTestId('inspector-field-widgetType').selectOption('pie-chart');
    await applyJsonField(page, 'props.series', series);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');
    await testInfo.attach('c1-pie-authored', { body: await page.screenshot(), contentType: 'image/png' });

    await saveDesigner(page, pid);

    // Readback: widgetType + series persisted verbatim.
    const persisted = await readPage(page, pid);
    expect(findBlockById(persisted.blocks, PIE_WIDGET)).toMatchObject({
      blockType: 'widget',
      widgetType: 'pie-chart',
      props: expect.objectContaining({ series }),
    });

    // Runtime preview: the pie renderer draws one slice per series entry.
    await enterPreview(page);
    const pie = page.getByTestId(`runtime-widget-pie-${PIE_WIDGET}`);
    await expect(pie).toBeVisible({ timeout: 10_000 });
    await expect(pie).toHaveAttribute('data-slices', String(series.length));
    // Each slice is a real <path>/<circle> element with a value attribute.
    for (let i = 0; i < series.length; i += 1) {
      const slice = page.getByTestId(`runtime-widget-pie-slice-${PIE_WIDGET}-${i}`);
      await expect(slice).toBeVisible();
      await expect(slice).toHaveAttribute('data-value', String(series[i].value));
    }
    await testInfo.attach('c1-pie-runtime', { body: await page.screenshot(), contentType: 'image/png' });
  });

  test('C2: area-chart — widgetType + series JSON persist and the runtime renders a filled area path', async ({
    page,
  }, testInfo) => {
    const series = [
      { label: 'Mon', value: 12 },
      { label: 'Tue', value: 26 },
      { label: 'Wed', value: 18 },
      { label: 'Thu', value: 34 },
    ];

    await openDesigner(page, pid);
    await selectBlock(page, AREA_WIDGET);
    await page.getByTestId('inspector-field-widgetType').selectOption('area-chart');
    await applyJsonField(page, 'props.series', series);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');
    await testInfo.attach('c2-area-authored', { body: await page.screenshot(), contentType: 'image/png' });

    await saveDesigner(page, pid);

    const persisted = await readPage(page, pid);
    expect(findBlockById(persisted.blocks, AREA_WIDGET)).toMatchObject({
      blockType: 'widget',
      widgetType: 'area-chart',
      props: expect.objectContaining({ series }),
    });

    await enterPreview(page);
    const area = page.getByTestId(`runtime-widget-area-${AREA_WIDGET}`);
    await expect(area).toBeVisible({ timeout: 10_000 });
    await expect(area).toHaveAttribute('data-points', series.map((s) => s.value).join(','));
    // The filled-area <path> is the distinguishing element vs a bare line chart.
    const fill = page.getByTestId(`runtime-widget-area-fill-${AREA_WIDGET}`);
    await expect(fill).toBeVisible();
    const d = await fill.getAttribute('d');
    expect(d && d.length > 0, 'area fill path d attribute is non-empty').toBeTruthy();
    await testInfo.attach('c2-area-runtime', { body: await page.screenshot(), contentType: 'image/png' });
  });

  test('C3: progress — widgetType + value persist and the runtime renders a percentage bar (not the number card)', async ({
    page,
  }, testInfo) => {
    const value = '72';
    const thresholds = [
      { value: 80, color: 'green' },
      { value: 50, color: 'amber' },
      { value: 0, color: 'red' },
    ];

    await openDesigner(page, pid);
    await selectBlock(page, PROGRESS_WIDGET);
    await page.getByTestId('inspector-field-widgetType').selectOption('progress');
    // Progress reads props.value as its percentage and props.thresholds for the band color.
    await page.getByTestId('inspector-field-props.value').fill(value);
    await applyJsonField(page, 'props.thresholds', thresholds);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');
    await testInfo.attach('c3-progress-authored', { body: await page.screenshot(), contentType: 'image/png' });

    await saveDesigner(page, pid);

    const persisted = await readPage(page, pid);
    expect(findBlockById(persisted.blocks, PROGRESS_WIDGET)).toMatchObject({
      blockType: 'widget',
      widgetType: 'progress',
      props: expect.objectContaining({ value, thresholds }),
    });

    await enterPreview(page);
    // The progress widget must NOT be short-circuited into the number-card value box
    // (props.value is its percentage). Assert the dedicated progress DOM renders.
    const progress = page.getByTestId(`runtime-widget-progress-${PROGRESS_WIDGET}`);
    await expect(progress).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId(`runtime-widget-progress-value-${PROGRESS_WIDGET}`)).toHaveText('72%');
    const bar = page.getByTestId(`runtime-widget-progress-bar-${PROGRESS_WIDGET}`);
    await expect(bar).toBeVisible();
    await expect(bar).toHaveAttribute('data-percent', '72');
    // The number-card single-value box must be absent for a progress widget.
    await expect(page.getByTestId(`runtime-widget-value-${PROGRESS_WIDGET}`)).toHaveCount(0);
    await testInfo.attach('c3-progress-runtime', { body: await page.screenshot(), contentType: 'image/png' });
  });
});
