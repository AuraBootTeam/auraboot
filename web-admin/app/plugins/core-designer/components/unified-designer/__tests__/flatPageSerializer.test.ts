import { describe, expect, it } from 'vitest';
import {
  FLAT_PAGE_SCHEMA_VERSION,
  FlatSerializationError,
  serializePageTreeToFlat,
} from '../persistence/flatPageSerializer';
import { migratePageSchemaV2ToV3 } from '../migration/migrateToV3';
import { toStableBlockId } from '../utils/blockIds';
import type { LegacyDslBlockV2, LegacyPageSchemaV2 } from '../types';

/**
 * Fixtures mirror the two producers of stored v4 rows:
 * - `PageSchemaDefaultBlockGenerator` (model publish auto-create), and
 * - the plugin importer (`meta_models_admin`-shaped object field refs).
 * The round-trip contract is deep-equal identity EXCEPT that object action
 * entries gain a renderer-inert `actionType` stamp: the serializer must always
 * record the current actionType or a changed action would silently revert on
 * reload (normalizeActionType falls back to the stored `code`).
 */

function stripActionTypeStamps(blocks: LegacyDslBlockV2[]): LegacyDslBlockV2[] {
  const visit = (block: LegacyDslBlockV2): LegacyDslBlockV2 => {
    const next: Record<string, unknown> = { ...block };
    for (const key of ['buttons', 'actions', 'rowActions'] as const) {
      if (!Array.isArray(next[key])) continue;
      next[key] = (next[key] as Array<string | Record<string, unknown>>).map((entry) => {
        if (typeof entry !== 'object' || entry === null) return entry;
        const { actionType: _stamp, ...rest } = entry as Record<string, unknown>;
        return rest;
      });
    }
    if (Array.isArray(next.blocks)) {
      next.blocks = (next.blocks as LegacyDslBlockV2[]).map(visit);
    }
    return next as LegacyDslBlockV2;
  };
  return blocks.map(visit);
}

function roundTrip(kind: string, blocks: LegacyDslBlockV2[], modelCode?: string) {
  const stored: LegacyPageSchemaV2 = {
    schemaVersion: 4,
    kind,
    id: 'round_trip_page',
    pageKey: 'round_trip_page',
    modelCode,
    blocks,
  };
  const tree = migratePageSchemaV2ToV3(stored);
  const flat = serializePageTreeToFlat(tree);
  expect(flat.schemaVersion).toBe(FLAT_PAGE_SCHEMA_VERSION);
  expect(flat.kind).toBe(kind);
  expect(stripActionTypeStamps(flat.blocks)).toEqual(blocks);
}

