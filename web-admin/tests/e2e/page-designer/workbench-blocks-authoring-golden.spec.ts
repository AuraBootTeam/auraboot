/**
 * Unified Designer — workbench-block (metric-strip / status-banner) authoring golden.
 *
 * This slice adds visual authoring of the two workbench blocks to the unified
 * page designer:
 *   - BlockRegistry: metric-strip + status-banner (category 'workbench')
 *   - InspectorSchemaRegistry: bare top-level fields (dataSource / variant /
 *     metrics for metric-strip; dataSource / statusField / errorField / toneMap /
 *     titleMap / … for status-banner) — keyed at the BLOCK TOP LEVEL because the
 *     live platform renderer (framework/meta/rendering/blocks/MetricStripBlockRenderer
 *     + StatusBannerBlockRenderer) reads them there, not under block.props.
 *   - RecursiveBlockRenderer: a config-driven REPRESENTATIVE preview inside the
 *     designer canvas (RuntimeMetricStripPreview / RuntimeStatusBannerPreview).
 *     Full data binding renders on the live /p/ page, not in the designer.
 *
 * Golden coverage:
 *   A1 — metric-strip authoring: seed a kind:'detail' (schemaVersion 3) page,
 *        open the designer, edit the metrics JSON + variant + dataSource in the
 *        inspector, save, reload + GET /api/pages readback `toMatchObject`
 *        (props persisted at the block TOP LEVEL), and assert the designer preview
 *        shows the authored metric labels representatively.
 *   A2 — status-banner authoring: same contract for statusField / toneMap /
 *        titleMap; preview shows a representative status sample.
 *   A3 — sad path: invalid metrics JSON shows a per-field error and is NOT written.
 *   L1 — live render: publish a kind:'list' (schemaVersion 4) custom page with a
 *        metric-strip + status-banner bound to static data sources, navigate to
 *        the live /p/c/<pageKey> page, and assert the REAL platform renderers
 *        render real values (metric value '7', status banner title). This proves
 *        the authored shape is end-to-end usable, not just persisted.
 *
 * Inspector data-testids verified against SchemaInspector.tsx:
 *   - text/number/select field: inspector-field-<path>
 *   - json field apply / error: inspector-json-field-apply-<path> /
 *     inspector-json-field-error-<path>
 * Designer preview testids verified against RecursiveBlockRenderer.tsx:
 *   - runtime-metric-strip-<id> / runtime-metric-strip-item-<key>
 *   - runtime-status-banner-<id> / runtime-status-banner-title-<id>
 * Live renderer testids verified against the platform renderers:
 *   - metric-strip-item-<key> / metric-strip-value-<key> / status-banner-<id>
 *
 * @since 4.0.0
 */

import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers';

const ADMIN_STORAGE_STATE =
  process.env.PW_ADMIN_STORAGE_STATE ||
  (process.env.PW_STORAGE_DIR ? `${process.env.PW_STORAGE_DIR}/admin.json` : './tests/storage/admin.json');

// ab_announcement is a published platform meta-model present in every OSS stack;
// the detail-kind seed only needs a real published modelCode for the root contract.
const MODEL_CODE = 'ab_announcement';

interface DslBlock {
  id?: string;
  blockType?: string;
  title?: unknown;
  dataSource?: unknown;
  variant?: unknown;
  metrics?: unknown;
  statusField?: unknown;
  errorField?: unknown;
  toneMap?: unknown;
  titleMap?: unknown;
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

/**
 * Switch the designer to preview mode, where the canvas swaps in the runtime
 * renderer (RecursiveBlockRenderer) — the only mode that renders the workbench
 * block representative preview. The edit/layout canvas shows block scaffolds only.
 */
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
  await page.evaluate(
    () => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))),
  );
  await page.getByTestId(`inspector-json-field-apply-${path}`).click();
  await page.evaluate(
    () => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))),
  );
}

const METRIC_STRIP = 'pd_wb_metric_strip';
const STATUS_BANNER = 'pd_wb_status_banner';

