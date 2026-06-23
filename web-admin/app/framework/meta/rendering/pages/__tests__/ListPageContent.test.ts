import { renderHook } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import {
  buildListReferenceDisplayCacheKey,
  buildViewManageFieldOptions,
  collectListReferenceDisplayConfigs,
  getActiveSavedQuickFilterPresetKey,
  getSavedQuickFilterPresetKeys,
  findPersonalPresetSavedView,
  isPersonalPresetSavedViewEdited,
  resolveColumnCapabilityDataType,
  resolveFieldMetaDataType,
  resolveFieldMetaDisplayName,
  resolveListSavedViewPageKey,
  resolveListMiscBlocksPosition,
  resolveTableBlockRowActions,
  shouldSkipListData,
  shouldSkipModelFieldMeta,
  useRestoreSavedViewFromUrl,
} from '../ListPageContent';

describe('collectListReferenceDisplayConfigs', () => {
  it('uses model field refTarget metadata to resolve reference display labels', () => {
    const configs = collectListReferenceDisplayConfigs(
      [
        {
          field: 'bom_task_project_id',
          label: 'Project',
        },
      ],
      new Map([
        [
          'bom_task_project_id',
          {
            code: 'bom_task_project_id',
            dataType: 'reference',
            extension: {
              refTarget: {
                modelCode: 'req_requirement_set_pcba_bom',
                displayField: 'bom_project_name',
              },
            },
          },
        ],
      ]),
    );

    expect(configs).toEqual([
      {
        field: 'bom_task_project_id',
        modelCode: 'req_requirement_set_pcba_bom',
        valueField: 'pid',
        displayField: 'bom_project_name',
        displayKey: 'bom_task_project_id_display',
      },
    ]);
    expect(buildListReferenceDisplayCacheKey(configs[0])).toBe(
      'bom_task_project_id|req_requirement_set_pcba_bom|pid|bom_project_name',
    );
  });
});

describe('resolveListSavedViewPageKey', () => {
  it('uses the loaded schema pageKey instead of the route model segment', () => {
    expect(resolveListSavedViewPageKey({ pageKey: 'e2et_order_list' }, 'e2et_order')).toBe(
      'e2et_order_list',
    );
  });

  it('falls back to the route table name when schema pageKey is absent', () => {
    expect(resolveListSavedViewPageKey({}, 'legacy_order')).toBe('legacy_order');
  });
});

describe('findPersonalPresetSavedView', () => {
  it('finds an existing personal SavedView created from a system preset', () => {
    const match = findPersonalPresetSavedView(
      [
        {
          pid: 'team-preset',
          scope: 'team',
          viewType: 'table',
          viewConfig: { meta: { originPresetKey: 'modified_this_week' } },
        },
        {
          pid: 'personal-preset',
          scope: 'personal',
          viewType: 'table',
          viewConfig: { meta: { originPresetKey: 'modified_this_week' } },
        },
      ],
      'modified_this_week',
    );

    expect(match?.pid).toBe('personal-preset');
  });

  it('ignores non-matching or non-personal preset views', () => {
    const match = findPersonalPresetSavedView(
      [
        {
          pid: 'created-today',
          scope: 'personal',
          viewType: 'table',
          viewConfig: { meta: { originPresetKey: 'created_today' } },
        },
        {
          pid: 'team-modified',
          scope: 'team',
          viewType: 'table',
          viewConfig: { meta: { originPresetKey: 'modified_this_week' } },
        },
      ],
      'modified_this_week',
    );

    expect(match).toBeUndefined();
  });
});

describe('quick filter preset saved-view lifecycle helpers', () => {
  const now = new Date(2026, 5, 17, 14, 32, 10);

  it('lists personal preset copies without including team views', () => {
    expect(
      getSavedQuickFilterPresetKeys([
        {
          scope: 'personal',
          viewConfig: { meta: { originPresetKey: 'created_today' } },
        },
        {
          scope: 'team',
          viewConfig: { meta: { originPresetKey: 'modified_this_week' } },
        },
        {
          scope: 'personal',
          viewConfig: { meta: { originPresetKey: 'created_today' } },
        },
      ]),
    ).toEqual(['created_today']);
  });

  it('resolves the active personal preset copy only for supported preset keys', () => {
    expect(
      getActiveSavedQuickFilterPresetKey({
        scope: 'personal',
        viewConfig: { meta: { originPresetKey: 'modified_this_week' } },
      }),
    ).toBe('modified_this_week');
    expect(
      getActiveSavedQuickFilterPresetKey({
        scope: 'personal',
        viewConfig: { meta: { originPresetKey: 'unknown_plugin_key' } },
      }),
    ).toBeNull();
    expect(
      getActiveSavedQuickFilterPresetKey({
        scope: 'team',
        viewConfig: { meta: { originPresetKey: 'modified_this_week' } },
      }),
    ).toBeNull();
  });

  it('detects whether a personal preset copy differs from the current system preset', () => {
    const baseView = {
      scope: 'personal' as const,
      viewConfig: {
        meta: { originPresetKey: 'created_today' },
        filters: [
          {
            fieldCode: 'created_at',
            operator: 'between' as const,
            value: { start: '2026-06-17', end: '2026-06-17T23:59:59' },
          },
        ],
      },
    };

    expect(isPersonalPresetSavedViewEdited(baseView, 'created_today', { now })).toBe(false);
    expect(
      isPersonalPresetSavedViewEdited(
        {
          ...baseView,
          viewConfig: {
            ...baseView.viewConfig,
            filters: [{ fieldCode: 'status', operator: 'eq' as const, value: 'open' }],
          },
        },
        'created_today',
        { now },
      ),
    ).toBe(true);
  });
});

