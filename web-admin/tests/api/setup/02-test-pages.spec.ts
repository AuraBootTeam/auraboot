/**
 * Setup Phase 2 — Test pages + system_overview dashboard.
 *
 * Creates the 3 page-designer fixture pages
 *   - e2e_test_dashboard (kind=list, stat-card + table)
 *   - e2e_test_form     (kind=form, form-section + form-buttons)
 *   - e2e_test_list     (kind=list, toolbar + filters + table + rowActions)
 * plus the system_overview dashboard so the home redirect lands on real
 * NumberCards instead of an empty placeholder.
 *
 * Replaces oss-reset-and-init.sh §6a + §6b. Idempotent: skip-if-exists.
 */

import { expect, test } from '@playwright/test';
import { authHeaders, loginAdmin } from './_helpers';

const TEST_PAGES = [
  {
    pageKey: 'e2e_test_dashboard',
    name: 'E2E Test Dashboard',
    title: 'E2E Test Dashboard',
    modelCode: 'page_schema',
    description: 'Overview-style list fixture for Page Designer E2E tests',
    kind: 'list',
    layout: { type: 'grid', cols: 12 },
    blocks: [
      {
        id: 'block_overview_stats',
        blockType: 'stat-card',
        layout: { colSpan: 12, rowSpan: 1 },
        title: 'Overview',
        cards: [
          { label: 'Total', value: '1234' },
          { label: 'Today', value: '56' },
        ],
      },
      {
        id: 'block_overview_table',
        blockType: 'table',
        layout: { colSpan: 12, rowSpan: 1 },
        columns: [
          { field: 'name', title: 'Name', width: 200 },
          { field: 'page_key', title: 'Page Key', width: 220 },
          { field: 'status', title: 'Status', width: 120 },
          { field: 'updated_at', title: 'Updated At', width: 180 },
        ],
      },
    ],
  },
  {
    pageKey: 'e2e_test_form',
    name: 'E2E Test Form',
    title: 'E2E Test Form',
    modelCode: 'page_schema',
    description: 'Form fixture for Page Designer E2E tests',
    kind: 'form',
    layout: { type: 'grid', cols: 12, gap: 12 },
    blocks: [
      {
        id: 'block_form_main',
        blockType: 'form-section',
        title: 'Basic Information',
        layout: { colSpan: 12, rowSpan: 1 },
        columns: 2,
        fields: [
          { field: 'name', layout: { colSpan: 6, rowSpan: 1 } },
          { field: 'page_key', layout: { colSpan: 6, rowSpan: 1 } },
          { field: 'kind', layout: { colSpan: 4, rowSpan: 1 } },
          { field: 'profile', layout: { colSpan: 4, rowSpan: 1 } },
          { field: 'model_code', layout: { colSpan: 4, rowSpan: 1 } },
          { field: 'description', layout: { colSpan: 12, rowSpan: 1 } },
        ],
      },
      {
        id: 'block_form_actions',
        blockType: 'form-buttons',
        layout: { colSpan: 12, rowSpan: 1 },
        buttons: [
          { code: 'save', primary: true, label: 'save' },
          { code: 'reset', label: 'reset' },
        ],
      },
    ],
  },
  {
    pageKey: 'e2e_test_list',
    name: 'E2E Test List',
    title: 'E2E Test List',
    modelCode: 'page_schema',
    description: 'List fixture for Page Designer E2E tests',
    kind: 'list',
    layout: { type: 'stack' },
    blocks: [
      {
        id: 'block_list_toolbar',
        blockType: 'toolbar',
        buttons: [
          { code: 'create', variant: 'primary', label: 'create' },
          { code: 'refresh', label: 'refresh' },
        ],
      },
      {
        id: 'block_list_filters',
        blockType: 'filters',
        fields: [{ field: 'name' }, { field: 'status' }],
      },
      {
        id: 'block_list_table',
        blockType: 'table',
        columns: [
          { field: 'name', title: 'Name', width: 200 },
          { field: 'page_key', title: 'Page Key', width: 220 },
          { field: 'status', title: 'Status', width: 120 },
          { field: 'updated_at', title: 'Updated At', width: 180 },
        ],
        rowActions: [
          { code: 'view', label: 'view' },
          { code: 'edit', label: 'edit' },
          { code: 'delete', label: 'delete' },
        ],
      },
    ],
  },
];

