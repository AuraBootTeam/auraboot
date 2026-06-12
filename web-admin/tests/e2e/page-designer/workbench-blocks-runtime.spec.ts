import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers';

const WORKBENCH_BLOCK_TYPES = [
  'metric-strip',
  'record-inspector',
  'candidate-list',
  'workbench-action-bar',
  'evidence-panel',
  'artifact-timeline',
  'review-drawer',
] as const;

type WorkbenchBlockType = (typeof WORKBENCH_BLOCK_TYPES)[number];

function buildWorkbenchDataSources() {
  const candidateData = [
    {
      pid: 'C-100',
      title: 'Copper audit line',
      subtitle: 'Imported from supplier workbook',
      status: 'Pending review',
      score: 94,
      evidence: {
        source: 'supplier-import',
        conflict: 'Unit price differs from contract',
        raw: {
          workbook: 'supplier-audit.xlsx',
          sheet: 'Lines',
        },
      },
    },
    {
      pid: 'C-200',
      title: 'Aluminum audit line',
      subtitle: 'Matched by SKU and batch',
      status: 'Ready',
      score: 88,
      evidence: {
        source: 'system-match',
        conflict: 'None',
        raw: {
          workbook: 'supplier-audit.xlsx',
          sheet: 'Accepted',
        },
      },
    },
  ];

  return {
    ds_metrics: {
      type: 'static',
      adaptor: 'records',
      data: [
        {
          pendingCount: 2,
          readyCount: 1,
          pendingText: 'requires analyst review',
        },
      ],
    },
    ds_candidates: {
      type: 'static',
      adaptor: 'records',
      data: candidateData,
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
          hash: 'abcdef1234567890',
          fileId: 'file-workbench-100',
        },
      ],
    },
  };
}

