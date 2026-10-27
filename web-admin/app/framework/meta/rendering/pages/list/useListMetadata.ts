import { findListFilterTabsBlock } from './listSchemaPolicy';
import { internalRequest } from '~/shared/services/http-client/internal-api';
import { dictionaryData } from '~/shared/services/dict-adapter';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { DataSourceManager } from '~/framework/meta/runtime/data-pipeline/DataSourceManager';
import { createExpressionContext } from '~/framework/meta/runtime/expression/context';
import type { ColumnConfig, FieldConfig } from '~/framework/meta/schemas/types';
import {
  type SavedView,
  type SortConfig,
  type ViewConfig,
  type ViewFilterConfig,
  type ViewScope,
  type ViewType,
} from '~/framework/smart/types/savedView';
import { fetchResult } from '~/shared/services/http-client';
import { modelService } from '~/shared/services/modelService';
import { savedViewService } from '~/shared/services/savedViewService';
import { type User, ResultHelper } from '~/utils/type';

import {
  resolveSavedViewFilterExpressions,
  resolveUserFilterCondition,
  tabFilterToQueryCondition,
  viewFilterToQueryCondition,
  type ListQueryFilterCondition,
} from './listFilterConditions';
import { useRestoreSavedViewFromUrl } from './listPageStateUtils';
import type { DictItem, DynamicEntity } from './listPageTypes';
import {
  areFiltersEqual,
  areSortsEqual,
  encodeFilters,
  resolveListFilterState,
  resolveListSortState,
} from './useListUrlState';

interface ListMetadataInput {
  modelCode: any;
  skipModelFieldMeta: boolean;
  schema: any;
  tableName: string;
  token: string | null | undefined;
  reloadViews: () => Promise<void>;
  urlViewPid: string | null;
  accessibleViews: SavedView[];
  viewsLoading: boolean;
  selectView: (pid: string) => void;
  setActiveViewType: React.Dispatch<React.SetStateAction<ViewType>>;
  currentView: SavedView | null;
  user: User | null;
  pendingSavedViewFiltersRef: React.RefObject<Record<string, any> | null>;
  setFilters: (
    filters: Record<string, any> | ((prev: Record<string, any>) => Record<string, any>),
  ) => void;
  initialUrlFiltersRef: React.RefObject<ViewFilterConfig[]>;
  hasLocalFilterChangeRef: React.RefObject<boolean>;
  chipFiltersRef: React.RefObject<ViewFilterConfig[]>;
  pendingChipFilterUrlSyncRef: React.RefObject<string | null | undefined>;
  setChipFilters: React.Dispatch<React.SetStateAction<ViewFilterConfig[]>>;
  initialUrlSortsRef: React.RefObject<SortConfig[]>;
  hasLocalSortChangeRef: React.RefObject<boolean>;
  setActiveSorts: React.Dispatch<React.SetStateAction<SortConfig[]>>;
  setPagination: (pagination: any) => void;
  pagination: { current: number; pageSize: number; total: number };
  activeQuickFilterRef: React.RefObject<string | null>;
  keywordRef: React.RefObject<string>;
  _setKeyword: React.Dispatch<React.SetStateAction<string>>;
  tableBlock: any;
  locale: string;
  filters: Record<string, any>;
  selectedIdList: string[];
  t: (key: string) => string;
  dataSourceManager: DataSourceManager;
  activeTab: string;
  data: DynamicEntity[];
}