describe('flatPageSerializer round-trip (generator-shaped v4 pages)', () => {
  it('carries the editor root block id through the flat document for identity stability', () => {
    const tree = migratePageSchemaV2ToV3({
      schemaVersion: 4,
      kind: 'list',
      id: 'customer_list',
      blocks: [{ id: 'table_default', blockType: 'table', columns: ['name'] }],
    });
    // migrate synthesizes the root; the serializer reports its id so the
    // repository can persist it in the page extension.
    const flat = serializePageTreeToFlat(tree);
    expect(flat.rootBlockId).toBe(toStableBlockId('list', 'customer_list'));
  });

  it('round-trips a table whose row actions persist in the rowActions array', () => {
    roundTrip('list', [
      {
        id: 'table_default',
        blockType: 'table',
        columns: ['name', 'actions'],
        rowActions: [
          {
            id: 'action_seed_row_open',
            code: 'command',
            label: 'Open',
            executionMode: 'live',
          },
        ],
        dataSource: 'tableData',
      },
    ]);
  });

  it('round-trips a generated list page (filters + toolbar + table)', () => {
    roundTrip('list', [
      {
        id: 'filters_default',
        blockType: 'filters',
        fields: ['wd_bal_employee', 'wd_bal_year'],
        actions: ['search', 'reset'],
      },
      {
        id: 'toolbar_default',
        blockType: 'toolbar',
        buttons: [
          {
            code: 'create',
            label: { 'en-US': 'Create', 'zh-CN': '新建' },
            primary: true,
            navigateTo: '/p/wd_leave_balance/new',
            permissionCode: 'wd.leave_balance.manage',
          },
        ],
      },
      {
        id: 'table_default',
        blockType: 'table',
        span: 12,
        columns: [
          { field: 'wd_bal_employee', width: 160 },
          { field: 'wd_bal_year', width: 100 },
          'actions',
        ],
        dataSource: 'tableData',
        props: { pageSize: 20, multiSelect: false, rowClickAction: 'detail' },
      },
    ], 'wd_leave_balance');
  });

  it('round-trips a generated form page (form-section + form-buttons)', () => {
    roundTrip('form', [
      {
        id: 'form_section_main',
        blockType: 'form-section',
        title: { 'zh-CN': '基本信息', 'en-US': 'Basic Info' },
        columns: 2,
        fields: ['name', 'phone', 'status'],
      },
      {
        id: 'form_buttons',
        blockType: 'form-buttons',
        buttons: [
          { code: 'submit', content: { 'zh-CN': '保存', 'en-US': 'Save' }, primary: true, action: { type: 'submit' } },
          { code: 'cancel', content: { 'zh-CN': '取消', 'en-US': 'Cancel' }, navigateTo: '/p/customer_list' },
        ],
      },
    ], 'customer');
  });

  it('round-trips a generated detail page (toolbar + detail-sections with collapse)', () => {
    roundTrip('detail', [
      {
        id: 'actions_top',
        blockType: 'toolbar',
        buttons: [
          { code: 'edit', content: { 'zh-CN': '编辑', 'en-US': 'Edit' }, navigateTo: '/p/customer_form/{pid}' },
        ],
      },
      {
        id: 'section_main',
        blockType: 'detail-section',
        title: { 'zh-CN': '基本信息', 'en-US': 'Basic Info' },
        columns: 2,
        fields: ['name', 'phone', 'status'],
      },
      {
        id: 'section_audit',
        blockType: 'detail-section',
        title: { 'zh-CN': '系统信息', 'en-US': 'System Info' },
        columns: 2,
        fields: ['createdBy', 'updatedBy'],
        collapsible: true,
        defaultCollapsed: true,
      },
    ], 'customer');
  });

  it('round-trips importer-shaped object field refs (component/dataSource/labels)', () => {
    roundTrip('list', [
      {
        id: 'model_filters',
        blockType: 'filters',
        fields: [
          {
            field: 'sourceType',
            label: { 'en-US': 'Data Source', 'zh-CN': '数据来源' },
            props: { placeholder: { 'en-US': 'All Sources', 'zh-CN': '全部来源' } },
            component: 'SmartSelect',
            dataSource: {
              type: 'static',
              data: [
                { label: { 'en-US': 'Physical Table', 'zh-CN': '物理表' }, value: 'physical' },
                { label: 'NamedQuery', value: 'namedQuery' },
              ],
              labelField: 'label',
              valueField: 'value',
            },
          },
        ],
        buttons: [
          { code: 'reset', content: { 'en-US': 'Reset', 'zh-CN': '重置' } },
          { code: 'search', content: { 'en-US': 'Search', 'zh-CN': '查询' }, primary: true },
        ],
      },
      {
        id: 'model_table',
        blockType: 'table',
        span: 12,
        columns: [
          { field: 'code', label: { 'en-US': 'Model Code', 'zh-CN': '模型编码' }, width: 220, sortable: true },
          { field: 'displayName', label: { 'en-US': 'Display Name', 'zh-CN': '显示名' }, width: 200 },
        ],
      },
    ], 'meta_model');
  });

  it('expands pipe-shorthand field refs into object refs (render-equivalent round-trip)', () => {
    // Pipe shorthand ("field|span:6") is a stored-v4 only encoding; the editor
    // tree has no representation for it, so the round-trip normalizes it to the
    // object ref form the renderer accepts equally.
    const stored: LegacyPageSchemaV2 = {
      schemaVersion: 4,
      kind: 'list',
      id: 'filters_mixed',
      blocks: [
        {
          id: 'filters_mixed',
          blockType: 'filters',
          fields: ['status', 'created_at|span:6'],
          actions: ['search'],
        },
      ],
    };
    const tree = migratePageSchemaV2ToV3(stored);
    const flat = serializePageTreeToFlat(tree);
    expect(flat.blocks).toEqual([
      {
        id: 'filters_mixed',
        blockType: 'filters',
        fields: ['status', { field: 'created_at', span: 6 }],
        actions: ['search'],
      },
    ]);
  });
});