function buildWorkbenchBlocks() {
  return [
    {
      id: 'wb_metrics',
      blockType: 'metric-strip',
      title: 'Runtime metrics',
      dataSource: 'ds_metrics',
      metrics: [
        {
          key: 'pending',
          label: 'Pending',
          valueField: 'pendingCount',
          subTextField: 'pendingText',
          tone: 'amber',
          onClick: {
            action: 'state.set',
            args: { reviewMode: 'pending' },
          },
        },
        {
          key: 'ready',
          label: 'Ready',
          valueField: 'readyCount',
          tone: 'green',
        },
      ],
    },
    {
      id: 'wb_actions',
      blockType: 'workbench-action-bar',
      surface: 'card',
      align: 'start',
      actions: [
        {
          code: 'mark_reviewed',
          label: 'Mark reviewed',
          variant: 'secondary',
          onClick: {
            action: 'state.set',
            args: { reviewMode: 'reviewed' },
          },
        },
        {
          code: 'pending_only',
          label: 'Pending mode active',
          variant: 'primary',
          visibleWhen: "state.reviewMode === 'pending'",
          onClick: {
            action: 'state.set',
            args: { reviewMode: 'pending-confirmed' },
          },
        },
        {
          code: 'reviewed_only',
          label: 'Reviewed mode active',
          variant: 'primary',
          visibleWhen: "state.reviewMode === 'reviewed'",
          onClick: {
            action: 'state.set',
            args: { reviewMode: 'reviewed-confirmed' },
          },
        },
      ],
    },
    {
      id: 'wb_candidates',
      blockType: 'candidate-list',
      dataSource: 'ds_candidates',
      selection: { bind: 'selectedWorkbenchRecord' },
      item: {
        titleField: 'title',
        subtitleField: 'subtitle',
        scoreField: 'score',
        detailFields: [
          { key: 'status', label: 'Status', field: 'status' },
          { key: 'source', label: 'Evidence source', sourceField: 'evidence', field: 'source' },
        ],
      },
    },
    {
      id: 'wb_record',
      blockType: 'record-inspector',
      context: '${state.selectedWorkbenchRecord}',
      empty: { title: 'Select a workbench record' },
      fields: [
        { field: 'title', label: 'Selected title', span: 2 },
        { field: 'status', label: 'Selected status' },
        { field: 'evidence.source', label: 'Selected evidence source' },
      ],
    },
    {
      id: 'wb_evidence',
      blockType: 'evidence-panel',
      title: 'Runtime evidence',
      context: '${state.selectedWorkbenchRecord.evidence}',
      empty: { title: 'Select evidence' },
      sections: [
        { key: 'source', label: 'Evidence source', field: 'source' },
        { key: 'conflict', label: 'Conflict', field: 'conflict' },
        { key: 'raw', label: 'Raw payload', field: 'raw', format: 'json' },
      ],
    },
    {
      id: 'wb_artifacts',
      blockType: 'artifact-timeline',
      title: 'Runtime artifacts',
      dataSource: 'ds_artifacts',
      item: {
        keyField: 'pid',
        titleField: 'filename',
        subtitleField: 'status',
        revisionField: 'revision',
        statusField: 'status',
        hashField: 'hash',
        fileIdField: 'fileId',
      },
    },
    {
      id: 'wb_review',
      blockType: 'review-drawer',
      context: '${state.selectedWorkbenchRecord}',
      empty: { title: 'Select a row' },
      titleTemplate: 'Review ${record.pid} · ${record.status}',
      summaryBadges: [
        { key: 'score', label: 'Score', valueField: 'score', unit: '%', tone: 'blue' },
        { key: 'source', label: 'Source', valueField: 'evidence.source', tone: 'purple' },
      ],
      compare: {
        rawTitle: 'Raw evidence',
        canonicalTitle: 'Selected record',
        rawFields: [
          { key: 'source', label: 'Source', field: 'evidence.source' },
          { key: 'conflict', label: 'Conflict', field: 'evidence.conflict' },
        ],
        canonicalFields: [
          { key: 'title', label: 'Title', field: 'title' },
          { key: 'status', label: 'Status', field: 'status' },
        ],
      },
      candidates: {
        dataSource: 'ds_candidates',
        selection: { bind: 'selectedDrawerCandidate' },
        item: {
          titleField: 'title',
          scoreField: 'score',
          detailFields: [
            { key: 'status', label: 'Status', field: 'status' },
            { key: 'source', label: 'Source', sourceField: 'evidence', field: 'source' },
          ],
        },
      },
      exportImpact: {
        dataSource: 'ds_artifacts',
        fields: [{ key: 'dirty', label: 'Export status', value: 'Regenerate after review' }],
      },
    },
  ];
}

async function createPublishedWorkbenchPage(page: import('@playwright/test').Page) {
  const id = uniqueId('workbench_runtime');
  const pageKey = id.replace(/-/g, '_');
  const blocks = buildWorkbenchBlocks();
  const dataSources = buildWorkbenchDataSources();
  const pagePayload = {
    name: `Workbench runtime ${id}`,
    pageKey,
    title: `Workbench runtime ${id}`,
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
  };

  const createResp = await page.request.post('/api/pages', { data: pagePayload });
  expect(createResp.ok(), `Create workbench page failed: ${createResp.status()}`).toBeTruthy();
  const createBody = await createResp.json();
  expect(createBody.code, 'create page API code').toBe('0');
  const pid = String(createBody.data?.pid || '');
  expect(pid, 'created page pid').toBeTruthy();

  const publishResp = await page.request.post(`/api/pages/${pid}/publish`);
  expect(publishResp.ok(), `Publish workbench page failed: ${publishResp.status()}`).toBeTruthy();
  const publishBody = await publishResp.json();
  expect(publishBody.code, 'publish page API code').toBe('0');
  expect(publishBody.data?.status, 'published page status').toBe('published');

  return { pid, pageKey };
}