describe('useRestoreSavedViewFromUrl', () => {
  it('restores selection again when URL view pid changes after views are loaded', () => {
    type RestoreHookProps = {
      urlViewPid: string | null;
      savedViews: Array<{ pid: string; viewType?: 'table' | null }>;
    };
    const sourceView = {
      pid: 'source-view',
      name: 'Global View',
      scope: 'global',
      viewType: 'table',
    } as const;
    const copiedView = {
      pid: 'personal-copy',
      name: 'Global View - My Copy',
      scope: 'personal',
      viewType: 'table',
    } as const;
    const selectView = vi.fn();
    const setActiveViewType = vi.fn();

    const { rerender } = renderHook(
      ({ urlViewPid, savedViews }: RestoreHookProps) =>
        useRestoreSavedViewFromUrl({
          urlViewPid,
          savedViews,
          viewsLoading: false,
          selectView,
          setActiveViewType,
        }),
      {
        initialProps: {
          urlViewPid: sourceView.pid,
          savedViews: [sourceView, copiedView],
        },
      },
    );

    expect(selectView).toHaveBeenLastCalledWith(sourceView.pid);

    selectView.mockClear();
    rerender({
      urlViewPid: copiedView.pid,
      savedViews: [sourceView, copiedView],
    });

    expect(selectView).toHaveBeenCalledTimes(1);
    expect(selectView).toHaveBeenCalledWith(copiedView.pid);
  });
});

describe('resolveTableBlockRowActions', () => {
  it('keeps nested table.rowActions executable for API-backed DSL pages', () => {
    expect(
      resolveTableBlockRowActions({
        blockType: 'table',
        rowActions: [{ code: 'detail' }],
        table: {
          rowActions: [
            { code: 'test', action: { type: 'flow' } },
            { code: 'delete', confirm: 'confirm.delete' },
          ],
        },
      }).map((action) => action.code),
    ).toEqual(['detail', 'test', 'delete']);
  });

  it('deduplicates table.rowActions already present at block level', () => {
    expect(
      resolveTableBlockRowActions({
        blockType: 'table',
        rowActions: [{ code: 'detail', label: 'Block Detail' }],
        table: {
          rowActions: [{ code: 'detail', label: 'Nested Detail' }, { code: 'console' }],
        },
      }),
    ).toEqual([{ code: 'detail', label: 'Block Detail' }, { code: 'console' }]);
  });
});

describe('shouldSkipListData', () => {
  it('skips dynamic list loading when the page opts in explicitly', () => {
    expect(
      shouldSkipListData({
        extension: { skipListData: true },
        blocks: [
          {
            id: 'rollout_monitor',
            blockType: 'custom',
            component: 'DecisionRolloutMonitorBlock',
          },
        ],
      }),
    ).toBe(true);
  });

  it('treats custom-only list pages as runtime block hosts', () => {
    expect(
      shouldSkipListData({
        blocks: [
          {
            id: 'rollout_monitor',
            blockType: 'custom',
            component: 'DecisionRolloutMonitorBlock',
          },
        ],
      }),
    ).toBe(true);
  });

  it('keeps normal table-backed list pages on dynamic data loading', () => {
    expect(
      shouldSkipListData({
        blocks: [
          {
            id: 'table',
            blockType: 'table',
            table: { columns: [{ field: 'name' }] },
          },
          {
            id: 'summary',
            blockType: 'custom',
            component: 'SummaryBlock',
          },
        ],
      }),
    ).toBe(false);
  });
});

describe('shouldSkipModelFieldMeta', () => {
  it('skips dynamic field metadata for custom-only host pages', () => {
    expect(
      shouldSkipModelFieldMeta({
        blocks: [
          {
            id: 'rollout_monitor',
            blockType: 'custom',
            component: 'DecisionRolloutMonitorBlock',
          },
        ],
      }),
    ).toBe(true);
  });

  it('lets API-backed DSL pages opt out of dynamic model field metadata', () => {
    expect(
      shouldSkipModelFieldMeta({
        dataSource: { type: 'api', endpoint: '/api/decision/rollouts' },
        extension: { skipFieldMeta: true },
        blocks: [
          {
            id: 'rollout_policy_table',
            blockType: 'table',
            table: { columns: [{ field: 'decisionCode' }] },
          },
        ],
      }),
    ).toBe(true);
  });

  it('keeps normal table-backed list pages on field metadata loading', () => {
    expect(
      shouldSkipModelFieldMeta({
        blocks: [
          {
            id: 'table',
            blockType: 'table',
            table: { columns: [{ field: 'name' }] },
          },
        ],
      }),
    ).toBe(false);
  });
});