/** Load model fields and dictionaries; restore view state and build query filters. */
export function useListMetadata(
  {
    modelCode,
    skipModelFieldMeta,
    schema,
    tableName,
    token,
    reloadViews,
    urlViewPid,
    accessibleViews,
    viewsLoading,
    selectView,
    setActiveViewType,
    currentView,
    user,
    pendingSavedViewFiltersRef,
    setFilters,
    initialUrlFiltersRef,
    hasLocalFilterChangeRef,
    chipFiltersRef,
    pendingChipFilterUrlSyncRef,
    setChipFilters,
    initialUrlSortsRef,
    hasLocalSortChangeRef,
    setActiveSorts,
    setPagination,
    pagination,
    activeQuickFilterRef,
    keywordRef,
    _setKeyword,
    tableBlock,
    locale,
    filters,
    selectedIdList,
    t,
    dataSourceManager,
    activeTab,
    data,
  }: ListMetadataInput,
  late: {
    filterBlock: any;
  },
) {
  // Resolve modelPid from modelCode for ViewManagePanel field operations
  const [modelPid, setModelPid] = useState<string | undefined>();
  useEffect(() => {
    if (!modelCode || skipModelFieldMeta) {
      setModelPid(undefined);
      return;
    }
    modelService
      .findByCode(modelCode)
      .then((model) => setModelPid(model.pid))
      .catch(() => setModelPid(undefined));
  }, [modelCode, skipModelFieldMeta]);

  const [modelFieldMap, setModelFieldMap] = useState<Map<string, any>>(new Map());
  // Call-time mirror of modelFieldMap: loadData / handleTabChange read it when a
  // request fires, so a filter request that waited on fieldMetaReadyRef sees the
  // settled metadata even though its closure was created before the re-render.
  const modelFieldMapRef = useRef<Map<string, any>>(new Map());
  // Resolves once the field metadata for the current model has settled (loaded
  // or failed). The initial list load awaits it when URL-sourced filters are
  // present, so their operators are typed before the first request.
  const fieldMetaReadyRef = useRef<Promise<void>>(Promise.resolve());
  const [referenceDisplayCache, setReferenceDisplayCache] = useState<
    Record<string, Record<string, string>>
  >({});

  useEffect(() => {
    let cancelled = false;
    const pageKey = schema?.modelCode || tableName;
    if (!pageKey || skipModelFieldMeta) {
      modelFieldMapRef.current = new Map();
      setModelFieldMap(new Map());
      fieldMetaReadyRef.current = Promise.resolve();
      return;
    }

    const ready = (async () => {
      try {
        const fieldsRes = await fetchResult<any[]>(`/api/dynamic/${pageKey}/field-meta`, {
          method: 'get',
          token: token || undefined,
        });
        if (cancelled) return;
        if (!ResultHelper.isSuccess(fieldsRes) || !fieldsRes.data) {
          modelFieldMapRef.current = new Map();
          setModelFieldMap(new Map());
          return;
        }
        const map = new Map<string, any>();
        for (const field of fieldsRes.data) {
          if (field?.code) map.set(field.code, field);
        }
        modelFieldMapRef.current = map;
        setModelFieldMap(map);
      } catch (error) {
        if (!cancelled) {
          modelFieldMapRef.current = new Map();
          setModelFieldMap(new Map());
          console.warn('[ListPageContent] Failed to load model field metadata:', error);
        }
      }
    })();
    fieldMetaReadyRef.current = ready;
    return () => {
      cancelled = true;
    };
  }, [schema?.modelCode, tableName, token, skipModelFieldMeta]);

  // Handle edit view (name, description, scope) via savedViewService + reload
  const handleEditView = useCallback(
    async (pid: string, name: string, description: string, scope: ViewScope) => {
      await savedViewService.updateView(pid, { name, description, scope });
      await reloadViews();
    },
    [reloadViews],
  );

  // Restore view from URL ?view= parameter (highest priority).
  useRestoreSavedViewFromUrl({
    urlViewPid,
    savedViews: accessibleViews,
    viewsLoading,
    selectView,
    setActiveViewType,
  });

  const currentViewPid = currentView?.pid;
  const currentViewType = currentView?.viewType;
  const currentViewConfig = currentView?.viewConfig;
  useEffect(() => {
    if (!currentViewPid) return;
    setActiveViewType((currentViewType as ViewType) || 'table');
  }, [currentViewPid, currentViewType]);

  const applyViewConfigToListState = useCallback(
    (viewConfig: ViewConfig | undefined): Record<string, any> => {
      const vc = viewConfig ?? {};
      const restoredViewFilters = resolveSavedViewFilterExpressions(vc.filters, {
        currentUserPid: user?.pid,
      });
      const restoredFilters: Record<string, any> = {};

      pendingSavedViewFiltersRef.current = restoredFilters;
      setFilters(restoredFilters);
      const filterOwnership = resolveListFilterState({
        initialUrlFilters: initialUrlFiltersRef.current,
        hasLocalFilterChange: hasLocalFilterChangeRef.current,
      });
      if (filterOwnership.applySavedViewFilters) {
        chipFiltersRef.current = restoredViewFilters;
        pendingChipFilterUrlSyncRef.current = encodeFilters(restoredViewFilters);
        setChipFilters((prev) =>
          areFiltersEqual(prev, restoredViewFilters) ? prev : restoredViewFilters,
        );
      }

      const restoredSorts = vc.sorts ?? [];
      const sortOwnership = resolveListSortState({
        initialUrlSorts: initialUrlSortsRef.current,
        hasLocalSortChange: hasLocalSortChangeRef.current,
      });
      if (sortOwnership.applySavedViewSorts) {
        // SavedView hydration is not a user sort edit, so it must not enqueue a
        // URL write. Clean list URLs therefore remain clean after hydration.
        setActiveSorts((prev) => (areSortsEqual(prev, restoredSorts) ? prev : restoredSorts));
      }

      if (vc.pagination?.pageSize && vc.pagination.pageSize > 0) {
        setPagination((prev: typeof pagination) => ({
          ...prev,
          pageSize: vc.pagination!.pageSize!,
        }));
      }

      return restoredFilters;
    },
    [setFilters, setPagination, user?.pid],
  );

  // Apply SavedView viewConfig (pagination + filters + sorts) when view changes.
  // Empty config is meaningful: it restores the selected view back to a clean list state.
  useEffect(() => {
    if (activeQuickFilterRef.current) return;
    if (!currentViewPid) return;
    applyViewConfigToListState(currentViewConfig);
  }, [applyViewConfigToListState, currentViewConfig, currentViewPid]);

  const clearKeyword = useCallback(() => {
    keywordRef.current = '';
    _setKeyword('');
  }, []);

  // Dict data cache
  const dictDataCache = useRef<Map<string, DictItem[]>>(new Map());

  // Load dict data
  useEffect(() => {
    if (!schema) return;

    // Get columns from tableBlock
    const tableBlock = schema.blocks
      ? schema.blocks.find((block: any) => block.blockType === 'table')
      : undefined;

    const rawColumns = tableBlock?.table?.columns || tableBlock?.columns;
    const columns: ColumnConfig[] = Array.isArray(rawColumns) ? rawColumns : [];
    const dictCodes = columns
      .filter((col: ColumnConfig) => col.dictCode)
      .map((col: ColumnConfig) => col.dictCode!);

    if (dictCodes.length === 0) {
      return;
    }

    // Get codes not yet loaded
    const unloadedCodes = dictCodes.filter((code: string) => !dictDataCache.current.has(code));

    if (unloadedCodes.length === 0) {
      return;
    }

    // Load all dict data in parallel
    const loadDictData = async () => {
      const promises = unloadedCodes.map(async (code: string) => {
        try {
          const result = await internalRequest('get', '/api/meta/dict/by-code/{code}/data', {
            path: { code },
            options: { token: token || undefined },
          });
          if (result.code === '0' && result.data) {
            dictDataCache.current.set(code, dictionaryData(result.data).items);
          }
        } catch (error) {
          console.error(`[ListPageContent] Failed to load dict: ${code}`, error);
        }
      });

      await Promise.all(promises);
    };

    loadDictData();
  }, [schema, token]);

  // Apply DSL pagination.pageSize when schema loads
  useEffect(() => {
    const dslPageSize = tableBlock?.table?.pagination?.pageSize;
    if (dslPageSize && dslPageSize > 0) {
      setPagination((prev: typeof pagination) => ({ ...prev, pageSize: dslPageSize }));
    }
  }, [tableBlock, setPagination]);

  // Create full ExpressionContext for field rendering
  const pageContext = useMemo(() => {
    return createExpressionContext({
      global: {
        locale,
        theme: 'light',
        user: undefined,
        tenant: undefined,
      },
      state: {
        filters,
        selectedIds: selectedIdList,
      },
      locale,
      t: (key: string) => t(key),
      token,
      __dataSourceManager: dataSourceManager,
    });
  }, [locale, filters, selectedIdList, t, token, dataSourceManager]);

  // Get current tab filter as QueryCondition (if tabs block exists)
  const getTabFilter = useCallback((): ListQueryFilterCondition | null => {
    if (!schema?.blocks) return null;
    const tabsBlock = findListFilterTabsBlock(schema.blocks);
    if (!tabsBlock?.tabs) return null;
    const currentTab = (tabsBlock.tabs as any[]).find((tab: any) => tab.key === activeTab);
    if (!currentTab?.filter) return null;
    return tabFilterToQueryCondition(currentTab.filter, user?.pid);
  }, [schema, activeTab, user?.pid]);

  const displayData = useMemo(() => {
    const tabCondition = getTabFilter();
    if (!tabCondition) return data;
    const operator = String(tabCondition.operator || 'EQ').toUpperCase();
    const expected = String(tabCondition.value ?? '');
    const expectedValues = (
      tabCondition.values ??
      (Array.isArray(tabCondition.value) ? tabCondition.value : [tabCondition.value])
    )
      .filter((value) => value != null)
      .map(String);
    return data.filter((record) => {
      const actual = String((record as Record<string, any>)[tabCondition.fieldName] ?? '');
      switch (operator) {
        case 'EQ':
          return actual === expected;
        case 'NE':
        case 'NEQ':
          return actual !== expected;
        case 'IN':
          return expectedValues.includes(actual);
        case 'NOT_IN':
        case 'NOTIN':
          return !expectedValues.includes(actual);
        default:
          return true;
      }
    });
  }, [data, getTabFilter]);

  // Build filters JSON array from tab filter + user filters
  const buildFiltersParam = useCallback(
    (
      tabCondition: ListQueryFilterCondition | null,
      userFilters?: Record<string, any>,
      chipFiltersList?: ViewFilterConfig[],
      filterFieldMap?: Map<string, any>,
    ) => {
      const conditions: ListQueryFilterCondition[] = [];
      if (tabCondition) conditions.push(tabCondition);
      // Convert user filters (key-value from filters) to QueryCondition format
      if (userFilters) {
        for (const [key, value] of Object.entries(userFilters)) {
          conditions.push(
            ...resolveUserFilterCondition(
              key,
              value,
              late.filterBlock?.fields as FieldConfig[] | undefined,
              filterFieldMap,
            ),
          );
        }
      }
      // Merge chip filters (from FilterChipBar) into conditions
      if (chipFiltersList) {
        for (const cf of chipFiltersList) {
          if (!cf.fieldCode) continue;
          const condition = viewFilterToQueryCondition(cf);
          if (condition) conditions.push(condition);
        }
      }
      return conditions.length > 0 ? JSON.stringify(conditions) : undefined;
    },
    // NOTE: filterBlock is declared later in the component; reads resolve at call time.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  );

  // Detect namedQuery data source from page DSL
  const namedQueryCode = useMemo(() => {
    if (schema?.dataSource?.type === 'namedQuery' && schema.dataSource.queryCode) {
      return schema.dataSource.queryCode;
    }
    // Also support top-level queryCode (e.g., NQ-backed list pages like "My Tasks")
    if (schema?.queryCode) {
      console.warn(
        '[DSL Deprecation] Top-level queryCode is deprecated. Use dataSource: { type: "namedQuery", queryCode: "..." }',
      );
      return schema.queryCode;
    }
    return null;
  }, [schema]);

  return {
    modelPid,
    modelFieldMap,
    modelFieldMapRef,
    fieldMetaReadyRef,
    referenceDisplayCache,
    setReferenceDisplayCache,
    handleEditView,
    applyViewConfigToListState,
    clearKeyword,
    dictDataCache,
    pageContext,
    getTabFilter,
    displayData,
    buildFiltersParam,
    namedQueryCode,
  };
}