test.describe('Page Designer workbench block runtime', () => {
  test('persists and renders all workbench blocks in a real dynamic custom page', async ({
    page,
  }) => {
    const { pageKey } = await createPublishedWorkbenchPage(page);

    const readbackResp = await page.request.get(`/api/pages/key/${pageKey}`);
    expect(readbackResp.ok(), `Readback workbench page failed: ${readbackResp.status()}`).toBeTruthy();
    const readback = await readbackResp.json();
    expect(readback.code, 'readback API code').toBe('0');
    expect(readback.data?.status, 'readback page status').toBe('published');
    expect(readback.data?.extension?.customOnly, 'custom-only runtime flag').toBe(true);
    expect(readback.data?.extension?.skipListData, 'skip list data runtime flag').toBe(true);
    const persistedTypes = (readback.data?.blocks || []).map((block: any) => block.blockType);
    expect(persistedTypes, 'all workbench block types persisted').toEqual(
      expect.arrayContaining([...WORKBENCH_BLOCK_TYPES]),
    );
    expect(new Set(persistedTypes).size, 'no duplicate block type in fixture').toBe(
      WORKBENCH_BLOCK_TYPES.length,
    );
    expect(Object.keys(readback.data?.dataSources || {}).sort(), 'workbench data sources persisted').toEqual(
      ['ds_artifacts', 'ds_candidates', 'ds_metrics'],
    );

    await page.goto(`/p/c/${pageKey}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('dynamic-list')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId('list-misc-blocks')).toBeVisible();

    await expect(page.getByTestId('metric-strip-wb_metrics')).toContainText('Runtime metrics');
    await expect(page.getByTestId('metric-strip-item-pending')).toContainText('2');
    await expect(page.getByTestId('metric-strip-item-pending')).toContainText(
      'requires analyst review',
    );
    await expect(page.getByTestId('metric-strip-item-ready')).toContainText('1');

    await expect(page.getByTestId('workbench-action-bar')).toBeVisible();
    await expect(page.getByTestId('workbench-action-pending_only')).toHaveCount(0);
    await page.getByTestId('metric-strip-item-pending').click();
    await expect(page.getByTestId('workbench-action-pending_only')).toBeVisible();
    await page.getByTestId('workbench-action-mark_reviewed').click();
    await expect(page.getByTestId('workbench-action-reviewed_only')).toBeVisible();

    await expect(page.getByTestId('candidate-list')).toContainText('Copper audit line');
    await expect(page.getByTestId('candidate-list-item-C-100-field-status')).toContainText(
      'Pending review',
    );
    await expect(page.getByTestId('record-inspector-empty')).toContainText(
      'Select a workbench record',
    );
    await expect(page.getByTestId('evidence-panel-empty')).toContainText('Select evidence');
    await expect(page.getByTestId('review-drawer-empty')).toContainText('Select a row');

    await expect(page.getByTestId('artifact-timeline')).toContainText('workbench-audit.xlsx');
    await expect(page.getByTestId('artifact-timeline-item-export-100')).toContainText('Rev 3');
    await expect(page.getByTestId('artifact-timeline-download-export-100')).toHaveAttribute(
      'href',
      '/api/file/download/file-workbench-100',
    );

    await page.getByTestId('candidate-list-item-C-100').click();
    await expect(page.getByTestId('record-inspector')).toContainText('Copper audit line');
    await expect(page.getByTestId('record-inspector')).toContainText('Pending review');
    await expect(page.getByTestId('record-inspector')).toContainText('supplier-import');

    await expect(page.getByTestId('evidence-panel')).toContainText('Unit price differs from contract');
    await expect(page.getByTestId('evidence-panel-section-raw')).toContainText(
      'supplier-audit.xlsx',
    );

    await expect(page.getByTestId('review-drawer')).toContainText('Review C-100 · Pending review');
    await expect(page.getByTestId('review-drawer-badge-score')).toContainText('94%');
    await expect(page.getByTestId('review-drawer-tab-compare')).toContainText('Raw evidence');
    await expect(page.getByTestId('review-drawer-tab-candidates')).toContainText(
      'Copper audit line',
    );

    const renderedTypes = await page.getByTestId('list-misc-blocks').evaluate((root) =>
      Array.from(root.querySelectorAll('[class*="block-"]'))
        .map((node) =>
          Array.from(node.classList).find((className) => className.startsWith('block-')),
        )
        .filter(Boolean)
        .map((className) => String(className).replace(/^block-/, '')),
    );
    expect(renderedTypes.sort(), 'all workbench block renderers mounted').toEqual(
      [...WORKBENCH_BLOCK_TYPES].sort(),
    );
  });
});