describe('resolveListMiscBlocksPosition', () => {
  it('defaults misc blocks after the table', () => {
    expect(resolveListMiscBlocksPosition({ extension: {} })).toBe('afterTable');
  });

  it('allows DSL pages to place custom blocks before the table', () => {
    expect(
      resolveListMiscBlocksPosition({ extension: { miscBlocksPosition: 'beforeTable' } }),
    ).toBe('beforeTable');
  });
});

describe('resolveFieldMetaDisplayName', () => {
  const map = new Map<string, any>([
    ['crm_crq_code', { code: 'crm_crq_code', displayName: 'RFQ编号' }],
    ['crm_crq_qty', { code: 'crm_crq_qty', extension: { displayName: '预估数量' } }],
    ['crm_crq_raw', { code: 'crm_crq_raw', displayName: 'crm_crq_raw' }],
    ['crm_crq_blank', { code: 'crm_crq_blank', displayName: '   ' }],
  ]);

  it('prefers the field displayName from field-meta', () => {
    expect(resolveFieldMetaDisplayName('crm_crq_code', map)).toBe('RFQ编号');
  });

  it('falls back to extension.displayName', () => {
    expect(resolveFieldMetaDisplayName('crm_crq_qty', map)).toBe('预估数量');
  });

  it('returns undefined when displayName is missing, blank, or just the raw code', () => {
    expect(resolveFieldMetaDisplayName('crm_crq_raw', map)).toBeUndefined();
    expect(resolveFieldMetaDisplayName('crm_crq_blank', map)).toBeUndefined();
    expect(resolveFieldMetaDisplayName('unknown_field', map)).toBeUndefined();
    expect(resolveFieldMetaDisplayName('crm_crq_code', undefined)).toBeUndefined();
  });
});

describe('resolveFieldMetaDataType', () => {
  const map = new Map<string, any>([
    ['e2et_order_customer', { code: 'e2et_order_customer', dataType: 'reference' }],
    ['cover', { code: 'cover', extension: { dataType: 'image' } }],
    ['blank', { code: 'blank', dataType: '   ' }],
  ]);

  it('resolves the field dataType from field-meta', () => {
    expect(resolveFieldMetaDataType('e2et_order_customer', map)).toBe('reference');
    expect(resolveFieldMetaDataType('cover', map)).toBe('image');
  });

  it('returns undefined when field metadata has no usable dataType', () => {
    expect(resolveFieldMetaDataType('blank', map)).toBeUndefined();
    expect(resolveFieldMetaDataType('unknown', map)).toBeUndefined();
    expect(resolveFieldMetaDataType('cover', undefined)).toBeUndefined();
  });
});

describe('resolveColumnCapabilityDataType', () => {
  it('prefers model field metadata over table column fallbacks for capability gates', () => {
    const map = new Map<string, any>([
      ['e2et_order_customer', { code: 'e2et_order_customer', dataType: 'reference' }],
    ]);

    expect(
      resolveColumnCapabilityDataType(
        {
          field: 'e2et_order_customer',
          valueType: 'text',
          sorter: true,
        },
        map,
      ),
    ).toBe('reference');
  });

  it('falls back to explicit column metadata and ignores non-string sorter values', () => {
    expect(
      resolveColumnCapabilityDataType(
        {
          field: 'status',
          valueType: 'enum',
          sorter: true,
        },
        new Map(),
      ),
    ).toBe('enum');
    expect(resolveColumnCapabilityDataType({ field: 'name', sorter: true }, new Map())).toBe(
      'text',
    );
  });
});

describe('buildViewManageFieldOptions', () => {
  it('includes model fields that are not visible table columns for advanced view capability gates', () => {
    const fields = buildViewManageFieldOptions(
      [
        {
          field: 'sc_name',
          label: 'Name',
          valueType: 'text',
        },
      ],
      new Map([
        ['sc_name', { code: 'sc_name', displayName: '名称', dataType: 'string' }],
        [
          'sc_attachment_file',
          { code: 'sc_attachment_file', displayName: '附件文件', dataType: 'file' },
        ],
      ]),
    );

    expect(fields).toContainEqual({
      code: 'sc_attachment_file',
      name: '附件文件',
      dataType: 'file',
    });
    expect(fields).toContainEqual({
      code: 'sc_name',
      name: 'Name',
      dataType: 'string',
    });
  });

  it('falls back to visible table columns when model field metadata is unavailable', () => {
    expect(
      buildViewManageFieldOptions(
        [
          {
            field: 'e2et_order_customer',
            label: 'Customer',
            valueType: 'text',
          },
        ],
        undefined,
      ),
    ).toEqual([
      {
        code: 'e2et_order_customer',
        name: 'Customer',
        dataType: 'text',
      },
    ]);
  });
});