const SYSTEM_OVERVIEW = {
  code: 'system_overview',
  title: 'System Overview',
  description: 'Live overview dashboard seeded for local development',
  scope: 'global',
  isDefault: true,
  layoutConfig: { columns: 12, rowHeight: 100, gap: 16 },
  widgets: [
    {
      i: 'w_pages',
      x: 0,
      y: 0,
      w: 3,
      h: 2,
      type: 'NumberCard',
      title: 'Pages',
      config: {
        title: 'Pages',
        label: 'Pages',
        color: '#2563EB',
        dataSource: {
          type: 'aggregate',
          modelCode: 'page_schema',
          metrics: [{ field: 'id', aggregation: 'count', alias: 'count' }],
        },
      },
    },
    {
      i: 'w_domain_configs',
      x: 3,
      y: 0,
      w: 3,
      h: 2,
      type: 'NumberCard',
      title: 'BPM Domains',
      config: {
        title: 'BPM Domains',
        label: 'BPM Domains',
        color: '#10B981',
        dataSource: {
          type: 'aggregate',
          modelCode: 'bpm_domain_config',
          metrics: [{ field: 'id', aggregation: 'count', alias: 'count' }],
        },
      },
    },
    {
      i: 'w_members',
      x: 6,
      y: 0,
      w: 3,
      h: 2,
      type: 'NumberCard',
      title: 'Members',
      config: {
        title: 'Members',
        label: 'Members',
        color: '#F59E0B',
        dataSource: {
          type: 'aggregate',
          modelCode: 'tenant_member',
          metrics: [{ field: 'id', aggregation: 'count', alias: 'count' }],
        },
      },
    },
    {
      i: 'w_sla_configs',
      x: 9,
      y: 0,
      w: 3,
      h: 2,
      type: 'NumberCard',
      title: 'SLA Rules',
      config: {
        title: 'SLA Rules',
        label: 'SLA Rules',
        color: '#8B5CF6',
        dataSource: {
          type: 'aggregate',
          modelCode: 'sla_config',
          metrics: [{ field: 'id', aggregation: 'count', alias: 'count' }],
        },
      },
    },
  ],
};

test.describe.configure({ mode: 'serial' });

test('02-test-pages: system_overview widget data sources are queryable', async ({
  request,
}) => {
  const jwt = await loginAdmin(request);
  const headers = authHeaders(jwt);

  for (const widget of SYSTEM_OVERVIEW.widgets) {
    const dataSource = widget.config.dataSource;
    const response = await request.post('/api/meta/chart-data', {
      headers,
      data: dataSource,
    });
    const body = await response.json().catch(() => null);
    const failure = `${widget.title} chart-data failed: ${response.status()} ${JSON.stringify(body)}`;

    expect(response.ok(), failure).toBeTruthy();
    expect(body?.code, failure).toBe('0');
  }
});

test('02-test-pages: create + publish 3 fixture pages', async ({ request }) => {
  const jwt = await loginAdmin(request);
  const headers = authHeaders(jwt);

  for (const payload of TEST_PAGES) {
    const existing = await request.get(`/api/pages/key/${payload.pageKey}`, { headers });
    if (existing.ok()) {
      const body = await existing.json().catch(() => null);
      if (body?.data?.pid) {
        // Already created in a previous run — leave it.
        continue;
      }
    }
    const created = await request.post('/api/pages', { headers, data: payload });
    if (!created.ok()) continue; // tolerate transient create races; downstream specs will surface real gaps
    const body = await created.json().catch(() => null);
    const pid = body?.data?.pid;
    if (pid) {
      await request.post(`/api/pages/${pid}/publish`, { headers, data: {} });
    }
  }
});

test('02-test-pages: create + publish system_overview dashboard', async ({ request }) => {
  const jwt = await loginAdmin(request);
  const headers = authHeaders(jwt);

  // Idempotency: GET by code first.
  const probe = await request.get('/api/dashboards/code/system_overview', { headers });
  if (probe.ok()) {
    const body = await probe.json().catch(() => null);
    const pid = body?.data?.pid;
    if (pid) {
      // Update in place + re-publish so re-runs against an existing dashboard
      // pick up any payload changes.
      await request.put(`/api/dashboards/${pid}`, { headers, data: SYSTEM_OVERVIEW });
      await request.post(`/api/dashboards/${pid}/publish`, { headers, data: {} });
      return;
    }
  }
  const created = await request.post('/api/dashboards', { headers, data: SYSTEM_OVERVIEW });
  if (!created.ok()) return;
  const body = await created.json().catch(() => null);
  const pid = body?.data?.pid;
  if (pid) {
    await request.post(`/api/dashboards/${pid}/publish`, { headers, data: {} });
  }
});
