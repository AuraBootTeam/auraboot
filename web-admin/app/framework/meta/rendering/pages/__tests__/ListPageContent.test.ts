import { act, renderHook } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import {
  buildListReferenceDisplayCacheKey,
  buildBulkFieldCommandPayload,
  buildViewManageFieldOptions,
  buildListColumnSettingsDefinitions,
  buildListFilterFieldMetadata,
  collectListReferenceDisplayConfigs,
  findPersonalPresetSavedView,
  getListFieldValueWithAlias,
  pruneNoopViewConfigPatch,
  renderComponentToValueType,
  resolveColumnCapabilityDataType,
  resolveFieldMetaDataType,
  resolveFieldMetaDisplayName,
  resolveFieldMetaRenderComponent,
  resolveListSystemReferenceDisplayConfig,
  shouldResolveListSystemReferenceValue,
  resolveListSavedViewPageKey,
  resolveListMiscBlocksPosition,
  resolveTableBlockRowActions,
  queryConditionToExportCondition,
  resolveUrlStateSyncAction,
  shouldSkipListData,
  shouldSkipModelFieldMeta,
  useRestoreSavedViewFromUrl,
  useSerializedSearchParamsUpdater,
  viewFilterToQueryCondition,
  resolveSavedViewFilterExpressions,
  resolveInitialListTabKey,
} from '../ListPageContent';

describe('resolveInitialListTabKey', () => {
  it('uses the explicit all tab when the DSL provides one', () => {
    expect(
      resolveInitialListTabKey([
        { blockType: 'tabs', tabs: [{ key: 'available' }, { key: 'all' }] },
      ]),
    ).toBe('all');
  });

  it('uses the first DSL tab when no all tab exists', () => {
    expect(
      resolveInitialListTabKey([
        { blockType: 'tabs', tabs: [{ key: 'available' }, { key: 'claimed' }] },
      ]),
    ).toBe('available');
  });

  it('falls back to all when the page has no valid tabs', () => {
    expect(resolveInitialListTabKey([])).toBe('all');
    expect(resolveInitialListTabKey([{ blockType: 'tabs', tabs: [{}] }])).toBe('all');
  });
});

describe('buildBulkFieldCommandPayload', () => {
  it('maps the collected value only to the DSL-owned command input', () => {
    expect(
      buildBulkFieldCommandPayload(
        { field: 'crm_opp_owner', component: 'MemberPicker', required: true },
        'user-public-pid',
      ),
    ).toEqual({ crm_opp_owner: 'user-public-pid' });
  });
});

describe('useSerializedSearchParamsUpdater', () => {
  it('merges same-turn functional URL updates instead of restoring a stale query string', () => {
    const committed: string[] = [];
    const routerSetter = vi.fn((nextInit: any) => {
      const next = typeof nextInit === 'function' ? nextInit(new URLSearchParams()) : nextInit;
      committed.push(new URLSearchParams(next).toString());
    });
    const { result } = renderHook(() =>
      useSerializedSearchParamsUpdater(
        new URLSearchParams('sort=updated_at%3Adesc'),
        routerSetter as any,
      ),
    );

    act(() => {
      result.current(
        (prev: URLSearchParams) => {
          const next = new URLSearchParams(prev);
          next.set('view', 'personal-view');
          return next;
        },
        { replace: true },
      );
      result.current(
        (prev: URLSearchParams) => {
          const next = new URLSearchParams(prev);
          next.set('pageNum', '1');
          return next;
        },
        { replace: true },
      );
    });

    expect(committed.at(-1)).toBe('sort=updated_at%3Adesc&view=personal-view&pageNum=1');
  });
});

describe('renderComponentToValueType', () => {
  it('maps renderComponent (and DSL renderType) to a list cell valueType', () => {
    expect(renderComponentToValueType('colorpicker')).toBe('color');
    expect(renderComponentToValueType('progress')).toBe('progress');
    expect(renderComponentToValueType('rating')).toBe('rating');
    expect(renderComponentToValueType('moneyinput')).toBe('currency');
    expect(renderComponentToValueType('input')).toBeUndefined();
    expect(renderComponentToValueType(undefined)).toBeUndefined();
  });
});

