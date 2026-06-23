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
import { BlockRenderer, type PageContentProps } from '@auraboot/runtime-kernel';
import { usePageRuntime } from '~/framework/meta/rendering/pages/hooks/usePageRuntime';
import { buildApiEndpoint, getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { createExpressionContext } from '~/framework/meta/runtime/expression/context';
import { evaluateCondition } from '~/framework/meta/runtime/expression/evaluator';
import type {
  BlockConfig,
  ColumnConfig,
  FieldConfig,
  ButtonConfig,
  TableConfig,
} from '~/framework/meta/schemas/types';
import { actionRegistry } from '~/framework/meta/runtime/actions/ActionRegistry';
import { sanitizeHtml } from '~/framework/meta/utils/sanitizeHtml';
import { cellRendererRegistry } from '~/framework/meta/runtime/renderers/CellRendererRegistry';
import { useActionHandler } from '~/framework/meta/hooks/useActionHandler';
import { resolveConfirmDialog } from '~/framework/meta/utils/i18nResolver';
import { confirmDialog } from '~/utils/confirmDialog';
import {
  AsyncTaskModalProvider,
  AsyncTaskModalHost,
} from '~/framework/meta/rendering/components/AsyncTaskModalContext';
import { useToastContext } from '~/contexts/ToastContext';
import { DataSourceProvider } from '~/framework/meta/contexts/DataSourceContext';
import { createFieldRenderer } from '~/framework/meta/utils/createFieldRenderer';
import { ErrorAlert } from '~/ui/ErrorAlert';
import { useAuth } from '~/contexts/AuthContext';
import { ListPageHeader } from './list/ListPageHeader';
import { useSavedViews } from '~/framework/smart/hooks/useSavedViews';
import { useTransientFlag } from '~/hooks/useTransientFlag';
import { resolveStatusTone, StatusDot } from '~/framework/meta/runtime/renderers/statusTone';
import { useAutoSaveView } from '~/framework/smart/hooks/useAutoSaveView';
import {
  DEFAULT_ROW_HEIGHT,
  type ViewFilterConfig,
  type RowHeight,
  type SavedView,
  type SavedViewCreateRequest,
  type ColumnConfig as ViewColumnConfig,
  type ViewType,
  type ViewScope,
  type ViewConfig,
  type SortConfig,
} from '~/framework/smart/types/savedView';
import {
  evaluateConditionalFormats,
  buildConditionalStyle,
} from '~/framework/smart/utils/conditionalFormatEvaluator';
import { SmartViewRenderer } from '~/framework/smart/components/view/SmartViewRenderer';
import { modelService } from '~/shared/services/modelService';
import { dynamicService } from '~/shared/services/dynamicService';
import { useTimezone } from '~/contexts/TimezoneContext';
import { deriveTestId } from '~/framework/meta/rendering/utils/deriveTestId';
import { ListTabs } from './list/ListTabs';
import { ListPagination } from './list/ListPagination';
import { ListModals } from './list/ListModals';
import { ListToolbar } from './list/ListToolbar';
import { ListTable } from './list/ListTable';
import { areSortsEqual, encodeSorts, decodeSorts } from './list/useListUrlState';
import {
  type QuickFilterPresetKey,
  buildQuickFilterPreset,
  buildQuickFilterPresetViewRequest,
  buildQuickFilterPresetViewFilters,
  getQuickFilterPresetDefinition,
  isQuickFilterPresetKey,
} from './list/quickFilterPresets';
import { resolveListRowClickMode } from './list/rowClickNavigation';
import { SelectAllMatchingBanner } from './list/SelectAllMatchingBanner';
import {
  type SelectionState,
  createSelectionModel,
  toggleRow as selectionToggleRow,
  selectPage as selectionSelectPage,
  clearPage as selectionClearPage,
  enterAllMatching as selectionEnterAllMatching,
  clearSelection as selectionClearSelection,
  isSelected as selectionIsSelected,
  selectedCount as selectionSelectedCount,
  isPageFullySelected as selectionIsPageFullySelected,
  getExplicitIds as selectionGetExplicitIds,
  isAllMatching as selectionIsAllMatching,
} from './list/selectionModel';
import { savedViewService } from '~/shared/services/savedViewService';
import { useDebouncedValue, useDebouncedCallback } from '~/hooks/useDebouncedValue';
import { evaluateVisibleWhen as evaluateVisibleWhenExpression } from './utils/visibleWhen';
import {
  buildCommandTargetParams,
  getLegacyCompatibleRecordPid,
  getPublicRecordKey,
} from '~/framework/meta/utils/publicRecordId';
import {
  buildPersonalCopyName,
  canCopySavedView,
  getSavedViewPersistenceMode,
  isSavedViewLockedPreset,
  mergeViewConfigPatch,
  summarizeViewConfigPatch,
} from '~/framework/smart/utils/savedViewPersistence';

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

export interface ListReferenceDisplayConfig {
  field: string;
  modelCode: string;
  valueField: string;
  displayField: string;
  displayKey: string;
}

export function buildListReferenceDisplayCacheKey(config: ListReferenceDisplayConfig): string {
  return `${config.field}|${config.modelCode}|${config.valueField}|${config.displayField}`;
}

export function findPersonalPresetSavedView(
  savedViews: Array<Pick<SavedView, 'pid' | 'scope' | 'viewConfig' | 'viewType'>>,
  presetKey: QuickFilterPresetKey,
): Pick<SavedView, 'pid' | 'scope' | 'viewConfig' | 'viewType'> | undefined {
  return savedViews.find(
    (view) =>
      String(view.scope).toLowerCase() === 'personal' &&
      view.viewConfig?.meta?.originPresetKey === presetKey,
  );
}

export function getSavedQuickFilterPresetKeys(
  savedViews: Array<Pick<SavedView, 'scope' | 'viewConfig'>>,
): QuickFilterPresetKey[] {
  const keys = new Set<QuickFilterPresetKey>();
  for (const view of savedViews) {
    const key = view.viewConfig?.meta?.originPresetKey;
    if (String(view.scope).toLowerCase() === 'personal' && isQuickFilterPresetKey(key)) {
      keys.add(key);
    }
  }
  return Array.from(keys);
}

export function getActiveSavedQuickFilterPresetKey(
  view: Pick<SavedView, 'scope' | 'viewConfig'> | null | undefined,
): QuickFilterPresetKey | null {
  const key = view?.viewConfig?.meta?.originPresetKey;
  if (String(view?.scope || '').toLowerCase() !== 'personal') return null;
  return isQuickFilterPresetKey(key) ? key : null;
}

function stableStringify(value: unknown): string {
  if (value == null || typeof value !== 'object') {
    return JSON.stringify(value);
  }
  if (Array.isArray(value)) {
    return `[${value.map((item) => stableStringify(item)).join(',')}]`;
  }
  const entries = Object.entries(value as Record<string, unknown>).sort(([a], [b]) =>
    a.localeCompare(b),
  );
  return `{${entries.map(([key, item]) => `${JSON.stringify(key)}:${stableStringify(item)}`).join(',')}}`;
}

function normalizePresetFilters(filters: ViewFilterConfig[] | undefined): string[] {
  return (filters ?? [])
    .map((filter) =>
      stableStringify({
        fieldCode: filter.fieldCode,
        operator: filter.operator,
        value: filter.value,
      }),
    )
    .sort();
}

export function isPersonalPresetSavedViewEdited(
  view: Pick<SavedView, 'scope' | 'viewConfig'> | null | undefined,
  presetKey: QuickFilterPresetKey,
  ctx: { userId?: string | number; now: Date },
): boolean {
  if (getActiveSavedQuickFilterPresetKey(view) !== presetKey) return false;
  const presetFilters = buildQuickFilterPresetViewFilters(buildQuickFilterPreset(presetKey, ctx));
  return stableStringify(normalizePresetFilters(view?.viewConfig?.filters)) !==
    stableStringify(normalizePresetFilters(presetFilters));
}

export function resolveListSavedViewPageKey(
  schema: { pageKey?: string | null } | null | undefined,
  routeTableName: string,
): string {
  return schema?.pageKey || routeTableName;
}

export function useRestoreSavedViewFromUrl({
  urlViewPid,
  savedViews,
  viewsLoading,
  selectView,
  setActiveViewType,
}: {
  urlViewPid: string | null;
  savedViews: Array<{ pid: string; viewType?: string | null }>;
  viewsLoading: boolean;
  selectView: (pid: string) => void;
  setActiveViewType: (viewType: ViewType) => void;
}): void {
  useEffect(() => {
    if (!urlViewPid || savedViews.length === 0 || viewsLoading) {
      return;
    }

    const match = savedViews.find((v) => v.pid === urlViewPid);
    if (!match) {
      return;
    }

    selectView(urlViewPid);
    if (match.viewType && match.viewType !== 'table') {
      setActiveViewType(match.viewType as ViewType);
    }
  }, [urlViewPid, savedViews, viewsLoading, selectView, setActiveViewType]);
}

export function resolveTableBlockRowActions(tableBlock: any): ButtonConfig[] {
  const blockRowActions = Array.isArray(tableBlock?.rowActions) ? tableBlock.rowActions : [];
  const tableRowActions = Array.isArray(tableBlock?.table?.rowActions)
    ? tableBlock.table.rowActions
    : [];
  const rowActions = [...blockRowActions];
  for (const action of tableRowActions) {
    if (!rowActions.some((existing: any) => existing.code === action.code)) {
      rowActions.push(action);
    }
  }
  return rowActions;
}

function readRefTargetConfig(
  column: ColumnConfig,
  meta?: Record<string, any>,
): Record<string, any> {
  return {
    ...(meta?.extension?.refTarget || {}),
    ...(meta?.refTarget || {}),
    ...((column as any).extension?.refTarget || {}),
    ...((column as any).props?.refTarget || {}),
    ...((column as any).refTarget || {}),
  };
}

/**
 * Resolve a header/filter label from the model field metadata (field-meta API).
 * The importer denormalises the field's effective displayName (zh-CN first)
 * into the field extension, so dynamic pages can label columns even when the
 * page config carries no explicit label and no i18n resource exists. Returns
 * undefined when metadata is missing so callers keep their own fallbacks.
 */
export function resolveFieldMetaDisplayName(
  fieldCode: string,
  modelFieldMap: Map<string, any> | undefined,
): string | undefined {
  if (!fieldCode || !modelFieldMap) return undefined;
  const meta = modelFieldMap.get(fieldCode);
  if (!meta) return undefined;
  const candidate = meta.displayName ?? meta.extension?.displayName;
  if (typeof candidate !== 'string') return undefined;
  const trimmed = candidate.trim();
  return trimmed && trimmed !== fieldCode ? trimmed : undefined;
}

function normalizeFieldDataType(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined;
  const trimmed = value.trim();
  return trimmed ? trimmed.toLowerCase() : undefined;
}

export function resolveFieldMetaDataType(
  fieldCode: string,
  modelFieldMap: Map<string, any> | undefined,
): string | undefined {
  if (!fieldCode || !modelFieldMap) return undefined;
  const meta = modelFieldMap.get(fieldCode);
  if (!meta) return undefined;
  return (
    normalizeFieldDataType(meta.dataType) ??
    normalizeFieldDataType(meta.fieldType) ??
    normalizeFieldDataType(meta.extension?.dataType) ??
    normalizeFieldDataType(meta.extension?.fieldType)
  );
}

export function resolveColumnCapabilityDataType(
  column: { field?: string; dataType?: unknown; valueType?: unknown; sorter?: unknown },
  modelFieldMap: Map<string, any> | undefined,
): string {
  return (
    resolveFieldMetaDataType(column.field ?? '', modelFieldMap) ??
    normalizeFieldDataType(column.dataType) ??
    normalizeFieldDataType(column.valueType) ??
    normalizeFieldDataType(column.sorter) ??
    'text'
  );
}

export interface ViewManageFieldOption {
  code: string;
  name: string;
  dataType: string;
}

export function buildViewManageFieldOptions(
  tableColumns: ColumnConfig[],
  modelFieldMap: Map<string, any> | undefined,
): ViewManageFieldOption[] {
  const byCode = new Map<string, ViewManageFieldOption>();

  for (const [fieldCode] of modelFieldMap?.entries() ?? []) {
    if (!fieldCode) continue;
    byCode.set(fieldCode, {
      code: fieldCode,
      name: resolveFieldMetaDisplayName(fieldCode, modelFieldMap) ?? fieldCode,
      dataType: resolveFieldMetaDataType(fieldCode, modelFieldMap) ?? 'string',
    });
  }

  for (const column of tableColumns) {
    if (!column.field || column.isActionColumn) continue;
    const fallbackName =
      typeof column.label === 'string'
        ? column.label
        : byCode.get(column.field)?.name ?? column.field;
    byCode.set(column.field, {
      code: column.field,
      name: fallbackName,
      dataType: resolveColumnCapabilityDataType(column, modelFieldMap),
    });
  }

  return Array.from(byCode.values());
}

export function collectListReferenceDisplayConfigs(
  columns: ColumnConfig[],
  modelFieldMap: Map<string, any>,
): ListReferenceDisplayConfig[] {
  const configs: ListReferenceDisplayConfig[] = [];
  const seen = new Set<string>();

  for (const column of columns) {
    if (!column.field || column.isActionColumn) continue;
    const meta = modelFieldMap.get(column.field);
    const refTarget = readRefTargetConfig(column, meta);
    const dataType = String(
      (column as any).dataType ||
        column.valueType ||
        meta?.dataType ||
        meta?.extension?.dataType ||
        '',
    ).toLowerCase();
    const hasReferenceShape =
      dataType === 'reference' ||
      column.valueType === 'reference' ||
      column.field.endsWith('_id') ||
      Object.keys(refTarget).length > 0;
    if (!hasReferenceShape) continue;

    const modelCode = String(
      refTarget.modelCode ||
        refTarget.targetModel ||
        refTarget.targetEntity ||
        meta?.referenceModelCode ||
        meta?.extension?.referenceModelCode ||
        '',
    ).trim();
    const displayField = String(
      refTarget.displayField || refTarget.labelField || refTarget.targetField || '',
    ).trim();
    const valueField = String(
      refTarget.valueField || refTarget.targetValueField || refTarget.idField || 'pid',
    ).trim();
    if (!modelCode || !displayField || !valueField) continue;

    const config: ListReferenceDisplayConfig = {
      field: column.field,
      modelCode,
      valueField,
      displayField,
      displayKey: `${column.field}_display`,
    };
    const key = buildListReferenceDisplayCacheKey(config);
    if (seen.has(key)) continue;
    seen.add(key);
    configs.push(config);
  }

  return configs;
}

interface PaginationResult<T> {
  records: T[];
  total: number;
  page: number;
  pageSize: number;
  totalPages: number;
}

const SYSTEM_FIELD_I18N_KEYS: Record<string, string> = {
  created_at: 'common.created_at',
  updated_at: 'common.updated_at',
  created_by: 'common.creator',
  updated_by: 'common.modifier',
};

export function getSystemFieldI18nKey(fieldCode: string): string | undefined {
  return SYSTEM_FIELD_I18N_KEYS[fieldCode];
}

function translateOrFallback(t: (key: string) => string, key: string, fallback: string): string {
  const resolved = t(key);
  return resolved && resolved !== key ? resolved : fallback;
}

/**
 * Build the expression context exposed to toolbar `visibleWhen` conditions.
 * Adds the loaded list's `recordCount` (length of currently loaded rows) and
 * `total` (server total), so conditions like `visibleWhen: "recordCount == 0"`
 * (e.g. a singleton "新建" button that hides once a record exists) evaluate.
 */
export function buildToolbarConditionContext(
  list: { total: number; records: unknown[] },
  base: Record<string, any>,
): Record<string, any> {
  return { ...base, recordCount: list.records?.length ?? 0, total: list.total ?? 0 };
}

export function shouldSkipListData(
  schema: { blocks?: BlockConfig[]; extension?: Record<string, any> } | null | undefined,
): boolean {
  if (!schema) return false;
  const extension = schema.extension ?? {};
  if (extension.skipListData === true || extension.customOnly === true) {
    return true;
  }
  const blocks = Array.isArray(schema.blocks) ? schema.blocks : [];
  if (blocks.length === 0) return false;
  const hasTableBlock = blocks.some((block: any) => block.blockType === 'table');
  const hasCustomBlock = blocks.some((block: any) => block.blockType === 'custom');
  return hasCustomBlock && !hasTableBlock;
}

export function shouldSkipModelFieldMeta(
  schema:
    | { blocks?: BlockConfig[]; extension?: Record<string, any>; dataSource?: Record<string, any> }
    | null
    | undefined,
  skipListData = shouldSkipListData(schema),
): boolean {
  if (skipListData) return true;
  const extension = schema?.extension ?? {};
  return extension.skipFieldMeta === true || extension.skipDynamicFieldMeta === true;
}

export type ListMiscBlocksPosition = 'beforeTable' | 'afterTable';

export function resolveListMiscBlocksPosition(
  schema: { extension?: Record<string, any> } | null | undefined,
): ListMiscBlocksPosition {
  return schema?.extension?.miscBlocksPosition === 'beforeTable' ? 'beforeTable' : 'afterTable';
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

// Quick filter chip definitions — preset views (T8). Keys + filter logic live
// in ./list/quickFilterPresets so the toolbar and the view switcher share one
// source of truth.
type QuickFilterKey = QuickFilterPresetKey;

// List Page Content Component
function ListPageContentInner(props: PageContentProps) {
  const { schema, tableName, token, listExtensions } = props;
  const { user, hasPermission } = useAuth();
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
  // T9 — cross-page selection model. `selectionState` is the single source of
  // truth (explicit picked ids, or all-matching mode with an exclusion set);
  // the page-scoped `selectedIds` Set + counts below are derived from it.
  const [selectionState, setSelectionState] = useState<SelectionState>(() =>
    createSelectionModel(),
  );
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
  const schemaExtension = (schema as any)?.extension ?? {};
  const skipListData = shouldSkipListData(schema);
  const skipModelFieldMeta = shouldSkipModelFieldMeta(schema, skipListData);
  const miscBlocksPosition = resolveListMiscBlocksPosition(schema);

  // Read initial sorts, keyword, and view from URL search params
  const urlSorts = useMemo(() => decodeSorts(searchParams.get('sort')), [searchParams]);
  const urlKeyword = useMemo(() => searchParams.get('keyword') || '', [searchParams]);
  const urlViewPid = useMemo(() => searchParams.get('view') || null, [searchParams]);
  // Active preset view (?preset=created_today) — persists across reload.
  const urlPreset = useMemo<QuickFilterKey | null>(() => {
    const raw = searchParams.get('preset');
    return isQuickFilterPresetKey(raw) ? raw : null;
  }, [searchParams]);

  // Search keyword for toolbar search input
  const [keyword, _setKeyword] = useState(urlKeyword);
  const keywordRef = useRef(keyword);
  const tabRequestSeqRef = useRef(0);

  // Debounced URL sync for keyword (300ms) — keeps input responsive while
  // reducing URL/history updates during rapid typing
  const syncKeywordToUrl = useDebouncedCallback((value: string) => {
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
  }, 300);

  // Debounced auto-search: triggers loadData 300ms after the user stops typing,
  // so results update without requiring Enter. Reduces API calls by ~60-80%
  // compared to firing on every keystroke.
  const debouncedSearch = useDebouncedCallback(() => {
    if (!schema) return;
    loadData({ page: 0, size: pagination.pageSize });
  }, 300);

  // Synchronous ref update ensures loadData always reads the latest keyword
  // even when called in the same event cycle as a state update (e.g., Playwright fill + Enter)
  const setKeyword = useCallback(
    (value: string) => {
      keywordRef.current = value;
      _setKeyword(value);
      // Sync keyword to URL (debounced 300ms)
      syncKeywordToUrl(value);
      // Auto-search after user stops typing (debounced 300ms)
      debouncedSearch();
    },
    [syncKeywordToUrl, debouncedSearch],
  );

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
  const setFilters = useCallback(
    (filters: Record<string, any> | ((prev: Record<string, any>) => Record<string, any>)) => {
      setPageState((prev) => ({
        ...prev,
        filters: typeof filters === 'function' ? filters(prev.filters) : filters,
      }));
    },
    [],
  );

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
  const tableBulkActions = useMemo<ButtonConfig[]>(() => {
    const configured =
      (tableBlock as any)?.table?.bulkActions ?? (tableBlock as any)?.bulkActions ?? [];
    return Array.isArray(configured) ? configured : [];
  }, [tableBlock]);
  // T9 — ids of the rows currently loaded on this page (cross-page selection
  // accumulates across these as the user pages).
  const pageRowIds = useMemo(
    () => data.map((row) => getPublicRecordKey(row) || '').filter(Boolean) as string[],
    [data],
  );
  const allMatchingSelected = selectionIsAllMatching(selectionState);
  // Page-scoped Set of selected ids — what the table's checkboxes render from
  // (the table compares against the visible page's row ids).
  const selectedIds = useMemo(
    () => new Set(pageRowIds.filter((id) => selectionIsSelected(selectionState, id))),
    [pageRowIds, selectionState],
  );
  // Whether every loaded row on this page is selected (drives the header
  // checkbox + the "select all N matching" banner offer).
  const pageFullySelected = useMemo(
    () => selectionIsPageFullySelected(selectionState, pageRowIds),
    [selectionState, pageRowIds],
  );
  // Effective selected count across all pages (page picks, or total-minus-
  // exclusions in all-matching mode).
  const effectiveSelectedCount = selectionSelectedCount(selectionState, pagination.total);
  // Explicit ids for bulk ops/export. In all-matching mode there is no finite
  // client-side id list — callers branch on `allMatchingSelected` and export by
  // the current filter instead.
  const explicitSelectedIds = useMemo(
    () => selectionGetExplicitIds(selectionState),
    [selectionState],
  );
  // The concrete ids a bulk op (delete/edit/custom action) should target. In
  // explicit mode this is the full cross-page pick; in all-matching mode we
  // fall back to the currently-selected rows on this page (a finite, safe set)
  // — export is the cross-page path and uses the filter directly.
  const selectedIdList = allMatchingSelected ? Array.from(selectedIds) : explicitSelectedIds;
  // Whether row selection (checkbox column + bulk bar + select-all banner) is
  // active for this page — DSL opt-in via the table block, minus any host-level
  // override.
  const selectionEnabled =
    !!((tableBlock as any)?.table?.selection || (tableBlock as any)?.selection) &&
    !listExtensions?.disableRowSelection;

  // T10 — column aggregation summary footer. DSL opt-in via the table block
  // (`table.showSummaryRow`); `undefined` lets ListTable auto-show the footer
  // whenever any column declares an `aggregate`. Aggregates cover the current
  // page only — a cross-page grand total would require a backend sum endpoint.
  const summaryRowEnabled = ((tableBlock as any)?.table?.showSummaryRow ??
    (tableBlock as any)?.showSummaryRow) as boolean | undefined;

  // T10 — expandable tree rows. DSL opt-in via the table block
  // (`table.treeConfig: { parentField }`). When present, self-referencing rows
  // render as an indented tree with expand/collapse chevrons; when absent the
  // table stays flat (byte-identical to today). Aggregation/sorting unaffected.
  const treeConfig = ((tableBlock as any)?.table?.treeConfig ?? (tableBlock as any)?.treeConfig) as
    | TableConfig['treeConfig']
    | undefined;

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
    return schema.blocks.filter((b: any) => !LIST_SPECIALIZED_BLOCK_TYPES.has(b.blockType));
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
  const [memberImportResult, setMemberImportResult] = useState<TenantMemberImportResult | null>(
    null,
  );
  const { formats: dateTimeFormats, timezone: effectiveTimezone } = useTimezone();
  const pendingSavedViewFiltersRef = useRef<Record<string, any> | null>(null);
  // When restoring a preset view from ?preset= on mount, skip the first run of
  // the debounced sort/filter effect so it doesn't re-fetch with empty filters.
  const skipFirstSortFilterEffectRef = useRef(false);
  const loadDataRef = useRef<
    | ((params?: { page?: number; size?: number; filters?: Record<string, any> }) => Promise<void>)
    | null
  >(null);

  // Record preview drawer state
  const [previewRecordId, setPreviewRecordId] = useState<string | null>(null);

  // SavedView integration
  const modelCode = schema?.modelCode || tableName;
  const pageKey = resolveListSavedViewPageKey(schema, tableName);
  const isTenantMemberPage = modelCode === 'tenant_member' || pageKey === 'tenant_member';
  const hideSavedViews =
    listExtensions?.hideSavedViews ?? Boolean(schemaExtension.hideSavedViews || skipListData);
  // Quick-filter / preset-view visibility (shared by the header preset bar and
  // the toolbar quick filters so both stay in sync).
  const hideQuickFilters =
    listExtensions?.hideQuickFilters ?? Boolean(schemaExtension.hideQuickFilters);
  const {
    views: savedViews,
    currentView,
    selectView,
    createView,
    updateView,
    updateViewConfig,
    deleteView: deleteSavedView,
    setDefaultView,
    duplicateView,
    copyToPersonal,
    reload: reloadViews,
    loading: viewsLoading,
  } = useSavedViews({ modelCode, pageKey, autoLoad: !!schema && !hideSavedViews && !skipListData });
  const [pendingSharedViewConfig, setPendingSharedViewConfig] =
    useState<Partial<ViewConfig> | null>(null);
  const [savingSharedViewDraft, setSavingSharedViewDraft] = useState(false);
  const [copyingSharedViewDraft, setCopyingSharedViewDraft] = useState(false);
  const savedViewPersistenceMode = getSavedViewPersistenceMode(currentView);
  const isCurrentViewLockedPreset = isSavedViewLockedPreset(currentView);
  const canCopyCurrentView = canCopySavedView(currentView);
  const canSaveSharedView = useMemo(() => {
    if (!currentView || savedViewPersistenceMode !== 'shared-draft') {
      return false;
    }
    if (isCurrentViewLockedPreset) {
      return false;
    }
    if (Array.isArray(currentView.actions)) {
      return currentView.actions.includes('save');
    }
    if (currentView.createdBy && user?.pid && currentView.createdBy === user.pid) {
      return true;
    }
    if (currentView.scope === 'team') {
      return (
        hasPermission('dashboard.saved_view.team.update') ||
        hasPermission('dashboard.saved_view.update')
      );
    }
    if (currentView.scope === 'global') {
      return hasPermission('dashboard.saved_view.update');
    }
    return false;
  }, [currentView, hasPermission, isCurrentViewLockedPreset, savedViewPersistenceMode, user?.pid]);
  const hasPendingSharedViewConfig =
    savedViewPersistenceMode === 'shared-draft' &&
    Object.keys(pendingSharedViewConfig ?? {}).length > 0;
  const effectiveViewConfig = useMemo(
    () =>
      currentView
        ? mergeViewConfigPatch(currentView.viewConfig, pendingSharedViewConfig ?? {})
        : undefined,
    [currentView, pendingSharedViewConfig],
  );
  const pendingSharedViewSummary = useMemo(
    () => summarizeViewConfigPatch(pendingSharedViewConfig),
    [pendingSharedViewConfig],
  );
  const translateCommon = useCallback(
    (key: string, fallback: string) => {
      const value = t(key);
      return value && value !== key ? value : fallback;
    },
    [t],
  );
  const savedQuickFilterPresetKeys = useMemo(
    () => getSavedQuickFilterPresetKeys(savedViews),
    [savedViews],
  );
  const activeSavedQuickFilterPresetKey = useMemo(
    () => getActiveSavedQuickFilterPresetKey(currentView),
    [currentView],
  );
  const activeSavedQuickFilterPresetEdited = useMemo(
    () =>
      activeSavedQuickFilterPresetKey
        ? isPersonalPresetSavedViewEdited(currentView, activeSavedQuickFilterPresetKey, {
            userId: user?.id,
            now: new Date(),
          })
        : false,
    [activeSavedQuickFilterPresetKey, currentView, user?.id],
  );

  useEffect(() => {
    setPendingSharedViewConfig(null);
  }, [currentView?.pid]);

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
  const [referenceDisplayCache, setReferenceDisplayCache] = useState<
    Record<string, Record<string, string>>
  >({});

  useEffect(() => {
    let cancelled = false;
    const pageKey = schema?.modelCode || tableName;
    if (!pageKey || skipModelFieldMeta) {
      setModelFieldMap(new Map());
      return;
    }

    async function loadModelFields(): Promise<void> {
      try {
        const fieldsRes = await fetchResult<any[]>(`/api/dynamic/${pageKey}/field-meta`, {
          method: 'get',
          token: token || undefined,
        });
        if (cancelled) return;
        if (!ResultHelper.isSuccess(fieldsRes) || !fieldsRes.data) {
          setModelFieldMap(new Map());
          return;
        }
        const map = new Map<string, any>();
        for (const field of fieldsRes.data) {
          if (field?.code) map.set(field.code, field);
        }
        setModelFieldMap(map);
      } catch (error) {
        if (!cancelled) {
          setModelFieldMap(new Map());
          console.warn('[ListPageContent] Failed to load model field metadata:', error);
        }
      }
    }

    loadModelFields();
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
    savedViews,
    viewsLoading,
    selectView,
    setActiveViewType,
  });

  useEffect(() => {
    if (!currentView) return;
    setActiveViewType((currentView.viewType as ViewType) || 'table');
  }, [currentView?.pid, currentView?.viewType]);

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
      setActiveSorts((prev) => (areSortsEqual(prev, vc.sorts) ? prev : vc.sorts!));
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
      if (!schema || skipListData) {
        setData([]);
        setError(null);
        setLoading(false);
        setPageState((prev) => ({
          ...prev,
          pagination: {
            ...prev.pagination,
            total: 0,
          },
        }));
        return;
      }

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
      skipListData,
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
    if (schema && !skipListData) {
      // Restore an active preset view from ?preset= so it survives reload —
      // a SavedView (?view=) takes precedence and carries its own filters.
      const initialPreset = urlViewPid ? null : urlPreset;
      if (initialPreset) {
        const presetFilters =
          buildQuickFilterPreset(initialPreset, { userId: user?.id, now: new Date() }) ?? {};
        setActiveQuickFilter(initialPreset);
        setFilters(presetFilters);
        // The debounced sort/filter effect would otherwise re-fetch once on
        // mount with the still-empty `filters` state and clobber this preset
        // load — skip exactly that first run so the preset filter wins.
        skipFirstSortFilterEffectRef.current = true;
        loadDataRef.current?.({
          page: pagination.current - 1,
          size: pagination.pageSize,
          filters: presetFilters,
        });
      } else {
        loadDataRef.current?.({ page: pagination.current - 1, size: pagination.pageSize, filters });
      }
    }
    // Intentionally only react to schema changes.
    // Pagination or filter updates are handled by explicit user actions.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [schema, skipListData]);

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
      if (skipListData) return;
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
            const apiTableName = schema?.modelCode ? schema.modelCode : tableName;
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
    [
      schema,
      buildFiltersParam,
      filters,
      pagination.pageSize,
      tableName,
      token,
      t,
      activeSorts,
      tableBlock,
      skipListData,
    ],
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

  const canUseButton = useCallback(
    (button: ButtonConfig): boolean => {
      return !button.permissionCode || hasPermission(button.permissionCode);
    },
    [hasPermission],
  );

  // Resolve button display label
  const resolveButtonLabel = useCallback(
    (button: ButtonConfig): string => {
      if (button.content) {
        return getLocalizedText(button.content, locale, t);
      }
      if (button.label) {
        const localized = getLocalizedText(button.label, locale, t);
        // If the DSL label is a bare lowercase identifier (e.g. "create",
        // "edit", "detail", "submit", "cancel"), getLocalizedText returns it
        // unchanged. Treat it as an i18n key candidate and probe well-known
        // namespaces before leaking the raw token to the UI.
        const isBareIdentifier =
          typeof button.label === 'string' &&
          /^[a-z][a-z0-9_]*$/.test(button.label) &&
          localized === button.label;
        if (isBareIdentifier) {
          const commonKey = `common.${button.label}`;
          const commonResolved = t(commonKey);
          if (commonResolved && commonResolved !== commonKey) return commonResolved;
          const actionKey = `action.${button.label}`;
          const actionResolved = t(actionKey);
          if (actionResolved && actionResolved !== actionKey) return actionResolved;
        }
        return localized;
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
    if (!schema || skipListData) return;
    // Preset restore (?preset=) already issued the filtered load on mount; skip
    // this first debounced run so it doesn't clobber it with empty filters.
    if (skipFirstSortFilterEffectRef.current) {
      skipFirstSortFilterEffectRef.current = false;
      return;
    }
    loadData({ page: 0, size: pagination.pageSize, filters });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [debouncedSortFilterValues, skipListData]);

  // Auto-save sorts to SavedView (debounced) + sync to URL
  useEffect(() => {
    if (!schema || skipListData) return;
    const encoded = encodeSorts(activeSorts);
    const currentEncoded = searchParams.get('sort');
    if ((encoded ?? null) !== (currentEncoded ?? null)) {
      setSearchParams(
        (prev) => {
          const p = new URLSearchParams(prev);
          if (encoded) {
            p.set('sort', encoded);
          } else {
            p.delete('sort');
          }
          return p;
        },
        { replace: true },
      );
    }
    if (viewsLoading) return;
    if (
      effectiveViewConfig?.sorts &&
      areSortsEqual(effectiveViewConfig.sorts, activeSorts)
    ) {
      return;
    }
    autoSave({ sorts: activeSorts });
    // autoSave is declared later in this component; evaluating it in the
    // dependency array would hit the temporal dead zone during render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    activeSorts,
    schema,
    viewsLoading,
    effectiveViewConfig?.sorts,
    searchParams,
    setSearchParams,
    skipListData,
  ]);

  const handleImportComplete = useCallback(() => {
    setImportOpen(false);
    loadData({ page: 0, size: pagination.pageSize, filters });
  }, [loadData, pagination.pageSize, filters]);

  // Bulk selection handlers (T9 — backed by the cross-page selection model).
  const toggleRowSelection = useCallback((recordId: string) => {
    setSelectionState((prev) => selectionToggleRow(prev, recordId));
  }, []);

  // Header checkbox: toggle whole-page selection. If the page is already fully
  // selected, un-check it; otherwise select every loaded row on the page.
  const toggleSelectAll = useCallback(() => {
    setSelectionState((prev) =>
      selectionIsPageFullySelected(prev, pageRowIds)
        ? selectionClearPage(prev, pageRowIds)
        : selectionSelectPage(prev, pageRowIds),
    );
  }, [pageRowIds]);

  // Banner action: opt into "select all N matching the current filter".
  const handleSelectAllMatching = useCallback(() => {
    setSelectionState((prev) => selectionEnterAllMatching(prev));
  }, []);

  // Banner action: drop back from all-matching to no selection.
  const clearAllSelection = useCallback(() => {
    setSelectionState((prev) => selectionClearSelection(prev));
  }, []);

  const handleBulkDelete = useCallback(
    async (ids: string[]) => {
      const mc = schema?.modelCode || tableName;
      await dynamicService.batchDelete(mc, ids);
      setSelectionState(selectionClearSelection);
      loadData({ page: 0, size: pagination.pageSize, filters });
    },
    [schema?.modelCode, tableName, loadData, pagination.pageSize, filters],
  );

  const handleBulkAction = useCallback(
    async (button: ButtonConfig, ids: string[]) => {
      if (ids.length === 0) return;
      if (!canUseButton(button)) return;

      const actionDef = button.action && typeof button.action === 'object' ? button.action : null;
      const actionType = (actionDef as any)?.type;
      const command = (actionDef as any)?.command || button.commandCode;
      if (!command) {
        showToast(
          translateOrFallback(
            t,
            'list.bulkAction.missingCommand',
            'Bulk action is missing a command',
          ),
          'error',
        );
        return;
      }

      const confirmKey = button.confirm || button.confirmMessageKey;
      if (confirmKey) {
        const { title, content } = resolveConfirmDialog(confirmKey, t);
        const confirmed = await confirmDialog({
          title,
          content,
          variant: button.danger || button.variant === 'danger' ? 'danger' : 'default',
        });
        if (!confirmed) return;
      }

      const label = resolveButtonLabel(button);
      let successCount = 0;
      const failures: string[] = [];

      if (actionType === 'bulk_state_transition') {
        for (const id of ids) {
          try {
            const result = await fetchResult(`/api/meta/commands/execute/${command}`, {
              method: 'post',
              params: {
                ...buildCommandTargetParams(id),
                payload: {},
                operationType: 'UPDATE',
              },
              token: token || undefined,
            });
            if (ResultHelper.isSuccess(result)) {
              successCount += 1;
            } else {
              failures.push((result as any).desc || (result as any).message || id);
            }
          } catch (error) {
            failures.push(error instanceof Error ? error.message : String(error));
          }
        }
      } else if (actionType === 'bulk_command') {
        const result = await fetchResult(`/api/meta/commands/execute/${command}`, {
          method: 'post',
          params: {
            payload: {
              recordIds: ids,
              selectedIds: ids,
              modelCode,
            },
          },
          token: token || undefined,
        });
        if (ResultHelper.isSuccess(result)) {
          successCount = ids.length;
        } else {
          failures.push((result as any).desc || (result as any).message || command);
        }
      } else {
        showToast(
          translateOrFallback(
            t,
            'list.bulkAction.unsupported',
            `Unsupported bulk action type: ${actionType || 'unknown'}`,
          ),
          'error',
        );
        return;
      }

      if (successCount > 0) {
        setSelectionState(selectionClearSelection);
        await loadData({ page: 0, size: pagination.pageSize, filters });
      }

      if (failures.length === 0) {
        showToast(
          translateOrFallback(
            t,
            'list.bulkAction.success',
            `${label} completed for ${successCount} records`,
          ),
          'success',
        );
      } else if (successCount > 0) {
        showToast(
          translateOrFallback(
            t,
            'list.bulkAction.partial',
            `${label} completed for ${successCount} records; ${failures.length} failed`,
          ),
          'warning',
        );
      } else {
        showToast(failures[0] || `${label} failed`, 'error');
      }
    },
    [
      canUseButton,
      filters,
      loadData,
      modelCode,
      pagination.pageSize,
      resolveButtonLabel,
      showToast,
      t,
      token,
    ],
  );

  const handleBulkEditComplete = useCallback(() => {
    setBulkEditOpen(false);
    setSelectionState(selectionClearSelection);
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
      const referenceConfig = collectListReferenceDisplayConfigs([column], modelFieldMap)[0];
      const cacheKey = referenceConfig
        ? buildListReferenceDisplayCacheKey(referenceConfig)
        : undefined;
      const referenceDisplayValue =
        referenceConfig && value !== null && value !== undefined
          ? record[referenceConfig.displayKey] ||
            referenceDisplayCache[cacheKey || '']?.[String(value)]
          : undefined;
      const recordForRenderer = referenceDisplayValue
        ? { ...record, [referenceConfig!.displayKey]: referenceDisplayValue }
        : record;
      const effectiveValueType = referenceConfig
        ? 'reference'
        : inferValueType(column, value, recordForRenderer);

      // Null/undefined handling
      if (
        !((column as any).allowNullRenderer === true) &&
        (value === null || value === undefined)
      ) {
        return <span className="text-text-3">-</span>;
      }

      // If dictCode exists, try to translate value to label
      if (column.dictCode) {
        const dictItems = dictDataCache.current.get(column.dictCode);
        if (dictItems) {
          const item = dictItems.find((i) => String(i.value) === String(value));
          if (item) {
            // §3 / §1.3: dict-coded status renders as 色点 + 文字, not a filled pill.
            // A dict item may carry `extension.icon` (category dims like lead source)
            // to render a leading icon instead of the semantic color dot.
            return (
              <StatusDot
                tone={resolveStatusTone(item.extension?.color)}
                label={item.label}
                icon={(item.extension as any)?.icon}
              />
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
      const rendererType = (column as any).cellRenderer || effectiveValueType;
      return cellRendererRegistry.render(rendererType, {
        value,
        record: recordForRenderer,
        column: {
          field: column.field,
          label: typeof column.label === 'string' ? column.label : undefined,
          valueType: effectiveValueType,
          cellRenderer: (column as any).cellRenderer,
          format: column.format,
          dateTimeFormats,
          timezone: effectiveTimezone,
          render: column.render,
          tagMap: (column as any).tagMap,
        },
        expressionContext: pageContext,
        locale,
        t,
        rowIndex,
      });
    },
    [
      runtime,
      pageContext,
      locale,
      t,
      inferValueType,
      dateTimeFormats,
      effectiveTimezone,
      modelFieldMap,
      referenceDisplayCache,
    ],
  );

  // Inline edit: save a single field value via dynamic data PUT API
  const handleInlineSave = useCallback(
    async (field: string, value: any, record: Record<string, any>) => {
      const pid = getLegacyCompatibleRecordPid(record);
      if (!pid) throw new Error('Record has no public pid');
      const slug = schema?.modelCode || tableName;
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
      const systemKey = getSystemFieldI18nKey(fieldCode);
      if (systemKey) {
        const systemLabel = t(systemKey);
        if (systemLabel && systemLabel !== systemKey) {
          return systemLabel;
        }
      }
      const mc = schema?.modelCode || tableName;
      const modelKey = `model.${mc}.${fieldCode}.label`;
      const modelLabel = t(modelKey);
      if (modelLabel && modelLabel !== modelKey) {
        return modelLabel;
      }
      const modelFieldKey = `field.${mc}.${fieldCode}.label`;
      const modelFieldLabel = t(modelFieldKey);
      if (modelFieldLabel && modelFieldLabel !== modelFieldKey) {
        return modelFieldLabel;
      }
      const fieldKey = `field.${fieldCode}.label`;
      const fieldLabel = t(fieldKey);
      if (fieldLabel && fieldLabel !== fieldKey) {
        return fieldLabel;
      }
      return resolveFieldMetaDisplayName(fieldCode, modelFieldMap) ?? fieldCode;
    },
    [schema?.modelCode, tableName, t, modelFieldMap],
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
      label: t(getSystemFieldI18nKey('created_at') || 'common.created_at') || 'Created At',
      valueType: 'datetime' as any,
    },
    {
      field: 'updated_at',
      label: t(getSystemFieldI18nKey('updated_at') || 'common.updated_at') || 'Updated At',
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

    // If the block has rowActions defined either at block level or under table,
    // synthesize an action column. Backend PageSchemaDTO commonly keeps actions
    // inside `table.rowActions`; dropping that path makes API-backed DSL pages
    // render only generated defaults.
    const rowActions = resolveTableBlockRowActions(tableBlock);
    let baseCols = cols as ColumnConfig[];
    if (rowActions && rowActions.length > 0) {
      const hasActionCol = cols.some((c: any) => c.isActionColumn);
      if (!hasActionCol) {
        baseCols = [
          ...cols,
          { field: '_actions', isActionColumn: true, buttons: rowActions },
        ] as ColumnConfig[];
      } else {
        // An action column already exists (e.g. default view/edit/delete injected
        // for existing models). Merge in the block-level custom rowActions (dedup
        // by code) so page-defined commands surface alongside the defaults — without
        // this, custom rowActions on existing models are silently dropped.
        baseCols = cols.map((c: any) => {
          if (!c.isActionColumn) return c;
          const existing = Array.isArray(c.buttons) ? c.buttons : [];
          const existingCodes = new Set(existing.map((b: any) => b.code));
          const merged = [
            ...existing,
            ...rowActions.filter((ra: any) => !existingCodes.has(ra.code)),
          ];
          return { ...c, buttons: merged };
        }) as ColumnConfig[];
      }
    }

    // Apply SavedView column config (visibility + order + width)
    const viewColumns = effectiveViewConfig?.columns;
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
  }, [tableBlock, effectiveViewConfig]);

  const referenceDisplayConfigs = useMemo(
    () => collectListReferenceDisplayConfigs(tableColumns, modelFieldMap),
    [tableColumns, modelFieldMap],
  );

  useEffect(() => {
    if (referenceDisplayConfigs.length === 0 || data.length === 0) return;
    let cancelled = false;

    async function loadReferenceDisplays(): Promise<void> {
      const pendingByConfig = referenceDisplayConfigs
        .map((config) => {
          const cacheKey = buildListReferenceDisplayCacheKey(config);
          const cached = referenceDisplayCache[cacheKey] || {};
          const values = Array.from(
            new Set(
              data
                .filter((record) => !record[config.displayKey])
                .map((record) => record[config.field])
                .filter((value) => value !== null && value !== undefined && value !== '')
                .map((value) => String(value)),
            ),
          ).filter((value) => cached[value] === undefined);
          return { config, cacheKey, values };
        })
        .filter((entry) => entry.values.length > 0);

      if (pendingByConfig.length === 0) return;

      const updates: Record<string, Record<string, string>> = {};
      await Promise.all(
        pendingByConfig.map(async ({ config, cacheKey, values }) => {
          try {
            const result = await fetchResult<PaginationResult<DynamicEntity> | DynamicEntity[]>(
              `${buildApiEndpoint(config.modelCode)}/list`,
              {
                method: 'get',
                params: {
                  pageNum: 1,
                  pageSize: Math.max(values.length, 1),
                  filters: JSON.stringify([
                    { fieldName: config.valueField, operator: 'IN', value: values },
                  ]),
                },
                token: token || undefined,
              },
            );
            if (cancelled || !ResultHelper.isSuccess(result) || !result.data) return;

            const responseData = result.data;
            const rows = Array.isArray(responseData) ? responseData : responseData.records || [];
            for (const row of rows) {
              const value = row[config.valueField];
              const label = row[config.displayField];
              if (value === null || value === undefined || label === null || label === undefined) {
                continue;
              }
              const text = String(label).trim();
              if (!text) continue;
              updates[cacheKey] = updates[cacheKey] || {};
              updates[cacheKey][String(value)] = text;
            }
          } catch (error) {
            console.warn(
              `[ListPageContent] Failed to resolve reference labels for ${config.field}:`,
              error,
            );
          }
        }),
      );

      if (cancelled || Object.keys(updates).length === 0) return;
      setReferenceDisplayCache((prev) => {
        const next = { ...prev };
        for (const [cacheKey, labels] of Object.entries(updates)) {
          next[cacheKey] = { ...(next[cacheKey] || {}), ...labels };
        }
        return next;
      });
    }

    loadReferenceDisplays();
    return () => {
      cancelled = true;
    };
  }, [referenceDisplayConfigs, data, referenceDisplayCache, token]);

  // Column order — derived from SavedView or default column order
  const [columnOrder, setColumnOrder] = useState<string[]>([]);

  // Initialize column order from SavedView or table columns
  useEffect(() => {
    const viewColumns = effectiveViewConfig?.columns;
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
  }, [effectiveViewConfig?.columns, tableColumns]);

  // Resolve column label via i18n
  const resolveColumnLabel = useCallback(
    (column: ColumnConfig): string => {
      if (column.label) {
        return getLocalizedText(column.label, locale, t);
      }
      if (column.isActionColumn) {
        return t('table.actions');
      }
      const systemKey = getSystemFieldI18nKey(column.field);
      if (systemKey) {
        const systemLabel = t(systemKey);
        if (systemLabel !== systemKey) return systemLabel;
      }
      const mc = schema?.modelCode || tableName;
      const modelKey = `model.${mc}.${column.field}.label`;
      const modelLabel = t(modelKey);
      if (modelLabel !== modelKey) return modelLabel;
      const modelFieldKey = `field.${mc}.${column.field}.label`;
      const modelFieldLabel = t(modelFieldKey);
      if (modelFieldLabel !== modelFieldKey) return modelFieldLabel;
      const fieldKey = `field.${column.field}.label`;
      const fieldLabel = t(fieldKey);
      if (fieldLabel !== fieldKey) return fieldLabel;
      // Probe `common.field.<code>` namespaced under `common.field.*` (legacy slot)
      const commonFieldKey = `common.field.${column.field}`;
      const commonFieldLabel = t(commonFieldKey);
      if (commonFieldLabel !== commonFieldKey) return commonFieldLabel;
      // Probe top-level `common.<code>` — covers system audit fields like
      // created_at / updated_at / created_by / updated_by which live directly
      // under the `common:` namespace in i18n yaml resources.
      const commonKey = `common.${column.field}`;
      const commonLabel = t(commonKey);
      if (commonLabel !== commonKey) return commonLabel;
      // Last resort before leaking the raw field code: the model field
      // metadata carries the imported displayName (zh-CN first).
      return resolveFieldMetaDisplayName(column.field, modelFieldMap) ?? column.field;
    },
    [locale, t, schema?.modelCode, tableName, modelFieldMap],
  );

  // Column widths map from SavedView
  const columnWidths = useMemo(() => {
    const widths: Record<string, number> = {};
    const viewColumns = effectiveViewConfig?.columns;
    if (viewColumns) {
      for (const vc of viewColumns) {
        if (vc.width) widths[vc.fieldCode] = vc.width;
      }
    }
    return widths;
  }, [effectiveViewConfig?.columns]);

  // Row style from conditional formats
  const getRowStyle = useCallback(
    (record: Record<string, any>): React.CSSProperties | undefined => {
      const cfStyle = evaluateConditionalFormats(
        effectiveViewConfig?.conditionalFormats,
        record,
      );
      return buildConditionalStyle(cfStyle);
    },
    [effectiveViewConfig?.conditionalFormats],
  );

  // Row click → navigate to detail page or open preview drawer
  const handleRowClick = useCallback(
    (record: Record<string, unknown>) => {
      if (listExtensions?.disableRowClick) {
        return;
      }

      const rowClickMode = resolveListRowClickMode({
        schemaDetailNavigation: (schema as any)?.options?.detailNavigation,
        tableOnRowClick: (tableBlock as any)?.onRowClick,
        tableRowClickAction: (tableBlock as any)?.props?.rowClickAction,
      });

      if (rowClickMode === 'none') {
        return;
      }

      // A configured detailUrl template ({field} placeholders) is resolved BEFORE the pid guard
      // so it works for records that have no pid (e.g. external-REST / aggregate list rows keyed
      // by a numeric id). Pages without a detailUrl keep requiring a pid (unchanged behavior).
      const detailUrl = (schema as any)?.options?.detailUrl || (tableBlock as any)?.detailUrl;
      if (rowClickMode === 'detail' && detailUrl) {
        const resolved = detailUrl.replace(/\{(\w+)\}/g, (_: string, key: string) =>
          String(record[key] ?? ''),
        );
        navigate(resolved);
        return;
      }

      const pid = record.pid as string | undefined;
      if (!pid) return;

      if (rowClickMode === 'detail') {
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
              const systemKey = getSystemFieldI18nKey(col.field);
              if (systemKey) {
                const systemLabel = t(systemKey);
                if (systemLabel !== systemKey) return systemLabel;
              }
              const mc = schema?.modelCode || tableName;
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

  const viewManageFields = useMemo(() => {
    const fieldMap = new Map(
      buildViewManageFieldOptions(tableColumns, modelFieldMap).map((field) => [
        field.code,
        field,
      ]),
    );

    for (const column of tableColumns) {
      if (!column.field || column.isActionColumn) continue;
      fieldMap.set(column.field, {
        code: column.field,
        name: resolveColumnLabel(column),
        dataType: resolveColumnCapabilityDataType(column, modelFieldMap),
      });
    }

    return Array.from(fieldMap.values());
  }, [tableColumns, modelFieldMap, resolveColumnLabel]);

  // Auto-save view config — delegates upsert logic to backend
  // §3: after sort/filter/column/row-height changes auto-save to the current
  // view, surface a quiet "已保存到当前视图" hint so the persistence is visible.
  const [viewSavedHintOn, flashViewSavedHint] = useTransientFlag(2200);

  const ensureViewAndUpdateConfig = useCallback(
    async (
      config: Partial<import('~/framework/smart/types/savedView').ViewConfig>,
      options?: { isStale?: () => boolean; rethrow?: boolean },
    ) => {
      try {
        const persistenceMode = getSavedViewPersistenceMode(currentView);
        if (persistenceMode === 'personal-persist') {
          await updateViewConfig(config);
        } else if (persistenceMode === 'shared-draft') {
          setPendingSharedViewConfig((prev) => mergeViewConfigPatch(prev, config));
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
        if (!options?.isStale?.() && persistenceMode !== 'shared-draft') {
          flashViewSavedHint();
        }
      } catch (err) {
        if (options?.isStale?.()) {
          return;
        }
        console.error('[ListPageContent] Failed to save view config:', err);
        if (options?.rethrow) {
          throw err;
        }
      }
    },
    [currentView, updateViewConfig, modelCode, pageKey, selectView, flashViewSavedHint],
  );

  const handleCopySharedDraftToPersonal = useCallback(async () => {
    if (!currentView) return;
    if (!canCopyCurrentView) {
      showErrorToast(
        translateCommon('common.saved_view_copy_disabled_reason', 'This view cannot be copied'),
      );
      return;
    }
    setCopyingSharedViewDraft(true);
    try {
      const copiedView = await copyToPersonal(currentView.pid, {
        name: buildPersonalCopyName(currentView.name),
        viewConfig: mergeViewConfigPatch(currentView.viewConfig, pendingSharedViewConfig ?? {}),
      });
      setPendingSharedViewConfig(null);
      setSearchParams(
        (prev) => {
          const p = new URLSearchParams(prev);
          p.set('view', copiedView.pid);
          p.delete('sort');
          p.delete('keyword');
          p.delete('filters');
          return p;
        },
        { replace: true },
      );
      showSuccessToast(
        translateCommon('common.saved_view_copied_to_personal', 'Copied as personal view'),
      );
    } catch (err) {
      showErrorToast(
        err instanceof Error
          ? err.message
          : translateCommon('common.saved_view_copy_failed', 'Failed to copy view'),
      );
    } finally {
      setCopyingSharedViewDraft(false);
    }
  }, [
    canCopyCurrentView,
    copyToPersonal,
    currentView,
    pendingSharedViewConfig,
    setSearchParams,
    showErrorToast,
    showSuccessToast,
    translateCommon,
  ]);

  const handleSaveSharedDraft = useCallback(async () => {
    if (!currentView || !hasPendingSharedViewConfig || !pendingSharedViewConfig) return;

    const targetName =
      currentView.scope === 'team'
        ? currentView.teamName || translateCommon('common.saved_view_scope_team', 'Team')
        : translateCommon('common.saved_view_scope_global', 'Global');
    const summaryText =
      pendingSharedViewSummary.length > 0
        ? pendingSharedViewSummary.join(', ')
        : translateCommon('common.saved_view_shared_changes', 'view configuration');
    const confirmed = await confirmDialog({
      title: translateCommon('common.saved_view_save_shared_confirm_title', 'Save shared view?'),
      content: translateCommon(
        'common.saved_view_save_shared_confirm_content',
        `This will update the shared view for ${targetName}. Changes: ${summaryText}.`,
      )
        .replace('{target}', targetName)
        .replace('{changes}', summaryText),
      confirmText: translateCommon('common.saved_view_save_shared', 'Save Shared View'),
      cancelText: translateCommon('common.cancel', 'Cancel'),
    });
    if (!confirmed) return;

    setSavingSharedViewDraft(true);
    try {
      await updateView({
        viewConfig: mergeViewConfigPatch(currentView.viewConfig, pendingSharedViewConfig),
      });
      setPendingSharedViewConfig(null);
      flashViewSavedHint();
      showSuccessToast(
        translateCommon('common.saved_view_shared_saved', 'Shared view updated'),
      );
    } catch (err) {
      showErrorToast(
        err instanceof Error
          ? err.message
          : translateCommon('common.saved_view_shared_save_failed', 'Failed to save shared view'),
      );
    } finally {
      setSavingSharedViewDraft(false);
    }
  }, [
    currentView,
    flashViewSavedHint,
    hasPendingSharedViewConfig,
    pendingSharedViewConfig,
    pendingSharedViewSummary,
    showErrorToast,
    showSuccessToast,
    translateCommon,
    updateView,
  ]);

  const autoSaveMountedRef = useRef(true);
  useEffect(() => {
    autoSaveMountedRef.current = true;
    return () => {
      autoSaveMountedRef.current = false;
    };
  }, []);

  const { autoSave } = useAutoSaveView({
    currentView,
    updateViewConfig: async (config) => {
      await ensureViewAndUpdateConfig(config, {
        isStale: () => !autoSaveMountedRef.current,
        rethrow: true,
      });
      return currentView!;
    },
  });

  // Handle column reorder via drag-and-drop
  const handleColumnReorder = useCallback(
    (newOrder: string[]) => {
      setColumnOrder(newOrder);
      // Persist to SavedView
      const cols = newOrder.map((fieldCode, idx) => {
        const existing = effectiveViewConfig?.columns?.find((c) => c.fieldCode === fieldCode);
        return { ...(existing || { fieldCode }), fieldCode, order: idx };
      });
      autoSave({ columns: cols });
    },
    [effectiveViewConfig, autoSave],
  );

  // Handle column resize
  const handleColumnResize = useCallback(
    (field: string, width: number) => {
      if (!currentView) return;
      const cols = [...(effectiveViewConfig?.columns || [])];
      const idx = cols.findIndex((c) => c.fieldCode === field);
      if (idx >= 0) {
        cols[idx] = { ...cols[idx], width };
      } else {
        cols.push({ fieldCode: field, width });
      }
      autoSave({ columns: cols });
    },
    [currentView, effectiveViewConfig, autoSave],
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

  // Evaluate toolbar button visibility (visibleWhen expression).
  // Exposes the loaded list's recordCount/total so conditions like
  // `recordCount == 0` (singleton "新建" button) work.
  const evaluateButtonVisible = useCallback(
    (button: ButtonConfig): boolean => {
      if (!canUseButton(button)) return false;
      // If no visibleWhen expression, always visible
      if (!button.visibleWhen) return true;
      const conditionContext = buildToolbarConditionContext(
        { total: pagination.total, records: data },
        createExpressionContext({ state: { filters } }),
      );
      return evaluateCondition(button.visibleWhen, conditionContext as any);
    },
    [canUseButton, pagination.total, data, filters],
  );

  const visibleBulkActions = useMemo(() => {
    return tableBulkActions.filter((button) => {
      if (!canUseButton(button)) return false;
      if (!button.visibleWhen) return true;
      const selectedIdsArray = selectedIdList;
      // In all-matching mode the selection spans every filtered record, so the
      // visibility count reflects the effective total (minus exclusions), not
      // just the finite id array.
      const visibleSelectedCount = allMatchingSelected
        ? effectiveSelectedCount
        : selectedIdsArray.length;
      const conditionContext = {
        ...buildToolbarConditionContext(
          { total: pagination.total, records: data },
          createExpressionContext({
            state: {
              filters,
              selectedIds: selectedIdsArray,
              selectedCount: visibleSelectedCount,
            },
          }),
        ),
        selectedIds: selectedIdsArray,
        selectedCount: visibleSelectedCount,
      };
      return evaluateCondition(button.visibleWhen, conditionContext as any);
    });
  }, [
    canUseButton,
    data,
    filters,
    pagination.total,
    selectedIdList,
    allMatchingSelected,
    effectiveSelectedCount,
    tableBulkActions,
  ]);

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

  // Shared export request — posts the given conditions to the export endpoint
  // and triggers a browser download of the returned file.
  const runExport = useCallback(
    async (
      format: 'xlsx' | 'csv',
      conditions: Array<{ field: string; operator: string; value: unknown }> | undefined,
    ) => {
      try {
        const res = await fetch(`/api/dynamic/${modelCode}/export`, {
          method: 'post',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            format: format === 'xlsx' ? 'excel' : 'csv',
            conditions,
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
    [modelCode, showErrorToast],
  );

  // Handle export from toolbar — exports the full/filtered result set.
  const handleExport = useCallback(
    async (format: 'xlsx' | 'csv') => {
      await runExport(format, exportFilterConditions);
    },
    [runExport, exportFilterConditions],
  );

  // T9 — export ONLY the selected records.
  //  - explicit mode: the current filter scope plus an `IN pid (...)` condition
  //    restricting the export to the picked ids (which may span pages).
  //  - all-matching mode: there is no finite id list — export the whole filtered
  //    set (the same conditions a normal export would use).
  const handleExportSelected = useCallback(
    async (format: 'xlsx' | 'csv' = 'xlsx') => {
      if (allMatchingSelected) {
        await runExport(format, exportFilterConditions);
        return;
      }
      if (explicitSelectedIds.length === 0) return;
      const conditions = [
        ...(exportFilterConditions ?? []),
        { field: 'pid', operator: 'IN', value: explicitSelectedIds },
      ];
      await runExport(format, conditions);
    },
    [allMatchingSelected, runExport, exportFilterConditions, explicitSelectedIds],
  );

  // Row height from current view config (with fallback)
  const effectiveRowHeight: RowHeight = effectiveViewConfig?.rowHeight || DEFAULT_ROW_HEIGHT;

  // Handle row height change -> update SavedView
  const handleRowHeightChange = useCallback(
    async (height: RowHeight) => {
      await ensureViewAndUpdateConfig({ rowHeight: height });
    },
    [ensureViewAndUpdateConfig],
  );

  // Sync the active preset view to the URL (?preset=created_today) so it
  // survives reload; pass null to clear it.
  const syncPresetToUrl = useCallback(
    (key: QuickFilterKey | null) => {
      setSearchParams(
        (prev) => {
          const p = new URLSearchParams(prev);
          if (key) {
            p.set('preset', key);
          } else {
            p.delete('preset');
          }
          return p;
        },
        { replace: true },
      );
    },
    [setSearchParams],
  );

  // Quick filter handler — toggle a preset view on/off, apply its filter, reload.
  const handleQuickFilter = useCallback(
    (key: QuickFilterKey) => {
      if (activeQuickFilter === key) {
        // Toggle off — clear preset filter and reload
        setActiveQuickFilter(null);
        setFilters({});
        syncPresetToUrl(null);
        loadData({ page: 0, size: pagination.pageSize, filters: {} });
        return;
      }
      const qf = buildQuickFilterPreset(key, { userId: user?.id, now: new Date() }) ?? {};
      setActiveQuickFilter(key);
      setFilters(qf);
      syncPresetToUrl(key);
      loadData({ page: 0, size: pagination.pageSize, filters: qf });
    },
    [activeQuickFilter, user, setFilters, syncPresetToUrl, loadData, pagination.pageSize],
  );

  const handleSaveActivePreset = useCallback(async () => {
    if (!activeQuickFilter) return;

    const existingPresetView = findPersonalPresetSavedView(savedViews, activeQuickFilter);
    if (existingPresetView) {
      selectView(existingPresetView.pid);
      setActiveViewType((existingPresetView.viewType as ViewType) || 'table');
      setActiveQuickFilter(null);
      syncPresetToUrl(null);
      setSearchParams(
        (prev) => {
          const p = new URLSearchParams(prev);
          p.set('view', existingPresetView.pid);
          p.delete('preset');
          p.delete('sort');
          p.delete('keyword');
          p.delete('filters');
          return p;
        },
        { replace: true },
      );
      showSuccessToast(
        translateCommon('common.saved_view_preset_saved_to_personal', 'Saved as personal view'),
      );
      return;
    }

    const presetDefinition = getQuickFilterPresetDefinition(activeQuickFilter);
    if (!presetDefinition) return;
    const presetName = translateCommon(
      presetDefinition.i18nKey,
      presetDefinition.fallbackLabel,
    );
    const request = buildQuickFilterPresetViewRequest(
      activeQuickFilter,
      { userId: user?.id, now: new Date() },
      { modelCode, pageKey, name: presetName },
    );
    if (!request) return;

    try {
      const view = await createView(request);
      setActiveQuickFilter(null);
      syncPresetToUrl(null);
      setSearchParams(
        (prev) => {
          const p = new URLSearchParams(prev);
          p.set('view', view.pid);
          p.delete('preset');
          p.delete('sort');
          p.delete('keyword');
          p.delete('filters');
          return p;
        },
        { replace: true },
      );
      showSuccessToast(
        translateCommon('common.saved_view_preset_saved_to_personal', 'Saved as personal view'),
      );
    } catch (err) {
      showErrorToast(
        err instanceof Error
          ? err.message
          : translateCommon('common.saved_view_preset_save_failed', 'Failed to save preset'),
      );
    }
  }, [
    activeQuickFilter,
    createView,
    modelCode,
    pageKey,
    savedViews,
    selectView,
    setSearchParams,
    showErrorToast,
    showSuccessToast,
    syncPresetToUrl,
    translateCommon,
    user?.id,
  ]);

  const handleResetActiveSavedPreset = useCallback(async () => {
    if (!currentView || !activeSavedQuickFilterPresetKey) return;

    const presetFilters = buildQuickFilterPreset(activeSavedQuickFilterPresetKey, {
      userId: user?.id,
      now: new Date(),
    });
    if (!presetFilters) return;

    const viewFilters = buildQuickFilterPresetViewFilters(presetFilters);
    const nextConfig: ViewConfig = {
      ...(currentView.viewConfig ?? {}),
      filters: viewFilters,
      meta: {
        ...(currentView.viewConfig?.meta ?? {}),
        managedBy: currentView.viewConfig?.meta?.managedBy ?? 'user',
        originPresetKey: activeSavedQuickFilterPresetKey,
      },
    };

    try {
      await updateView({ viewConfig: nextConfig });
      setFilters(presetFilters);
      loadData({ page: 0, size: pagination.pageSize, filters: presetFilters });
      flashViewSavedHint();
      showSuccessToast(
        translateCommon('common.saved_view_preset_reset_done', 'Preset view reset'),
      );
    } catch (err) {
      showErrorToast(
        err instanceof Error
          ? err.message
          : translateCommon('common.saved_view_preset_reset_failed', 'Failed to reset preset view'),
      );
    }
  }, [
    activeSavedQuickFilterPresetKey,
    currentView,
    flashViewSavedHint,
    loadData,
    pagination.pageSize,
    setFilters,
    showErrorToast,
    showSuccessToast,
    translateCommon,
    updateView,
    user?.id,
  ]);

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
  }, [
    filters,
    memberImportFile,
    pagination.pageSize,
    showErrorToast,
    showSuccessToast,
    showWarningToast,
    token,
  ]);

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
        <div className="rounded-card bg-panel relative shadow-sm">
          {/* §3: quiet auto-save confirmation — appears briefly after a
              sort/filter/column/row-height change is persisted to the view. */}
          {viewSavedHintOn && (
            <div
              className="bg-accent-weak text-accent rounded-pill text-aux pointer-events-none absolute top-2 right-3 z-10 flex items-center gap-1.5 px-2.5 py-1 shadow-sm transition-opacity"
              role="status"
              data-testid="view-saved-hint"
            >
              <span className="bg-status-green rounded-pill h-1.5 w-1.5" aria-hidden="true" />
              {/* usePageRuntime's t() returns the key itself when missing, so a
                  plain `|| fallback` never fires — guard against the raw key leaking. */}
              {((s) => (s && s !== 'common.view_saved' ? s : 'Saved to current view'))(
                t('common.view_saved'),
              )}
            </div>
          )}
          {hasPendingSharedViewConfig && currentView && (
            <div
              className="absolute top-2 right-3 z-10 flex items-center gap-2 rounded-md border border-amber-200 bg-amber-50 px-3 py-1.5 text-xs text-amber-900 shadow-sm"
              role="status"
              data-testid="shared-view-draft-banner"
            >
              <span>
                {translateCommon(
                  'common.saved_view_shared_draft',
                  'Shared view has local changes',
                )}
                {pendingSharedViewSummary.length > 0 && (
                  <span className="ml-1 text-amber-700">
                    {pendingSharedViewSummary.join(', ')}
                  </span>
                )}
              </span>
              <button
                type="button"
                className="rounded px-2 py-1 font-medium text-amber-900 hover:bg-amber-100 disabled:cursor-not-allowed disabled:opacity-60"
                onClick={handleSaveSharedDraft}
                disabled={!canSaveSharedView || savingSharedViewDraft}
                title={
                  canSaveSharedView
                    ? translateCommon(
                        'common.saved_view_save_shared_confirm_title',
                        'Save shared view?',
                      )
                    : translateCommon(
                        isCurrentViewLockedPreset
                          ? 'common.saved_view_locked_preset_reason'
                          : 'common.saved_view_save_shared_disabled_reason',
                        isCurrentViewLockedPreset
                          ? 'Plugin preset views cannot be saved directly. Copy it to My Views first.'
                          : 'You do not have permission to save this shared view yet.',
                      )
                }
                data-testid={
                  canSaveSharedView ? 'shared-view-save' : 'shared-view-save-disabled'
                }
              >
                {savingSharedViewDraft
                  ? translateCommon('common.saving', 'Saving...')
                  : translateCommon('common.saved_view_save_shared', 'Save Shared View')}
              </button>
              {!canSaveSharedView && (
                <span className="text-amber-700">
                  {translateCommon(
                    isCurrentViewLockedPreset
                      ? 'common.saved_view_locked_preset_reason'
                      : 'common.saved_view_save_shared_disabled_reason',
                    isCurrentViewLockedPreset
                      ? 'Plugin preset views cannot be saved directly. Copy it to My Views first.'
                      : 'You do not have permission to save this shared view yet.',
                  )}
                </span>
              )}
              <button
                type="button"
                className="rounded px-2 py-1 font-medium text-blue-700 hover:bg-blue-50 disabled:opacity-60"
                onClick={handleCopySharedDraftToPersonal}
                disabled={copyingSharedViewDraft || !canCopyCurrentView}
                title={
                  canCopyCurrentView
                    ? translateCommon('common.saved_view_copy_to_personal', 'Copy to My View')
                    : translateCommon(
                        'common.saved_view_copy_disabled_reason',
                        'This view cannot be copied',
                      )
                }
                data-testid="shared-view-copy-to-personal"
              >
                {copyingSharedViewDraft
                  ? translateCommon('common.saving', 'Saving...')
                  : translateCommon('common.saved_view_copy_to_personal', 'Copy to My View')}
              </button>
              <button
                type="button"
                className="rounded px-2 py-1 text-amber-800 hover:bg-amber-100"
                onClick={() => setPendingSharedViewConfig(null)}
                data-testid="shared-view-dismiss-draft"
              >
                {translateCommon('common.saved_view_dismiss_draft', 'Discard')}
              </button>
            </div>
          )}
          {/* Page title, view selector, and action buttons */}
          <ListPageHeader
            title={
              schema.title
                ? getLocalizedText(schema.title, locale, t)
                : schema.name && schema.name.trim()
                  ? schema.name
                  : tableName
            }
            modelCode={modelCode}
            savedViews={savedViews}
            currentView={currentView}
            viewsLoading={viewsLoading}
            activeViewType={activeViewType}
            onSelectView={(pid) => {
              selectView(pid);
              // Sync view selection to URL
              setSearchParams(
                (prev) => {
                  const p = new URLSearchParams(prev);
                  p.set('view', pid);
                  // Clear temporary filter/sort params when switching views
                  p.delete('sort');
                  p.delete('keyword');
                  p.delete('filters');
                  return p;
                },
                { replace: true },
              );
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
            toolbarActions={effectiveViewConfig?.toolbarActions}
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
            hideSavedViews={hideSavedViews}
            hideBuiltInImport={
              skipListData
                ? true
                : (listExtensions?.hideBuiltInImport ??
                  (schema as any)?.extension?.hideBuiltInImport ??
                  (schema as any)?.extension?.hideToolbarMore)
            }
            hideBuiltInExport={
              skipListData
                ? true
                : (listExtensions?.hideBuiltInExport ??
                  (schema as any)?.extension?.hideBuiltInExport ??
                  (schema as any)?.extension?.hideToolbarMore)
            }
            hideBuiltInPrint={
              skipListData
                ? true
                : (listExtensions?.hideBuiltInPrint ??
                  (schema as any)?.extension?.hideBuiltInPrint ??
                  (schema as any)?.extension?.hideToolbarMore)
            }
          />

          {isTenantMemberPage && memberImportDialogOpen && (
            <div
              role="dialog"
              aria-modal="true"
              data-testid="member-import-dialog"
              className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
            >
              <div className="rounded-card bg-panel w-full max-w-2xl p-6 shadow-xl">
                <div className="mb-4 flex items-center justify-between">
                  <div>
                    <h3 className="text-text text-lg font-semibold">批量导入成员</h3>
                    <p className="text-text-2 mt-1 text-sm">
                      入口归属 tenant_member。导入的主语义是批量准入，不是直接维护组织树。
                    </p>
                  </div>
                  <button
                    type="button"
                    onClick={() => {
                      setMemberImportDialogOpen(false);
                      resetMemberImportState();
                    }}
                    className="text-text-2 hover:text-text-2 text-sm"
                  >
                    关闭
                  </button>
                </div>

                <div className="text-text-2 space-y-4 text-sm">
                  <div className="rounded-control bg-accent-weak border border-blue-200 p-3">
                    <p className="font-medium text-blue-900">推荐流程</p>
                    <p className="mt-1 text-blue-800">
                      Excel 导入先处理租户成员准入，再按邮箱复用已有 User
                      或创建待激活账号，最后再建立组织关系。
                    </p>
                  </div>

                  <div className="rounded-control border-border bg-subtle border p-4">
                    <div className="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
                      <div>
                        <p className="text-text font-medium">1. 下载模板</p>
                        <p className="text-text-2 mt-1 text-xs">
                          支持 `.xlsx`。必填列是姓名、邮箱；部门和职位用于补全组织关系。
                        </p>
                      </div>
                      <a
                        href="/api/tenant/members/import/template"
                        className="rounded-control bg-panel text-accent hover:bg-accent-weak inline-flex items-center justify-center border border-blue-200 px-3 py-2 text-sm font-medium"
                      >
                        下载模板
                      </a>
                    </div>

                    <div className="mt-4">
                      <p className="text-text font-medium">2. 选择文件</p>
                      <input
                        data-testid="member-import-file-input"
                        type="file"
                        accept=".xlsx,.xls"
                        onChange={(event) => {
                          const selectedFile = event.target.files?.[0] ?? null;
                          setMemberImportFile(selectedFile);
                          setMemberImportError(null);
                        }}
                        className="rounded-control border-border-strong text-text-2 file:rounded-control file:bg-accent hover:file:bg-accent-hover mt-2 block w-full border px-3 py-2 text-sm file:mr-3 file:border-0 file:px-3 file:py-2 file:text-sm file:font-medium file:text-white"
                      />
                      {memberImportFile && (
                        <p className="text-text-2 mt-2 text-xs">已选择: {memberImportFile.name}</p>
                      )}
                    </div>

                    {memberImportError && (
                      <div className="rounded-control bg-status-red-bg mt-3 border border-red-200 px-3 py-2 text-sm text-red-700">
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
                        className="rounded-control border-border-strong text-text-2 hover:bg-hover border px-4 py-2 text-sm font-medium"
                      >
                        取消
                      </button>
                      <button
                        type="button"
                        data-testid="member-import-submit"
                        disabled={!memberImportFile || memberImportLoading}
                        onClick={() => void handleTenantMemberImport()}
                        className="rounded-control bg-accent hover:bg-accent-hover px-4 py-2 text-sm font-medium text-white disabled:cursor-not-allowed disabled:bg-blue-300"
                      >
                        {memberImportLoading ? '导入中...' : '开始导入'}
                      </button>
                    </div>
                  </div>

                  <div>
                    <p className="text-text font-medium">导入结果分三类</p>
                    <ul className="mt-2 list-disc space-y-1 pl-5">
                      <li>已绑定已有用户：复用现有 User，创建 TenantMember，并建立组织关系</li>
                      <li>待激活成员：创建准入记录，发送邀请，等首次设置密码后启用登录</li>
                      <li>冲突项：邮箱重复、同租户已存在成员、部门不存在、职位不存在</li>
                    </ul>
                  </div>

                  <div>
                    <p className="text-text font-medium">建议模板字段</p>
                    <div className="rounded-control border-border mt-2 overflow-hidden border">
                      <table className="divide-border min-w-full divide-y text-sm">
                        <thead className="bg-subtle">
                          <tr>
                            <th className="text-text-2 px-3 py-2 text-left font-medium">字段</th>
                            <th className="text-text-2 px-3 py-2 text-left font-medium">必填</th>
                            <th className="text-text-2 px-3 py-2 text-left font-medium">说明</th>
                          </tr>
                        </thead>
                        <tbody className="divide-border bg-panel divide-y">
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
                            <td className="px-3 py-2">
                              用于自动建立组织关系；不存在时进入冲突列表
                            </td>
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

                  <div className="rounded-control bg-status-amber-bg border border-amber-200 p-3 text-amber-900">
                    第一版实现先固定入口和规则，不默认给新导入人员生成可直接登录的密码。新用户应走邀请激活链路。
                  </div>

                  {memberImportResult && (
                    <div
                      data-testid="member-import-result"
                      className="rounded-card border border-emerald-200 bg-emerald-50 p-4"
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
                        <div className="rounded-control bg-panel mt-4 overflow-hidden border border-amber-200">
                          <table className="min-w-full divide-y divide-amber-100 text-sm">
                            <thead className="bg-status-amber-bg">
                              <tr>
                                <th className="px-3 py-2 text-left font-medium text-amber-900">
                                  行号
                                </th>
                                <th className="px-3 py-2 text-left font-medium text-amber-900">
                                  姓名
                                </th>
                                <th className="px-3 py-2 text-left font-medium text-amber-900">
                                  邮箱
                                </th>
                                <th className="px-3 py-2 text-left font-medium text-amber-900">
                                  原因
                                </th>
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
                    className="rounded-control border-border-strong text-text-2 hover:bg-hover border px-4 py-2 text-sm font-medium"
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
              <div className="rounded-card bg-panel w-full max-w-md p-6 shadow-xl">
                <div className="mb-4 flex items-center justify-between">
                  <h3 className="text-text text-lg font-semibold">Member Invite</h3>
                  <button
                    type="button"
                    onClick={() => setInviteDialogOpen(false)}
                    className="text-text-2 hover:text-text-2 text-sm"
                  >
                    Close
                  </button>
                </div>
                <div className="space-y-4">
                  {inviteCodeData?.code ? (
                    <>
                      <div className="rounded-control bg-subtle p-3">
                        <div className="text-text-2 mb-1 text-xs font-medium">
                          Current Invite Code
                        </div>
                        <div className="text-text font-mono text-lg tracking-wider">
                          {inviteCodeData.code}
                        </div>
                        {inviteCodeData.expiredAt && (
                          <div className="text-text-2 mt-1 text-xs">
                            Expires at {new Date(inviteCodeData.expiredAt).toLocaleString()}
                          </div>
                        )}
                      </div>
                      <div className="flex gap-2">
                        <button
                          type="button"
                          onClick={handleGenerateInviteCode}
                          disabled={inviteLoading}
                          className="rounded-control bg-accent hover:bg-accent-hover flex-1 px-3 py-2 text-sm font-medium text-white disabled:opacity-60"
                        >
                          Refresh
                        </button>
                        <button
                          type="button"
                          onClick={handleRevokeInviteCode}
                          disabled={inviteLoading}
                          className="rounded-control flex-1 bg-red-600 px-3 py-2 text-sm font-medium text-white hover:bg-red-700 disabled:opacity-60"
                        >
                          Revoke
                        </button>
                      </div>
                    </>
                  ) : (
                    <div className="space-y-3">
                      <div className="rounded-control bg-status-amber-bg p-3 text-sm text-amber-800">
                        No active invite code. Generate one to invite members into this tenant.
                      </div>
                      <button
                        type="button"
                        onClick={handleGenerateInviteCode}
                        disabled={inviteLoading}
                        className="rounded-control bg-accent hover:bg-accent-hover w-full px-3 py-2 text-sm font-medium text-white disabled:opacity-60"
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
          {filterFormVisible &&
            filterBlock &&
            filterBlock.fields &&
            filterBlock.fields.length > 0 && (
              <div
                data-testid="search-area"
                data-ab-testid={deriveTestId('list', modelCode, 'filters')}
                className="print-hide border-border bg-subtle border-b px-6 py-4"
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
                        className="text-accent flex items-center gap-1 text-sm hover:text-blue-800"
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
                        className="rounded-control text-text-2 hover:bg-hover hover:text-text-2 px-3 py-2 text-sm transition-colors"
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
                          className={`rounded-control px-4 py-2 ${
                            button.primary || button.variant === 'primary'
                              ? 'bg-accent hover:bg-accent-hover text-white'
                              : button.variant === 'danger' || button.danger
                                ? 'bg-red-600 text-white hover:bg-red-700'
                                : 'border-border-strong bg-panel text-text-2 hover:bg-hover border'
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
                          className="rounded-control border-border-strong bg-panel text-text-2 hover:bg-hover border px-4 py-2"
                        >
                          {t('action.reset')}
                        </button>
                        <button
                          type="button"
                          data-testid="filter-search"
                          onClick={handleSearch}
                          className="rounded-control bg-accent hover:bg-accent-hover px-4 py-2 text-white"
                        >
                          {t('action.search')}
                        </button>
                      </>
                    )}
                  </div>
                </div>
              </div>
            )}

          {skipListData ? (
            miscListBlocks.length > 0 &&
            runtime && (
              <div className="flex flex-col gap-4 p-4" data-testid="list-misc-blocks">
                {miscListBlocks.map((block: any, idx: number) => (
                  <BlockRenderer
                    key={block.id || `misc-list-${idx}`}
                    block={block}
                    runtime={runtime}
                    areaId="list-misc"
                  />
                ))}
              </div>
            )
          ) : activeViewType === 'table' ? (
            <>
              {miscBlocksPosition === 'beforeTable' && miscListBlocks.length > 0 && runtime && (
                <div className="flex flex-col gap-4 p-4" data-testid="list-misc-blocks">
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
                hasFilterBlock={
                  !!(filterBlock && filterBlock.fields && filterBlock.fields.length > 0)
                }
                activeQuickFilter={activeQuickFilter}
                onQuickFilter={handleQuickFilter}
                onSaveActivePreset={handleSaveActivePreset}
                savedPresetKeys={savedQuickFilterPresetKeys}
                activeSavedPresetKey={activeSavedQuickFilterPresetKey}
                activeSavedPresetEdited={activeSavedQuickFilterPresetEdited}
                onResetActiveSavedPreset={handleResetActiveSavedPreset}
                activeSorts={activeSorts}
                onSortsChange={setActiveSorts}
                sortableColumns={tableColumns
                  .filter((c: ColumnConfig) => !c.isActionColumn && c.field && c.sortable !== false)
                  .map((c: ColumnConfig) => ({
                    field: c.field,
                    label: resolveColumnLabel(c),
                    valueType: c.valueType || c.sorter,
                  }))}
                rowHeight={effectiveViewConfig?.rowHeight}
                onRowHeightChange={handleRowHeightChange}
                onColumnSettingsOpen={() => setColumnSettingsOpen(true)}
                chipFilters={chipFilters}
                onChipFiltersChange={setChipFilters}
                fieldMetadata={tableColumns
                  .filter((c: ColumnConfig) => !c.isActionColumn && c.field)
                  .map((c: ColumnConfig) => ({
                    fieldCode: c.field,
                    label: resolveColumnLabel(c),
                    fieldType: c.valueType || c.sorter || 'text',
                    dictCode: c.dictCode,
                  }))}
                resolveChipValueLabel={(filter) => {
                  const col = tableColumns.find((c: ColumnConfig) => c.field === filter.fieldCode);
                  const dc = (col as any)?.dictCode as string | undefined;
                  if (!dc) return undefined;
                  const item = dictDataCache.current
                    .get(dc)
                    ?.find((i) => String(i.value) === String(filter.value));
                  return item?.label;
                }}
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
                hideQuickFilters={hideQuickFilters}
                hideSort={listExtensions?.hideSort ?? Boolean(schemaExtension.hideSort)}
                hideColumnSettings={
                  listExtensions?.hideColumnSettings ?? Boolean(schemaExtension.hideColumnSettings)
                }
                hideRowHeight={
                  listExtensions?.hideRowHeight ?? Boolean(schemaExtension.hideRowHeight)
                }
                hideFilterChips={
                  listExtensions?.hideFilterChips ?? Boolean(schemaExtension.hideFilterChips)
                }
              />

              {/* T9 — cross-page select-all banner. Shown once the whole page
                  is selected and more matching records exist beyond it, or
                  while in "all N matching" mode. */}
              <SelectAllMatchingBanner
                enabled={selectionEnabled}
                pageFullySelected={pageFullySelected}
                allMatchingSelected={allMatchingSelected}
                pageSelectedCount={selectedIds.size}
                total={pagination.total}
                onSelectAllMatching={handleSelectAllMatching}
                onClearSelection={clearAllSelection}
                t={t}
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
                canUseButton={canUseButton}
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
                enableSelection={selectionEnabled}
                showSummaryRow={summaryRowEnabled}
                locale={locale}
                treeConfig={treeConfig}
              />

              {/* G7 — misc blocks (chart / description / rich-text / divider /
                  stat-card / etc.) dispatched via BlockRenderer fallback
                  registry. Unknown block types surface a visible placeholder. */}
              {miscBlocksPosition === 'afterTable' && miscListBlocks.length > 0 && runtime && (
                <div className="flex flex-col gap-4 p-4" data-testid="list-misc-blocks">
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
                selectedCount={effectiveSelectedCount}
                selectedIds={selectedIdList}
                modelCode={modelCode}
                onBulkEdit={() => setBulkEditOpen(true)}
                onBulkDelete={handleBulkDelete}
                onBulkExport={() => handleExportSelected('xlsx')}
                bulkActions={visibleBulkActions}
                onBulkAction={handleBulkAction}
                resolveBulkActionLabel={resolveButtonLabel}
                onClearSelection={clearAllSelection}
              />
            </>
          ) : (
            <SmartViewRenderer
              view={
                {
                  ...(currentView || {}),
                  modelCode,
                  viewType: activeViewType,
                  viewConfig: effectiveViewConfig || {},
                } as any
              }
              onGanttTaskClick={navigateToRecordView}
              onOpenViewConfig={() => setViewManageOpen(true)}
              onSwitchToTableView={() => setActiveViewType('table')}
              onCardClick={(card) => navigateToRecordView(getLegacyCompatibleRecordPid(card))}
              onEventClick={navigateToRecordView}
              onGalleryCardClick={navigateToRecordView}
              onTreeNodeClick={navigateToRecordView}
              onDataRefresh={() => loadData({ page: 0, size: pagination.pageSize })}
              linkageFilters={[]}
              pageKey={pageKey}
            />
          )}

          <ListModals
            // BulkEditModal
            bulkEditOpen={bulkEditOpen}
            onBulkEditClose={() => setBulkEditOpen(false)}
            selectedIds={selectedIdList}
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
              if (view.pid) {
                setSearchParams(
                  (prev) => {
                    const p = new URLSearchParams(prev);
                    p.set('view', view.pid);
                    return p;
                  },
                  { replace: true },
                );
              }
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
            viewManageFields={viewManageFields}
            // ColumnSettingsPanel
            columnSettingsOpen={columnSettingsOpen}
            onColumnSettingsClose={() => setColumnSettingsOpen(false)}
            allColumnDefs={allColumnDefs}
            viewColumns={effectiveViewConfig?.columns}
            onColumnSettingsSave={handleColumnSettingsSave}
            t={t}
            // FilterFieldPicker
            fieldPickerOpen={fieldPickerOpen}
            fieldPickerAnchor={fieldPickerAnchor}
            fieldPickerFields={tableColumns
              .filter((c: ColumnConfig) => !c.isActionColumn && c.field)
              .map((c: ColumnConfig) => ({
                fieldCode: c.field,
                label: resolveColumnLabel(c),
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
              const cols = (effectiveViewConfig?.columns || []).map((c) =>
                c.fieldCode === contextMenu.column.field ? { ...c, visible: false } : c,
              );
              // If column not in saved config yet, add it as hidden
              if (!cols.find((c) => c.fieldCode === contextMenu.column.field)) {
                cols.push({ fieldCode: contextMenu.column.field, visible: false });
              }
              void ensureViewAndUpdateConfig({ columns: cols });
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
      <AsyncTaskModalHost />
    </DataSourceProvider>
  );
}

/**
 * Public wrapper: provides the shared async-task-modal context so that commands
 * dispatched from any nested `useActionHandler` (the page's own, and each
 * ToolbarBlockRenderer's) surface in the single page-level progress modal
 * rendered by {@link AsyncTaskModalHost}.
 */
export function ListPageContent(props: PageContentProps) {
  return (
    <AsyncTaskModalProvider>
      <ListPageContentInner {...props} />
    </AsyncTaskModalProvider>
  );
}
