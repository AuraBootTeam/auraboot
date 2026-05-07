/**
 * ListPageContent — Extracted rendering logic from dynamic.$tableName.tsx
 *
 * Receives { schema, tableName, token } from DynamicPageRenderer and uses
 * usePageRuntime for runtime setup (replacing useDynamicPageSetup).
 *
 * Contains ALL rendering logic: filters, toolbar, table, pagination,
 * tabs, SavedView integration, dashboard rendering, dict cache, etc.
 */

import React, { useState, useCallback, useEffect, useMemo, useRef } from 'react';
import { useSearchParams } from 'react-router';
import type { PageContentProps } from '~/framework/meta/profiles/types';
import { usePageRuntime } from '~/framework/meta/rendering/pages/hooks/usePageRuntime';
import { BlockRenderer } from '~/framework/meta/rendering/BlockRenderer';
import { buildApiEndpoint, getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { createExpressionContext } from '~/framework/meta/runtime/expression/context';
import type { BlockConfig, ColumnConfig, FieldConfig, ButtonConfig } from '~/framework/meta/schemas/types';
import { actionRegistry } from '~/framework/meta/runtime/actions/ActionRegistry';
import { sanitizeHtml } from '~/framework/meta/utils/sanitizeHtml';
import { cellRendererRegistry } from '~/framework/meta/runtime/renderers/CellRendererRegistry';
import { useActionHandler } from '~/framework/meta/hooks/useActionHandler';
import { useToastContext } from '~/contexts/ToastContext';
import { DataSourceProvider } from '~/framework/meta/contexts/DataSourceContext';
import { createFieldRenderer } from '~/framework/meta/utils/createFieldRenderer';
import { ErrorAlert } from '~/ui/ErrorAlert';
import { useAuth } from '~/contexts/AuthContext';
import { ListPageHeader } from './list/ListPageHeader';
import { useSavedViews } from '~/framework/smart/hooks/useSavedViews';
import { useAutoSaveView } from '~/framework/smart/hooks/useAutoSaveView';
import {
  DEFAULT_ROW_HEIGHT,
  type ViewFilterConfig,
  type RowHeight,
  type SavedViewCreateRequest,
  type ColumnConfig as ViewColumnConfig,
  type ViewType,
  type ViewScope,
  type SortConfig,
} from '~/framework/smart/types/savedView';
import {
  evaluateConditionalFormats,
  buildConditionalStyle,
} from '~/framework/smart/utils/conditionalFormatEvaluator';
import { SmartViewRenderer } from '~/framework/smart/components/view/SmartViewRenderer';
import { modelService } from '~/shared/services/modelService';
import { useTimezone } from '~/contexts/TimezoneContext';
import { deriveTestId } from '~/framework/meta/rendering/utils/deriveTestId';
import { ListTabs } from './list/ListTabs';
import { ListPagination } from './list/ListPagination';
import { ListModals } from './list/ListModals';
import { ListToolbar } from './list/ListToolbar';
import { ListTable } from './list/ListTable';
import { encodeSorts, decodeSorts } from './list/useListUrlState';
import { resolveListRowClickMode } from './list/rowClickNavigation';
import { savedViewService } from '~/shared/services/savedViewService';
import { useDebouncedValue, useDebouncedCallback } from '~/hooks/useDebouncedValue';
import { evaluateVisibleWhen as evaluateVisibleWhenExpression } from './utils/visibleWhen';

// Dict data item type
interface DictItem {
  value: string;
  label: string;
  extension?: Record<string, any>;
}

interface DynamicEntity {
  [key: string]: any;
  id?: string;
  pid?: string;
}

interface PaginationResult<T> {
  records: T[];
  total: number;
  page: number;
  pageSize: number;
  totalPages: number;
}

interface InviteCodeData {
  code: string;
  expiredAt?: string;
  createdAt?: string;
}

interface TenantMemberImportError {
  rowNumber: number;
  name?: string | null;
  email?: string | null;
  reason: string;
}

interface TenantMemberImportResult {
  totalRows: number;
  successCount: number;
  errorCount: number;
  existingUserBoundCount: number;
  invitedCount: number;
  employeeCreatedCount: number;
  errors: TenantMemberImportError[];
}

// Quick filter chip definitions
type QuickFilterKey = 'my_records' | 'created_today' | 'modified_this_week';

// List Page Content Component
export function ListPageContent(props: PageContentProps) {
  const { schema, tableName, token, listExtensions } = props;
  const { user } = useAuth();
  const { showSuccessToast, showErrorToast, showWarningToast, showInfoToast } = useToastContext();
  const showToast = useCallback(
    (message: string, type: 'success' | 'error' | 'warning' | 'info') => {
      switch (type) {
        case 'success':
          showSuccessToast(message);
          break;
        case 'error':
          showErrorToast(message);
          break;
        case 'warning':
          showWarningToast(message);
          break;
        case 'info':
          showInfoToast(message);
          break;
      }
    },
    [showSuccessToast, showErrorToast, showWarningToast, showInfoToast],
  );

  // Parse filter_* params from URL for drill-down navigation
  const [searchParams, setSearchParams] = useSearchParams();
  const urlFilters = useMemo(() => {
    const filters: Record<string, any> = {};
    searchParams.forEach((value, key) => {
      if (key.startsWith('filter_')) {
        const fieldName = key.replace('filter_', '');
        filters[fieldName] = value;
      }
    });
    return filters;
  }, [searchParams]);
  const urlPageNum = useMemo(() => {
    const raw = searchParams.get('pageNum');
    if (!raw) return null;
    const parsed = Number(raw);
    return Number.isFinite(parsed) && parsed >= 1 ? Math.floor(parsed) : null;
  }, [searchParams]);
  const urlPageSize = useMemo(() => {
    const raw = searchParams.get('pageSize');
    if (!raw) return null;
    const parsed = Number(raw);
    return Number.isFinite(parsed) && parsed >= 1 ? Math.floor(parsed) : null;
  }, [searchParams]);

  // State management - P2-1 fix: merged into single state
  const [data, setData] = useState<DynamicEntity[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [bulkEditOpen, setBulkEditOpen] = useState(false);
  const [pageState, setPageState] = useState(() => ({
    filters: { ...urlFilters } as Record<string, any>,
    pagination: {
      current: urlPageNum ?? 1,
      pageSize: urlPageSize ?? 20,
      total: 0,
    },
  }));

  // P2-1 fix: destructure state for convenience
  const { filters, pagination } = pageState;

  // Read initial sorts, keyword, and view from URL search params
  const urlSorts = useMemo(() => decodeSorts(searchParams.get('sort')), [searchParams]);
  const urlKeyword = useMemo(() => searchParams.get('keyword') || '', [searchParams]);
  const urlViewPid = useMemo(() => searchParams.get('view') || null, [searchParams]);

  // Search keyword for toolbar search input
  const [keyword, _setKeyword] = useState(urlKeyword);
  const keywordRef = useRef(keyword);
  const tabRequestSeqRef = useRef(0);

  // Debounced URL sync for keyword (300ms) — keeps input responsive while
  // reducing URL/history updates during rapid typing
  const syncKeywordToUrl = useDebouncedCallback(
    (value: string) => {
      setSearchParams(
        (prev) => {
          const p = new URLSearchParams(prev);
          if (value.trim()) {
            p.set('keyword', value.trim());
          } else {
            p.delete('keyword');
          }
          return p;
        },
        { replace: true },
      );
    },
    300,
  );

  // Debounced auto-search: triggers loadData 300ms after the user stops typing,
  // so results update without requiring Enter. Reduces API calls by ~60-80%
  // compared to firing on every keystroke.
  const debouncedSearch = useDebouncedCallback(
    () => {
      if (!schema) return;
      loadData({ page: 0, size: pagination.pageSize });
    },
    300,
  );

  // Synchronous ref update ensures loadData always reads the latest keyword
  // even when called in the same event cycle as a state update (e.g., Playwright fill + Enter)
  const setKeyword = useCallback((value: string) => {
    keywordRef.current = value;
    _setKeyword(value);
    // Sync keyword to URL (debounced 300ms)
    syncKeywordToUrl(value);
    // Auto-search after user stops typing (debounced 300ms)
    debouncedSearch();
  }, [syncKeywordToUrl, debouncedSearch]);

  // Active sort state — initialized from URL > SavedView > DSL defaultSort
  const [activeSorts, setActiveSorts] = useState<SortConfig[]>(() => urlSorts);
  // Active filter chips — user-added filters via chip bar (separate from filters)
  const [chipFilters, setChipFilters] = useState<ViewFilterConfig[]>([]);
  // FilterFieldPicker state
  const [fieldPickerOpen, setFieldPickerOpen] = useState(false);
  const [fieldPickerAnchor, setFieldPickerAnchor] = useState<
    { x: number; y: number } | undefined
  >();
  // FilterValuePopover state — for editing a chip's operator + value
  const [editingChipIdx, setEditingChipIdx] = useState<number | null>(null);
  const [valuePopoverAnchor, setValuePopoverAnchor] = useState<
    { x: number; y: number } | undefined
  >();

  // Client-side grouping state
  const [groupByField, setGroupByField] = useState<string | null>(null);
  const [collapsedGroups, setCollapsedGroups] = useState<Set<string>>(new Set());

  const groupedData = useMemo(() => {
    if (!groupByField || data.length === 0) return null;
    const groups = new Map<string, DynamicEntity[]>();
    for (const row of data) {
      const key = String(row[groupByField] ?? '(empty)');
      if (!groups.has(key)) groups.set(key, []);
      groups.get(key)!.push(row);
    }
    return Array.from(groups.entries()).map(([key, rows]) => ({ key, rows, count: rows.length }));
  }, [data, groupByField]);

  const toggleGroupCollapse = useCallback((key: string) => {
    setCollapsedGroups((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }, []);

  // P2-1 fix: helper functions for updating filters and pagination
  const setFilters = useCallback((filters: Record<string, any>) => {
    setPageState((prev) => ({ ...prev, filters }));
  }, []);

  const setPagination = useCallback((pagination: any) => {
    setPageState((prev) => ({
      ...prev,
      pagination: typeof pagination === 'function' ? pagination(prev.pagination) : pagination,
    }));
  }, []);

  const shouldPersistPaginationToUrlRef = useRef(urlPageNum != null || urlPageSize != null);
  const tableBlock = useMemo(() => {
    if (!schema?.blocks) return null;
    return schema.blocks.find((block: any) => block.blockType === 'table') || null;
  }, [schema]);

  // G7 dispatch — list pages hardcode table/filters/toolbar/tabs/form-buttons
  // in the layout above, but any additional block types in the schema
  // (chart / description / rich-text / divider / stat-card / etc.) were
  // previously dropped silently. Collect them here and render via BlockRenderer
  // at the end of the list page so they appear in the UI.
  const LIST_SPECIALIZED_BLOCK_TYPES = new Set<string>([
    'table',
    'filters',
    'toolbar',
    'tabs',
    'form-buttons',
    'form-section',
  ]);
  const miscListBlocks = useMemo(() => {
    if (!schema?.blocks) return [];
    return schema.blocks.filter(
      (b: any) => !LIST_SPECIALIZED_BLOCK_TYPES.has(b.blockType),
    );
  }, [schema]);

  // Sync URL pagination params -> local state (supports refresh and browser back/forward).
  useEffect(() => {
    if (urlPageNum == null && urlPageSize == null) return;
    shouldPersistPaginationToUrlRef.current = true;
    setPageState((prev) => {
      const nextCurrent = urlPageNum ?? prev.pagination.current;
      const nextPageSize = urlPageSize ?? prev.pagination.pageSize;
      if (nextCurrent === prev.pagination.current && nextPageSize === prev.pagination.pageSize) {
        return prev;
      }
      return {
        ...prev,
        pagination: {
          ...prev.pagination,
          current: nextCurrent,
          pageSize: nextPageSize,
        },
      };
    });
  }, [urlPageNum, urlPageSize]);

  // Sync local pagination state -> URL query params (preserve existing filter_* params).
  useEffect(() => {
    if (!shouldPersistPaginationToUrlRef.current) return;
    const currentUrlPageNum = Number(searchParams.get('pageNum'));
    const currentUrlPageSize = Number(searchParams.get('pageSize'));
    const needsUpdate =
      currentUrlPageNum !== pagination.current || currentUrlPageSize !== pagination.pageSize;
    if (!needsUpdate) return;
    const nextParams = new URLSearchParams(searchParams);
    nextParams.set('pageNum', String(pagination.current));
    nextParams.set('pageSize', String(pagination.pageSize));
    setSearchParams(nextParams, { replace: true });
  }, [pagination.current, pagination.pageSize, searchParams, setSearchParams]);

  // Use usePageRuntime instead of useDynamicPageSetup
  const { runtime, dataSourceManager, t, locale, navigate } = usePageRuntime(schema, {
    token: token || undefined,
    additionalContext: {
      filters,
    },
  });

  const navigateToRecordView = useCallback(
    (recordId: string | number | null | undefined) => {
      if (recordId == null || recordId === '') {
        return;
      }
      navigate(`/p/${tableName}/view/${String(recordId)}`);
    },
    [navigate, tableName],
  );

  // Tab state for tabs
  const [activeTab, setActiveTab] = useState('all');
  const [importOpen, setImportOpen] = useState(false);
  const [viewManageOpen, setViewManageOpen] = useState(false);
  const [columnSettingsOpen, setColumnSettingsOpen] = useState(false);
  const [contextMenu, setContextMenu] = useState<{
    x: number;
    y: number;
    column: ColumnConfig;
  } | null>(null);
  const [activeQuickFilter, setActiveQuickFilter] = useState<QuickFilterKey | null>(null);
  const [filtersExpanded, setFiltersExpanded] = useState(false);
  const [filterFormVisible, setFilterFormVisible] = useState(false);
  const [activeViewType, setActiveViewType] = useState<ViewType>('table');
  const [startCreateViewMode, setStartCreateViewMode] = useState(false);
  const [inviteDialogOpen, setInviteDialogOpen] = useState(false);
  const [inviteLoading, setInviteLoading] = useState(false);
  const [inviteCodeData, setInviteCodeData] = useState<InviteCodeData | null>(null);
  const [memberImportDialogOpen, setMemberImportDialogOpen] = useState(false);
  const [memberImportFile, setMemberImportFile] = useState<File | null>(null);
  const [memberImportLoading, setMemberImportLoading] = useState(false);
  const [memberImportError, setMemberImportError] = useState<string | null>(null);
  const [memberImportResult, setMemberImportResult] = useState<TenantMemberImportResult | null>(null);
  const { formats: dateTimeFormats } = useTimezone();
  const pendingSavedViewFiltersRef = useRef<Record<string, any> | null>(null);
  const loadDataRef = useRef<
    | ((params?: { page?: number; size?: number; filters?: Record<string, any> }) => Promise<void>)
    | null
  >(null);

  // Record preview drawer state
  const [previewRecordId, setPreviewRecordId] = useState<string | null>(null);

  // SavedView integration
  const modelCode = schema?.modelCode || tableName;
  const pageKey = tableName;
  const isTenantMemberPage = modelCode === 'tenant_member' || pageKey === 'tenant_member';
  const {
    views: savedViews,
    currentView,
    selectView,
    createView,
    updateViewConfig,
    deleteView: deleteSavedView,
    setDefaultView,
    duplicateView,
    reload: reloadViews,
    loading: viewsLoading,
  } = useSavedViews({ modelCode, pageKey, autoLoad: !!schema && !listExtensions?.hideSavedViews });

  // Resolve modelPid from modelCode for ViewManagePanel field operations
  const [modelPid, setModelPid] = useState<string | undefined>();
  useEffect(() => {
    if (!modelCode) return;
    modelService
      .findByCode(modelCode)
      .then((model) => setModelPid(model.pid))
      .catch(() => setModelPid(undefined));
  }, [modelCode]);

  // Handle edit view (name, description, scope) via savedViewService + reload
  const handleEditView = useCallback(
    async (pid: string, name: string, description: string, scope: ViewScope) => {
      await savedViewService.updateView(pid, { name, description, scope });
      await reloadViews();
    },
    [reloadViews],
  );

  // Restore view from URL ?view= parameter (highest priority)
  useEffect(() => {
    if (urlViewPid && savedViews.length > 0 && !viewsLoading) {
      const match = savedViews.find((v) => v.pid === urlViewPid);
      if (match) {
        selectView(urlViewPid);
        if (match.viewType && match.viewType !== 'table') {
          setActiveViewType(match.viewType as ViewType);
        }
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [savedViews, viewsLoading]); // Run when views finish loading

  // Apply SavedView viewConfig (pagination + filters) when view changes
  useEffect(() => {
    if (!currentView?.viewConfig) return;
    const vc = currentView.viewConfig;
    if (vc.pagination?.pageSize && vc.pagination.pageSize > 0) {
      setPagination((prev: typeof pagination) => ({
        ...prev,
        pageSize: vc.pagination!.pageSize!,
      }));
    }
    // Apply saved filters
    if (vc.filters && vc.filters.length > 0) {
      const restoredFilters: Record<string, any> = {};
      vc.filters.forEach((f) => {
        restoredFilters[f.fieldCode] = f.value;
      });
      pendingSavedViewFiltersRef.current = restoredFilters;
      setFilters(restoredFilters);
    }
    // Apply saved sorts
    if (vc.sorts && vc.sorts.length > 0) {
      setActiveSorts(vc.sorts);
    }
  }, [currentView, setPagination, setFilters]);

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
          const result = await fetchResult(`/api/meta/dict/by-code/${code}/data`, {
            method: 'get',
            token: token || undefined,
          });
          if (ResultHelper.isSuccess(result) && result.data) {
            // Adapt dict data format
            const data = result.data as { items?: DictItem[] } | DictItem[];
            const items: DictItem[] = Array.isArray(data) ? data : data.items || [];
            dictDataCache.current.set(code, items);
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
      },
      locale,
      t: (key: string) => t(key),
      __dataSourceManager: dataSourceManager,
    });
  }, [locale, filters, t, dataSourceManager]);

  // Get current tab filter as QueryCondition (if tabs block exists)
  const getTabFilter = useCallback((): {
    fieldName: string;
    operator: string;
    value: string;
  } | null => {
    if (!schema?.blocks) return null;
    const tabsBlock = schema.blocks.find((block: any) => block.blockType === 'tabs');
    if (!tabsBlock?.tabs) return null;
    const currentTab = (tabsBlock.tabs as any[]).find((tab: any) => tab.key === activeTab);
    if (!currentTab?.filter) return null;
    const { field, fieldName, value, operator } = currentTab.filter;
    return { fieldName: fieldName || field, operator: operator || 'EQ', value };
  }, [schema, activeTab]);

  const displayData = useMemo(() => {
    const tabCondition = getTabFilter();
    if (!tabCondition) return data;
    const operator = String(tabCondition.operator || 'EQ').toUpperCase();
    const expected = String(tabCondition.value ?? '');
    return data.filter((record) => {
      const actual = String((record as Record<string, any>)[tabCondition.fieldName] ?? '');
      switch (operator) {
        case 'EQ':
          return actual === expected;
        case 'NE':
        case 'NEQ':
          return actual !== expected;
        default:
          return true;
      }
    });
  }, [data, getTabFilter]);

  // Build filters JSON array from tab filter + user filters
  const buildFiltersParam = useCallback(
    (
      tabCondition: { fieldName: string; operator: string; value: string } | null,
      userFilters?: Record<string, any>,
      chipFiltersList?: ViewFilterConfig[],
    ) => {
      const conditions: Array<{ fieldName: string; operator: string; value: string }> = [];
      if (tabCondition) {
        conditions.push(tabCondition);
      }
      // Convert user filters (key-value from filters) to QueryCondition format
      if (userFilters) {
        for (const [key, value] of Object.entries(userFilters)) {
          if (value == null || value === '') continue;
          // Handle date range objects { start, end }
          if (typeof value === 'object' && ('start' in value || 'end' in value)) {
            if (value.start) {
              conditions.push({ fieldName: key, operator: 'gte', value: String(value.start) });
            }
            if (value.end) {
              conditions.push({ fieldName: key, operator: 'lte', value: String(value.end) });
            }
          } else {
            const fieldConfig = ((filterBlock?.fields || []) as FieldConfig[]).find(
              (field) => field.field === key,
            );
            const explicitOperator = (fieldConfig as any)?.operator || fieldConfig?.props?.operator;
            const operator =
              typeof explicitOperator === 'string' && explicitOperator.trim()
                ? explicitOperator.trim().toUpperCase()
                : fieldConfig?.component === 'SmartInput'
                  ? 'LIKE'
                  : 'EQ';
            const textValue = String(value);
            conditions.push({
              fieldName: key,
              operator,
              value: operator === 'LIKE' ? `%${textValue}%` : textValue,
            });
          }
        }
      }
      // Merge chip filters (from FilterChipBar) into conditions
      if (chipFiltersList) {
        for (const cf of chipFiltersList) {
          if (!cf.fieldCode) continue;
          // Skip unary operators (isNull/isNotNull) that don't need a value
          if (cf.operator === 'isNull' || cf.operator === 'isNotNull') {
            conditions.push({
              fieldName: cf.fieldCode,
              operator: cf.operator.toUpperCase(),
              value: '',
            });
            continue;
          }
          if (cf.value == null || cf.value === '') continue;
          const op = cf.operator?.toUpperCase() || 'EQ';
          const val = String(cf.value);
          conditions.push({
            fieldName: cf.fieldCode,
            operator: op,
            value: op === 'LIKE' ? `%${val}%` : val,
          });
        }
      }
      return conditions.length > 0 ? JSON.stringify(conditions) : undefined;
    },
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

  // Load data from API - P2-1 fix: use destructured pagination
  const loadData = useCallback(
    async (params?: { page?: number; size?: number; filters?: Record<string, any> }) => {
      if (!schema) return;

      try {
        setLoading(true);
        setError(null);

        // Resolve endpoint: use API datasource endpoint if configured, else default dynamic table
        let endpoint: string;
        let method: 'get' | 'post' | 'put' | 'delete' | 'patch' | 'options' = 'get';
        const isApiDatasource = schema.dataSource?.type === 'api' && schema.dataSource.endpoint;
        if (isApiDatasource) {
          endpoint = schema.dataSource!.endpoint!;
          method = (schema.dataSource!.method as typeof method) || 'get';
        } else {
          // Use schema.modelCode for API endpoint when available (supports NQ-backed pages
          // where URL tableName differs from actual model, e.g., /p/crm-my-tasks → crm_activity)
          const apiTableName = schema.modelCode ? schema.modelCode : tableName;
          endpoint = `${buildApiEndpoint(apiTableName)}/list`;
        }

        const requestedPageNum = (params?.page ?? pagination.current - 1) + 1;
        const requestedPageSize = params?.size ?? pagination.pageSize;
        const requestedPageZeroBased = Math.max(requestedPageNum - 1, 0);
        const queryParams: Record<string, any> = {};

        if (isApiDatasource) {
          queryParams.page = requestedPageZeroBased;
          queryParams.size = requestedPageSize;
        } else {
          queryParams.pageNum = requestedPageNum;
          queryParams.pageSize = requestedPageSize;
        }

        if (isApiDatasource) {
          // For API datasources, pass filter values as individual query params
          if (params?.filters) {
            for (const [key, value] of Object.entries(params.filters)) {
              if (value == null || value === '') continue;
              // Handle date range objects { start, end }
              if (typeof value === 'object' && ('start' in value || 'end' in value)) {
                if (value.start) queryParams[`${key}_start`] = String(value.start);
                if (value.end) queryParams[`${key}_end`] = String(value.end);
              } else {
                queryParams[key] = String(value);
              }
            }
          }
          if (activeSorts.length === 1) {
            queryParams.sortField = activeSorts[0].fieldCode;
            queryParams.sortOrder = activeSorts[0].direction;
          } else if (tableBlock?.defaultSort?.field) {
            queryParams.sortField = tableBlock.defaultSort.field;
            queryParams.sortOrder = String(tableBlock.defaultSort.order || 'desc').toLowerCase();
          }
        } else {
          // For standard dynamic tables, use JSON filters array
          const tabCondition = getTabFilter();
          const filtersParam = buildFiltersParam(tabCondition, params?.filters, chipFilters);
          if (filtersParam) {
            queryParams.filters = filtersParam;
          }
          // Use active sorts (user-driven) > SavedView sorts > DSL defaultSort
          // Multi-field sort: use sortFields param (field:direction pairs) when >1 sort
          if (activeSorts.length > 1) {
            queryParams.sortFields = activeSorts
              .map((s) => `${s.fieldCode}:${s.direction}`)
              .join(',');
          } else if (activeSorts.length === 1) {
            queryParams.sortField = activeSorts[0].fieldCode;
            queryParams.sortOrder = activeSorts[0].direction;
          } else if (tableBlock?.defaultSort?.field) {
            queryParams.sortField = tableBlock.defaultSort.field;
            queryParams.sortOrder = String(tableBlock.defaultSort.order || 'desc').toLowerCase();
          }
        }

        // Pass keyword search to backend (supported by DynamicController)
        // Use ref to get latest value — avoids stale closure when Enter triggers
        // in the same React cycle as the keyword state update
        const currentKeyword = keywordRef.current;
        if (currentKeyword.trim()) {
          queryParams.keyword = currentKeyword.trim();
        }

        // When page uses namedQuery data source, pass queryCode to backend
        if (namedQueryCode) {
          queryParams.queryCode = namedQueryCode;
        }

        const result = await fetchResult<PaginationResult<DynamicEntity>>(endpoint, {
          method,
          params: queryParams,
          token: token || undefined,
        });

        if (ResultHelper.isSuccess(result) && result.data) {
          // Handle both paginated ({ records, total, current }) and flat array responses
          const responseData = result.data;
          if (Array.isArray(responseData)) {
            // API returned flat array — client-side pagination
            const start = requestedPageZeroBased * requestedPageSize;
            const sliced = responseData.slice(start, start + requestedPageSize);
            setData(sliced as DynamicEntity[]);
            setPageState((prev) => ({
              ...prev,
              pagination: {
                ...prev.pagination,
                total: responseData.length,
                current: requestedPageNum,
              },
            }));
          } else {
            const records = responseData.records ?? [];
            const currentPage = Number(responseData.page ?? requestedPageNum) || requestedPageNum;
            const total = Number(responseData.total ?? 0);
            setData(records);
            setPageState((prev) => ({
              ...prev,
              pagination: {
                ...prev.pagination,
                total,
                current: currentPage,
              },
            }));
          }
        } else {
          setError(result.desc || t('common.loadDataError') || 'Failed to load data');
        }
      } catch (err) {
        setError(
          err instanceof Error ? err.message : t('common.loadDataError') || 'Failed to load data',
        );
      } finally {
        setLoading(false);
      }
    },
    [
      schema,
      tableName,
      token,
      pagination.current,
      pagination.pageSize,
      getTabFilter,
      buildFiltersParam,
      namedQueryCode,
      tableBlock,
      activeSorts,
      chipFilters,
    ],
  );

  useEffect(() => {
    loadDataRef.current = loadData;
  }, [loadData]);

  useEffect(() => {
    const restoredFilters = pendingSavedViewFiltersRef.current;
    if (!restoredFilters) return;
    pendingSavedViewFiltersRef.current = null;
    loadData({ page: 0, size: pagination.pageSize, filters: restoredFilters });
  }, [currentView?.pid, loadData, pagination.pageSize]);

  // Use unified action handler hook
  // IMPORTANT: Must be declared before any useEffect that references handleAction
  // to avoid temporal dead zone ("Cannot access 'handleAction' before initialization").
  const { handleAction } = useActionHandler({
    runtime,
    navigate,
    tableName,
    context: {
      loadData,
      filters,
      setFilters,
      pagination,
      setPagination,
    },
    dataSourceManager,
    locale,
    t,
    token: token || undefined,
    showToast,
    onError: (err) => setError(err.message),
  });

  // Listen for cell-button-click events from Button field renderer (GAP-131)
  useEffect(() => {
    const handler = (e: Event) => {
      const detail = (e as CustomEvent).detail;
      if (detail?.commandCode && detail?.record) {
        handleAction({ code: detail.commandCode, action: 'executeCommand' } as any, detail.record);
      }
    };
    window.addEventListener('cell-button-click', handler);
    return () => window.removeEventListener('cell-button-click', handler);
  }, [handleAction]);

  // Initial data load - only execute once when schema is loaded
  // Pass current filters (which may include URL filter_* params) for the first load
  useEffect(() => {
      if (schema) {
        loadDataRef.current?.({ page: pagination.current - 1, size: pagination.pageSize, filters });
      }
    // Intentionally only react to schema changes.
    // Pagination or filter updates are handled by explicit user actions.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [schema]);

  useEffect(() => {
    listExtensions?.onDataChange?.(data);
  }, [data, listExtensions]);

  useEffect(() => {
    const eventName = listExtensions?.reloadEventName;
    if (!eventName) {
      return;
    }

    const handleReload = () => {
      loadDataRef.current?.({ page: pagination.current - 1, size: pagination.pageSize, filters });
    };

    window.addEventListener(eventName, handleReload);
    return () => window.removeEventListener(eventName, handleReload);
  }, [filters, pagination.current, pagination.pageSize, listExtensions?.reloadEventName]);

  // Handle tab change - reload data with tab filter
  const handleTabChange = useCallback(
    (tabKey: string) => {
      setActiveTab(tabKey);
      const requestSeq = ++tabRequestSeqRef.current;
      // Compute tab filter directly (don't rely on getTabFilter since activeTab is stale in closure)
      let tabCondition: { fieldName: string; operator: string; value: string } | null = null;
      if (schema?.blocks) {
        const tabsBlock = schema.blocks.find((block: any) => block.blockType === 'tabs');
        const tabDef = (tabsBlock?.tabs as any[])?.find((t: any) => t.key === tabKey);
        if (tabDef?.filter) {
          tabCondition = {
            fieldName: tabDef.filter.fieldName || tabDef.filter.field,
            operator: tabDef.filter.operator || 'EQ',
            value: tabDef.filter.value,
          };
        }
      }

      // Call loadData with pre-built params (bypass getTabFilter to avoid stale closure)
      (async () => {
        try {
          setLoading(true);
          setError(null);

          // Resolve endpoint: API datasource vs standard dynamic table
          let endpoint: string;
          let method: 'get' | 'post' = 'get';
          const isApiDatasource = schema?.dataSource?.type === 'api' && schema.dataSource.endpoint;
          if (isApiDatasource) {
            endpoint = schema!.dataSource!.endpoint!;
            method = (schema!.dataSource!.method as 'get' | 'post') || 'get';
          } else {
            const apiTableName = schema?.modelCode
              ? schema.modelCode
              : tableName;
            endpoint = `${buildApiEndpoint(apiTableName)}/list`;
          }

          // Build query params
          const queryParams: Record<string, any> = {};
          if (isApiDatasource) {
            queryParams.page = 0;
            queryParams.size = pagination.pageSize;
          } else {
            queryParams.pageNum = 1;
            queryParams.pageSize = pagination.pageSize;
          }
          if (isApiDatasource) {
            // API datasource: tab filter as individual query param
            if (tabCondition) {
              queryParams[tabCondition.fieldName] = tabCondition.value;
            }
            if (filters) {
              for (const [key, value] of Object.entries(filters)) {
                if (value == null || value === '') continue;
                if (typeof value === 'object' && ('start' in value || 'end' in value)) {
                  if (value.start) queryParams[`${key}_start`] = String(value.start);
                  if (value.end) queryParams[`${key}_end`] = String(value.end);
                } else {
                  queryParams[key] = String(value);
                }
              }
            }
            if (activeSorts.length === 1) {
              queryParams.sortField = activeSorts[0].fieldCode;
              queryParams.sortOrder = activeSorts[0].direction;
            } else if (tableBlock?.defaultSort?.field) {
              queryParams.sortField = tableBlock.defaultSort.field;
              queryParams.sortOrder = String(tableBlock.defaultSort.order || 'desc').toLowerCase();
            }
          } else {
            const filtersParam = buildFiltersParam(tabCondition, filters, chipFilters);
            if (filtersParam) {
              queryParams.filters = filtersParam;
            }
          }

          const result = await fetchResult<PaginationResult<DynamicEntity>>(endpoint, {
            method,
            params: queryParams,
            token: token || undefined,
          });
          if (requestSeq !== tabRequestSeqRef.current) return;
          if (ResultHelper.isSuccess(result) && result.data) {
            const responseData = result.data;
            if (Array.isArray(responseData)) {
              // API returned flat array — client-side pagination
              const sliced = (responseData as any[]).slice(0, pagination.pageSize);
              setData(sliced as DynamicEntity[]);
              setPageState((prev) => ({
                ...prev,
                pagination: {
                  ...prev.pagination,
                  total: (responseData as any[]).length,
                  current: 1,
                },
              }));
            } else {
              const records = responseData.records ?? [];
              const currentPage = Number(responseData.page ?? 1) || 1;
              const total = Number(responseData.total ?? 0);
              setData(records);
              setPageState((prev) => ({
                ...prev,
                pagination: {
                  ...prev.pagination,
                  total,
                  current: currentPage,
                },
              }));
            }
          } else {
            setError(result.desc || t('common.loadDataError') || 'Failed to load data');
          }
        } catch (err) {
          if (requestSeq !== tabRequestSeqRef.current) return;
          setError(
            err instanceof Error ? err.message : t('common.loadDataError') || 'Failed to load data',
          );
        } finally {
          if (requestSeq === tabRequestSeqRef.current) {
            setLoading(false);
          }
        }
      })();
    },
    [schema, buildFiltersParam, filters, pagination.pageSize, tableName, token, t, activeSorts, tableBlock],
  );

  // Evaluate visibleWhen expression against a row record
  const evaluateVisibleWhen = useCallback(
    (visibleWhen: string | undefined, record?: Record<string, any>): boolean => {
      return evaluateVisibleWhenExpression(visibleWhen, {
        record: record || {},
        row: record || {},
        form: record || {},
      });
    },
    [],
  );

  // Resolve button display label
  const resolveButtonLabel = useCallback(
    (button: ButtonConfig): string => {
      if (button.content) {
        return getLocalizedText(button.content, locale, t);
      }
      if (button.label) {
        return getLocalizedText(button.label, locale, t);
      }
      // Try plugin-namespaced key from commandCode (e.g., "crm:contact_lead" → "crm.action.contact_lead")
      if (button.commandCode && button.commandCode.includes(':')) {
        const [ns, actionCode] = button.commandCode.split(':');
        const nsKey = `${ns}.action.${actionCode}`;
        const nsResolved = t(nsKey);
        if (nsResolved && nsResolved !== nsKey) return nsResolved;
      }
      if (button.label) {
        const labelStr = typeof button.label === 'string' ? button.label : undefined;
        if (labelStr) {
          const labelResolved = t(labelStr);
          if (labelResolved && labelResolved !== labelStr) return labelResolved;
          return labelStr;
        }
      }
      if (button.action && typeof button.action === 'string') {
        // Map action values to i18n keys
        const actionKeyMap: Record<string, string> = {
          edit: 'update',
          navigate: 'view',
          detail: 'view',
        };
        const i18nAction = actionKeyMap[button.action] || button.action;
        const i18nKey = `action.${i18nAction}`;
        const translated = t(i18nKey);
        if (translated && translated !== i18nKey) {
          return translated;
        }
      }
      // Try button code as i18n key
      const codeKey = `action.${button.code}`;
      const codeResolved = t(codeKey);
      if (codeResolved && codeResolved !== codeKey) return codeResolved;
      return button.code;
    },
    [locale, t],
  );

  // Handle search - using ActionRegistry
  const handleSearch = useCallback(() => {
    actionRegistry.execute('search', {
      args: {},
      navigate,
      tableName,
      loadData,
      filters,
      setFilters,
      pagination,
      setPagination,
      dataSourceManager,
      locale,
      t,
      token: token || undefined,
      fetchResult,
      buildApiEndpoint,
    });
  }, [filters, loadData, navigate, tableName, dataSourceManager, locale, t, token, pagination]);

  // Handle reset - using ActionRegistry
  const handleReset = useCallback(() => {
    actionRegistry.execute('reset', {
      args: {},
      navigate,
      tableName,
      loadData,
      filters,
      setFilters,
      pagination,
      setPagination,
      dataSourceManager,
      locale,
      t,
      token: token || undefined,
      fetchResult,
      buildApiEndpoint,
    });
  }, [loadData, navigate, tableName, dataSourceManager, locale, t, token, filters, pagination]);

  // Handle pagination - P2-1 fix: use destructured pagination
  const handlePageChange = useCallback(
    (page: number) => {
      shouldPersistPaginationToUrlRef.current = true;
      setPagination((prev: typeof pagination) => ({ ...prev, current: page }));
      loadData({ page: page - 1, filters });
    },
    [filters, loadData, setPagination],
  );

  const handlePageSizeChange = useCallback(
    (pageSize: number) => {
      shouldPersistPaginationToUrlRef.current = true;
      setPagination((prev: typeof pagination) => ({ ...prev, current: 1, pageSize }));
      loadData({ page: 0, size: pageSize, filters });
    },
    [filters, loadData, setPagination],
  );

  // Column header sort toggle: none → asc → desc → none
  // Shift+click appends to multi-sort, regular click replaces
  const toggleSort = useCallback((fieldCode: string, multiSort = false) => {
    setActiveSorts((prev) => {
      const existing = prev.find((s) => s.fieldCode === fieldCode);
      let next: SortConfig[];
      if (!existing) {
        // Add new sort
        const newSort: SortConfig = { fieldCode, direction: 'asc', priority: prev.length };
        next = multiSort ? [...prev, newSort] : [newSort];
      } else if (existing.direction === 'asc') {
        // asc → desc
        next = multiSort
          ? prev.map((s) => (s.fieldCode === fieldCode ? { ...s, direction: 'desc' as const } : s))
          : [{ fieldCode, direction: 'desc', priority: 0 }];
      } else {
        // desc → clear
        next = multiSort ? prev.filter((s) => s.fieldCode !== fieldCode) : [];
      }
      return next;
    });
  }, []);

  // Debounced re-fetch when sorts or chip filters change (150ms).
  // Prevents multiple rapid API calls when users adjust multiple filters
  // or sort columns in quick succession.
  const debouncedSortFilterValues = useDebouncedValue(
    useMemo(() => ({ activeSorts, chipFilters }), [activeSorts, chipFilters]),
    150,
  );

  useEffect(() => {
    if (!schema) return;
    loadData({ page: 0, size: pagination.pageSize, filters });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [debouncedSortFilterValues]);

  // Auto-save sorts to SavedView (debounced) + sync to URL
  useEffect(() => {
    if (!schema) return;
    autoSave({ sorts: activeSorts });
    // Sync sorts to URL
    setSearchParams(
      (prev) => {
        const p = new URLSearchParams(prev);
        const encoded = encodeSorts(activeSorts);
        if (encoded) {
          p.set('sort', encoded);
        } else {
          p.delete('sort');
        }
        return p;
      },
      { replace: true },
    );
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeSorts]);

  const handleImportComplete = useCallback(() => {
    setImportOpen(false);
    loadData({ page: 0, size: pagination.pageSize, filters });
  }, [loadData, pagination.pageSize, filters]);

  // Bulk selection handlers
  const toggleRowSelection = useCallback((recordId: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(recordId)) next.delete(recordId);
      else next.add(recordId);
      return next;
    });
  }, []);

  const toggleSelectAll = useCallback(() => {
    setSelectedIds((prev) => {
      if (prev.size === data.length && data.length > 0) return new Set();
      return new Set(data.map((r) => r.pid || r.id || '').filter(Boolean));
    });
  }, [data]);

  const handleBulkDelete = useCallback(
    async (ids: string[]) => {
      const mc = (schema?.modelCode || tableName);
      const resp = await fetchResult(`/api/dynamic/${mc}/batch`, {
        method: 'delete',
        params: ids,
        token: token || undefined,
      });
      if (ResultHelper.isSuccess(resp)) {
        setSelectedIds(new Set());
        loadData({ page: 0, size: pagination.pageSize, filters });
      }
    },
    [schema?.modelCode, tableName, token, loadData, pagination.pageSize, filters],
  );

  const handleBulkEditComplete = useCallback(() => {
    setBulkEditOpen(false);
    setSelectedIds(new Set());
    loadData({ page: 0, size: pagination.pageSize, filters });
  }, [loadData, pagination.pageSize, filters]);

  // Render filter field using utility (P1-5 fix: extract repeated logic)
  const renderSmartField = useMemo(
    () => createFieldRenderer(filters, setFilters, pageContext),
    [filters, pageContext],
  );

  const inferValueType = useCallback(
    (column: ColumnConfig, value: any, record?: DynamicEntity): ColumnConfig['valueType'] => {
      if (column.valueType) {
        return column.valueType;
      }
      const field = column.field || '';
      // REFERENCE field: either ends with _id, or has a {field}_display sibling in the record
      if (field.endsWith('_id') || (record && record[`${field}_display`] !== undefined)) {
        return 'reference';
      }
      if (field.endsWith('_at')) {
        return 'datetime';
      }
      if (field.endsWith('_date')) {
        return 'date';
      }
      if (field.endsWith('_time')) {
        return 'time';
      }
      if (typeof value === 'string' && /\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(value)) {
        return 'datetime';
      }
      // Detect boolean values (native boolean or string "true"/"false")
      if (typeof value === 'boolean' || value === 'true' || value === 'false') {
        return 'boolean' as any;
      }
      return undefined;
    },
    [],
  );

  // Render cell content using CellRendererRegistry
  const renderCellContent = useCallback(
    (column: ColumnConfig, record: DynamicEntity, rowIndex: number) => {
      const value = record[column.field];
      const effectiveValueType = inferValueType(column, value, record);

      // Null/undefined handling
      if (!((column as any).allowNullRenderer === true) && (value === null || value === undefined)) {
        return <span className="text-gray-400">-</span>;
      }

      // If dictCode exists, try to translate value to label
      if (column.dictCode) {
        const dictItems = dictDataCache.current.get(column.dictCode);
        if (dictItems) {
          const item = dictItems.find((i) => String(i.value) === String(value));
          if (item) {
            // Display dict label with tag style, color from extension.color
            const tagColor = item.extension?.color || 'blue';
            const colorMap: Record<string, string> = {
              gray: 'bg-gray-100 text-gray-800',
              red: 'bg-red-100 text-red-800',
              orange: 'bg-orange-100 text-orange-800',
              yellow: 'bg-yellow-100 text-yellow-800',
              green: 'bg-green-100 text-green-800',
              blue: 'bg-blue-100 text-blue-800',
              indigo: 'bg-indigo-100 text-indigo-800',
              purple: 'bg-purple-100 text-purple-800',
              pink: 'bg-pink-100 text-pink-800',
              cyan: 'bg-cyan-100 text-cyan-800',
            };
            const colorCls = colorMap[tagColor] || colorMap.blue;
            return (
              <span
                className={`inline-flex rounded-full px-2 py-1 text-xs font-medium ${colorCls}`}
              >
                {item.label}
              </span>
            );
          }
        }
        // Dict not loaded or no match — show raw value
        return String(value);
      }

      // Handle custom render expressions first (if runtime is available)
      if (typeof column.render === 'string' && runtime) {
        try {
          const context = runtime.getContext();
          const evaluator = runtime.getEvaluator();
          const rendered = evaluator.evaluateTemplate(column.render, {
            ...context,
            row: record,
          });
          return <span dangerouslySetInnerHTML={{ __html: sanitizeHtml(rendered) }} />;
        } catch (err) {
          console.error('Render expression failed:', err);
        }
      }

      // Use CellRendererRegistry for valueType-based rendering
      return cellRendererRegistry.render(effectiveValueType, {
        value,
        record,
        column: {
          field: column.field,
          label: typeof column.label === 'string' ? column.label : undefined,
          valueType: effectiveValueType,
          format: column.format,
          dateTimeFormats,
          render: column.render,
          tagMap: (column as any).tagMap,
        },
        expressionContext: pageContext,
        locale,
        t,
        rowIndex,
      });
    },
    [runtime, pageContext, locale, t, inferValueType, dateTimeFormats],
  );

  // Inline edit: save a single field value via dynamic data PUT API
  const handleInlineSave = useCallback(
    async (field: string, value: any, record: Record<string, any>) => {
      const pid = record.pid || record.id;
      if (!pid) throw new Error('Record has no pid/id');
      const slug = (schema?.modelCode || tableName);
      const result = await fetchResult<any>(`/api/dynamic/${slug}/${pid}`, {
        method: 'put',
        token: token || undefined,
        params: { [field]: value },
      });
      if (!ResultHelper.isSuccess(result)) {
        throw new Error(result.desc || 'Save failed');
      }
      // Refresh list data
      loadDataRef.current?.({ page: pagination.current - 1, size: pagination.pageSize, filters });
    },
    [schema?.modelCode, tableName, token, pagination, filters],
  );

  // Fetch namedQuery filter schema when page uses namedQuery data source
  const [nqFilterFields, setNqFilterFields] = useState<any[] | null>(null);
  useEffect(() => {
    if (!namedQueryCode || !token) return;
    const fetchFilterSchema = async () => {
      try {
        const result = await fetchResult<any[]>(
          `${buildApiEndpoint(schema?.modelCode || tableName)}/filter-schema?queryCode=${encodeURIComponent(namedQueryCode)}`,
          { method: 'get', token: token || undefined },
        );
        if (ResultHelper.isSuccess(result) && result.data) {
          setNqFilterFields(result.data);
        }
      } catch (err) {
        console.warn('[ListPageContent] Failed to fetch namedQuery filter schema:', err);
      }
    };
    fetchFilterSchema();
  }, [namedQueryCode, token, tableName]);

  // Extract blocks from schema (UnifiedSchema.blocks)
  // NOTE: These must be computed before early returns to keep hook count stable
  const allBlocks = useMemo(() => {
    return schema?.blocks || [];
  }, [schema]);

  // Map NamedQuery uiComponent to SmartField component name
  const mapNqComponent = useCallback((uiComponent?: string): string => {
    switch (uiComponent) {
      case 'number':
      case 'numberRange':
        return 'SmartNumber';
      case 'select':
        return 'SmartSelect';
      case 'dateRange':
      case 'date':
        return 'SmartDate';
      case 'switch':
        return 'SmartSwitch';
      case 'search':
      case 'cascader':
      case 'userPicker':
        return 'SmartInput';
      default:
        return 'SmartInput';
    }
  }, []);

  const resolveModelFieldLabel = useCallback(
    (fieldCode: string) => {
      const mc = schema?.modelCode || tableName;
      const modelKey = `model.${mc}.${fieldCode}.label`;
      const modelLabel = t(modelKey);
      if (modelLabel && modelLabel !== modelKey) {
        return modelLabel;
      }
      const fieldKey = `field.${fieldCode}.label`;
      const fieldLabel = t(fieldKey);
      if (fieldLabel && fieldLabel !== fieldKey) {
        return fieldLabel;
      }
      return fieldCode;
    },
    [schema?.modelCode, tableName, t],
  );

  const filterBlock = useMemo(() => {
    const tableBlock = allBlocks.find((b: any) => b.blockType === 'table');
    const rawColumns = (tableBlock as any)?.table?.columns || (tableBlock as any)?.columns;
    const columns: ColumnConfig[] = Array.isArray(rawColumns) ? rawColumns : [];
    const columnLabelMap = new Map<string, any>();
    columns.forEach((col) => {
      if (!col.field || col.isActionColumn) return;
      if (col.label) {
        columnLabelMap.set(col.field, col.label);
      }
    });

    const patchFilterFields = (fields: FieldConfig[]): FieldConfig[] =>
      fields.map((field) => {
        if (field.label) return field;
        const columnLabel = columnLabelMap.get(field.field);
        return {
          ...field,
          label: columnLabel || resolveModelFieldLabel(field.field),
        };
      });

    const found = allBlocks.find((block: any) => block.blockType === 'filters');
    if (found) {
      const foundFields = Array.isArray((found as any).fields) ? (found as any).fields : [];
      return {
        ...found,
        fields: patchFilterFields(foundFields),
      };
    }

    // NamedQuery data source: synthesize filters from param-schema
    if (namedQueryCode && nqFilterFields && nqFilterFields.length > 0) {
      return {
        blockType: 'filters' as const,
        fields: nqFilterFields.map((f: any) => ({
          field: f.fieldCode,
          label:
            f.displayName || columnLabelMap.get(f.fieldCode) || resolveModelFieldLabel(f.fieldCode),
          component: mapNqComponent(f.uiComponent),
          props: {
            ...(f.placeholder ? { placeholder: f.placeholder } : {}),
            ...(f.defaultValue ? { defaultValue: f.defaultValue } : {}),
          },
        })),
        buttons: [],
      };
    }

    // Auto-synthesize from table searchFields
    if (
      tableBlock &&
      (tableBlock as any).searchFields &&
      Array.isArray((tableBlock as any).searchFields)
    ) {
      const searchFields = (tableBlock as any).searchFields as string[];
      return {
        blockType: 'filters' as const,
        fields: searchFields.map((fieldCode: string) => ({
          field: fieldCode,
          label: columnLabelMap.get(fieldCode) || resolveModelFieldLabel(fieldCode),
          component: 'SmartInput',
          props: {},
        })),
        buttons: [],
      };
    }

    return undefined;
  }, [allBlocks, namedQueryCode, nqFilterFields, mapNqComponent, resolveModelFieldLabel]);

  const actionBlock = useMemo(() => {
    const found = allBlocks.find(
      (block: any) => block.blockType === 'form-buttons' || block.blockType === 'toolbar',
    );
    return found;
  }, [allBlocks]);

  // System fields on every dynamic entity — not in DSL but available in API response
  const SYSTEM_FIELD_DEFS: ColumnConfig[] = [
    {
      field: 'created_at',
      label: t('common.created_at') || 'Created At',
      valueType: 'datetime' as any,
    },
    {
      field: 'updated_at',
      label: t('common.updated_at') || 'Updated At',
      valueType: 'datetime' as any,
    },
    {
      field: 'created_by',
      label: t('common.creator') || 'Created By',
    },
    {
      field: 'updated_by',
      label: t('common.modifier') || 'Updated By',
    },
  ];

  // Get columns: prefer table.columns, fallback to block-level columns
  // Then apply SavedView column visibility/order overrides
  const tableColumns: ColumnConfig[] = useMemo(() => {
    if (!tableBlock) return [];
    const cols = (tableBlock as BlockConfig).table?.columns || tableBlock.columns;
    if (!Array.isArray(cols)) return [];

    // If the block has rowActions defined at block level, synthesize an action column
    const rowActions = tableBlock.rowActions;
    let baseCols = cols as ColumnConfig[];
    if (rowActions && rowActions.length > 0) {
      const hasActionCol = cols.some((c: any) => c.isActionColumn);
      if (!hasActionCol) {
        baseCols = [
          ...cols,
          { field: '_actions', isActionColumn: true, buttons: rowActions },
        ] as ColumnConfig[];
      }
    }

    // Apply SavedView column config (visibility + order + width)
    const viewColumns = currentView?.viewConfig?.columns;
    if (!viewColumns || viewColumns.length === 0) return baseCols;

    const viewColMap = new Map(viewColumns.map((vc) => [vc.fieldCode, vc]));
    // Filter visible columns and apply order
    // Include system fields that are explicitly enabled in viewConfig
    const baseFields = new Set(baseCols.map((c) => c.field));
    const sysCols = SYSTEM_FIELD_DEFS.filter((sf) => {
      const vc = viewColMap.get(sf.field);
      return vc && vc.visible !== false && !baseFields.has(sf.field);
    });
    const allCols = [...baseCols, ...sysCols];
    const visibleCols = allCols
      .map((col) => {
        const vc = viewColMap.get(col.field);
        if (vc && vc.visible === false) return null;
        // Apply width override
        if (vc?.width) {
          return { ...col, width: vc.width };
        }
        return col;
      })
      .filter((col): col is ColumnConfig => col !== null);

    // Sort by SavedView order if specified
    const hasOrder = viewColumns.some((vc) => vc.order !== undefined && vc.order !== null);
    if (hasOrder) {
      visibleCols.sort((a, b) => {
        const orderA = viewColMap.get(a.field)?.order ?? 999;
        const orderB = viewColMap.get(b.field)?.order ?? 999;
        return orderA - orderB;
      });
    }

    return visibleCols;
  }, [tableBlock, currentView]);

  // Column order — derived from SavedView or default column order
  const [columnOrder, setColumnOrder] = useState<string[]>([]);

  // Initialize column order from SavedView or table columns
  useEffect(() => {
    const viewColumns = currentView?.viewConfig?.columns;
    if (viewColumns && viewColumns.length > 0) {
      const hasOrder = viewColumns.some((vc) => vc.order !== undefined && vc.order !== null);
      if (hasOrder) {
        const ordered = [...viewColumns]
          .filter((vc) => vc.visible !== false)
          .sort((a, b) => (a.order ?? 999) - (b.order ?? 999))
          .map((vc) => vc.fieldCode);
        setColumnOrder(ordered);
        return;
      }
    }
    // Default: use tableColumns order
    setColumnOrder(tableColumns.filter((c) => !c.isActionColumn).map((c) => c.field));
  }, [currentView, tableColumns]);

  // Resolve column label via i18n
  const resolveColumnLabel = useCallback(
    (column: ColumnConfig): string => {
      if (column.label) {
        return getLocalizedText(column.label, locale, t);
      }
      if (column.isActionColumn) {
        return t('table.actions');
      }
      const mc = (schema?.modelCode || tableName);
      const modelKey = `model.${mc}.${column.field}.label`;
      const modelLabel = t(modelKey);
      if (modelLabel !== modelKey) return modelLabel;
      const fieldKey = `field.${column.field}.label`;
      const fieldLabel = t(fieldKey);
      if (fieldLabel !== fieldKey) return fieldLabel;
      const commonKey = `common.field.${column.field}`;
      const commonLabel = t(commonKey);
      return commonLabel !== commonKey ? commonLabel : column.field;
    },
    [locale, t, schema?.modelCode, tableName],
  );

  // Column widths map from SavedView
  const columnWidths = useMemo(() => {
    const widths: Record<string, number> = {};
    const viewColumns = currentView?.viewConfig?.columns;
    if (viewColumns) {
      for (const vc of viewColumns) {
        if (vc.width) widths[vc.fieldCode] = vc.width;
      }
    }
    return widths;
  }, [currentView]);

  // Row style from conditional formats
  const getRowStyle = useCallback(
    (record: Record<string, any>): React.CSSProperties | undefined => {
      const cfStyle = evaluateConditionalFormats(
        currentView?.viewConfig?.conditionalFormats,
        record,
      );
      return buildConditionalStyle(cfStyle);
    },
    [currentView],
  );

  // Row click → navigate to detail page or open preview drawer
  const handleRowClick = useCallback(
    (record: Record<string, unknown>) => {
      if (listExtensions?.disableRowClick) {
        return;
      }
      const pid = record.pid as string | undefined;
      if (!pid) return;

      const rowClickMode = resolveListRowClickMode({
        schemaDetailNavigation: (schema as any)?.options?.detailNavigation,
        tableOnRowClick: (tableBlock as any)?.onRowClick,
        tableRowClickAction: (tableBlock as any)?.props?.rowClickAction,
      });

      if (rowClickMode === 'none') {
        return;
      }

      if (rowClickMode === 'detail') {
        // Support custom URL template with {field} placeholders
        const detailUrl = (schema as any)?.options?.detailUrl || (tableBlock as any)?.detailUrl;
        if (detailUrl) {
          const resolved = detailUrl.replace(/\{(\w+)\}/g, (_: string, key: string) =>
            String(record[key] ?? ''),
          );
          navigate(resolved);
          return;
        }
        // Resolve detail page key: check extension.relatedPages.detail first, then options.detailPageKey,
        // then fall back to the {tableName}/view/{pid} convention (which derives {tableName}_detail).
        const relatedDetailPageKey = (schema as any)?.extension?.relatedPages?.detail;
        const optionDetailPageKey = (schema as any)?.options?.detailPageKey;
        const resolvedDetailPageKey = relatedDetailPageKey || optionDetailPageKey;
        if (resolvedDetailPageKey) {
          navigate(`/p/${resolvedDetailPageKey}/view/${pid}`);
        } else {
          navigate(`/p/${tableName}/view/${pid}`);
        }
        return;
      }

      setPreviewRecordId(pid);
    },
    [schema, tableBlock, tableName, navigate, listExtensions?.disableRowClick],
  );

  // All column definitions for ColumnSettingsPanel (with labels)
  const allColumnDefs = useMemo(() => {
    if (!tableBlock) return [];
    const cols = (tableBlock as BlockConfig).table?.columns || tableBlock.columns;
    if (!Array.isArray(cols)) return [];
    const dslCols = (cols as ColumnConfig[])
      .filter((c) => !c.isActionColumn)
      .map((col) => ({
        field: col.field,
        label: col.label
          ? typeof col.label === 'string'
            ? col.label
            : getLocalizedText(col.label, locale, t)
          : (() => {
              const mc = (schema?.modelCode || tableName);
              const modelKey = `model.${mc}.${col.field}.label`;
              const modelLabel = t(modelKey);
              if (modelLabel !== modelKey) return modelLabel;
              const fieldKey = `field.${col.field}.label`;
              const fieldLabel = t(fieldKey);
              if (fieldLabel !== fieldKey) return fieldLabel;
              const commonKey = `common.field.${col.field}`;
              const commonLabel = t(commonKey);
              return commonLabel !== commonKey ? commonLabel : col.field;
            })(),
      }));
    // Append system fields (hidden by default in ColumnSettingsPanel)
    const sysDefs = SYSTEM_FIELD_DEFS.map((sf) => ({
      field: sf.field,
      label: typeof sf.label === 'string' ? sf.label : sf.field,
    }));
    return [...dslCols, ...sysDefs];
  }, [tableBlock, locale, t, tableName, schema?.modelCode]);

  // Auto-save view config — delegates upsert logic to backend
  const ensureViewAndUpdateConfig = useCallback(
    async (config: Partial<import('~/framework/smart/types/savedView').ViewConfig>) => {
      try {
        if (currentView) {
          await updateViewConfig(config);
        } else {
          // No explicit view — use backend auto-save (atomic upsert of implicit view)
          const view = await savedViewService.autoSave({
            modelCode,
            pageKey,
            viewConfig: config,
          });
          // Select the newly created implicit view so subsequent saves go through updateViewConfig
          if (view) {
            selectView(view.pid);
          }
        }
      } catch (err) {
        console.error('[ListPageContent] Failed to save view config:', err);
      }
    },
    [currentView, updateViewConfig, modelCode, pageKey, selectView],
  );

  const { autoSave } = useAutoSaveView({
    currentView,
    updateViewConfig: async (config) => {
      await ensureViewAndUpdateConfig(config);
      return currentView!;
    },
  });

  // Handle column reorder via drag-and-drop
  const handleColumnReorder = useCallback(
    (newOrder: string[]) => {
      setColumnOrder(newOrder);
      // Persist to SavedView
      const cols = newOrder.map((fieldCode, idx) => {
        const existing = currentView?.viewConfig?.columns?.find((c) => c.fieldCode === fieldCode);
        return { ...(existing || { fieldCode }), fieldCode, order: idx };
      });
      autoSave({ columns: cols });
    },
    [currentView, autoSave],
  );

  // Handle column resize
  const handleColumnResize = useCallback(
    (field: string, width: number) => {
      if (!currentView) return;
      const cols = [...(currentView.viewConfig?.columns || [])];
      const idx = cols.findIndex((c) => c.fieldCode === field);
      if (idx >= 0) {
        cols[idx] = { ...cols[idx], width };
      } else {
        cols.push({ fieldCode: field, width });
      }
      autoSave({ columns: cols });
    },
    [currentView, autoSave],
  );

  // Handle column settings save -> update SavedView
  const handleColumnSettingsSave = useCallback(
    async (columns: ViewColumnConfig[]) => {
      await ensureViewAndUpdateConfig({ columns });
    },
    [ensureViewAndUpdateConfig],
  );

  // Handle toolbar action config change -> update SavedView
  const handleToolbarConfigChange = useCallback(
    (config: import('~/framework/smart/types/savedView').ToolbarActionConfig[]) => {
      autoSave({ toolbarActions: config });
    },
    [autoSave],
  );

  // Evaluate button visibility (visibleWhen expression)
  const evaluateButtonVisible = useCallback(
    (button: ButtonConfig): boolean => {
      // If no visibleWhen expression, always visible
      if (!button.visibleWhen) return true;
      // Simple expression evaluation: treat as always visible for now
      // (full expression evaluation would use createExpressionContext)
      return true;
    },
    [],
  );

  // Build export filter conditions for toolbar
  const exportFilterConditions = useMemo(() => {
    const conditions: Array<{ field: string; operator: string; value: unknown }> = [];
    const tabCondition = getTabFilter();
    if (tabCondition) {
      conditions.push({
        field: tabCondition.fieldName,
        operator: tabCondition.operator,
        value: tabCondition.value,
      });
    }
    if (filters) {
      for (const [key, value] of Object.entries(filters)) {
        if (value == null || value === '') continue;
        if (typeof value === 'object' && ('start' in value || 'end' in value)) {
          if ((value as any).start)
            conditions.push({ field: key, operator: 'gte', value: String((value as any).start) });
          if ((value as any).end)
            conditions.push({ field: key, operator: 'lte', value: String((value as any).end) });
        } else {
          conditions.push({ field: key, operator: 'EQ', value: String(value) });
        }
      }
    }
    return conditions.length > 0 ? conditions : undefined;
  }, [filters, getTabFilter]);

  // Handle export from toolbar
  const handleExport = useCallback(
    async (format: 'xlsx' | 'csv') => {
      try {
        const res = await fetch(`/api/dynamic/${modelCode}/export`, {
          method: 'post',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            format: format === 'xlsx' ? 'excel' : 'csv',
            conditions: exportFilterConditions,
          }),
        });
        if (!res.ok) throw new Error('Export request failed');
        const data = await res.json();
        if (!ResultHelper.isSuccess(data) || !data.data?.downloadUrl) {
          throw new Error(data.desc || 'Export failed');
        }
        const link = document.createElement('a');
        link.href = data.data.downloadUrl;
        link.download = `${modelCode}_export.${format}`;
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
      } catch (err) {
        console.error('Export failed:', err);
        showErrorToast(err instanceof Error ? err.message : 'Export failed');
      }
    },
    [modelCode, exportFilterConditions, showErrorToast],
  );

  // Row height from current view config (with fallback)
  const effectiveRowHeight: RowHeight = currentView?.viewConfig?.rowHeight || DEFAULT_ROW_HEIGHT;

  // Handle row height change -> update SavedView
  const handleRowHeightChange = useCallback(
    async (height: RowHeight) => {
      await ensureViewAndUpdateConfig({ rowHeight: height });
    },
    [ensureViewAndUpdateConfig],
  );

  // Quick filter handler — toggle on/off, apply filter and reload
  const handleQuickFilter = useCallback(
    (key: QuickFilterKey) => {
      if (activeQuickFilter === key) {
        // Toggle off — clear filter and reload
        setActiveQuickFilter(null);
        setFilters({});
        loadData({ page: 0, size: pagination.pageSize, filters: {} });
        return;
      }
      setActiveQuickFilter(key);
      const today = new Date().toISOString().split('T')[0];
      const weekAgo = new Date(Date.now() - 7 * 86400000).toISOString().split('T')[0];
      let qf: Record<string, any> = {};
      switch (key) {
        case 'my_records':
          if (user?.id) {
            const createdBy = Number.parseInt(String(user.id), 10);
            if (!Number.isNaN(createdBy)) {
              qf = { created_by: createdBy };
            }
          }
          break;
        case 'created_today':
          qf = { created_at: { start: today, end: today + 'T23:59:59' } };
          break;
        case 'modified_this_week':
          qf = { updated_at: { start: weekAgo, end: today + 'T23:59:59' } };
          break;
      }
      setFilters(qf);
      loadData({ page: 0, size: pagination.pageSize, filters: qf });
    },
    [activeQuickFilter, user, setFilters, loadData, pagination.pageSize],
  );

  // Save current filters to SavedView
  const handleSaveFilters = useCallback(async () => {
    const viewFilters = Object.entries(filters)
      .filter(([, v]) => v != null && v !== '')
      .map(([field, value]) => ({
        fieldCode: field,
        operator: 'eq' as const,
        value: String(value),
      }));
    await ensureViewAndUpdateConfig({ filters: viewFilters });
  }, [filters, ensureViewAndUpdateConfig]);

  const loadCurrentInviteCode = useCallback(async () => {
    if (!isTenantMemberPage) return;
    try {
      const result = await fetchResult<InviteCodeData | null>('/api/tenant/invite-code/current', {
        method: 'get',
        token: token || undefined,
      });
      if (ResultHelper.isSuccess(result)) {
        setInviteCodeData(result.data ?? null);
      }
    } catch {
      setInviteCodeData(null);
    }
  }, [isTenantMemberPage, token]);

  useEffect(() => {
    void loadCurrentInviteCode();
  }, [loadCurrentInviteCode]);

  const handleGenerateInviteCode = useCallback(async () => {
    setInviteLoading(true);
    try {
      const result = await fetchResult<string>('/api/tenant/invite-code/generate?expiryDays=7', {
        method: 'post',
        token: token || undefined,
      });
      if (!ResultHelper.isSuccess(result) || !result.data) {
        throw new Error(result.desc || result.message || 'Failed to generate invite code');
      }
      const current = await fetchResult<InviteCodeData | null>('/api/tenant/invite-code/current', {
        method: 'get',
        token: token || undefined,
      });
      if (ResultHelper.isSuccess(current) && current.data) {
        setInviteCodeData(current.data);
      } else {
        setInviteCodeData({ code: result.data });
      }
      showSuccessToast('Invite code generated');
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to generate invite code';
      showErrorToast(message);
    } finally {
      setInviteLoading(false);
    }
  }, [showErrorToast, showSuccessToast, token]);

  const handleRevokeInviteCode = useCallback(async () => {
    if (!inviteCodeData?.code) return;
    setInviteLoading(true);
    try {
      const result = await fetchResult<boolean>(
        `/api/tenant/invite-code/revoke?code=${encodeURIComponent(inviteCodeData.code)}`,
        {
          method: 'post',
          token: token || undefined,
        },
      );
      if (!ResultHelper.isSuccess(result) || result.data !== true) {
        throw new Error(result.desc || result.message || 'Failed to revoke invite code');
      }
      setInviteCodeData(null);
      showSuccessToast('Invite code revoked');
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to revoke invite code';
      showErrorToast(message);
    } finally {
      setInviteLoading(false);
    }
  }, [inviteCodeData?.code, showErrorToast, showSuccessToast, token]);

  const resetMemberImportState = useCallback(() => {
    setMemberImportFile(null);
    setMemberImportLoading(false);
    setMemberImportError(null);
    setMemberImportResult(null);
  }, []);

  const handleTenantMemberImport = useCallback(async () => {
    if (!memberImportFile) {
      setMemberImportError('请先选择 Excel 文件');
      return;
    }

    setMemberImportLoading(true);
    setMemberImportError(null);
    try {
      const XLSX = await import('xlsx');
      const workbook = XLSX.read(await memberImportFile.arrayBuffer(), { type: 'array' });
      const firstSheet = workbook.Sheets[workbook.SheetNames[0]];
      if (!firstSheet) {
        throw new Error('Excel 文件缺少可读取的工作表');
      }
      const rows = XLSX.utils.sheet_to_json<Record<string, unknown>>(firstSheet, { defval: '' });
      const payload = rows.map((row) => ({
        name: String(row['姓名*'] ?? row['姓名'] ?? row['name'] ?? '').trim(),
        email: String(row['邮箱*'] ?? row['邮箱'] ?? row['email'] ?? '').trim(),
        phone: String(row['手机号'] ?? row['手机'] ?? row['phone'] ?? '').trim(),
        department: String(row['部门'] ?? row['department'] ?? '').trim(),
        position: String(row['职位'] ?? row['position'] ?? '').trim(),
      }));

      const response = await fetch('/api/tenant/members/import-rows', {
        method: 'post',
        headers: {
          'Content-Type': 'application/json',
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
        body: JSON.stringify(payload),
      });
      const body = await response.json().catch(() => null);
      if (!response.ok || !body || !ResultHelper.isSuccess(body)) {
        throw new Error(body?.message || body?.desc || '批量导入失败');
      }

      const result = body.data as TenantMemberImportResult;
      setMemberImportResult(result);
      if (result.successCount > 0) {
        showSuccessToast(`已导入 ${result.successCount} 条成员记录`);
        await loadDataRef.current?.({
          page: 0,
          size: pagination.pageSize,
          filters,
        });
      }
      if (result.errorCount > 0) {
        showWarningToast(`有 ${result.errorCount} 条记录导入失败，请检查错误列表`);
      }
    } catch (err) {
      const message = err instanceof Error ? err.message : '批量导入失败';
      setMemberImportError(message);
      showErrorToast(message);
    } finally {
      setMemberImportLoading(false);
    }
  }, [filters, memberImportFile, pagination.pageSize, showErrorToast, showSuccessToast, showWarningToast, token]);

  const listTabsBlock = useMemo(() => {
    const found = allBlocks.find((block: any) => block.blockType === 'tabs');
    return found;
  }, [allBlocks]);

  // Error handling — local errors only (schema loading errors handled by DynamicPageRenderer)
  if (error) {
    return (
      <ErrorAlert
        error={error}
        onRetry={() => {
          setError(null);
          window.location.reload();
        }}
      />
    );
  }

  return (
    <DataSourceProvider manager={dataSourceManager}>
      <div
        className="mx-auto w-full px-2 py-3"
        data-testid="dynamic-list"
        data-ab-testid={deriveTestId('list', modelCode, 'container')}
      >
        <div className="rounded-lg bg-white shadow-sm">
          {/* Page title, view selector, and action buttons */}
          <ListPageHeader
            title={schema.title ? getLocalizedText(schema.title, locale, t) : tableName}
            modelCode={modelCode}
            savedViews={savedViews}
            currentView={currentView}
            viewsLoading={viewsLoading}
            activeViewType={activeViewType}
            onSelectView={(pid) => {
              selectView(pid);
              // Sync view selection to URL
              setSearchParams((prev) => {
                const p = new URLSearchParams(prev);
                p.set('view', pid);
                // Clear temporary filter/sort params when switching views
                p.delete('sort');
                p.delete('keyword');
                p.delete('filters');
                return p;
              }, { replace: true });
              const view = savedViews.find((v) => v.pid === pid);
              if (view?.viewType && view.viewType !== 'table') {
                setActiveViewType(view.viewType as ViewType);
              }
            }}
            onCreateView={(viewType) => {
              if (viewType) {
                setActiveViewType(viewType);
              }
              setStartCreateViewMode(true);
              setViewManageOpen(true);
            }}
            onManageViews={() => {
              setStartCreateViewMode(false);
              setViewManageOpen(true);
            }}
            onViewTypeChange={(vt) => {
              setActiveViewType(vt);
              if (vt !== 'table' && (!currentView || currentView.viewType !== vt)) {
                const match = savedViews.find((v) => v.viewType === vt);
                if (match) selectView(match.pid);
              }
            }}
            buttons={actionBlock?.buttons || []}
            toolbarActions={currentView?.viewConfig?.toolbarActions}
            onAction={handleAction}
            onToolbarConfigChange={handleToolbarConfigChange}
            resolveLabel={resolveButtonLabel}
            evaluateVisible={evaluateButtonVisible}
            onImport={() => setImportOpen(true)}
            onExport={handleExport}
            exportFilters={exportFilterConditions}
            isTenantMemberPage={isTenantMemberPage}
            onInvite={() => setInviteDialogOpen(true)}
            onImportMembers={() => {
              resetMemberImportState();
              setMemberImportDialogOpen(true);
            }}
            hideSavedViews={listExtensions?.hideSavedViews}
            hideBuiltInImport={listExtensions?.hideBuiltInImport}
            hideBuiltInExport={listExtensions?.hideBuiltInExport}
            hideBuiltInPrint={listExtensions?.hideBuiltInPrint}
          />

          {isTenantMemberPage && memberImportDialogOpen && (
            <div
              role="dialog"
              aria-modal="true"
              data-testid="member-import-dialog"
              className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
            >
              <div className="w-full max-w-2xl rounded-lg bg-white p-6 shadow-xl">
                <div className="mb-4 flex items-center justify-between">
                  <div>
                    <h3 className="text-lg font-semibold text-gray-900">批量导入成员</h3>
                    <p className="mt-1 text-sm text-gray-500">
                      入口归属 tenant_member。导入的主语义是批量准入，不是直接维护组织树。
                    </p>
                  </div>
                  <button
                    type="button"
                    onClick={() => {
                      setMemberImportDialogOpen(false);
                      resetMemberImportState();
                    }}
                    className="text-sm text-gray-500 hover:text-gray-700"
                  >
                    关闭
                  </button>
                </div>

                <div className="space-y-4 text-sm text-gray-700">
                  <div className="rounded-md border border-blue-200 bg-blue-50 p-3">
                    <p className="font-medium text-blue-900">推荐流程</p>
                    <p className="mt-1 text-blue-800">
                      Excel 导入先处理租户成员准入，再按邮箱复用已有 User 或创建待激活账号，最后再建立组织关系。
                    </p>
                  </div>

                  <div className="rounded-md border border-gray-200 bg-gray-50 p-4">
                    <div className="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
                      <div>
                        <p className="font-medium text-gray-900">1. 下载模板</p>
                        <p className="mt-1 text-xs text-gray-500">
                          支持 `.xlsx`。必填列是姓名、邮箱；部门和职位用于补全组织关系。
                        </p>
                      </div>
                      <a
                        href="/api/tenant/members/import/template"
                        className="inline-flex items-center justify-center rounded-md border border-blue-200 bg-white px-3 py-2 text-sm font-medium text-blue-700 hover:bg-blue-50"
                      >
                        下载模板
                      </a>
                    </div>

                    <div className="mt-4">
                      <p className="font-medium text-gray-900">2. 选择文件</p>
                      <input
                        data-testid="member-import-file-input"
                        type="file"
                        accept=".xlsx,.xls"
                        onChange={(event) => {
                          const selectedFile = event.target.files?.[0] ?? null;
                          setMemberImportFile(selectedFile);
                          setMemberImportError(null);
                        }}
                        className="mt-2 block w-full rounded-md border border-gray-300 px-3 py-2 text-sm text-gray-700 file:mr-3 file:rounded-md file:border-0 file:bg-blue-600 file:px-3 file:py-2 file:text-sm file:font-medium file:text-white hover:file:bg-blue-700"
                      />
                      {memberImportFile && (
                        <p className="mt-2 text-xs text-gray-500">已选择: {memberImportFile.name}</p>
                      )}
                    </div>

                    {memberImportError && (
                      <div className="mt-3 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
                        {memberImportError}
                      </div>
                    )}

                    <div className="mt-4 flex items-center justify-end gap-2">
                      <button
                        type="button"
                        onClick={() => {
                          setMemberImportDialogOpen(false);
                          resetMemberImportState();
                        }}
                        className="rounded-md border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
                      >
                        取消
                      </button>
                      <button
                        type="button"
                        data-testid="member-import-submit"
                        disabled={!memberImportFile || memberImportLoading}
                        onClick={() => void handleTenantMemberImport()}
                        className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:bg-blue-300"
                      >
                        {memberImportLoading ? '导入中...' : '开始导入'}
                      </button>
                    </div>
                  </div>

                  <div>
                    <p className="font-medium text-gray-900">导入结果分三类</p>
                    <ul className="mt-2 list-disc space-y-1 pl-5">
                      <li>已绑定已有用户：复用现有 User，创建 TenantMember，并建立组织关系</li>
                      <li>待激活成员：创建准入记录，发送邀请，等首次设置密码后启用登录</li>
                      <li>冲突项：邮箱重复、同租户已存在成员、部门不存在、职位不存在</li>
                    </ul>
                  </div>

                  <div>
                    <p className="font-medium text-gray-900">建议模板字段</p>
                    <div className="mt-2 overflow-hidden rounded-md border border-gray-200">
                      <table className="min-w-full divide-y divide-gray-200 text-sm">
                        <thead className="bg-gray-50">
                          <tr>
                            <th className="px-3 py-2 text-left font-medium text-gray-600">字段</th>
                            <th className="px-3 py-2 text-left font-medium text-gray-600">必填</th>
                            <th className="px-3 py-2 text-left font-medium text-gray-600">说明</th>
                          </tr>
                        </thead>
                        <tbody className="divide-y divide-gray-200 bg-white">
                          <tr>
                            <td className="px-3 py-2">姓名</td>
                            <td className="px-3 py-2">是</td>
                            <td className="px-3 py-2">员工显示名称</td>
                          </tr>
                          <tr>
                            <td className="px-3 py-2">邮箱</td>
                            <td className="px-3 py-2">是</td>
                            <td className="px-3 py-2">作为账号识别键；优先用于复用已有 User</td>
                          </tr>
                          <tr>
                            <td className="px-3 py-2">手机号</td>
                            <td className="px-3 py-2">否</td>
                            <td className="px-3 py-2">补充联系方式</td>
                          </tr>
                          <tr>
                            <td className="px-3 py-2">部门</td>
                            <td className="px-3 py-2">否</td>
                            <td className="px-3 py-2">用于自动建立组织关系；不存在时进入冲突列表</td>
                          </tr>
                          <tr>
                            <td className="px-3 py-2">职位</td>
                            <td className="px-3 py-2">否</td>
                            <td className="px-3 py-2">依赖部门/职位主数据匹配</td>
                          </tr>
                        </tbody>
                      </table>
                    </div>
                  </div>

                  <div className="rounded-md border border-amber-200 bg-amber-50 p-3 text-amber-900">
                    第一版实现先固定入口和规则，不默认给新导入人员生成可直接登录的密码。新用户应走邀请激活链路。
                  </div>

                  {memberImportResult && (
                    <div
                      data-testid="member-import-result"
                      className="rounded-md border border-emerald-200 bg-emerald-50 p-4"
                    >
                      <p className="font-medium text-emerald-900">导入结果</p>
                      <div className="mt-2 grid gap-2 text-sm text-emerald-900 md:grid-cols-2">
                        <div>总行数: {memberImportResult.totalRows}</div>
                        <div>成功: {memberImportResult.successCount}</div>
                        <div>复用已有用户: {memberImportResult.existingUserBoundCount}</div>
                        <div>待激活邀请: {memberImportResult.invitedCount}</div>
                        <div>建立组织关系: {memberImportResult.employeeCreatedCount}</div>
                        <div>失败: {memberImportResult.errorCount}</div>
                      </div>

                      {memberImportResult.errors.length > 0 && (
                        <div className="mt-4 overflow-hidden rounded-md border border-amber-200 bg-white">
                          <table className="min-w-full divide-y divide-amber-100 text-sm">
                            <thead className="bg-amber-50">
                              <tr>
                                <th className="px-3 py-2 text-left font-medium text-amber-900">行号</th>
                                <th className="px-3 py-2 text-left font-medium text-amber-900">姓名</th>
                                <th className="px-3 py-2 text-left font-medium text-amber-900">邮箱</th>
                                <th className="px-3 py-2 text-left font-medium text-amber-900">原因</th>
                              </tr>
                            </thead>
                            <tbody className="divide-y divide-amber-100">
                              {memberImportResult.errors.slice(0, 10).map((item) => (
                                <tr key={`${item.rowNumber}-${item.email ?? item.name ?? 'row'}`}>
                                  <td className="px-3 py-2">{item.rowNumber}</td>
                                  <td className="px-3 py-2">{item.name || '-'}</td>
                                  <td className="px-3 py-2">{item.email || '-'}</td>
                                  <td className="px-3 py-2 text-amber-900">{item.reason}</td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                      )}
                    </div>
                  )}
                </div>

                <div className="mt-6 flex items-center justify-end gap-2">
                  <button
                    type="button"
                    onClick={() => {
                      setMemberImportDialogOpen(false);
                      resetMemberImportState();
                    }}
                    className="rounded-md border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
                  >
                    关闭
                  </button>
                </div>
              </div>
            </div>
          )}

          {isTenantMemberPage && inviteDialogOpen && (
            <div
              role="dialog"
              aria-modal="true"
              data-testid="invite-dialog"
              className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
            >
              <div className="w-full max-w-md rounded-lg bg-white p-6 shadow-xl">
                <div className="mb-4 flex items-center justify-between">
                  <h3 className="text-lg font-semibold text-gray-900">Member Invite</h3>
                  <button
                    type="button"
                    onClick={() => setInviteDialogOpen(false)}
                    className="text-sm text-gray-500 hover:text-gray-700"
                  >
                    Close
                  </button>
                </div>
                <div className="space-y-4">
                  {inviteCodeData?.code ? (
                    <>
                      <div className="rounded-md bg-gray-50 p-3">
                        <div className="mb-1 text-xs font-medium text-gray-500">
                          Current Invite Code
                        </div>
                        <div className="font-mono text-lg tracking-wider text-gray-900">
                          {inviteCodeData.code}
                        </div>
                        {inviteCodeData.expiredAt && (
                          <div className="mt-1 text-xs text-gray-500">
                            Expires at {new Date(inviteCodeData.expiredAt).toLocaleString()}
                          </div>
                        )}
                      </div>
                      <div className="flex gap-2">
                        <button
                          type="button"
                          onClick={handleGenerateInviteCode}
                          disabled={inviteLoading}
                          className="flex-1 rounded-md bg-blue-600 px-3 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-60"
                        >
                          Refresh
                        </button>
                        <button
                          type="button"
                          onClick={handleRevokeInviteCode}
                          disabled={inviteLoading}
                          className="flex-1 rounded-md bg-red-600 px-3 py-2 text-sm font-medium text-white hover:bg-red-700 disabled:opacity-60"
                        >
                          Revoke
                        </button>
                      </div>
                    </>
                  ) : (
                    <div className="space-y-3">
                      <div className="rounded-md bg-amber-50 p-3 text-sm text-amber-800">
                        No active invite code. Generate one to invite members into this tenant.
                      </div>
                      <button
                        type="button"
                        onClick={handleGenerateInviteCode}
                        disabled={inviteLoading}
                        className="w-full rounded-md bg-blue-600 px-3 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-60"
                      >
                        Generate Invite Code
                      </button>
                    </div>
                  )}
                </div>
              </div>
            </div>
          )}

          {/* List Tabs */}
          {listTabsBlock?.tabs && (listTabsBlock.tabs as any[]).length > 0 && (
            <ListTabs
              tabs={listTabsBlock.tabs as any[]}
              activeTab={activeTab}
              onTabChange={handleTabChange}
              locale={locale}
              t={t}
            />
          )}

          {/* Filter area - Using Smart Components with collapse/expand (hidden in print) */}
          {filterFormVisible && filterBlock && filterBlock.fields && filterBlock.fields.length > 0 && (
            <div
              data-testid="search-area"
              data-ab-testid={deriveTestId('list', modelCode, 'filters')}
              className="print-hide border-b border-gray-200 bg-gray-50 px-6 py-4"
              data-print="hide"
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  e.preventDefault();
                  handleSearch();
                }
              }}
            >
              <div
                className={`grid grid-cols-1 gap-4 md:grid-cols-12 ${
                  !filtersExpanded && filterBlock.fields.length > 4
                    ? 'max-h-[72px] overflow-hidden'
                    : ''
                }`}
              >
                {filterBlock.fields.map((field: FieldConfig) => (
                  <div
                    key={field.field}
                    className="min-w-0"
                    style={{
                      gridColumn: `span ${Math.min(Math.max(field.layout?.colSpan || 4, 1), 12)}`,
                    }}
                  >
                    {renderSmartField(
                      field.component === 'SmartSelect' && field.props?.inline === undefined
                        ? { ...field, props: { ...(field.props || {}), inline: true } }
                        : field,
                    )}
                  </div>
                ))}
              </div>
              <div className="mt-4 flex items-center justify-between">
                {/* Expand/collapse toggle */}
                <div>
                  {filterBlock.fields.length > 4 && (
                    <button
                      type="button"
                      data-testid="filter-toggle"
                      onClick={() => setFiltersExpanded((prev) => !prev)}
                      className="flex items-center gap-1 text-sm text-blue-600 hover:text-blue-800"
                    >
                      {filtersExpanded ? (
                        <>
                          <svg
                            className="h-4 w-4"
                            fill="none"
                            stroke="currentColor"
                            viewBox="0 0 24 24"
                          >
                            <path
                              strokeLinecap="round"
                              strokeLinejoin="round"
                              strokeWidth={2}
                              d="M5 15l7-7 7 7"
                            />
                          </svg>
                          {t('action.collapse') !== 'action.collapse'
                            ? t('action.collapse')
                            : 'Collapse'}
                        </>
                      ) : (
                        <>
                          <svg
                            className="h-4 w-4"
                            fill="none"
                            stroke="currentColor"
                            viewBox="0 0 24 24"
                          >
                            <path
                              strokeLinecap="round"
                              strokeLinejoin="round"
                              strokeWidth={2}
                              d="M19 9l-7 7-7-7"
                            />
                          </svg>
                          {t('action.expand') !== 'action.expand' ? t('action.expand') : 'Expand'}
                        </>
                      )}
                    </button>
                  )}
                </div>
                <div className="flex items-center space-x-2">
                  {/* Save current filters to view */}
                  {currentView && (
                    <button
                      type="button"
                      data-testid="filter-save"
                      onClick={handleSaveFilters}
                      className="rounded-md px-3 py-2 text-sm text-gray-500 transition-colors hover:bg-gray-100 hover:text-gray-700"
                      title="Save filters to current view"
                    >
                      <svg
                        className="mr-1 inline h-4 w-4"
                        fill="none"
                        stroke="currentColor"
                        viewBox="0 0 24 24"
                      >
                        <path
                          strokeLinecap="round"
                          strokeLinejoin="round"
                          strokeWidth={2}
                          d="M8 7H5a2 2 0 00-2 2v9a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-3m-1 4l-3 3m0 0l-3-3m3 3V4"
                        />
                      </svg>
                    </button>
                  )}
                  {/* DSL-driven buttons if defined, otherwise fallback to defaults */}
                  {filterBlock.buttons && filterBlock.buttons.length > 0 ? (
                    filterBlock.buttons.map((button: ButtonConfig) => (
                      <button
                        type="button"
                        key={button.code}
                        data-testid={`filter-btn-${button.code}`}
                        onClick={() => handleAction(button)}
                        className={`rounded-md px-4 py-2 ${
                          button.primary || button.variant === 'primary'
                            ? 'bg-blue-600 text-white hover:bg-blue-700'
                            : button.variant === 'danger' || button.danger
                              ? 'bg-red-600 text-white hover:bg-red-700'
                              : 'border border-gray-300 bg-white text-gray-600 hover:bg-gray-50'
                        }`}
                      >
                        {resolveButtonLabel(button)}
                      </button>
                    ))
                  ) : (
                    <>
                      {/* Default buttons for backward compatibility */}
                      <button
                        type="button"
                        data-testid="filter-reset"
                        onClick={handleReset}
                        className="rounded-md border border-gray-300 bg-white px-4 py-2 text-gray-600 hover:bg-gray-50"
                      >
                        {t('action.reset')}
                      </button>
                      <button
                        type="button"
                        data-testid="filter-search"
                        onClick={handleSearch}
                        className="rounded-md bg-blue-600 px-4 py-2 text-white hover:bg-blue-700"
                      >
                        {t('action.search')}
                      </button>
                    </>
                  )}
                </div>
              </div>
            </div>
          )}

          {activeViewType === 'table' ? (
            <>
              <ListToolbar
                keyword={keyword}
                onKeywordChange={setKeyword}
                onSearch={() => {
                  // Flush debounced URL sync + cancel pending auto-search, then search immediately
                  syncKeywordToUrl.flush();
                  debouncedSearch.cancel();
                  loadData({ page: 0, size: pagination.pageSize });
                }}
                filterFormVisible={filterFormVisible}
                onFilterFormToggle={() => setFilterFormVisible((prev) => !prev)}
                hasFilterBlock={!!(filterBlock && filterBlock.fields && filterBlock.fields.length > 0)}
                activeQuickFilter={activeQuickFilter}
                onQuickFilter={handleQuickFilter}
                activeSorts={activeSorts}
                onSortsChange={setActiveSorts}
                sortableColumns={tableColumns
                  .filter((c: ColumnConfig) => !c.isActionColumn && c.field && c.sortable !== false)
                  .map((c: ColumnConfig) => ({
                    field: c.field,
                    label: c.label
                      ? typeof c.label === 'string'
                        ? c.label
                        : (c.label as any)?.['zh-CN'] || c.field
                      : (() => {
                          const mc = (schema?.modelCode || tableName);
                          const key = `model.${mc}.${c.field}.label`;
                          const resolved = t(key);
                          return resolved !== key ? resolved : c.field;
                        })(),
                    valueType: c.valueType || c.sorter,
                  }))}
                rowHeight={currentView?.viewConfig?.rowHeight}
                onRowHeightChange={handleRowHeightChange}
                onColumnSettingsOpen={() => setColumnSettingsOpen(true)}
                chipFilters={chipFilters}
                onChipFiltersChange={setChipFilters}
                fieldMetadata={tableColumns
                  .filter((c: ColumnConfig) => !c.isActionColumn && c.field)
                  .map((c: ColumnConfig) => ({
                    fieldCode: c.field,
                    label: c.label
                      ? typeof c.label === 'string'
                        ? c.label
                        : (c.label as any)?.['zh-CN'] || c.field
                      : (() => {
                          const mc = (schema?.modelCode || tableName);
                          const key = `model.${mc}.${c.field}.label`;
                          const resolved = t(key);
                          return resolved !== key ? resolved : c.field;
                        })(),
                    fieldType: c.valueType || c.sorter || 'text',
                    dictCode: c.dictCode,
                  }))}
                onAddFilter={(e?: React.MouseEvent) => {
                  const rect = (e?.currentTarget as HTMLElement)?.getBoundingClientRect?.();
                  setFieldPickerAnchor(
                    rect ? { x: rect.left, y: rect.bottom + 4 } : { x: 300, y: 200 },
                  );
                  setFieldPickerOpen(true);
                }}
                onChipClick={(idx, e) => {
                  const rect = (e.currentTarget as HTMLElement).getBoundingClientRect();
                  setValuePopoverAnchor({ x: rect.left, y: rect.bottom + 4 });
                  setEditingChipIdx(idx);
                }}
                onClearAll={() => {
                  setChipFilters([]);
                  setActiveSorts([]);
                }}
              />

              {/* Table area — extracted to ListTable with DnD column reorder */}
              <ListTable
                columns={tableColumns}
                data={displayData}
                loading={loading}
                activeSorts={activeSorts}
                selectedIds={selectedIds}
                rowHeight={effectiveRowHeight}
                modelCode={modelCode}
                columnOrder={columnOrder}
                onColumnReorder={handleColumnReorder}
                onColumnResize={handleColumnResize}
                onToggleSort={toggleSort}
                onSelectRow={(id, _checked) => toggleRowSelection(id)}
                onSelectAll={() => toggleSelectAll()}
                onRowClick={handleRowClick}
                onContextMenu={(e, column) => {
                  setContextMenu({ x: e.clientX, y: e.clientY, column });
                }}
                renderCellContent={(record, column, rowIndex) =>
                  renderCellContent(column, record, rowIndex)
                }
                evaluateVisibleWhen={evaluateVisibleWhen}
                resolveButtonLabel={resolveButtonLabel}
                handleAction={handleAction}
                resolveColumnLabel={resolveColumnLabel}
                columnWidths={columnWidths}
                groupedData={groupedData}
                groupByField={groupByField ?? undefined}
                collapsedGroups={collapsedGroups}
                onToggleGroupCollapse={toggleGroupCollapse}
                getRowStyle={getRowStyle}
                previewRecordId={previewRecordId}
                t={t}
                onInlineSave={handleInlineSave}
                dictDataCache={dictDataCache.current}
                enableSelection={!!tableBlock?.table?.selection && !listExtensions?.disableRowSelection}
              />

              {/* G7 — misc blocks (chart / description / rich-text / divider /
                  stat-card / etc.) dispatched via BlockRenderer fallback
                  registry. Unknown block types surface a visible placeholder. */}
              {miscListBlocks.length > 0 && runtime && (
                <div
                  className="flex flex-col gap-4 p-4"
                  data-testid="list-misc-blocks"
                >
                  {miscListBlocks.map((block: any, idx: number) => (
                    <BlockRenderer
                      key={block.id || `misc-list-${idx}`}
                      block={block}
                      runtime={runtime}
                      areaId="list-misc"
                    />
                  ))}
                </div>
              )}

              {/* Pagination + Bulk Action Toolbar */}
              <ListPagination
                current={pagination.current}
                pageSize={pagination.pageSize}
                total={pagination.total}
                onPageChange={handlePageChange}
                onPageSizeChange={handlePageSizeChange}
                t={t}
                selectedCount={selectedIds.size}
                selectedIds={Array.from(selectedIds)}
                modelCode={modelCode}
                onBulkEdit={() => setBulkEditOpen(true)}
                onBulkDelete={handleBulkDelete}
                onClearSelection={() => setSelectedIds(new Set())}
              />
            </>
          ) : (
            <SmartViewRenderer
              view={
                {
                  ...(currentView || {}),
                  modelCode,
                  viewType: activeViewType,
                  viewConfig: currentView?.viewConfig || {},
                } as any
              }
              onGanttTaskClick={navigateToRecordView}
              onOpenViewConfig={() => setViewManageOpen(true)}
              onSwitchToTableView={() => setActiveViewType('table')}
              onCardClick={(card) => navigateToRecordView((card as any).pid || (card as any).id)}
              onEventClick={navigateToRecordView}
              onGalleryCardClick={navigateToRecordView}
              onTreeNodeClick={navigateToRecordView}
              onDataRefresh={() => loadData({ page: 0, size: pagination.pageSize })}
              linkageFilters={[]}
            />
          )}

          <ListModals
            // BulkEditModal
            bulkEditOpen={bulkEditOpen}
            onBulkEditClose={() => setBulkEditOpen(false)}
            selectedIds={Array.from(selectedIds)}
            modelCode={modelCode}
            bulkEditFields={tableColumns
              .filter((c) => !c.isActionColumn && c.field)
              .map((c) => ({
                code: c.field,
                name: c.field,
                dataType: c.valueType || 'string',
              }))}
            onBulkEditComplete={handleBulkEditComplete}
            // ImportModal
            importOpen={importOpen}
            onImportClose={() => setImportOpen(false)}
            onImportComplete={handleImportComplete}
            // ViewManagePanel
            viewManageOpen={viewManageOpen}
            onViewManageClose={() => {
              setViewManageOpen(false);
              setStartCreateViewMode(false);
            }}
            savedViews={savedViews}
            currentView={currentView}
            onCreateView={async (req: SavedViewCreateRequest) => createView(req)}
            onCreateViewSuccess={(view) => {
              const newType = (view.viewType as ViewType) || 'table';
              setActiveViewType(newType);
              setStartCreateViewMode(false);
            }}
            onDeleteView={async (pid: string) => {
              await deleteSavedView(pid);
            }}
            onDuplicateView={async (pid: string, name: string) => {
              await duplicateView(pid, name);
            }}
            onEditView={handleEditView}
            onSetDefaultView={async (pid: string) => {
              await setDefaultView(pid);
            }}
            onSelectView={(pid) => {
              selectView(pid);
              const view = savedViews.find((v) => v.pid === pid);
              setActiveViewType((view?.viewType as ViewType) || 'table');
            }}
            pageKey={pageKey}
            activeViewType={activeViewType}
            startInCreateMode={startCreateViewMode}
            modelPid={modelPid}
            onFieldsCreated={() => {
              loadData({ page: 0, size: pagination.pageSize, filters });
            }}
            onViewConfigSaved={reloadViews}
            viewManageFields={tableColumns
              .filter((c: ColumnConfig) => !c.isActionColumn && c.field)
              .map((c: ColumnConfig) => ({
                code: c.field,
                name: c.label
                  ? typeof c.label === 'string'
                    ? c.label
                    : (c.label as any)?.['zh-CN'] || c.field
                  : c.field,
                dataType: c.valueType || c.sorter || 'text',
              }))}
            // ColumnSettingsPanel
            columnSettingsOpen={columnSettingsOpen}
            onColumnSettingsClose={() => setColumnSettingsOpen(false)}
            allColumnDefs={allColumnDefs}
            viewColumns={currentView?.viewConfig?.columns}
            onColumnSettingsSave={handleColumnSettingsSave}
            t={t}
            // FilterFieldPicker
            fieldPickerOpen={fieldPickerOpen}
            fieldPickerAnchor={fieldPickerAnchor}
            fieldPickerFields={tableColumns
              .filter((c: ColumnConfig) => !c.isActionColumn && c.field)
              .map((c: ColumnConfig) => ({
                fieldCode: c.field,
                label: c.label
                  ? typeof c.label === 'string'
                    ? c.label
                    : (c.label as any)?.['zh-CN'] || c.field
                  : (() => {
                      const mc = (schema?.modelCode || tableName);
                      const key = `model.${mc}.${c.field}.label`;
                      const resolved = t(key);
                      return resolved !== key ? resolved : c.field;
                    })(),
                fieldType: c.valueType || c.sorter || 'text',
                dictCode: c.dictCode,
              }))}
            chipFilterFieldCodes={chipFilters.map((f) => f.fieldCode)}
            onFieldPickerSelect={(fieldCode) => {
              // Add a new empty filter chip and immediately open the value popover
              const newFilter: ViewFilterConfig = {
                fieldCode,
                operator: 'eq',
                value: '',
              };
              setChipFilters((prev) => {
                const next = [...prev, newFilter];
                // Open value popover for the newly added chip
                setTimeout(() => {
                  setEditingChipIdx(next.length - 1);
                  // Position near the "Add Filter" button
                  setValuePopoverAnchor(fieldPickerAnchor || { x: 300, y: 200 });
                }, 50);
                return next;
              });
              setFieldPickerOpen(false);
            }}
            onFieldPickerClose={() => setFieldPickerOpen(false)}
            // FilterValuePopover
            editingChipIdx={editingChipIdx}
            chipFilters={chipFilters}
            valuePopoverAnchor={valuePopoverAnchor}
            tableColumns={tableColumns}
            schema={schema}
            tableName={tableName}
            onFilterApply={(operator, value) => {
              setChipFilters((prev) =>
                prev.map((f, i) =>
                  i === editingChipIdx ? { ...f, operator: operator as any, value } : f,
                ),
              );
              setEditingChipIdx(null);
            }}
            onFilterCancel={() => setEditingChipIdx(null)}
            // ColumnContextMenu
            contextMenu={contextMenu}
            activeSorts={activeSorts}
            onSort={(dir) => {
              if (!contextMenu) return;
              if (dir === 'clear') {
                setActiveSorts((prev) =>
                  prev.filter((s) => s.fieldCode !== contextMenu.column.field),
                );
              } else {
                setActiveSorts([
                  { fieldCode: contextMenu.column.field, direction: dir, priority: 0 },
                ]);
              }
            }}
            onFreeze={(_pos) => {
              // Column freeze is handled via DSL config; for runtime, update SavedView
              // This would require extending ViewColumnConfig with frozen support
            }}
            onHide={() => {
              if (!currentView || !contextMenu) return;
              const cols = (currentView.viewConfig?.columns || []).map((c) =>
                c.fieldCode === contextMenu.column.field ? { ...c, visible: false } : c,
              );
              // If column not in saved config yet, add it as hidden
              if (!cols.find((c) => c.fieldCode === contextMenu.column.field)) {
                cols.push({ fieldCode: contextMenu.column.field, visible: false });
              }
              updateViewConfig({ columns: cols });
            }}
            onFilterByColumn={() => {
              // Placeholder — will be connected to FilterChipBar in integration step
            }}
            onGroupBy={() => {
              if (!contextMenu) return;
              setGroupByField((prev) =>
                prev === contextMenu.column.field ? null : contextMenu.column.field,
              );
            }}
            onContextMenuClose={() => setContextMenu(null)}
            // RecordPreviewDrawer
            previewRecordId={previewRecordId}
            previewApiEndpoint={
              schema?.dataSource?.type === 'api'
                ? schema.dataSource.endpoint || (schema.dataSource as any).url
                : undefined
            }
            previewDetailPageKey={(schema as any)?.extension?.relatedPages?.detail}
            onPreviewClose={() => setPreviewRecordId(null)}
          />
        </div>
      </div>
    </DataSourceProvider>
  );
}