describe('buildListColumnSettingsDefinitions', () => {
  it('combines DSL defaults, hidden readable model fields and system fields', () => {
    const modelFields = new Map<string, any>([
      ['name', { code: 'name', dataType: 'string', extension: { displayName: '名称' } }],
      ['amount', { code: 'amount', dataType: 'decimal', extension: { displayName: '金额' } }],
      ['private_note', { code: 'private_note', dataType: 'string', visible: false }],
    ]);

    expect(
      buildListColumnSettingsDefinitions(
        [{ field: 'name', width: 180, fixed: 'left' }],
        modelFields,
        [{ field: 'updated_at', label: '更新时间', valueType: 'datetime' }],
        (column) => (typeof column.label === 'string' ? column.label : '名称'),
      ),
    ).toEqual([
      {
        field: 'name',
        label: '名称',
        dataType: 'string',
        group: 'business',
        defaultVisible: true,
        defaultWidth: 180,
        defaultFrozenPosition: 'left',
      },
      {
        field: 'amount',
        label: '金额',
        dataType: 'decimal',
        group: 'business',
        defaultVisible: false,
      },
      {
        field: 'updated_at',
        label: '更新时间',
        dataType: 'datetime',
        group: 'system',
        defaultVisible: false,
      },
    ]);
  });
});

describe('resolveFieldMetaRenderComponent', () => {
  it('reads extension.renderComponent from the field-meta map, normalized to lower case', () => {
    const map = new Map<string, any>([
      [
        'sc_color',
        { code: 'sc_color', dataType: 'string', extension: { renderComponent: 'ColorPicker' } },
      ],
      ['sc_name', { code: 'sc_name', dataType: 'string' }],
    ]);
    expect(resolveFieldMetaRenderComponent('sc_color', map)).toBe('colorpicker');
    expect(resolveFieldMetaRenderComponent('sc_name', map)).toBeUndefined();
    expect(resolveFieldMetaRenderComponent('missing', map)).toBeUndefined();
    expect(resolveFieldMetaRenderComponent('sc_color', undefined)).toBeUndefined();
  });
});

describe('getListFieldValueWithAlias', () => {
  it('reads camelCase API fields for snake_case DSL table columns', () => {
    const record = {
      processKey: 'wd_leave_approval',
      processName: '请假审批',
      deployedAt: '2026-06-24T02:35:57Z',
    };

    expect(getListFieldValueWithAlias(record, 'process_key')).toBe('wd_leave_approval');
    expect(getListFieldValueWithAlias(record, 'process_name')).toBe('请假审批');
    expect(getListFieldValueWithAlias(record, 'deployed_at')).toBe('2026-06-24T02:35:57Z');
  });

  it('keeps the exact list field value when both exact and alias keys exist', () => {
    expect(
      getListFieldValueWithAlias({ process_key: 'exact', processKey: 'alias' }, 'process_key'),
    ).toBe('exact');
  });
});

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