describe('flatPageSerializer designer-authored trees', () => {
  it('serializes an authored list tree without a synthetic kind root wrapper', () => {
    const flat = serializePageTreeToFlat({
      schemaVersion: 3,
      kind: 'list',
      id: 'my_list',
      modelCode: 'customer',
      blocks: [
        {
          id: 'list_root',
          blockType: 'list',
          dataSource: { model: 'customer' },
          blocks: [
            {
              id: 'list_filters',
              blockType: 'filter-bar',
              region: 'filters',
              props: { actions: ['search', 'reset'] },
              blocks: [
                { id: 'filter_status', blockType: 'filter-field', field: 'status', props: { component: 'select' } },
                { id: 'filter_name', blockType: 'filter-field', field: 'name' },
              ],
            },
            {
              id: 'list_toolbar',
              blockType: 'action-bar',
              region: 'toolbar',
              blocks: [
                { id: 'action_create', blockType: 'action', actionType: 'create', props: { label: 'Create' } },
                {
                  id: 'action_export',
                  blockType: 'action',
                  actionType: 'command',
                  props: { label: 'Export', command: 'customer.export' },
                },
              ],
            },
            {
              id: 'table_customers',
              blockType: 'table',
              props: { pageSize: 20 },
              dataSource: { ref: 'tableData' },
              blocks: [
                { id: 'column_name', blockType: 'column', field: 'name', layout: { width: 220 }, props: { label: 'Name' } },
                { id: 'column_status', blockType: 'column', field: 'status' },
              ],
            },
          ],
        },
      ],
    });

    expect(flat.kind).toBe('list');
    expect(flat.blocks).toEqual([
      {
        id: 'list_filters',
        blockType: 'filters',
        fields: [
          { id: 'filter_status', field: 'status', component: 'select' },
          { field: 'name', id: 'filter_name' },
        ],
        actions: ['search', 'reset'],
      },
      {
        id: 'list_toolbar',
        blockType: 'toolbar',
        buttons: [
          { label: 'Create', code: 'create', actionType: 'create', id: 'action_create' },
          { label: 'Export', command: 'customer.export', code: 'command', actionType: 'command', id: 'action_export' },
        ],
      },
      {
        id: 'table_customers',
        blockType: 'table',
        columns: [
          { label: 'Name', field: 'name', id: 'column_name', width: 220 },
          { field: 'status', id: 'column_status' },
        ],
        dataSource: 'tableData',
        props: { pageSize: 20 },
      },
    ]);
  });

  it('moves row-action children of a table into the flat rowActions array', () => {
    const flat = serializePageTreeToFlat({
      schemaVersion: 3,
      kind: 'list',
      id: 'my_list',
      blocks: [
        {
          id: 'list_root',
          blockType: 'list',
          blocks: [
            {
              id: 'table_customers',
              blockType: 'table',
              blocks: [
                { id: 'column_name', blockType: 'column', field: 'name' },
                {
                  id: 'action_open_row',
                  blockType: 'action',
                  region: 'row-actions',
                  actionType: 'command',
                  props: { label: 'Open', command: 'customer.open' },
                },
              ],
            },
          ],
        },
      ],
    });

    expect(flat.blocks).toEqual([
      {
        id: 'table_customers',
        blockType: 'table',
        columns: [{ field: 'name', id: 'column_name' }],
        rowActions: [{ label: 'Open', command: 'customer.open', code: 'command', actionType: 'command', id: 'action_open_row' }],
      },
    ]);
  });

  it('serializes tabs containers with recovered tab keys and nested blocks', () => {
    const flat = serializePageTreeToFlat({
      schemaVersion: 3,
      kind: 'detail',
      id: 'my_detail',
      blocks: [
        {
          id: 'detail_tabs',
          blockType: 'tabs',
          blocks: [
            {
              // id shape produced by migrateTabRef: toStableBlockId(parentId, key)
              id: 'detail_tabs_overview',
              blockType: 'tab',
              title: { 'zh-CN': '概览', 'en-US': 'Overview' },
              blocks: [
                {
                  id: 'section_overview',
                  blockType: 'detail-section',
                  blocks: [{ id: 'field_name', blockType: 'field', field: 'name' }],
                },
              ],
            },
          ],
        },
      ],
    });

    expect(flat.blocks).toEqual([
      {
        id: 'detail_tabs',
        blockType: 'tabs',
        tabs: [
          {
            id: 'detail_tabs_overview',
            key: 'overview',
            label: { 'zh-CN': '概览', 'en-US': 'Overview' },
            blocks: [
              {
                id: 'section_overview',
                blockType: 'detail-section',
                fields: [{ id: 'field_name', field: 'name' }],
              },
            ],
          },
        ],
      },
    ]);
  });

  it('passes through detail-specialized blocks (activity-timeline, divider) with nested blocks', () => {
    const flat = serializePageTreeToFlat({
      schemaVersion: 3,
      kind: 'detail',
      id: 'my_detail',
      blocks: [
        {
          id: 'timeline',
          blockType: 'activity-timeline',
          props: { pageSize: 10 },
          blocks: [{ id: 'divider_1', blockType: 'divider' }],
        },
      ],
    });

    expect(flat.blocks).toEqual([
      {
        id: 'timeline',
        blockType: 'activity-timeline',
        props: { pageSize: 10 },
        blocks: [{ id: 'divider_1', blockType: 'divider' }],
      },
    ]);
  });
});