test.describe.serial('Unified Designer workbench-block authoring golden', () => {
  test.describe.configure({ timeout: 120_000 });

  const uid = uniqueId('pdwb');
  let pid = '';

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: ADMIN_STORAGE_STATE });
    const page = await ctx.newPage();

    // A detail-kind page surfaces both workbench blocks in its palette/policy
    // (kindPolicy.detail allows metric-strip + status-banner). The two blocks
    // are seeded as bare scaffolds; the inspector authoring below fills them.
    const resp = await page.request.post('/api/pages', {
      data: {
        name: `Workbench authoring ${uid}`,
        pageKey: `pd_wb_${uid}`.replace(/-/g, '_'),
        title: `Workbench authoring ${uid}`,
        kind: 'detail',
        modelCode: MODEL_CODE,
        schemaVersion: 3,
        blocks: [
          {
            id: 'detail_root',
            blockType: 'detail',
            title: 'Workbench authoring root',
            layout: { span: 12 },
            blocks: [
              {
                id: METRIC_STRIP,
                blockType: 'metric-strip',
                title: 'KPI strip',
                layout: { span: 12 },
              },
              {
                id: STATUS_BANNER,
                blockType: 'status-banner',
                title: 'Task status',
                layout: { span: 12 },
              },
            ],
          },
        ],
        extension: { e2e: true, scenario: 'workbench-blocks-authoring-golden' },
      },
    });
    expect(resp.ok(), `seed page failed: ${resp.status()} ${await resp.text()}`).toBeTruthy();
    const body = await resp.json();
    expect(body.code, 'seed page API code').toBe('0');
    pid = String(body.data?.pid ?? '');
    expect(pid, 'seeded pid').toBeTruthy();

    await ctx.close();
  });

  test('A1: metric-strip — metrics JSON + variant + dataSource persist at the block top level and preview shows labels', async ({
    page,
  }, testInfo) => {
    const metrics = [
      { key: 'open_total', label: { 'en-US': 'Open', 'zh-CN': '未决' }, valueField: 'open_total', tone: 'blue' },
      { key: 'open_critical', label: { 'en-US': 'Critical', 'zh-CN': '严重' }, valueField: 'open_critical', tone: 'red' },
      { key: 'active', label: { 'en-US': 'Active', 'zh-CN': '处理中' }, valueField: 'active_total', tone: 'amber' },
    ];
    const dataSource = `andonStats_${uid}`;

    await openDesigner(page, pid);
    await selectBlock(page, METRIC_STRIP);
    await testInfo.attach('a1-metric-strip-selected', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });

    // dataSource (string id) is a text field; variant a select; metrics a JSON array.
    await page.getByTestId('inspector-field-dataSource').fill(dataSource);
    await page.getByTestId('inspector-field-variant').selectOption('cards');
    await applyJsonField(page, 'metrics', metrics);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');
    await testInfo.attach('a1-metric-strip-authored', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });

    await saveDesigner(page, pid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 30_000 });
    await selectBlock(page, METRIC_STRIP);
    await expect(page.getByTestId('inspector-field-dataSource')).toHaveValue(dataSource);
    await expect(page.getByTestId('inspector-field-variant')).toHaveValue('cards');
    await expect(page.getByTestId('inspector-field-metrics')).toContainText('open_critical');
    // designer representative preview (preview mode) shows the authored labels +
    // placeholder values; the full data binding happens on the live /p/ page.
    await enterPreviewMode(page);
    await expect(page.getByTestId(`runtime-metric-strip-item-open_total`)).toBeVisible();
    await expect(page.getByTestId(`runtime-metric-strip-item-open_critical`)).toContainText('严重');
    await expect(page.getByTestId(`runtime-metric-strip-value-open_total`)).toContainText('—');
    await testInfo.attach('a1-metric-strip-reloaded-preview', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });
    await enterEditMode(page);

    // Readback: props persist at the block TOP LEVEL (not under props), exactly
    // where the live platform renderer reads them.
    const persisted = await readPage(page, pid);
    const block = findBlockById(persisted.blocks, METRIC_STRIP);
    expect(block).toMatchObject({
      blockType: 'metric-strip',
      dataSource,
      variant: 'cards',
      metrics,
    });
  });

  test('A2: status-banner — statusField + toneMap + titleMap persist at the block top level and preview shows a status sample', async ({
    page,
  }, testInfo) => {
    const statusField = 'bom_task_status';
    const errorField = 'bom_task_error_message';
    const dataSource = `taskSummary_${uid}`;
    const toneMap = { parsing: 'blue', matching: 'blue', failed: 'red' };
    const titleMap = {
      parsing: { 'en-US': 'Parsing BOM', 'zh-CN': '正在解析' },
      matching: { 'en-US': 'Matching', 'zh-CN': '正在匹配' },
      failed: { 'en-US': 'Failed', 'zh-CN': '解析失败' },
    };

    await openDesigner(page, pid);
    await selectBlock(page, STATUS_BANNER);

    await page.getByTestId('inspector-field-dataSource').fill(dataSource);
    await page.getByTestId('inspector-field-statusField').fill(statusField);
    await page.getByTestId('inspector-field-errorField').fill(errorField);
    await applyJsonField(page, 'toneMap', toneMap);
    await applyJsonField(page, 'titleMap', titleMap);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');
    await testInfo.attach('a2-status-banner-authored', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });

    await saveDesigner(page, pid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 30_000 });
    await selectBlock(page, STATUS_BANNER);
    await expect(page.getByTestId('inspector-field-statusField')).toHaveValue(statusField);
    await expect(page.getByTestId('inspector-field-toneMap')).toContainText('failed');
    await expect(page.getByTestId('inspector-field-titleMap')).toContainText('Parsing BOM');
    // designer representative preview (preview mode) shows a status sample driven
    // by the configured titleMap. The platform persists object keys in its own
    // order, so the representative status is whichever key the renderer reads
    // first — assert it is ONE OF the configured statuses (config-driven), not a
    // specific one, and that its zh-CN title renders.
    await enterPreviewMode(page);
    const sample = page.getByTestId(`runtime-status-banner-sample-${STATUS_BANNER}`);
    await expect(sample).toBeVisible();
    const sampleStatus = await sample.getAttribute('data-status');
    expect(['parsing', 'matching', 'failed']).toContain(sampleStatus);
    const expectedTitle: Record<string, string> = {
      parsing: '正在解析',
      matching: '正在匹配',
      failed: '解析失败',
    };
    await expect(page.getByTestId(`runtime-status-banner-title-${STATUS_BANNER}`)).toContainText(
      expectedTitle[sampleStatus as string],
    );
    await testInfo.attach('a2-status-banner-reloaded-preview', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });
    await enterEditMode(page);

    const persisted = await readPage(page, pid);
    const block = findBlockById(persisted.blocks, STATUS_BANNER);
    expect(block).toMatchObject({
      blockType: 'status-banner',
      dataSource,
      statusField,
      errorField,
      toneMap,
      titleMap,
    });
  });

  test('A3 (sad path): invalid metrics JSON shows a per-field error and is NOT written back', async ({
    page,
  }, testInfo) => {
    await openDesigner(page, pid);
    await selectBlock(page, METRIC_STRIP);

    const before = await readPage(page, pid);
    const beforeMetrics = findBlockById(before.blocks, METRIC_STRIP)?.metrics;
    expect(Array.isArray(beforeMetrics), 'A1 metrics present before sad path').toBeTruthy();

    const metricsField = page.getByTestId('inspector-field-metrics');
    await expect(metricsField).toBeVisible({ timeout: 5_000 });
    await metricsField.fill('[ { key: open, ');
    await page.evaluate(
      () => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))),
    );
    await page.getByTestId('inspector-json-field-apply-metrics').click();
    await expect(page.getByTestId('inspector-json-field-error-metrics')).toBeVisible({
      timeout: 5_000,
    });
    await testInfo.attach('a3-invalid-metrics-json-error', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });

    const after = await readPage(page, pid);
    const afterMetrics = findBlockById(after.blocks, METRIC_STRIP)?.metrics;
    expect(afterMetrics).toEqual(beforeMetrics);
  });

  test('L1 (live render): published custom page renders the real platform metric-strip + status-banner with bound data', async ({
    page,
  }, testInfo) => {
    // Mirrors workbench-blocks-runtime.spec.ts: a kind:'list' custom page with
    // static data sources; navigate to /p/c/<pageKey> and assert the REAL
    // platform renderers (not the designer preview) render real values.
    const id = uniqueId('pdwb_live');
    const pageKey = id.replace(/-/g, '_');
    const blocks = [
      {
        id: 'live_metrics',
        blockType: 'metric-strip',
        title: 'Live KPI strip',
        dataSource: 'ds_metrics',
        variant: 'cards',
        metrics: [
          { key: 'pending', label: 'Pending', valueField: 'pendingCount', tone: 'amber' },
          { key: 'ready', label: 'Ready', valueField: 'readyCount', tone: 'green' },
        ],
      },
      {
        id: 'live_status',
        blockType: 'status-banner',
        dataSource: 'ds_status',
        statusField: 'task_status',
        errorField: 'task_error',
        toneMap: { parsing: 'blue', failed: 'red' },
        titleMap: {
          parsing: { 'en-US': 'Parsing BOM', 'zh-CN': '正在解析' },
          failed: { 'en-US': 'Failed', 'zh-CN': '失败' },
        },
        descriptionMap: {
          parsing: { 'en-US': 'Reading the workbook', 'zh-CN': '正在读取文件' },
        },
      },
    ];
    const dataSources = {
      ds_metrics: {
        type: 'static',
        adaptor: 'records',
        data: [{ pendingCount: 7, readyCount: 3 }],
      },
      ds_status: {
        type: 'static',
        adaptor: 'records',
        data: [{ task_status: 'parsing', task_error: '' }],
      },
    };

    const createResp = await page.request.post('/api/pages', {
      data: {
        name: `Workbench live ${id}`,
        pageKey,
        title: `Workbench live ${id}`,
        kind: 'list',
        modelCode: 'tenant',
        profile: 'admin',
        layout: { type: 'stack', gap: 12 },
        blocks,
        dataSources,
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
    expect(createResp.ok(), `create live page failed: ${createResp.status()} ${await createResp.text()}`).toBeTruthy();
    const createBody = await createResp.json();
    expect(createBody.code, 'create live page API code').toBe('0');
    const livePid = String(createBody.data?.pid || '');
    expect(livePid, 'created live pid').toBeTruthy();

    const publishResp = await page.request.post(`/api/pages/${livePid}/publish`);
    expect(publishResp.ok(), `publish live page failed: ${publishResp.status()}`).toBeTruthy();
    const publishBody = await publishResp.json();
    expect(publishBody.code, 'publish live page API code').toBe('0');
    expect(publishBody.data?.status, 'published live page status').toBe('published');

    await page.goto(`/p/c/${pageKey}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('dynamic-list')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId('list-misc-blocks')).toBeVisible();

    // Real platform MetricStripBlockRenderer renders bound values (not '—').
    await expect(page.getByTestId('metric-strip-live_metrics')).toContainText('Live KPI strip');
    await expect(page.getByTestId('metric-strip-item-pending')).toContainText('Pending');
    await expect(page.getByTestId('metric-strip-value-pending')).toContainText('7');
    await expect(page.getByTestId('metric-strip-value-ready')).toContainText('3');

    // Real platform StatusBannerBlockRenderer resolves status 'parsing' → tone/title.
    await expect(page.getByTestId('status-banner-live_status')).toBeVisible();
    await expect(page.getByTestId('status-banner-live_status')).toContainText('正在解析');
    await expect(page.getByTestId('status-banner-live_status')).toContainText('正在读取文件');

    await testInfo.attach('l1-live-render', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });
  });
});