describe('resolveListSystemReferenceDisplayConfig', () => {
  it('routes sys_user references to the admin user detail endpoint', () => {
    expect(resolveListSystemReferenceDisplayConfig('sys_user')).toEqual({
      detailEndpoint: '/api/admin/users',
      labelFields: ['displayName', 'realName', 'username', 'email'],
    });
  });

  it('leaves normal business references on dynamic list resolution', () => {
    expect(resolveListSystemReferenceDisplayConfig('crm_account_common')).toBeUndefined();
  });

  it('only resolves path-style user identifiers, not already-projected display labels', () => {
    expect(shouldResolveListSystemReferenceValue('01KYXD8DZNMG0S0FMRHY3VSMNC')).toBe(true);
    expect(shouldResolveListSystemReferenceValue('user-9')).toBe(true);
    expect(shouldResolveListSystemReferenceValue('Admin User')).toBe(false);
    expect(shouldResolveListSystemReferenceValue('SOT qo_sales')).toBe(false);
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

describe('pruneNoopViewConfigPatch', () => {
  it('removes empty sort patches that match the saved view state', () => {
    expect(pruneNoopViewConfigPatch({ rowHeight: 'medium', sorts: [] }, { sorts: [] })).toBeNull();
  });

  it('keeps empty sort patches when they clear a saved sort', () => {
    expect(
      pruneNoopViewConfigPatch(
        { sorts: [{ fieldCode: 'amount', direction: 'desc' }] },
        { sorts: [] },
      ),
    ).toEqual({ sorts: [] });
  });

  it('keeps other changed draft sections while pruning no-op sorts', () => {
    expect(
      pruneNoopViewConfigPatch(
        { rowHeight: 'medium', sorts: [] },
        { rowHeight: 'tall', sorts: [] },
      ),
    ).toEqual({ rowHeight: 'tall' });
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

describe('buildListFilterFieldMetadata', () => {
  it('uses model metadata for numeric, enum and reference filter controls', () => {
    const fields = buildListFilterFieldMetadata(
      [
        { field: 'amount', label: 'Amount' },
        { field: 'stage', label: 'Stage', renderType: 'tag' },
        { field: 'owner', label: 'Owner' },
      ],
      new Map([
        ['amount', { code: 'amount', dataType: 'money' }],
        ['stage', { code: 'stage', dataType: 'enum', dictCode: 'opp_stage' }],
        [
          'owner',
          {
            code: 'owner',
            dataType: 'reference',
            refTarget: {
              targetEntity: 'sys_user',
              valueField: 'pid',
              displayField: 'displayName',
            },
          },
        ],
      ]),
      (column) => String(column.label),
    );

    expect(fields).toContainEqual({
      fieldCode: 'amount',
      label: 'Amount',
      fieldType: 'money',
      dictCode: undefined,
      referenceModelCode: undefined,
      referenceValueField: 'pid',
      referenceDisplayField: undefined,
    });
    expect(fields).toContainEqual(
      expect.objectContaining({
        fieldCode: 'stage',
        fieldType: 'enum',
        dictCode: 'opp_stage',
      }),
    );
    expect(fields).toContainEqual(
      expect.objectContaining({
        fieldCode: 'owner',
        fieldType: 'reference',
        referenceModelCode: 'sys_user',
        referenceValueField: 'pid',
        referenceDisplayField: 'displayName',
      }),
    );
  });

  it('can supply localized system metadata for sort chips outside the visible table', () => {
    expect(
      buildListFilterFieldMetadata(
        [{ field: 'updated_at', label: '更新时间', valueType: 'datetime' }],
        new Map(),
        (column) => String(column.label),
      ),
    ).toContainEqual({
      fieldCode: 'updated_at',
      label: '更新时间',
      fieldType: 'datetime',
      referenceDisplayField: undefined,
      referenceModelCode: undefined,
      referenceValueField: 'pid',
      dictCode: undefined,
    });
  });
});

describe('viewFilterToQueryCondition', () => {
  it('keeps IN and BETWEEN values as arrays for list and export requests', () => {
    const inCondition = viewFilterToQueryCondition({
      fieldCode: 'forecast_category',
      operator: 'in',
      value: ['commit', 'best_case'],
    });
    const betweenCondition = viewFilterToQueryCondition({
      fieldCode: 'close_date',
      operator: 'between',
      value: ['2026-08-01', '2026-08-31'],
    });

    expect(inCondition).toEqual({
      fieldName: 'forecast_category',
      operator: 'IN',
      values: ['commit', 'best_case'],
    });
    expect(betweenCondition).toEqual({
      fieldName: 'close_date',
      operator: 'BETWEEN',
      values: ['2026-08-01', '2026-08-31'],
    });
    expect(queryConditionToExportCondition(inCondition!)).toEqual({
      field: 'forecast_category',
      operator: 'IN',
      value: ['commit', 'best_case'],
    });
  });

  it('keeps numbers typed and wraps LIKE values only once', () => {
    expect(
      viewFilterToQueryCondition({ fieldCode: 'amount', operator: 'gte', value: 100000 }),
    ).toEqual({ fieldName: 'amount', operator: 'GTE', value: 100000 });
    expect(
      viewFilterToQueryCondition({ fieldCode: 'name', operator: 'like', value: '华东' }),
    ).toEqual({ fieldName: 'name', operator: 'LIKE', value: '%华东%' });
  });
});

describe('resolveSavedViewFilterExpressions', () => {
  it('resolves both supported current-user syntaxes to a public user PID', () => {
    const resolved = resolveSavedViewFilterExpressions(
      [
        {
          fieldCode: 'owner',
          operator: 'eq',
          value: null,
          isExpression: true,
          expression: '#currentUser',
        },
        {
          fieldCode: 'reviewer',
          operator: 'eq',
          value: null,
          isExpression: true,
          expression: '${system.currentUser}',
        },
      ],
      { currentUserPid: ' 01K2USERPID ' },
    );

    expect(resolved.map((filter) => filter.value)).toEqual(['01K2USERPID', '01K2USERPID']);
    expect(resolved.every((filter) => filter.isExpression)).toBe(true);
  });

  it('drops stale values for unsupported expressions and preserves static filters', () => {
    const staticFilter = { fieldCode: 'stage', operator: 'eq' as const, value: 'closed_won' };
    const resolved = resolveSavedViewFilterExpressions(
      [
        staticFilter,
        {
          fieldCode: 'owner',
          operator: 'eq',
          value: 'stale-user',
          isExpression: true,
          expression: '#unsupported',
        },
      ],
      { currentUserPid: '01K2USERPID' },
    );

    expect(resolved[0]).toBe(staticFilter);
    expect(resolved[1].value).toBeUndefined();
    expect(viewFilterToQueryCondition(resolved[1])).toBeNull();
  });

  it('resolves the department-owner expression to an authenticated backend resolver', () => {
    const resolved = resolveSavedViewFilterExpressions(
      [
        {
          fieldCode: 'owner',
          operator: 'in',
          value: null,
          isExpression: true,
          expression: '#currentDepartmentOwners',
        },
      ],
      { currentUserPid: '01K2USERPID' },
    );

    expect(resolved[0].value).toEqual({
      $currentDepartmentOwnerPids: { includeSubDepartments: true },
    });
    expect(viewFilterToQueryCondition(resolved[0])).toEqual({
      fieldName: 'owner',
      operator: 'IN',
      values: [{ $currentDepartmentOwnerPids: { includeSubDepartments: true } }],
    });
  });

  it('resolves collaborative records through the authenticated backend without exposing PIDs', () => {
    const resolved = resolveSavedViewFilterExpressions(
      [
        {
          fieldCode: 'pid',
          operator: 'in',
          value: null,
          isExpression: true,
          expression: '#currentSharedRecords',
        },
      ],
      { currentUserPid: '01K2USERPID' },
    );

    expect(resolved[0].value).toEqual({
      $currentSharedRecordPids: { action: 'read' },
    });
    expect(viewFilterToQueryCondition(resolved[0])).toEqual({
      fieldName: 'pid',
      operator: 'IN',
      values: [{ $currentSharedRecordPids: { action: 'read' } }],
    });
  });
});

describe('resolveUrlStateSyncAction', () => {
  it('does not let a stale URL write clobber newer local list state', () => {
    expect(resolveUrlStateSyncAction('new-filter-state', 'older-filter-state')).toBe(
      'wait-for-local',
    );
    expect(resolveUrlStateSyncAction('new-filter-state', 'new-filter-state')).toBe('ack-local');
    expect(resolveUrlStateSyncAction(undefined, 'browser-history-state')).toBe('apply-url');
  });

  it('keeps a restored default sort until React Router acknowledges the local URL write', () => {
    const restoredSort = 'updated_at:desc';

    expect(resolveUrlStateSyncAction(restoredSort, null)).toBe('wait-for-local');
    expect(resolveUrlStateSyncAction(restoredSort, restoredSort)).toBe('ack-local');
    expect(resolveUrlStateSyncAction(undefined, restoredSort)).toBe('apply-url');
  });

  it('acknowledges clearing a sort without reapplying the stale sorted URL', () => {
    expect(resolveUrlStateSyncAction(null, 'updated_at:desc')).toBe('wait-for-local');
    expect(resolveUrlStateSyncAction(null, null)).toBe('ack-local');
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