describe('flatPageSerializer fail-fast boundaries', () => {
  it('rejects composite/dashboard kinds — they have no flat runtime representation', () => {
    expect(() =>
      serializePageTreeToFlat({
        schemaVersion: 3,
        kind: 'composite',
        id: 'customer_workspace',
        blocks: [],
      }),
    ).toThrow(FlatSerializationError);
    expect(() =>
      serializePageTreeToFlat({
        schemaVersion: 3,
        kind: 'dashboard',
        id: 'dash',
        blocks: [],
      }),
    ).toThrow(FlatSerializationError);
  });

  it('rejects designer-only blocks with the offending block id', () => {
    expect(() =>
      serializePageTreeToFlat({
        schemaVersion: 3,
        kind: 'form',
        id: 'my_form',
        blocks: [
          {
            id: 'form_root',
            blockType: 'form',
            blocks: [
              { id: 'repeater_rows', blockType: 'repeater', props: { model: 'items' } },
            ],
          },
        ],
      }),
    ).toThrow(/repeater_rows/);
  });

  it('rejects container blocks nested inside a section (would be silently dropped)', () => {
    expect(() =>
      serializePageTreeToFlat({
        schemaVersion: 3,
        kind: 'form',
        id: 'my_form',
        blocks: [
          {
            id: 'form_root',
            blockType: 'form',
            blocks: [
              {
                id: 'section_basic',
                blockType: 'form-section',
                blocks: [
                  { id: 'sub_table_tasks', blockType: 'sub-table', props: { model: 'tasks' } },
                ],
              },
            ],
          },
        ],
      }),
    ).toThrow(/sub_table_tasks/);
  });

  it('rejects leaf blocks misplaced at the top level', () => {
    expect(() =>
      serializePageTreeToFlat({
        schemaVersion: 3,
        kind: 'list',
        id: 'my_list',
        blocks: [{ id: 'loose_field', blockType: 'field', field: 'name' }],
      }),
    ).toThrow(/loose_field/);
  });

  it('rejects widgets whose widgetType has no flat counterpart', () => {
    expect(() =>
      serializePageTreeToFlat({
        schemaVersion: 3,
        kind: 'list',
        id: 'my_list',
        blocks: [
          {
            id: 'list_root',
            blockType: 'list',
            blocks: [
              { id: 'widget_revenue', blockType: 'widget', widgetType: 'number-card', props: {} },
            ],
          },
        ],
      }),
    ).toThrow(/widget_revenue/);
  });

  it('maps chart/stat-card widgets to their flat block types', () => {
    const flat = serializePageTreeToFlat({
      schemaVersion: 3,
      kind: 'list',
      id: 'my_list',
      blocks: [
        {
          id: 'list_root',
          blockType: 'list',
          blocks: [
            { id: 'chart_sales', blockType: 'widget', widgetType: 'chart', props: { title: 'Sales' } },
          ],
        },
      ],
    });
    expect(flat.blocks).toEqual([
      { id: 'chart_sales', blockType: 'chart', props: { title: 'Sales' } },
    ]);
  });
});
