/**
 * Unified Designer — display / data blocks authoring golden.
 *
 * Adds visual authoring of four non workbench-family platform blocks to the
 * unified page designer:
 *   - stat-card       ← StatCardBlockRenderer       (single-metric KPI card)
 *   - description      ← DescriptionBlockRenderer     (static rich-text panel)
 *   - record-comments  ← RecordComments (DetailPageContent dispatch) (comment thread)
 *   - embedded-list    ← EmbeddedListBlockRenderer    (in-page filterable list)
 *
 * Architecture (same as the workbench family):
 *   - BlockRegistry: all four registered (stat-card→dashboard, description/
 *     record-comments→detail, embedded-list→list category).
 *   - InspectorSchemaRegistry: fields keyed at the EXACT path each live renderer
 *     reads — verified against renderer source:
 *       stat-card     → block.dataSource (string) + block.statCard (JSON object
 *                        the renderer spreads over props: value/unit/trend/
 *                        trendDirection/valueField).
 *       description   → block.content (the bare path the renderer reads first,
 *                        ahead of props.content / props.text).
 *       record-comments→ title only — RecordComments derives modelCode + recordPid
 *                        from the surrounding detail page + current record, so it
 *                        has NO authorable data props (surfacing them would be
 *                        invented fields the live renderer ignores).
 *       embedded-list → bare block.modelCode / parentField / columns (JSON) /
 *                        pageSize / searchable / filterable.
 *   - RecursiveBlockRenderer: a config-driven REPRESENTATIVE preview inside the
 *     designer canvas (Runtime*Preview). Full data binding renders on the live /p/
 *     page, not in the designer.
 *
 * Golden coverage:
 *   B1..B4 — per-block authoring: edit the inspector props (text / select / JSON),
 *        save, reload + GET /api/pages readback `toMatchObject` (props persisted at
 *        the exact path), and assert the designer representative preview shows the
 *        authored content.
 *   B5 — sad path: invalid columns JSON on embedded-list shows a per-field error
 *        and is NOT written to the block.
 *   L1 — live render: publish a kind:'list' (schemaVersion 4) custom page with
 *        stat-card / description / embedded-list bound to static data, navigate to
 *        /p/c/<pageKey>, and assert the REAL platform renderers render real values.
 *        (record-comments is intentionally excluded from L1: it is dispatched only
 *        by the detail-page path with a page-context-derived modelCode/recordPid —
 *        it does not render through the list misc-block BlockRenderer path, and has
 *        no block-level data to bind. Its live thread is covered by the platform's
 *        own RecordComments tests; here it is authoring-golden + preview only.)
 *
 * Inspector data-testids verified against SchemaInspector.tsx:
 *   - text/number/select/boolean field: inspector-field-<path>
 *   - json field apply / error: inspector-json-field-apply-<path> /
 *     inspector-json-field-error-<path>
 * Designer preview testids verified against RecursiveBlockRenderer.tsx:
 *   - runtime-stat-card-value-<id> / runtime-stat-card-binding-<id>
 *   - runtime-description-content-<id>
 *   - runtime-record-comments-sample-<id>
 *   - runtime-embedded-list-binding-<id> / runtime-embedded-list-column-<key>
 * Live renderer testids/markers verified against the platform renderers:
 *   - stat-card-block / stat-card-value / stat-card-trend (StatCardBlockRenderer)
 *   - .description-block (DescriptionBlockRenderer)
 *   - embedded-list-<id> (EmbeddedListBlockRenderer / RecordListView)
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
  statCard?: unknown;
  content?: unknown;
  modelCode?: unknown;
  parentField?: unknown;
  columns?: unknown;
  pageSize?: unknown;
  searchable?: unknown;
  filterable?: unknown;
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

async function fillTextField(page: Page, path: string, value: string): Promise<void> {
  const field = page.getByTestId(`inspector-field-${path}`);
  await expect(field).toBeVisible({ timeout: 5_000 });
  await field.fill(value);
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

// Block ids seeded into the detail page (one per block type).
const STAT_CARD = 'pd_disp_stat_card';
const DESCRIPTION = 'pd_disp_description';
const RECORD_COMMENTS = 'pd_disp_record_comments';
const EMBEDDED_LIST = 'pd_disp_embedded_list';

test.describe.serial('Unified Designer display-blocks authoring golden', () => {
  test.describe.configure({ timeout: 150_000 });

  const uid = uniqueId('pddisp');
  let pid = '';

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: ADMIN_STORAGE_STATE });
    const page = await ctx.newPage();

    // A detail-kind page surfaces every display block in its palette/policy
    // (kindPolicy.detail allows all four). The blocks are seeded as bare
    // scaffolds; the inspector authoring in each test fills them.
    const resp = await page.request.post('/api/pages', {
      data: {
        name: `Display blocks authoring ${uid}`,
        pageKey: `pd_disp_${uid}`.replace(/-/g, '_'),
        title: `Display blocks authoring ${uid}`,
        kind: 'detail',
        modelCode: MODEL_CODE,
        schemaVersion: 3,
        blocks: [
          {
            id: 'detail_root',
            blockType: 'detail',
            title: 'Display blocks root',
            layout: { span: 12 },
            blocks: [
              { id: STAT_CARD, blockType: 'stat-card', title: 'Orders today', layout: { span: 12 } },
              { id: DESCRIPTION, blockType: 'description', title: 'Notes', layout: { span: 12 } },
              { id: RECORD_COMMENTS, blockType: 'record-comments', title: 'Discussion', layout: { span: 12 } },
              { id: EMBEDDED_LIST, blockType: 'embedded-list', title: 'Line items', layout: { span: 12 } },
            ],
          },
        ],
        extension: { e2e: true, scenario: 'display-blocks-authoring-golden' },
      },
    });
    expect(resp.ok(), `seed page failed: ${resp.status()} ${await resp.text()}`).toBeTruthy();
    const body = await resp.json();
    expect(body.code, 'seed page API code').toBe('0');
    pid = String(body.data?.pid ?? '');
    expect(pid, 'seeded pid').toBeTruthy();

    await ctx.close();
  });

  test('B1: stat-card — dataSource + statCard JSON persist at the block top level and preview shows the metric', async ({
    page,
  }, testInfo) => {
    const dataSource = `ds_orders_${uid}`;
    const statCard = { value: 42, unit: 'orders', trend: '+12%', trendDirection: 'up', valueField: 'open_total' };

    await openDesigner(page, pid);
    await selectBlock(page, STAT_CARD);
    await fillTextField(page, 'dataSource', dataSource);
    await applyJsonField(page, 'statCard', statCard);
    await saveDesigner(page, pid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 30_000 });
    await selectBlock(page, STAT_CARD);
    await expect(page.getByTestId('inspector-field-dataSource')).toHaveValue(dataSource);
    await expect(page.getByTestId('inspector-field-statCard')).toContainText('open_total');

    await enterPreviewMode(page);
    await expect(page.getByTestId(`runtime-stat-card-value-${STAT_CARD}`)).toContainText('42');
    await expect(page.getByTestId(`runtime-stat-card-trend-${STAT_CARD}`)).toContainText('+12%');
    await expect(page.getByTestId(`runtime-stat-card-binding-${STAT_CARD}`)).toContainText(dataSource);
    await testInfo.attach('b1-stat-card-preview', { body: await page.screenshot(), contentType: 'image/png' });
    await enterEditMode(page);

    const block = findBlockById((await readPage(page, pid)).blocks, STAT_CARD);
    expect(block).toMatchObject({ blockType: 'stat-card', dataSource, statCard });
  });

  test('B2: description — content persists at the bare block.content path and preview shows the text', async ({
    page,
  }, testInfo) => {
    const content = 'Read before submitting';

    await openDesigner(page, pid);
    await selectBlock(page, DESCRIPTION);
    await fillTextField(page, 'content', content);
    await saveDesigner(page, pid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 30_000 });
    await selectBlock(page, DESCRIPTION);
    await expect(page.getByTestId('inspector-field-content')).toHaveValue(content);

    await enterPreviewMode(page);
    await expect(page.getByTestId(`runtime-description-content-${DESCRIPTION}`)).toContainText(content);
    await testInfo.attach('b2-description-preview', { body: await page.screenshot(), contentType: 'image/png' });
    await enterEditMode(page);

    // content persists at the BARE top-level path the live renderer reads first
    // (block.content ?? props.content ?? props.text).
    const block = findBlockById((await readPage(page, pid)).blocks, DESCRIPTION);
    expect(block).toMatchObject({ blockType: 'description', content });
  });

  test('B3: record-comments — title persists and the preview shows the representative thread (data is page-context-driven)', async ({
    page,
  }, testInfo) => {
    const title = 'Review thread';

    await openDesigner(page, pid);
    await selectBlock(page, RECORD_COMMENTS);
    await fillTextField(page, 'title', title);
    await saveDesigner(page, pid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 30_000 });
    await selectBlock(page, RECORD_COMMENTS);
    await expect(page.getByTestId('inspector-field-title')).toHaveValue(title);
    // The inspector deliberately exposes NO data fields (modelCode/recordPid are
    // derived from the surrounding detail page + record by the live renderer).
    await expect(page.getByTestId('inspector-field-modelCode')).toHaveCount(0);

    await enterPreviewMode(page);
    await expect(page.getByTestId(`runtime-record-comments-${RECORD_COMMENTS}`)).toContainText(title);
    await expect(page.getByTestId(`runtime-record-comments-sample-${RECORD_COMMENTS}`)).toBeVisible();
    await testInfo.attach('b3-record-comments-preview', { body: await page.screenshot(), contentType: 'image/png' });
    await enterEditMode(page);

    const block = findBlockById((await readPage(page, pid)).blocks, RECORD_COMMENTS);
    expect(block).toMatchObject({ blockType: 'record-comments', title });
  });

  test('B4: embedded-list — modelCode + parentField + columns JSON persist at the block top level and preview shows the binding', async ({
    page,
  }, testInfo) => {
    const modelCode = 'ab_announcement';
    const parentField = 'parent_id';
    const columns = [
      { field: 'title', label: { 'en-US': 'Title', 'zh-CN': '标题' } },
      { field: 'status', label: { 'en-US': 'Status', 'zh-CN': '状态' } },
    ];

    await openDesigner(page, pid);
    await selectBlock(page, EMBEDDED_LIST);
    await fillTextField(page, 'modelCode', modelCode);
    await fillTextField(page, 'parentField', parentField);
    await applyJsonField(page, 'columns', columns);
    await saveDesigner(page, pid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 30_000 });
    await selectBlock(page, EMBEDDED_LIST);
    await expect(page.getByTestId('inspector-field-modelCode')).toHaveValue(modelCode);
    await expect(page.getByTestId('inspector-field-parentField')).toHaveValue(parentField);
    await expect(page.getByTestId('inspector-field-columns')).toContainText('status');

    await enterPreviewMode(page);
    await expect(page.getByTestId(`runtime-embedded-list-binding-${EMBEDDED_LIST}`)).toContainText(modelCode);
    await expect(page.getByTestId(`runtime-embedded-list-binding-${EMBEDDED_LIST}`)).toContainText(parentField);
    await expect(page.getByTestId('runtime-embedded-list-column-title')).toContainText('标题');
    await expect(page.getByTestId('runtime-embedded-list-column-status')).toContainText('状态');
    await testInfo.attach('b4-embedded-list-preview', { body: await page.screenshot(), contentType: 'image/png' });
    await enterEditMode(page);

    const block = findBlockById((await readPage(page, pid)).blocks, EMBEDDED_LIST);
    expect(block).toMatchObject({ blockType: 'embedded-list', modelCode, parentField, columns });
  });

  test('B5 (sad path): invalid columns JSON on embedded-list shows a per-field error and is NOT written back', async ({
    page,
  }, testInfo) => {
    await openDesigner(page, pid);
    await selectBlock(page, EMBEDDED_LIST);

    const before = findBlockById((await readPage(page, pid)).blocks, EMBEDDED_LIST)?.columns;
    expect(Array.isArray(before), 'B4 columns present before sad path').toBeTruthy();

    const columnsField = page.getByTestId('inspector-field-columns');
    await expect(columnsField).toBeVisible({ timeout: 5_000 });
    await columnsField.fill('[ { field: title, ');
    await page.evaluate(
      () => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))),
    );
    await page.getByTestId('inspector-json-field-apply-columns').click();
    await expect(page.getByTestId('inspector-json-field-error-columns')).toBeVisible({ timeout: 5_000 });
    await testInfo.attach('b5-invalid-columns-json-error', { body: await page.screenshot(), contentType: 'image/png' });

    const after = findBlockById((await readPage(page, pid)).blocks, EMBEDDED_LIST)?.columns;
    expect(after).toEqual(before);
  });

  test('L1 (live render): published custom page renders the real platform stat-card / description / embedded-list', async ({
    page,
  }, testInfo) => {
    // A kind:'list' custom page with static data sources; navigate to /p/c/<pageKey>
    // and assert the REAL platform renderers (not the designer preview) render real
    // values. record-comments is excluded — it is detail-page-context-driven and
    // does not render through the list misc-block BlockRenderer path.
    const id = uniqueId('pddisp_live');
    const pageKey = id.replace(/-/g, '_');

    const dataSources = {
      ds_orders: {
        type: 'static',
        adaptor: 'records',
        data: [{ open_total: 128 }],
      },
    };

    const blocks = [
      {
        id: 'live_stat',
        blockType: 'stat-card',
        title: 'Open orders',
        dataSource: 'ds_orders',
        statCard: { valueField: 'open_total', unit: 'orders', trend: '+5%', trendDirection: 'up' },
      },
      {
        id: 'live_desc',
        blockType: 'description',
        title: 'Runtime notes',
        content: 'Review the open orders before closing the shift.',
      },
      {
        id: 'live_embedded',
        blockType: 'embedded-list',
        title: 'Announcements',
        modelCode: 'ab_announcement',
        columns: [
          { field: 'title', label: 'Title' },
          { field: 'status', label: 'Status' },
        ],
      },
    ];

    const createResp = await page.request.post('/api/pages', {
      data: {
        name: `Display blocks live ${id}`,
        pageKey,
        title: `Display blocks live ${id}`,
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

    // Real StatCardBlockRenderer pulls the bound value (open_total=128) from the
    // static data source first row.
    await expect(page.getByTestId('stat-card-block').first()).toBeVisible();
    await expect(page.getByTestId('stat-card-value').first()).toContainText('128');
    await expect(page.getByTestId('stat-card-trend').first()).toContainText('+5%');

    // Real DescriptionBlockRenderer renders block.content (sanitized HTML).
    await expect(page.locator('.description-block').first()).toContainText(
      'Review the open orders before closing the shift.',
    );

    // Real EmbeddedListBlockRenderer mounts the record list for ab_announcement.
    // The section wrapper + the inner RecordListView share the testid prefix, so
    // scope to the first match (the section) and assert the bound column headers
    // rendered through the real renderer (not the designer preview).
    await expect(page.getByTestId('embedded-list-live_embedded').first()).toBeVisible();
    await expect(page.getByTestId('embedded-list-live_embedded').first()).toContainText('Title');
    await expect(page.getByTestId('embedded-list-live_embedded').first()).toContainText('Status');

    await testInfo.attach('l1-live-render', { body: await page.screenshot(), contentType: 'image/png' });
  });
});
