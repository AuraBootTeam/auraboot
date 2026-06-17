/**
 * Unified Designer — workbench-family batch 2 authoring golden.
 *
 * Completes the workbench-block family in the unified page designer. Batch 1
 * (PR #766) added metric-strip + status-banner; this slice adds visual authoring
 * of the remaining six platform workbench blocks:
 *   - workbench-action-bar ← WorkbenchActionBarBlockRenderer
 *   - review-drawer        ← ReviewDrawerBlockRenderer
 *   - evidence-panel       ← EvidencePanelBlockRenderer
 *   - record-inspector     ← RecordInspectorBlockRenderer
 *   - candidate-list       ← CandidateListBlockRenderer
 *   - artifact-timeline    ← ArtifactTimelineBlockRenderer
 *
 * Architecture (same as batch 1):
 *   - BlockRegistry: all six registered, category 'workbench'
 *   - InspectorSchemaRegistry: BARE top-level fields (actions / dataSource /
 *     sections / fields / item / context / compare / candidates / …) keyed at the
 *     BLOCK TOP LEVEL because the live platform renderers
 *     (framework/meta/rendering/blocks/*) read them there, NOT under block.props.
 *   - RecursiveBlockRenderer: a config-driven REPRESENTATIVE preview inside the
 *     designer canvas (Runtime*Preview). Full data binding renders on the live /p/
 *     page, not in the designer.
 *
 * Golden coverage:
 *   B1..B6 — per-block authoring: seed a kind:'detail' (schemaVersion 3) page
 *        with the block scaffold, open the designer, edit the bare top-level props
 *        in the inspector (text / select / JSON), save, reload + GET /api/pages
 *        readback `toMatchObject` (props persisted at the block TOP LEVEL), and
 *        assert the designer representative preview shows the authored content.
 *   B7 — sad path: invalid actions JSON on workbench-action-bar shows a per-field
 *        error and is NOT written to the block.
 *   L1 — live render: publish a kind:'list' (schemaVersion 4) custom page with all
 *        six blocks bound to static data sources, navigate to /p/c/<pageKey>, and
 *        assert the REAL platform renderers render real values + interactive state
 *        (select candidate → record-inspector / evidence-panel populate). This
 *        proves the authored shape is end-to-end usable, not just persisted.
 *
 * Inspector data-testids verified against SchemaInspector.tsx:
 *   - text/number/select field: inspector-field-<path>
 *   - json field apply / error: inspector-json-field-apply-<path> /
 *     inspector-json-field-error-<path>
 * Designer preview testids verified against RecursiveBlockRenderer.tsx:
 *   - runtime-workbench-action-bar-<id> / runtime-evidence-panel-section-<key>
 *   - runtime-record-inspector-field-<field> / runtime-candidate-list-sample-<id>
 *   - runtime-artifact-timeline-title-<id> / runtime-review-drawer-sample-<id>
 * Live renderer testids verified against the platform renderers:
 *   - workbench-action-bar / workbench-action-<code>
 *   - candidate-list / candidate-list-item-<rowKey> / record-inspector
 *   - evidence-panel / evidence-panel-section-<key>
 *   - artifact-timeline / artifact-timeline-item-<rowKey> / -download-<rowKey>
 *   - review-drawer / review-drawer-badge-<key>
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
  actions?: unknown;
  sections?: unknown;
  fields?: unknown;
  item?: unknown;
  context?: unknown;
  surface?: unknown;
  align?: unknown;
  summaryBadges?: unknown;
  compare?: unknown;
  candidates?: unknown;
  selection?: unknown;
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

/** Fill a basic-tab text/textarea inspector field. */
async function fillTextField(page: Page, path: string, value: string): Promise<void> {
  const field = page.getByTestId(`inspector-field-${path}`);
  await expect(field).toBeVisible({ timeout: 5_000 });
  await field.fill(value);
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

// Block ids seeded into the detail page (one per block type).
const ACTION_BAR = 'pd_wb2_action_bar';
const REVIEW_DRAWER = 'pd_wb2_review_drawer';
const EVIDENCE_PANEL = 'pd_wb2_evidence_panel';
const RECORD_INSPECTOR = 'pd_wb2_record_inspector';
const CANDIDATE_LIST = 'pd_wb2_candidate_list';
const ARTIFACT_TIMELINE = 'pd_wb2_artifact_timeline';

test.describe.serial('Unified Designer workbench-family batch-2 authoring golden', () => {
  test.describe.configure({ timeout: 150_000 });

  const uid = uniqueId('pdwb2');
  let pid = '';

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: ADMIN_STORAGE_STATE });
    const page = await ctx.newPage();

    // A detail-kind page surfaces every workbench block in its palette/policy
    // (kindPolicy.detail allows the workbench family). The six blocks are seeded
    // as bare scaffolds; the inspector authoring in each test fills them.
    const resp = await page.request.post('/api/pages', {
      data: {
        name: `Workbench batch2 authoring ${uid}`,
        pageKey: `pd_wb2_${uid}`.replace(/-/g, '_'),
        title: `Workbench batch2 authoring ${uid}`,
        kind: 'detail',
        modelCode: MODEL_CODE,
        schemaVersion: 3,
        blocks: [
          {
            id: 'detail_root',
            blockType: 'detail',
            title: 'Workbench batch2 root',
            layout: { span: 12 },
            blocks: [
              { id: ACTION_BAR, blockType: 'workbench-action-bar', title: 'Reconcile actions', layout: { span: 12 } },
              { id: REVIEW_DRAWER, blockType: 'review-drawer', title: 'Row review', layout: { span: 12 } },
              { id: EVIDENCE_PANEL, blockType: 'evidence-panel', title: 'Parse evidence', layout: { span: 12 } },
              { id: RECORD_INSPECTOR, blockType: 'record-inspector', title: 'Record', layout: { span: 12 } },
              { id: CANDIDATE_LIST, blockType: 'candidate-list', title: 'Candidates', layout: { span: 12 } },
              { id: ARTIFACT_TIMELINE, blockType: 'artifact-timeline', title: 'Artifacts', layout: { span: 12 } },
            ],
          },
        ],
        extension: { e2e: true, scenario: 'workbench-blocks-batch2-authoring-golden' },
      },
    });
    expect(resp.ok(), `seed page failed: ${resp.status()} ${await resp.text()}`).toBeTruthy();
    const body = await resp.json();
    expect(body.code, 'seed page API code').toBe('0');
    pid = String(body.data?.pid ?? '');
    expect(pid, 'seeded pid').toBeTruthy();

    await ctx.close();
  });

  test('B1: workbench-action-bar — actions JSON + surface + align persist at the block top level and preview shows action labels', async ({
    page,
  }, testInfo) => {
    const actions = [
      { code: 'confirm', label: { 'en-US': 'Confirm', 'zh-CN': '确认' }, variant: 'primary' },
      { code: 'reject', label: { 'en-US': 'Reject', 'zh-CN': '驳回' }, variant: 'danger' },
    ];

    await openDesigner(page, pid);
    await selectBlock(page, ACTION_BAR);
    await page.getByTestId('inspector-field-surface').selectOption('card');
    await page.getByTestId('inspector-field-align').selectOption('end');
    await applyJsonField(page, 'actions', actions);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');
    await saveDesigner(page, pid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 30_000 });
    await selectBlock(page, ACTION_BAR);
    await expect(page.getByTestId('inspector-field-surface')).toHaveValue('card');
    await expect(page.getByTestId('inspector-field-align')).toHaveValue('end');
    await expect(page.getByTestId('inspector-field-actions')).toContainText('reject');

    await enterPreviewMode(page);
    await expect(page.getByTestId('runtime-workbench-action-bar-action-confirm')).toContainText('确认');
    await expect(page.getByTestId('runtime-workbench-action-bar-action-reject')).toContainText('驳回');
    await testInfo.attach('b1-action-bar-preview', { body: await page.screenshot(), contentType: 'image/png' });
    await enterEditMode(page);

    const block = findBlockById((await readPage(page, pid)).blocks, ACTION_BAR);
    expect(block).toMatchObject({ blockType: 'workbench-action-bar', surface: 'card', align: 'end', actions });
  });

  test('B2: evidence-panel — dataSource + sections JSON persist at the block top level and preview shows section labels', async ({
    page,
  }, testInfo) => {
    const dataSource = `ds_evidence_${uid}`;
    const sections = [
      { key: 'raw', field: 'raw_payload', label: { 'en-US': 'Raw', 'zh-CN': '原始报文' }, format: 'json' },
      { key: 'note', field: 'analyst_note', label: { 'en-US': 'Note', 'zh-CN': '分析备注' } },
    ];

    await openDesigner(page, pid);
    await selectBlock(page, EVIDENCE_PANEL);
    await fillTextField(page, 'dataSource', dataSource);
    await applyJsonField(page, 'sections', sections);
    await saveDesigner(page, pid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 30_000 });
    await selectBlock(page, EVIDENCE_PANEL);
    await expect(page.getByTestId('inspector-field-dataSource')).toHaveValue(dataSource);
    await expect(page.getByTestId('inspector-field-sections')).toContainText('analyst_note');

    await enterPreviewMode(page);
    await expect(page.getByTestId('runtime-evidence-panel-section-raw')).toContainText('原始报文');
    await expect(page.getByTestId('runtime-evidence-panel-section-note')).toContainText('分析备注');
    await testInfo.attach('b2-evidence-panel-preview', { body: await page.screenshot(), contentType: 'image/png' });
    await enterEditMode(page);

    const block = findBlockById((await readPage(page, pid)).blocks, EVIDENCE_PANEL);
    expect(block).toMatchObject({ blockType: 'evidence-panel', dataSource, sections });
  });

  test('B3: record-inspector — context + fields JSON persist at the block top level and preview shows field labels', async ({
    page,
  }, testInfo) => {
    const context = '${state.selectedRow}';
    const fields = [
      { field: 'material_name', label: { 'en-US': 'Name', 'zh-CN': '物料名称' }, span: 2 },
      { field: 'material_code', path: 'material_code', label: { 'en-US': 'Code', 'zh-CN': '物料编码' } },
    ];

    await openDesigner(page, pid);
    await selectBlock(page, RECORD_INSPECTOR);
    await fillTextField(page, 'context', context);
    await applyJsonField(page, 'fields', fields);
    await saveDesigner(page, pid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 30_000 });
    await selectBlock(page, RECORD_INSPECTOR);
    await expect(page.getByTestId('inspector-field-context')).toHaveValue(context);
    await expect(page.getByTestId('inspector-field-fields')).toContainText('material_code');

    await enterPreviewMode(page);
    await expect(page.getByTestId('runtime-record-inspector-field-material_name')).toContainText('物料名称');
    await expect(page.getByTestId('runtime-record-inspector-field-material_code')).toContainText('物料编码');
    await testInfo.attach('b3-record-inspector-preview', { body: await page.screenshot(), contentType: 'image/png' });
    await enterEditMode(page);

    const block = findBlockById((await readPage(page, pid)).blocks, RECORD_INSPECTOR);
    expect(block).toMatchObject({ blockType: 'record-inspector', context, fields });
  });

  test('B4: candidate-list — dataSource + item + selection persist at the block top level and preview shows item config', async ({
    page,
  }, testInfo) => {
    const dataSource = `ds_candidates_${uid}`;
    const item = {
      titleField: 'material_code',
      subtitleField: 'material_name',
      scoreField: 'match_score',
      detailFields: [{ key: 'score', field: 'match_score', label: { 'en-US': 'Score', 'zh-CN': '匹配分' } }],
    };
    const selection = { bind: 'selectedCandidate' };

    await openDesigner(page, pid);
    await selectBlock(page, CANDIDATE_LIST);
    await fillTextField(page, 'dataSource', dataSource);
    await applyJsonField(page, 'item', item);
    await applyJsonField(page, 'selection', selection);
    await saveDesigner(page, pid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 30_000 });
    await selectBlock(page, CANDIDATE_LIST);
    await expect(page.getByTestId('inspector-field-dataSource')).toHaveValue(dataSource);
    await expect(page.getByTestId('inspector-field-item')).toContainText('match_score');
    await expect(page.getByTestId('inspector-field-selection')).toContainText('selectedCandidate');

    await enterPreviewMode(page);
    await expect(page.getByTestId(`runtime-candidate-list-sample-${CANDIDATE_LIST}`)).toContainText('material_code');
    await expect(page.getByTestId('runtime-candidate-list-field-score')).toContainText('匹配分');
    await testInfo.attach('b4-candidate-list-preview', { body: await page.screenshot(), contentType: 'image/png' });
    await enterEditMode(page);

    const block = findBlockById((await readPage(page, pid)).blocks, CANDIDATE_LIST);
    expect(block).toMatchObject({ blockType: 'candidate-list', dataSource, item, selection });
  });

  test('B5: artifact-timeline — dataSource + item field bindings persist at the block top level and preview shows the bound fields', async ({
    page,
  }, testInfo) => {
    const dataSource = `ds_artifacts_${uid}`;
    const item = {
      keyField: 'pid',
      titleField: 'bom_er_filename',
      revisionField: 'bom_er_revision_no',
      statusField: 'bom_er_status',
      fileIdField: 'bom_er_file_id',
    };

    await openDesigner(page, pid);
    await selectBlock(page, ARTIFACT_TIMELINE);
    await fillTextField(page, 'dataSource', dataSource);
    await applyJsonField(page, 'item', item);
    await saveDesigner(page, pid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 30_000 });
    await selectBlock(page, ARTIFACT_TIMELINE);
    await expect(page.getByTestId('inspector-field-dataSource')).toHaveValue(dataSource);
    await expect(page.getByTestId('inspector-field-item')).toContainText('bom_er_filename');

    await enterPreviewMode(page);
    await expect(page.getByTestId(`runtime-artifact-timeline-title-${ARTIFACT_TIMELINE}`)).toContainText(
      'bom_er_filename',
    );
    await expect(page.getByTestId(`runtime-artifact-timeline-download-${ARTIFACT_TIMELINE}`)).toContainText(
      'bom_er_file_id',
    );
    await testInfo.attach('b5-artifact-timeline-preview', { body: await page.screenshot(), contentType: 'image/png' });
    await enterEditMode(page);

    const block = findBlockById((await readPage(page, pid)).blocks, ARTIFACT_TIMELINE);
    expect(block).toMatchObject({ blockType: 'artifact-timeline', dataSource, item });
  });

  test('B6: review-drawer — context + summaryBadges + compare + candidates persist at the block top level and preview shows a representative drawer', async ({
    page,
  }, testInfo) => {
    const context = '${state.selectedRow}';
    const contextDataSource = `ds_rows_${uid}`;
    const summaryBadges = [
      { key: 'status', label: { 'en-US': 'Status', 'zh-CN': '状态' }, valueField: 'status', tone: 'blue' },
    ];
    const compare = {
      rawTitle: { 'en-US': 'Raw', 'zh-CN': '原始' },
      canonicalTitle: { 'en-US': 'Canonical', 'zh-CN': '标准' },
      rawFields: [{ key: 'src', label: 'Source', field: 'source' }],
      canonicalFields: [{ key: 'code', label: 'Code', field: 'std_code' }],
    };
    const candidates = {
      dataSource: `ds_candidates_${uid}`,
      selection: { bind: 'selectedDrawerCandidate' },
      item: { titleField: 'material_code' },
      actions: [{ code: 'confirm', label: { 'en-US': 'Confirm', 'zh-CN': '确认' }, variant: 'primary' }],
    };

    await openDesigner(page, pid);
    await selectBlock(page, REVIEW_DRAWER);
    await fillTextField(page, 'context', context);
    await fillTextField(page, 'contextDataSource', contextDataSource);
    await applyJsonField(page, 'summaryBadges', summaryBadges);
    await applyJsonField(page, 'compare', compare);
    await applyJsonField(page, 'candidates', candidates);
    await saveDesigner(page, pid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 30_000 });
    await selectBlock(page, REVIEW_DRAWER);
    await expect(page.getByTestId('inspector-field-context')).toHaveValue(context);
    await expect(page.getByTestId('inspector-field-summaryBadges')).toContainText('status');
    await expect(page.getByTestId('inspector-field-compare')).toContainText('canonicalFields');
    await expect(page.getByTestId('inspector-field-candidates')).toContainText('selectedDrawerCandidate');

    await enterPreviewMode(page);
    await expect(page.getByTestId(`runtime-review-drawer-sample-${REVIEW_DRAWER}`)).toBeVisible();
    await expect(page.getByTestId('runtime-review-drawer-badge-status')).toBeVisible();
    await expect(page.getByTestId(`runtime-review-drawer-compare-${REVIEW_DRAWER}`)).toContainText(
      'Raw 1 / Canonical 1',
    );
    await testInfo.attach('b6-review-drawer-preview', { body: await page.screenshot(), contentType: 'image/png' });
    await enterEditMode(page);

    const block = findBlockById((await readPage(page, pid)).blocks, REVIEW_DRAWER);
    expect(block).toMatchObject({ blockType: 'review-drawer', context, contextDataSource, summaryBadges, compare, candidates });
  });

  test('B7 (sad path): invalid actions JSON on workbench-action-bar shows a per-field error and is NOT written back', async ({
    page,
  }, testInfo) => {
    await openDesigner(page, pid);
    await selectBlock(page, ACTION_BAR);

    const before = findBlockById((await readPage(page, pid)).blocks, ACTION_BAR)?.actions;
    expect(Array.isArray(before), 'B1 actions present before sad path').toBeTruthy();

    const actionsField = page.getByTestId('inspector-field-actions');
    await expect(actionsField).toBeVisible({ timeout: 5_000 });
    await actionsField.fill('[ { code: confirm, ');
    await page.evaluate(
      () => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))),
    );
    await page.getByTestId('inspector-json-field-apply-actions').click();
    await expect(page.getByTestId('inspector-json-field-error-actions')).toBeVisible({ timeout: 5_000 });
    await testInfo.attach('b7-invalid-actions-json-error', { body: await page.screenshot(), contentType: 'image/png' });

    const after = findBlockById((await readPage(page, pid)).blocks, ACTION_BAR)?.actions;
    expect(after).toEqual(before);
  });

  test('L1 (live render): published custom page renders the real platform workbench-family blocks with bound data + interactive state', async ({
    page,
  }, testInfo) => {
    // Mirrors workbench-blocks-runtime.spec.ts: a kind:'list' custom page with
    // static data sources; navigate to /p/c/<pageKey> and assert the REAL platform
    // renderers (not the designer preview) render real values and the candidate ->
    // record-inspector / evidence-panel state binding works end to end.
    const id = uniqueId('pdwb2_live');
    const pageKey = id.replace(/-/g, '_');

    const dataSources = {
      ds_candidates: {
        type: 'static',
        adaptor: 'records',
        data: [
          {
            pid: 'C-100',
            title: 'Copper audit line',
            status: 'Pending review',
            score: 94,
            evidence: { source: 'supplier-import', conflict: 'Unit price differs from contract' },
          },
        ],
      },
      ds_artifacts: {
        type: 'static',
        adaptor: 'records',
        data: [
          {
            pid: 'export-100',
            filename: 'workbench-audit.xlsx',
            revision: 3,
            status: 'generated',
            fileId: 'file-workbench-100',
          },
        ],
      },
    };

    const blocks = [
      {
        id: 'live_actions',
        blockType: 'workbench-action-bar',
        surface: 'card',
        align: 'start',
        actions: [{ code: 'mark_reviewed', label: 'Mark reviewed', variant: 'secondary', onClick: { action: 'state.set', args: { reviewMode: 'reviewed' } } }],
      },
      {
        id: 'live_candidates',
        blockType: 'candidate-list',
        dataSource: 'ds_candidates',
        selection: { bind: 'selectedRow' },
        item: {
          titleField: 'title',
          scoreField: 'score',
          detailFields: [{ key: 'status', label: 'Status', field: 'status' }],
        },
      },
      {
        id: 'live_record',
        blockType: 'record-inspector',
        context: '${state.selectedRow}',
        empty: { title: 'Select a workbench record' },
        fields: [
          { field: 'title', label: 'Selected title', span: 2 },
          { field: 'status', label: 'Selected status' },
          { field: 'evidence.source', label: 'Selected evidence source' },
        ],
      },
      {
        id: 'live_evidence',
        blockType: 'evidence-panel',
        title: 'Runtime evidence',
        context: '${state.selectedRow.evidence}',
        empty: { title: 'Select evidence' },
        sections: [
          { key: 'source', label: 'Evidence source', field: 'source' },
          { key: 'conflict', label: 'Conflict', field: 'conflict' },
        ],
      },
      {
        id: 'live_artifacts',
        blockType: 'artifact-timeline',
        title: 'Runtime artifacts',
        dataSource: 'ds_artifacts',
        item: {
          keyField: 'pid',
          titleField: 'filename',
          revisionField: 'revision',
          statusField: 'status',
          fileIdField: 'fileId',
        },
      },
      {
        id: 'live_review',
        blockType: 'review-drawer',
        context: '${state.selectedRow}',
        empty: { title: 'Select a row' },
        titleTemplate: 'Review ${record.pid} · ${record.status}',
        summaryBadges: [{ key: 'score', label: 'Score', valueField: 'score', unit: '%', tone: 'blue' }],
        compare: {
          rawTitle: 'Raw evidence',
          canonicalTitle: 'Selected record',
          rawFields: [{ key: 'source', label: 'Source', field: 'evidence.source' }],
          canonicalFields: [{ key: 'title', label: 'Title', field: 'title' }],
        },
      },
    ];

    const createResp = await page.request.post('/api/pages', {
      data: {
        name: `Workbench batch2 live ${id}`,
        pageKey,
        title: `Workbench batch2 live ${id}`,
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

    // Real platform renderers render bound values.
    await expect(page.getByTestId('workbench-action-bar')).toBeVisible();
    await expect(page.getByTestId('workbench-action-mark_reviewed')).toBeVisible();
    await expect(page.getByTestId('candidate-list')).toContainText('Copper audit line');
    await expect(page.getByTestId('candidate-list-item-C-100-field-status')).toContainText('Pending review');
    await expect(page.getByTestId('artifact-timeline')).toContainText('workbench-audit.xlsx');
    await expect(page.getByTestId('artifact-timeline-item-export-100')).toContainText('Rev 3');
    await expect(page.getByTestId('artifact-timeline-download-export-100')).toHaveAttribute(
      'href',
      '/api/file/download/file-workbench-100',
    );

    // Empty states before selection (real renderers, not the designer preview).
    await expect(page.getByTestId('record-inspector-empty')).toContainText('Select a workbench record');
    await expect(page.getByTestId('evidence-panel-empty')).toContainText('Select evidence');
    await expect(page.getByTestId('review-drawer-empty')).toContainText('Select a row');

    // Select a candidate → record-inspector + evidence-panel + review-drawer
    // populate via real runtime state binding (state.selectedRow).
    await page.getByTestId('candidate-list-item-C-100').click();
    await expect(page.getByTestId('record-inspector')).toContainText('Copper audit line');
    await expect(page.getByTestId('record-inspector')).toContainText('Pending review');
    await expect(page.getByTestId('evidence-panel')).toContainText('Unit price differs from contract');
    await expect(page.getByTestId('review-drawer')).toContainText('Review C-100 · Pending review');
    await expect(page.getByTestId('review-drawer-badge-score')).toContainText('94%');

    await testInfo.attach('l1-live-render', { body: await page.screenshot(), contentType: 'image/png' });
  });
});
