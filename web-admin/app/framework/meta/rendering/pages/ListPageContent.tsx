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
import {
  createSearchParams,
  useSearchParams,
  type NavigateFunction,
  type NavigateOptions,
  type To,
} from 'react-router';
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
import {
  resolveCommandErrorMessage,
  useActionHandler,
} from '~/framework/meta/hooks/useActionHandler';
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
import { ViewAnalysisDrawer } from './list/ViewAnalysisDrawer';
import {
  buildColumnSettingsRows,
  serializeColumnSettings,
  type ColumnSettingsDefinition,
  type ColumnSettingsSavePayload,
} from '~/framework/smart/components/view/ColumnSettingsPanel';
import { ListTable } from './list/ListTable';
import {
  BulkActionResultDialog,
  type BulkActionFailure,
  type BulkActionResult,
} from './list/BulkActionResultDialog';
import {
  areFiltersEqual,
  areSortsEqual,
  decodeFilters,
  decodeSorts,
  encodeFilters,
  encodeSorts,
} from './list/useListUrlState';
import {
  type QuickFilterPresetKey,
  buildQuickFilterPreset,
  buildQuickFilterPresetViewRequest,
  getQuickFilterPresetDefinition,
  getQuickFilterPresetDefinitions,
  isQuickFilterPresetKey,
} from './list/quickFilterPresets';
import { assembleQuickFilterChips, type QuickFilterChip } from './list/quickFilterChips';
import { resolveListRowClickMode } from './list/rowClickNavigation';
import { SelectAllMatchingBanner } from './list/SelectAllMatchingBanner';
import { SavedViewOverlayStatusBanner } from './list/SavedViewOverlayStatusBanner';
import {
  resolveAuditUserCellValue,
  resolveAuditUserDisplayFields,
} from './list/auditUserDisplayFields';
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
  getExcludedIds as selectionGetExcludedIds,
  isAllMatching as selectionIsAllMatching,
} from './list/selectionModel';
import {
  resolveBuiltInBulkCapabilities,
  selectBulkEditableColumns,
  type BuiltInBulkCapabilitiesConfig,
} from './list/bulkCapabilities';
import { savedViewService, type ChipPin } from '~/shared/services/savedViewService';
import { useDebouncedValue, useDebouncedCallback } from '~/hooks/useDebouncedValue';
import { evaluateVisibleWhen as evaluateVisibleWhenExpression } from './utils/visibleWhen';
import { TenantMemberAccountImportDialog } from './list/TenantMemberAccountImportDialog';
import {
  buildCommandTargetParams,
  getLegacyCompatibleRecordPid,
  getPublicRecordKey,
} from '~/framework/meta/utils/publicRecordId';
import {
  buildPersonalCopyName,
  canCopySavedView,
  getSavedViewPersistenceMode,
  isImplicitSavedView,
  isSavedViewLockedPreset,
  mergeViewConfigPatch,
  summarizeViewConfigPatch,
} from '~/framework/smart/utils/savedViewPersistence';
import { canUseImport } from '~/framework/smart/components/data-tools/importCapability';

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

interface ListLoadDataParams {
  page?: number;
  size?: number;
  filters?: Record<string, any>;
  sorts?: SortConfig[];
  chipFilters?: ViewFilterConfig[];
}

interface BulkFieldCommandState {
  button: ButtonConfig;
  selectedIds: string[];
  selectedCount: number;
  actionLabel: string;
  field: FieldConfig;
  operationType: 'UPDATE' | 'DELETE';
}

export function buildBulkFieldCommandPayload(field: FieldConfig, value: unknown) {
  return { [field.field]: value };
}

export function resolveInitialListTabKey(blocks: unknown): string {
  if (!Array.isArray(blocks)) return 'all';
  const tabsBlock = blocks.find(
    (block): block is { blockType: string; tabs?: Array<{ key?: unknown }> } =>
      Boolean(block && typeof block === 'object' && (block as any).blockType === 'tabs'),
  );
  const tabs = Array.isArray(tabsBlock?.tabs) ? tabsBlock.tabs : [];
  const allTab = tabs.find((tab) => tab?.key === 'all');
  const initialKey = allTab?.key ?? tabs[0]?.key;
  return typeof initialKey === 'string' && initialKey.length > 0 ? initialKey : 'all';
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

interface ListSystemReferenceDisplayConfig {
  detailEndpoint: string;
  labelFields: string[];
}

const LIST_SYSTEM_REFERENCE_DISPLAY_MODELS: Record<string, ListSystemReferenceDisplayConfig> = {
  sys_user: {
    detailEndpoint: '/api/admin/users',
    labelFields: ['displayName', 'realName', 'username', 'email'],
  },
};

export function resolveListSystemReferenceDisplayConfig(
  modelCode: string,
): ListSystemReferenceDisplayConfig | undefined {
  return LIST_SYSTEM_REFERENCE_DISPLAY_MODELS[modelCode];
}

/**
 * System-reference detail endpoints accept public identifiers, not projected
 * display labels. Some list APIs already return a label in the raw reference
 * field; whitespace cannot occur in a path-style public PID, so such values
 * should render directly instead of generating guaranteed 404 lookups.
 */
export function shouldResolveListSystemReferenceValue(value: string): boolean {
  const normalized = value.trim();
  return normalized.length > 0 && !/\s/u.test(normalized);
}

function pickSystemReferenceLabel(
  row: Record<string, any>,
  configuredDisplayField: string,
  labelFields: string[],
): string | undefined {
  for (const field of [configuredDisplayField, ...labelFields]) {
    const value = row[field];
    if (value === null || value === undefined) continue;
    const text = String(value).trim();
    if (text) return text;
  }
  return undefined;
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

export function viewConfigFiltersToRuntimeFilters(
  filters: ViewFilterConfig[] | undefined,
): Record<string, any> {
  const restoredFilters: Record<string, any> = {};
  for (const filter of filters ?? []) {
    restoredFilters[filter.fieldCode] = filter.value;
  }
  return restoredFilters;
}

export interface SavedViewFilterExpressionContext {
  /** Public user PID used by reference fields such as an opportunity owner. */
  currentUserPid?: string;
}

const CURRENT_DEPARTMENT_OWNER_PIDS_RESOLVER = {
  $currentDepartmentOwnerPids: { includeSubDepartments: true },
} as const;

const CURRENT_SHARED_RECORD_PIDS_RESOLVER = {
  $currentSharedRecordPids: { action: 'read' },
} as const;

/**
 * Resolve the deliberately small, documented set of SavedView filter expressions.
 *
 * SavedView expressions are convenience filters, not an authorization boundary;
 * backend data-scope policies remain authoritative. Unknown expressions are left
 * inactive instead of accidentally reusing a stale static value.
 */
export function resolveSavedViewFilterExpressions(
  filters: ViewFilterConfig[] | undefined,
  context: SavedViewFilterExpressionContext,
): ViewFilterConfig[] {
  return (filters ?? []).map((filter) => {
    if (!filter.isExpression) return filter;

    const expression = String(filter.expression ?? '').trim();
    const resolvedValue =
      expression === '#currentUser' || expression === '${system.currentUser}'
        ? context.currentUserPid?.trim() || undefined
        : expression === '#currentDepartmentOwners' ||
            expression === '${system.currentDepartmentOwners}'
          ? CURRENT_DEPARTMENT_OWNER_PIDS_RESOLVER
          : expression === '#currentSharedRecords' ||
              expression === '${system.currentSharedRecords}'
            ? CURRENT_SHARED_RECORD_PIDS_RESOLVER
            : undefined;

    return { ...filter, value: resolvedValue };
  });
}

function clearTransientViewSearchParams(params: URLSearchParams): void {
  for (const key of Array.from(params.keys())) {
    if (
      key === 'sort' ||
      key === 'keyword' ||
      key === 'filters' ||
      key === 'preset' ||
      key === 'pageNum' ||
      key.startsWith('filter_')
    ) {
      params.delete(key);
    }
  }
}

function stableConfigString(value: unknown): string {
  return JSON.stringify(value ?? null);
}

function isNoopViewConfigPatchEntry(
  key: keyof ViewConfig,
  value: unknown,
  baseValue: unknown,
): boolean {
  if (value === undefined) {
    return true;
  }
  if (key === 'sorts') {
    return areSortsEqual(
      Array.isArray(baseValue) ? (baseValue as SortConfig[]) : [],
      Array.isArray(value) ? (value as SortConfig[]) : [],
    );
  }
  if (Array.isArray(value)) {
    const normalizedBase = Array.isArray(baseValue) ? baseValue : [];
    return stableConfigString(value) === stableConfigString(normalizedBase);
  }
  return stableConfigString(value) === stableConfigString(baseValue);
}

export function pruneNoopViewConfigPatch(
  base: ViewConfig | Partial<ViewConfig> | null | undefined,
  patch: Partial<ViewConfig> | null | undefined,
): Partial<ViewConfig> | null {
  if (!patch) {
    return null;
  }

  const pruned: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(patch) as Array<[keyof ViewConfig, unknown]>) {
    if (!isNoopViewConfigPatchEntry(key, value, base?.[key])) {
      pruned[key] = value;
    }
  }

  return Object.keys(pruned).length > 0 ? (pruned as Partial<ViewConfig>) : null;
}

export function resolveListSavedViewPageKey(
  schema: { pageKey?: string | null } | null | undefined,
  routeTableName: string,
): string {
  return schema?.pageKey || routeTableName;
}

function snakeToCamel(value: string): string {
  return value.replace(/_([a-zA-Z0-9])/g, (_, char: string) => char.toUpperCase());
}

function camelToSnake(value: string): string {
  return value.replace(/[A-Z]/g, (char) => `_${char.toLowerCase()}`);
}

export function getListFieldValueWithAlias(
  record: Record<string, any>,
  fieldCode: string | undefined,
): unknown {
  // A bare-string column shorthand (e.g. `columns: ['name', 'pageKey']`) reaches the
  // legacy list runtime without a resolved `field`, so `column.field` can be undefined.
  // Guard the alias lookup here so such a column renders empty instead of throwing
  // `Cannot read properties of undefined (reading 'includes')`, which would blank the
  // whole page behind the render error boundary ("Oops!").
  if (!record || typeof fieldCode !== 'string' || fieldCode.length === 0) {
    return undefined;
  }
  if (Object.prototype.hasOwnProperty.call(record, fieldCode)) {
    return record[fieldCode];
  }
  const alias = fieldCode.includes('_') ? snakeToCamel(fieldCode) : camelToSnake(fieldCode);
  if (alias !== fieldCode && Object.prototype.hasOwnProperty.call(record, alias)) {
    return record[alias];
  }
  return undefined;
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

type SearchParamsSetter = ReturnType<typeof useSearchParams>[1];

/**
 * Serialize URL search-param writes made during the same React turn.
 *
 * React Router's functional `setSearchParams` form does not queue updates like
 * React state does. List state can change sorts, filters, pagination and the
 * selected SavedView together, so independently queued writers could otherwise
 * restore an older query string and drop a newly selected `view` parameter.
 */
export function useSerializedSearchParamsUpdater(
  searchParams: URLSearchParams,
  setSearchParams: SearchParamsSetter,
  writesEnabledRef?: { current: boolean },
): SearchParamsSetter {
  const latestSearchParamsRef = useRef(new URLSearchParams(searchParams));

  useEffect(() => {
    latestSearchParamsRef.current = new URLSearchParams(searchParams);
  }, [searchParams]);

  return useCallback<SearchParamsSetter>(
    (nextInit, options) => {
      if (writesEnabledRef?.current === false) return;
      const resolvedInit =
        typeof nextInit === 'function'
          ? nextInit(new URLSearchParams(latestSearchParamsRef.current))
          : nextInit;
      const next = createSearchParams(resolvedInit);
      latestSearchParamsRef.current = new URLSearchParams(next);
      setSearchParams(next, options);
    },
    [setSearchParams, writesEnabledRef],
  );
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

/**
 * A field's visual control lives in `extension.renderComponent` (colorpicker / progress /
 * rating / moneyinput / ...), NOT in its dataType. List cell-type resolution used to ignore
 * it, so those columns fell back to raw text/number. Surface it so the cell renderer can
 * pick the right presentation. Mirrors the designer's `platformTablePreview.inferColumnValueType`.
 */
export function resolveFieldMetaRenderComponent(
  fieldCode: string,
  modelFieldMap: Map<string, any> | undefined,
): string | undefined {
  if (!fieldCode || !modelFieldMap) return undefined;
  const meta = modelFieldMap.get(fieldCode);
  if (!meta) return undefined;
  const raw =
    meta.extension?.renderComponent ??
    meta.renderComponent ??
    meta.extension?.component ??
    meta.uiSchema?.component;
  return typeof raw === 'string' && raw.trim() ? raw.trim().toLowerCase() : undefined;
}

/** Map a renderComponent / DSL renderType to a list cell valueType (undefined = no override). */
export function renderComponentToValueType(
  component: string | undefined,
): ColumnConfig['valueType'] | undefined {
  switch (component) {
    case 'colorpicker':
    case 'color':
      return 'color';
    case 'progress':
    case 'progressfield':
      return 'progress';
    case 'rating':
    case 'ratingfield':
      return 'rating';
    case 'moneyinput':
    case 'money':
      return 'currency';
    default:
      return undefined;
  }
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

export interface ListFilterFieldMetadata {
  fieldCode: string;
  label: string;
  fieldType: string;
  dictCode?: string;
  referenceModelCode?: string;
  referenceValueField?: string;
  referenceDisplayField?: string;
}

/**
 * Build the typed metadata shared by the filter picker, filter chips and value editor.
 *
 * A table column is primarily a presentation contract, so many DSL pages omit its
 * data type. The model field metadata remains the source of truth for filter
 * operators and editors (money must expose numeric comparisons, enums their dict,
 * and references their target model).
 */
export function buildListFilterFieldMetadata(
  tableColumns: ColumnConfig[],
  modelFieldMap: Map<string, any> | undefined,
  resolveLabel: (column: ColumnConfig) => string,
): ListFilterFieldMetadata[] {
  return tableColumns
    .filter((column) => !column.isActionColumn && Boolean(column.field))
    .map((column) => {
      const meta = modelFieldMap?.get(column.field);
      const refTarget = readRefTargetConfig(column, meta);
      const referenceModelCode = String(
        refTarget.modelCode ||
          refTarget.targetModel ||
          refTarget.targetEntity ||
          meta?.referenceModelCode ||
          meta?.extension?.referenceModelCode ||
          '',
      ).trim();
      return {
        fieldCode: column.field,
        label: resolveLabel(column),
        fieldType: resolveColumnCapabilityDataType(column, modelFieldMap),
        dictCode: column.dictCode || meta?.dictCode || meta?.extension?.dictCode || undefined,
        referenceModelCode: referenceModelCode || undefined,
        referenceValueField: String(
          refTarget.valueField || refTarget.targetValueField || refTarget.idField || 'pid',
        ),
        referenceDisplayField:
          String(
            refTarget.displayField || refTarget.labelField || refTarget.targetField || '',
          ).trim() || undefined,
      };
    });
}

export interface ListQueryFilterCondition {
  fieldName: string;
  operator: string;
  value?: unknown;
  values?: unknown[];
}

/** Preserve array-valued IN/BETWEEN filters for the dynamic query contract. */
export function viewFilterToQueryCondition(
  filter: ViewFilterConfig,
): ListQueryFilterCondition | null {
  if (!filter.fieldCode) return null;
  const operator = (filter.operator || 'eq').toUpperCase();
  if (operator === 'ISNULL' || operator === 'ISNOTNULL') {
    return { fieldName: filter.fieldCode, operator, value: null };
  }
  if (filter.value == null || filter.value === '') return null;
  if (operator === 'IN' || operator === 'BETWEEN') {
    const values = Array.isArray(filter.value) ? filter.value : [filter.value];
    if (values.length === 0) return null;
    return { fieldName: filter.fieldCode, operator, values };
  }
  return {
    fieldName: filter.fieldCode,
    operator,
    value: operator === 'LIKE' ? `%${String(filter.value)}%` : filter.value,
  };
}

/** Convert the dynamic-list condition shape to the export endpoint shape. */
export function queryConditionToExportCondition(condition: ListQueryFilterCondition): {
  field: string;
  operator: string;
  value: unknown;
} {
  return {
    field: condition.fieldName,
    operator: condition.operator,
    value: condition.values ?? condition.value ?? null,
  };
}

export function resolveUrlStateSyncAction(
  pendingLocalEncoding: string | null | undefined,
  currentUrlEncoding: string | null,
): 'apply-url' | 'ack-local' | 'wait-for-local' {
  if (pendingLocalEncoding === undefined) return 'apply-url';
  return pendingLocalEncoding === currentUrlEncoding ? 'ack-local' : 'wait-for-local';
}

export function applyLocalSortUpdate(
  previous: SortConfig[],
  update: SortConfig[] | ((previous: SortConfig[]) => SortConfig[]),
  pendingUrlSync: { current: string | null | undefined },
): SortConfig[] {
  const next = typeof update === 'function' ? update(previous) : update;
  pendingUrlSync.current = encodeSorts(next);
  return next;
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
        : (byCode.get(column.field)?.name ?? column.field);
    byCode.set(column.field, {
      code: column.field,
      name: fallbackName,
      dataType: resolveColumnCapabilityDataType(column, modelFieldMap),
    });
  }

  return Array.from(byCode.values());
}

export function buildListColumnSettingsDefinitions(
  baseColumns: ColumnConfig[],
  modelFieldMap: Map<string, any> | undefined,
  systemColumns: ColumnConfig[],
  resolveLabel: (column: ColumnConfig) => string,
): ColumnSettingsDefinition[] {
  const definitions = new Map<string, ColumnSettingsDefinition>();

  for (const column of baseColumns) {
    if (!column.field || column.isActionColumn) continue;
    definitions.set(column.field, {
      field: column.field,
      label: resolveLabel(column),
      ...(column.mandatory === true ? { mandatory: true } : {}),
      dataType: resolveColumnCapabilityDataType(column, modelFieldMap),
      group: 'business',
      defaultVisible: true,
      defaultWidth:
        typeof column.width === 'number'
          ? column.width
          : typeof column.width === 'string'
            ? Number.parseInt(column.width, 10) || undefined
            : undefined,
      defaultFrozenPosition: column.fixed,
    });
  }

  for (const [fieldCode, meta] of modelFieldMap?.entries() ?? []) {
    if (!fieldCode || meta?.visible === false || definitions.has(fieldCode)) continue;
    definitions.set(fieldCode, {
      field: fieldCode,
      label: resolveFieldMetaDisplayName(fieldCode, modelFieldMap) ?? fieldCode,
      dataType: resolveFieldMetaDataType(fieldCode, modelFieldMap) ?? 'text',
      group: 'business',
      defaultVisible: false,
    });
  }

  for (const column of systemColumns) {
    if (!column.field || definitions.has(column.field)) continue;
    definitions.set(column.field, {
      field: column.field,
      label: resolveLabel(column),
      dataType: resolveColumnCapabilityDataType(column, modelFieldMap),
      group: 'system',
      defaultVisible: false,
    });
  }

  return Array.from(definitions.values());
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
  const [searchParams, routerSetSearchParams] = useSearchParams();
  const listUrlWritesEnabledRef = useRef(true);
  const setSearchParams = useSerializedSearchParamsUpdater(
    searchParams,
    routerSetSearchParams,
    listUrlWritesEnabledRef,
  );
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
  const [bulkFieldCommand, setBulkFieldCommand] = useState<BulkFieldCommandState | null>(null);
  const [analysisOpen, setAnalysisOpen] = useState(false);
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
  const isApiDatasourcePage = Boolean(
    schema?.dataSource?.type === 'api' && schema.dataSource.endpoint,
  );
  const skipListData = shouldSkipListData(schema);
  const skipModelFieldMeta = shouldSkipModelFieldMeta(schema, skipListData);
  const miscBlocksPosition = resolveListMiscBlocksPosition(schema);

  // Read initial sorts, keyword, and view from URL search params
  const urlSorts = useMemo(() => decodeSorts(searchParams.get('sort')), [searchParams]);
  const urlChipFilters = useMemo(() => decodeFilters(searchParams.get('filters')), [searchParams]);
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
  const pendingSortUrlSyncRef = useRef<string | null | undefined>(undefined);
  const setLocalActiveSorts = useCallback(
    (update: SortConfig[] | ((previous: SortConfig[]) => SortConfig[])) => {
      setActiveSorts((previous) => applyLocalSortUpdate(previous, update, pendingSortUrlSyncRef));
    },
    [],
  );
  // Active filter chips — user-added filters via chip bar (separate from filters)
  const [chipFilters, setChipFilters] = useState<ViewFilterConfig[]>(() => urlChipFilters);
  const pendingChipFilterUrlSyncRef = useRef<string | null | undefined>(undefined);
  const setLocalChipFilters = useCallback(
    (update: ViewFilterConfig[] | ((previous: ViewFilterConfig[]) => ViewFilterConfig[])) => {
      setChipFilters((previous) => {
        const next = typeof update === 'function' ? update(previous) : update;
        pendingChipFilterUrlSyncRef.current = encodeFilters(next);
        return next;
      });
    },
    [],
  );
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
  const chipFiltersRef = useRef<ViewFilterConfig[]>(chipFilters);

  useEffect(() => {
    chipFiltersRef.current = chipFilters;
  }, [chipFilters]);

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

  const shouldPersistPaginationToUrlRef = useRef(
    urlPageNum != null ||
      urlPageSize != null ||
      urlSorts.length > 0 ||
      urlChipFilters.length > 0 ||
      Boolean(urlKeyword),
  );
  const tableBlock = useMemo(() => {
    if (!schema?.blocks) return null;
    return schema.blocks.find((block: any) => block.blockType === 'table') || null;
  }, [schema]);
  const auditUserDisplayFields = useMemo(
    () => resolveAuditUserDisplayFields(tableBlock),
    [tableBlock],
  );
  const tableBulkActions = useMemo<ButtonConfig[]>(() => {
    const configured =
      (tableBlock as any)?.table?.bulkActions ?? (tableBlock as any)?.bulkActions ?? [];
    return Array.isArray(configured) ? configured : [];
  }, [tableBlock]);
  const tableBulkCapabilities = useMemo<BuiltInBulkCapabilitiesConfig | undefined>(() => {
    const configured =
      (tableBlock as any)?.table?.bulkCapabilities ?? (tableBlock as any)?.bulkCapabilities;
    return configured && typeof configured === 'object' ? configured : undefined;
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
  const selectedIdList = allMatchingSelected ? [] : explicitSelectedIds;
  const allMatchingExcludedIds = useMemo(
    () => selectionGetExcludedIds(selectionState),
    [selectionState],
  );
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

  // Sync URL chip filters -> local state (supports refresh and browser back/forward).
  useEffect(() => {
    const currentUrlEncoding = searchParams.get('filters');
    const syncAction = resolveUrlStateSyncAction(
      pendingChipFilterUrlSyncRef.current,
      currentUrlEncoding,
    );
    if (syncAction === 'wait-for-local') return;
    if (syncAction === 'ack-local') {
      pendingChipFilterUrlSyncRef.current = undefined;
      return;
    }
    setChipFilters((prev) => (areFiltersEqual(prev, urlChipFilters) ? prev : urlChipFilters));
  }, [searchParams, urlChipFilters]);

  // Sync URL sorts -> local state (supports refresh and browser back/forward).
  useEffect(() => {
    const currentUrlEncoding = searchParams.get('sort');
    const syncAction = resolveUrlStateSyncAction(pendingSortUrlSyncRef.current, currentUrlEncoding);
    if (syncAction === 'wait-for-local') return;
    if (syncAction === 'ack-local') {
      pendingSortUrlSyncRef.current = undefined;
      return;
    }
    setActiveSorts((prev) => (areSortsEqual(prev, urlSorts) ? prev : urlSorts));
  }, [searchParams, urlSorts]);

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
  const navigateAwayFromList = useCallback(
    ((toOrDelta: To | number, options?: NavigateOptions) => {
      // List URL state effects (SavedView sorts, filters and pagination) can still be queued
      // when a user clicks Create/View/Edit. Once an outgoing navigation starts, those stale
      // effects must never replace the destination route with the list's query-string URL.
      listUrlWritesEnabledRef.current = false;
      return typeof toOrDelta === 'number' ? navigate(toOrDelta) : navigate(toOrDelta, options);
    }) as NavigateFunction,
    [navigate],
  );

  const appendListSearch = useCallback(
    (path: string) => {
      const query = searchParams.toString();
      if (!query) return path;
      return `${path}${path.includes('?') ? '&' : '?'}${query}`;
    },
    [searchParams],
  );

  const navigateToRecordView = useCallback(
    (recordPid: string | number | null | undefined) => {
      if (recordPid == null || recordPid === '') {
        return;
      }
      navigateAwayFromList(appendListSearch(`/p/${tableName}/view/${String(recordPid)}`));
    },
    [appendListSearch, navigateAwayFromList, tableName],
  );

  // Tab state for tabs
  const [activeTab, setActiveTab] = useState(() => resolveInitialListTabKey(schema?.blocks));
  const [importOpen, setImportOpen] = useState(false);
  const [viewManageOpen, setViewManageOpen] = useState(false);
  const [columnSettingsOpen, setColumnSettingsOpen] = useState(false);
  const [contextMenu, setContextMenu] = useState<{
    x: number;
    y: number;
    column: ColumnConfig;
  } | null>(null);
  const [activeQuickFilter, setActiveQuickFilter] = useState<QuickFilterKey | null>(null);
  const activeQuickFilterRef = useRef<QuickFilterKey | null>(null);

  useEffect(() => {
    activeQuickFilterRef.current = activeQuickFilter;
  }, [activeQuickFilter]);

  useEffect(() => {
    if (!urlViewPid || !searchParams.has('preset')) return;
    setActiveQuickFilter(null);
    setSearchParams(
      (prev) => {
        const p = new URLSearchParams(prev);
        p.delete('preset');
        return p;
      },
      { replace: true },
    );
  }, [searchParams, setSearchParams, urlViewPid]);

  const [filtersExpanded, setFiltersExpanded] = useState(false);
  const [filterFormVisible, setFilterFormVisible] = useState(false);
  const [activeViewType, setActiveViewType] = useState<ViewType>('table');
  const [startCreateViewMode, setStartCreateViewMode] = useState(false);
  const [inviteDialogOpen, setInviteDialogOpen] = useState(false);
  const [inviteLoading, setInviteLoading] = useState(false);
  const [inviteCodeData, setInviteCodeData] = useState<InviteCodeData | null>(null);
  const [memberImportDialogOpen, setMemberImportDialogOpen] = useState(false);
  const [bulkActionResult, setBulkActionResult] = useState<BulkActionResult | null>(null);
  const { formats: dateTimeFormats, timezone: effectiveTimezone } = useTimezone();
  const pendingSavedViewFiltersRef = useRef<Record<string, any> | null>(null);
  // When restoring a preset view from ?preset= on mount, skip the first run of
  // the debounced sort/filter effect so it doesn't re-fetch with empty filters.
  const skipFirstSortFilterEffectRef = useRef(false);
  const loadDataRef = useRef<((params?: ListLoadDataParams) => Promise<void>) | null>(null);

  // Record preview drawer state
  const [previewRecordId, setPreviewRecordId] = useState<string | null>(null);

  // SavedView integration
  const modelCode = schema?.modelCode || tableName;
  const importConfig = schemaExtension.import as
    | import('~/framework/smart/components/data-tools/ImportModal').ImportConfiguration
    | undefined;
  const canImport = canUseImport(importConfig, hasPermission);
  const pageKey = resolveListSavedViewPageKey(schema, tableName);
  const isTenantMemberPage = modelCode === 'tenant_member' || pageKey === 'tenant_member';
  const hideSavedViews =
    listExtensions?.hideSavedViews ?? Boolean(schemaExtension.hideSavedViews || skipListData);
  // Quick filters live only in the toolbar. They apply to default view mode and
  // are not mirrored as saved-view selector entries.
  const hideQuickFilters =
    listExtensions?.hideQuickFilters ?? Boolean(schemaExtension.hideQuickFilters);
  const {
    views: savedViews,
    accessibleViews,
    currentView,
    selectView,
    selectDefaultView,
    upsertView,
    createView,
    updateView,
    deleteView: deleteSavedView,
    setDefaultView,
    duplicateView,
    copyToPersonal,
    reload: reloadViews,
    loading: viewsLoading,
  } = useSavedViews({
    modelCode,
    pageKey,
    scopeFilter: 'all',
    autoLoad: !!schema && !hideSavedViews && !skipListData,
  });

  const availableViewTypes = useMemo<ViewType[]>(() => {
    const supported = new Set<ViewType>(['table']);
    for (const view of accessibleViews) {
      if (view.viewType === 'kanban') supported.add('kanban');
    }
    return ['table', 'kanban'].filter((type) => supported.has(type as ViewType)) as ViewType[];
  }, [accessibleViews]);

  // Quick-filter chip pins (Half B): the current user's pinned views for this
  // model/page, merged into the chip row alongside built-in presets + global pins.
  const [chipPins, setChipPins] = useState<ChipPin[]>([]);
  const loadChipPins = useCallback(async () => {
    if (!schema || hideQuickFilters || skipListData) {
      setChipPins([]);
      return;
    }
    try {
      setChipPins(await savedViewService.getChipPins({ modelCode, pageKey }));
    } catch {
      setChipPins([]);
    }
  }, [schema, hideQuickFilters, skipListData, modelCode, pageKey]);
  useEffect(() => {
    void loadChipPins();
  }, [loadChipPins]);

  // Team-scoped views the user may pin for their team. They are already visible
  // in the all-scope selector; this separate fetch is only for team pin authoring
  // and remains permission-gated.
  const canManageTeamPins =
    hasPermission('dashboard.saved_view.team.update') ||
    hasPermission('dashboard.saved_view.update');
  const [teamViews, setTeamViews] = useState<SavedView[]>([]);
  const loadTeamViews = useCallback(async () => {
    if (!schema || !canManageTeamPins || skipListData) {
      setTeamViews([]);
      return;
    }
    try {
      setTeamViews(await savedViewService.getTeamViews({ modelCode, pageKey }));
    } catch {
      setTeamViews([]);
    }
  }, [schema, canManageTeamPins, skipListData, modelCode, pageKey]);
  useEffect(() => {
    void loadTeamViews();
  }, [loadTeamViews]);

  const [pendingViewConfig, setPendingViewConfig] = useState<Partial<ViewConfig> | null>(null);
  const [savingViewDraft, setSavingViewDraft] = useState(false);
  const [copyingViewDraft, setCopyingViewDraft] = useState(false);
  const [repairingViewOverlay, setRepairingViewOverlay] = useState(false);
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
    savedViewPersistenceMode === 'shared-draft' && Object.keys(pendingViewConfig ?? {}).length > 0;
  const hasPendingPersonalViewConfig =
    savedViewPersistenceMode === 'personal-persist' &&
    Object.keys(pendingViewConfig ?? {}).length > 0;
  const hasPendingViewConfig = hasPendingPersonalViewConfig || hasPendingSharedViewConfig;
  const effectiveViewConfig = useMemo(
    () =>
      currentView
        ? mergeViewConfigPatch(currentView.viewConfig, pendingViewConfig ?? {})
        : undefined,
    [currentView, pendingViewConfig],
  );
  const activeViewTemplate = useMemo(
    () =>
      accessibleViews.find((view) => view.viewType === activeViewType && view.isDefault) ??
      accessibleViews.find((view) => view.viewType === activeViewType) ??
      null,
    [accessibleViews, activeViewType],
  );
  const activeTabViewFilter = useMemo<ViewFilterConfig | null>(() => {
    const tabsBlock = schema?.blocks?.find((block: any) => block.blockType === 'tabs');
    const tab = (tabsBlock?.tabs as any[] | undefined)?.find(
      (candidate) => candidate.key === activeTab,
    );
    if (!tab?.filter) return null;
    const fieldCode = tab.filter.fieldName || tab.filter.field;
    if (!fieldCode) return null;
    return {
      fieldCode,
      operator: String(tab.filter.operator || 'EQ').toLowerCase() as ViewFilterConfig['operator'],
      value: tab.filter.value,
    };
  }, [activeTab, schema?.blocks]);
  const resolvedEffectiveViewFilters = useMemo(
    () =>
      resolveSavedViewFilterExpressions(effectiveViewConfig?.filters, {
        currentUserPid: user?.pid,
      }),
    [effectiveViewConfig?.filters, user?.pid],
  );
  const activeRuntimeViewFilters = useMemo<ViewFilterConfig[]>(() => {
    const merged = [
      ...resolvedEffectiveViewFilters,
      ...Object.entries(filters)
        .filter(([, value]) => value != null && value !== '')
        .map(([fieldCode, value]) => ({
          fieldCode,
          operator: 'eq' as const,
          value,
        })),
      ...chipFilters,
      ...(activeTabViewFilter ? [activeTabViewFilter] : []),
    ];
    const unique = new Map<string, ViewFilterConfig>();
    for (const filter of merged) {
      unique.set(`${filter.fieldCode}:${filter.operator}:${JSON.stringify(filter.value)}`, filter);
    }
    return Array.from(unique.values());
  }, [activeTabViewFilter, chipFilters, filters, resolvedEffectiveViewFilters]);
  const effectiveNonNullViewConfig = useMemo<Partial<ViewConfig>>(
    () =>
      Object.fromEntries(
        Object.entries(effectiveViewConfig ?? {}).filter(
          ([, value]) => value !== null && value !== undefined,
        ),
      ),
    [effectiveViewConfig],
  );
  const activeViewConfig = useMemo<ViewConfig>(
    () => ({
      ...(activeViewTemplate?.viewConfig ?? {}),
      ...effectiveNonNullViewConfig,
      filters: activeRuntimeViewFilters,
    }),
    [activeRuntimeViewFilters, activeViewTemplate?.viewConfig, effectiveNonNullViewConfig],
  );
  const pendingViewSummary = useMemo(
    () => summarizeViewConfigPatch(pendingViewConfig),
    [pendingViewConfig],
  );
  const translateCommon = useCallback(
    (key: string, fallback: string) => {
      const value = t(key);
      return value && value !== key ? value : fallback;
    },
    [t],
  );
  const overlayMeta = currentView?.viewConfig?.meta;
  const overlayCanWrite =
    Boolean(currentView) &&
    (savedViewPersistenceMode !== 'shared-draft' || canSaveSharedView) &&
    !isCurrentViewLockedPreset;
  const canRepairViewOverlay = overlayCanWrite && !hasPendingViewConfig;
  const repairViewOverlayUnavailableReason = hasPendingViewConfig
    ? translateCommon(
        'common.saved_view_overlay_pending_draft',
        '请先保存或放弃当前视图的本地变更。',
      )
    : undefined;

  const handleRepairViewOverlay = useCallback(async () => {
    if (!currentView?.viewConfig || !canRepairViewOverlay) return;

    setRepairingViewOverlay(true);
    try {
      await updateView({ viewConfig: currentView.viewConfig });
      showSuccessToast(
        translateCommon(
          'common.saved_view_overlay_repair_success',
          '失效设置已清理，个人视图已适配当前页面。',
        ),
      );
    } catch (err) {
      showErrorToast(
        err instanceof Error
          ? err.message
          : translateCommon('common.saved_view_overlay_repair_failed', '个人视图修复失败'),
      );
    } finally {
      setRepairingViewOverlay(false);
    }
  }, [
    canRepairViewOverlay,
    currentView,
    showErrorToast,
    showSuccessToast,
    translateCommon,
    updateView,
  ]);
  useEffect(() => {
    setPendingViewConfig(null);
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
      chipFiltersRef.current = restoredViewFilters;
      setLocalChipFilters((prev) =>
        areFiltersEqual(prev, restoredViewFilters) ? prev : restoredViewFilters,
      );

      const restoredSorts = vc.sorts ?? [];
      setLocalActiveSorts((prev) => (areSortsEqual(prev, restoredSorts) ? prev : restoredSorts));

      if (vc.pagination?.pageSize && vc.pagination.pageSize > 0) {
        setPagination((prev: typeof pagination) => ({
          ...prev,
          pageSize: vc.pagination!.pageSize!,
        }));
      }

      return restoredFilters;
    },
    [setFilters, setLocalActiveSorts, setLocalChipFilters, setPagination, user?.pid],
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
        selectedIds: selectedIdList,
      },
      locale,
      t: (key: string) => t(key),
      token,
      __dataSourceManager: dataSourceManager,
    });
  }, [locale, filters, selectedIdList, t, token, dataSourceManager]);

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
      const conditions: ListQueryFilterCondition[] = [];
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
          const condition = viewFilterToQueryCondition(cf);
          if (condition) conditions.push(condition);
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
    async (params?: ListLoadDataParams) => {
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
          // where URL tableName differs from actual model, e.g., /p/crm-my-tasks → crm_activity_common)
          const apiTableName = schema.modelCode ? schema.modelCode : tableName;
          endpoint = `${buildApiEndpoint(apiTableName)}/list`;
        }

        const requestedPageNum = (params?.page ?? pagination.current - 1) + 1;
        const requestedPageSize = params?.size ?? pagination.pageSize;
        const requestedPageZeroBased = Math.max(requestedPageNum - 1, 0);
        const requestedSorts = params?.sorts ?? activeSorts;
        const queryParams: Record<string, any> = {};

        if (isApiDatasource) {
          queryParams.page = requestedPageZeroBased;
          queryParams.size = requestedPageSize;
        } else {
          queryParams.pageNum = requestedPageNum;
          queryParams.pageSize = requestedPageSize;
          if (auditUserDisplayFields) {
            queryParams.auditUserDisplayFields = auditUserDisplayFields;
          }
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
          if (requestedSorts.length === 1) {
            queryParams.sortField = requestedSorts[0].fieldCode;
            queryParams.sortOrder = requestedSorts[0].direction;
          } else if (tableBlock?.defaultSort?.field) {
            queryParams.sortField = tableBlock.defaultSort.field;
            queryParams.sortOrder = String(tableBlock.defaultSort.order || 'desc').toLowerCase();
          }
        } else {
          // For standard dynamic tables, use JSON filters array
          const tabCondition = getTabFilter();
          const filtersParam = buildFiltersParam(
            tabCondition,
            params?.filters,
            params?.chipFilters ?? chipFiltersRef.current,
          );
          if (filtersParam) {
            queryParams.filters = filtersParam;
          }
          // Use active sorts (user-driven) > SavedView sorts > DSL defaultSort
          // Multi-field sort: use sortFields param (field:direction pairs) when >1 sort
          if (requestedSorts.length > 1) {
            queryParams.sortFields = requestedSorts
              .map((s) => `${s.fieldCode}:${s.direction}`)
              .join(',');
          } else if (requestedSorts.length === 1) {
            queryParams.sortField = requestedSorts[0].fieldCode;
            queryParams.sortOrder = requestedSorts[0].direction;
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
      auditUserDisplayFields,
      activeSorts,
      skipListData,
    ],
  );

  useEffect(() => {
    loadDataRef.current = loadData;
  }, [loadData]);

  const handleSelectDefaultView = useCallback(() => {
    const implicitDefaultView =
      savedViews.find((view) => view.scope === 'personal' && isImplicitSavedView(view)) ?? null;
    const implicitViewConfig = implicitDefaultView?.viewConfig;
    const restoredFilters = applyViewConfigToListState(implicitViewConfig);
    const restoredSorts = implicitViewConfig?.sorts ?? [];
    setPendingViewConfig(null);
    selectDefaultView();
    setActiveQuickFilter(null);
    clearKeyword();
    setActiveViewType((implicitDefaultView?.viewType as ViewType) || 'table');
    setSearchParams(
      (prev) => {
        const p = new URLSearchParams(prev);
        p.delete('view');
        clearTransientViewSearchParams(p);
        return p;
      },
      { replace: true },
    );
    loadData({
      page: 0,
      size: pagination.pageSize,
      filters: restoredFilters,
      sorts: restoredSorts,
    });
  }, [
    applyViewConfigToListState,
    clearKeyword,
    loadData,
    pagination.pageSize,
    savedViews,
    selectDefaultView,
    setSearchParams,
  ]);

  useEffect(() => {
    const restoredFilters = pendingSavedViewFiltersRef.current;
    if (!restoredFilters) return;
    pendingSavedViewFiltersRef.current = null;
    loadData({
      page: 0,
      size: pagination.pageSize,
      filters: restoredFilters,
      sorts: currentView?.viewConfig?.sorts ?? [],
    });
  }, [currentView?.pid, currentView?.viewConfig?.sorts, loadData, pagination.pageSize]);

  // Use unified action handler hook
  // IMPORTANT: Must be declared before any useEffect that references handleAction
  // to avoid temporal dead zone ("Cannot access 'handleAction' before initialization").
  const { handleAction } = useActionHandler({
    runtime,
    navigate: navigateAwayFromList,
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
    // A failed row/toolbar ACTION (e.g. a command rejected by a business rule such as the FR-05
    // startup interlock) must surface as a toast only — useActionHandler already calls notifyToast.
    // Do NOT route it into the page-level `error` state: that replaces the whole list with the
    // full-page "加载失败" ErrorAlert (which is reserved for data/schema load failures), forcing a
    // reload to recover. Blocking a single row's action should never blank the table.
    onError: (err) => {
      if (import.meta.env?.DEV)
        console.warn('[ListPageContent] action error (shown via toast):', err.message);
    },
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
            if (auditUserDisplayFields) {
              queryParams.auditUserDisplayFields = auditUserDisplayFields;
            }
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
      chipFilters,
      filters,
      pagination.pageSize,
      tableName,
      token,
      t,
      activeSorts,
      tableBlock,
      auditUserDisplayFields,
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
    setLocalActiveSorts((prev) => {
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
  }, [setLocalActiveSorts]);

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
      pendingSortUrlSyncRef.current = encoded;
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
    if (areSortsEqual(effectiveViewConfig?.sorts ?? [], activeSorts)) {
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

  // Sync chip filters to URL so detail navigation and browser back/forward keep the list state.
  useEffect(() => {
    if (!schema || skipListData) return;
    const encoded = encodeFilters(chipFilters);
    const currentEncoded = searchParams.get('filters');
    if ((encoded ?? null) === (currentEncoded ?? null)) return;
    pendingChipFilterUrlSyncRef.current = encoded;
    shouldPersistPaginationToUrlRef.current = true;
    setSearchParams(
      (prev) => {
        const p = new URLSearchParams(prev);
        if (encoded) {
          p.set('filters', encoded);
        } else {
          p.delete('filters');
        }
        p.set('pageNum', '1');
        return p;
      },
      { replace: true },
    );
  }, [chipFilters, schema, searchParams, setSearchParams, skipListData]);

  const handleImportComplete = useCallback(() => {
    setImportOpen(false);
    loadData({ page: 0, size: pagination.pageSize, filters });
  }, [loadData, pagination.pageSize, filters]);

  // Bulk selection handlers (T9 — backed by the cross-page selection model).
  const toggleRowSelection = useCallback((recordPid: string) => {
    setSelectionState((prev) => selectionToggleRow(prev, recordPid));
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

  const executeTargetedBulkCommand = useCallback(
    async (
      button: ButtonConfig,
      ids: string[],
      payload: Record<string, unknown>,
      operationType: 'UPDATE' | 'DELETE' = 'UPDATE',
    ) => {
      const label = resolveButtonLabel(button);
      const isZhLocale = locale.toLowerCase().startsWith('zh');
      let successCount = 0;
      const failures: BulkActionFailure[] = [];
      const recordLabelById = new Map(
        data.map((record) => {
          const id = getPublicRecordKey(record) || '';
          const recordLabel =
            record.name ||
            record.title ||
            record.crm_opp_name ||
            record.code ||
            record.crm_opp_code ||
            id;
          return [id, String(recordLabel)] as const;
        }),
      );
      const command = (button.action as any)?.command || button.commandCode;
      if (!command) return;

      for (const id of ids) {
        try {
          const result = await fetchResult(`/api/meta/commands/execute/${command}`, {
            method: 'post',
            params: {
              ...buildCommandTargetParams(id),
              payload,
              operationType,
            },
            token: token || undefined,
          });
          if (ResultHelper.isSuccess(result)) {
            successCount += 1;
          } else {
            failures.push({
              recordPid: id,
              recordLabel: recordLabelById.get(id) || id,
              reason: resolveCommandErrorMessage(result, command),
            });
          }
        } catch (error) {
          failures.push({
            recordPid: id,
            recordLabel: recordLabelById.get(id) || id,
            reason: error instanceof Error ? error.message : String(error),
          });
        }
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
            isZhLocale
              ? `${label}已完成，成功 ${successCount} 条`
              : `${label} completed for ${successCount} records`,
          ),
          'success',
        );
      } else {
        showToast(
          successCount > 0
            ? translateOrFallback(
                t,
                'list.bulkAction.partial',
                isZhLocale
                  ? `${label}部分完成：成功 ${successCount} 条，失败 ${failures.length} 条`
                  : `${label} completed for ${successCount} records; ${failures.length} failed`,
              )
            : failures[0]?.reason || `${label} failed`,
          successCount > 0 ? 'warning' : 'error',
        );
        setBulkActionResult({ actionLabel: label, successCount, failures });
      }
    },
    [data, filters, loadData, locale, pagination.pageSize, resolveButtonLabel, showToast, t, token],
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

      if (actionType === 'bulk_field_command') {
        const field = (actionDef as any)?.input as FieldConfig | undefined;
        if (!field?.field) {
          showToast(
            translateOrFallback(
              t,
              'list.bulkAction.missingInput',
              'Bulk field action is missing its input field',
            ),
            'error',
          );
          return;
        }
        setBulkFieldCommand({
          button,
          selectedIds: [...ids],
          selectedCount: ids.length,
          actionLabel: resolveButtonLabel(button),
          field,
          operationType: (actionDef as any)?.operationType || 'UPDATE',
        });
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

      if (actionType === 'bulk_state_transition' || actionType === 'bulk_record_command') {
        await executeTargetedBulkCommand(
          button,
          ids,
          {},
          (actionDef as any)?.operationType || 'UPDATE',
        );
        return;
      }

      const label = resolveButtonLabel(button);
      const isZhLocale = locale.toLowerCase().startsWith('zh');
      let successCount = 0;
      const failures: BulkActionFailure[] = [];

      if (actionType === 'bulk_command') {
        const result = await fetchResult(`/api/meta/commands/execute/${command}`, {
          method: 'post',
          params: {
            payload: {
              recordPids: ids,
              selectedIds: ids,
              modelCode,
            },
          },
          token: token || undefined,
        });
        if (ResultHelper.isSuccess(result)) {
          successCount = ids.length;
        } else {
          failures.push({
            recordPid: command,
            recordLabel: command,
            reason: resolveCommandErrorMessage(result, command),
          });
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
            isZhLocale
              ? `${label}已完成，成功 ${successCount} 条`
              : `${label} completed for ${successCount} records`,
          ),
          'success',
        );
      } else if (successCount > 0) {
        showToast(
          translateOrFallback(
            t,
            'list.bulkAction.partial',
            isZhLocale
              ? `${label}部分完成：成功 ${successCount} 条，失败 ${failures.length} 条`
              : `${label} completed for ${successCount} records; ${failures.length} failed`,
          ),
          'warning',
        );
        setBulkActionResult({ actionLabel: label, successCount, failures });
      } else {
        showToast(failures[0]?.reason || `${label} failed`, 'error');
        setBulkActionResult({ actionLabel: label, successCount, failures });
      }
    },
    [
      canUseButton,
      executeTargetedBulkCommand,
      filters,
      loadData,
      locale,
      modelCode,
      pagination.pageSize,
      resolveButtonLabel,
      showToast,
      t,
      token,
    ],
  );

  const handleBulkFieldCommandSubmit = useCallback(
    async (value: unknown) => {
      if (!bulkFieldCommand) return;
      await executeTargetedBulkCommand(
        bulkFieldCommand.button,
        bulkFieldCommand.selectedIds,
        buildBulkFieldCommandPayload(bulkFieldCommand.field, value),
        bulkFieldCommand.operationType,
      );
      setBulkFieldCommand(null);
    },
    [bulkFieldCommand, executeTargetedBulkCommand],
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
      // renderComponent-driven visual type (colorpicker/progress/rating/money): the field's
      // control comes from extension.renderComponent, not its dataType, so the name-suffix
      // heuristics below miss it. Also honor the DSL column `renderType` key as a fallback.
      const byRenderComponent =
        renderComponentToValueType(resolveFieldMetaRenderComponent(field, modelFieldMap)) ??
        renderComponentToValueType(
          typeof (column as any).renderType === 'string'
            ? (column as any).renderType.toLowerCase()
            : undefined,
        );
      if (byRenderComponent) {
        return byRenderComponent;
      }
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
    [modelFieldMap],
  );

  // Render cell content using CellRendererRegistry
  const renderCellContent = useCallback(
    (column: ColumnConfig, record: DynamicEntity, rowIndex: number) => {
      const rawValue = getListFieldValueWithAlias(record, column.field);
      const value = resolveAuditUserCellValue(record, column.field, rawValue);
      const recordWithAliasedField = Object.prototype.hasOwnProperty.call(record, column.field)
        ? record
        : { ...record, [column.field]: value };
      const referenceConfig = collectListReferenceDisplayConfigs([column], modelFieldMap)[0];
      const cacheKey = referenceConfig
        ? buildListReferenceDisplayCacheKey(referenceConfig)
        : undefined;
      const referenceDisplayValue =
        referenceConfig && value !== null && value !== undefined
          ? recordWithAliasedField[referenceConfig.displayKey] ||
            referenceDisplayCache[cacheKey || '']?.[String(value)]
          : undefined;
      const recordForRenderer = referenceDisplayValue
        ? { ...recordWithAliasedField, [referenceConfig!.displayKey]: referenceDisplayValue }
        : recordWithAliasedField;
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
            row: recordForRenderer,
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
  const SYSTEM_FIELD_DEFS: ColumnConfig[] = useMemo(
    () => [
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
    ],
    [t],
  );

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
    // Include model and system fields that are explicitly enabled in viewConfig.
    // This lets SavedView promote a valid model field that the DSL table does
    // not show by default, while the page schema remains the default layout.
    const baseFields = new Set(baseCols.map((c) => c.field));
    const modelCols: ColumnConfig[] = [];
    for (const vc of viewColumns) {
      if (vc.visible === false || baseFields.has(vc.fieldCode)) continue;
      const meta = modelFieldMap.get(vc.fieldCode);
      if (!meta) continue;
      const renderComponent = resolveFieldMetaRenderComponent(vc.fieldCode, modelFieldMap);
      const dataType = resolveFieldMetaDataType(vc.fieldCode, modelFieldMap);
      modelCols.push({
        field: vc.fieldCode,
        label: resolveFieldMetaDisplayName(vc.fieldCode, modelFieldMap) ?? vc.fieldCode,
        valueType: renderComponentToValueType(renderComponent),
        sorter: dataType,
        sortable: true,
        dictCode: meta.dictCode || meta.extension?.dictCode || undefined,
        refTarget: meta.refTarget || meta.extension?.refTarget || undefined,
      } as ColumnConfig);
      baseFields.add(vc.fieldCode);
    }
    const sysCols = SYSTEM_FIELD_DEFS.filter((sf) => {
      const vc = viewColMap.get(sf.field);
      return vc && vc.visible !== false && !baseFields.has(sf.field);
    });
    const allCols = [...baseCols, ...modelCols, ...sysCols];
    const visibleCols = allCols
      .map((col) => {
        const vc = viewColMap.get(col.field);
        if (vc && vc.visible === false) return null;
        return {
          ...col,
          ...(vc?.width ? { width: vc.width } : {}),
          ...(vc?.frozen && vc.frozenPosition
            ? { fixed: vc.frozenPosition }
            : vc?.frozen === false
              ? { fixed: undefined }
              : {}),
        };
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
  }, [tableBlock, effectiveViewConfig, modelFieldMap, SYSTEM_FIELD_DEFS]);

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
            const systemConfig = resolveListSystemReferenceDisplayConfig(config.modelCode);
            if (systemConfig) {
              await Promise.all(
                values.filter(shouldResolveListSystemReferenceValue).map(async (value) => {
                  const result = await fetchResult<Record<string, any>>(
                    `${systemConfig.detailEndpoint}/${encodeURIComponent(value)}`,
                    {
                      method: 'get',
                      token: token || undefined,
                    },
                  );
                  if (cancelled || !ResultHelper.isSuccess(result) || !result.data) return;
                  const label = pickSystemReferenceLabel(
                    result.data,
                    config.displayField,
                    systemConfig.labelFields,
                  );
                  if (!label) return;
                  updates[cacheKey] = updates[cacheKey] || {};
                  updates[cacheKey][value] = label;
                }),
              );
              return;
            }

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
      const cfStyle = evaluateConditionalFormats(effectiveViewConfig?.conditionalFormats, record);
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
        navigateAwayFromList(appendListSearch(resolved));
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
          navigateAwayFromList(appendListSearch(`/p/${resolvedDetailPageKey}/view/${pid}`));
        } else {
          navigateAwayFromList(appendListSearch(`/p/${tableName}/view/${pid}`));
        }
        return;
      }

      setPreviewRecordId(pid);
    },
    [
      appendListSearch,
      schema,
      tableBlock,
      tableName,
      navigateAwayFromList,
      listExtensions?.disableRowClick,
    ],
  );

  // All model-backed column definitions for ColumnSettingsPanel. DSL columns
  // define the default visible subset; other readable model fields begin hidden.
  const allColumnDefs = useMemo(() => {
    if (!tableBlock) return [];
    const cols = (tableBlock as BlockConfig).table?.columns || tableBlock.columns;
    if (!Array.isArray(cols)) return [];
    return buildListColumnSettingsDefinitions(
      cols as ColumnConfig[],
      modelFieldMap,
      SYSTEM_FIELD_DEFS,
      resolveColumnLabel,
    );
  }, [tableBlock, modelFieldMap, resolveColumnLabel, SYSTEM_FIELD_DEFS]);

  const viewManageFields = useMemo(() => {
    const fieldMap = new Map(
      buildViewManageFieldOptions(tableColumns, modelFieldMap).map((field) => [field.code, field]),
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

  const filterFieldMetadata = useMemo(() => {
    const businessFields = buildListFilterFieldMetadata(
      tableColumns,
      modelFieldMap,
      resolveColumnLabel,
    );
    const known = new Set(businessFields.map((field) => field.fieldCode));
    const systemFields = buildListFilterFieldMetadata(
      SYSTEM_FIELD_DEFS.filter((column) => !known.has(column.field)),
      modelFieldMap,
      resolveColumnLabel,
    );
    return [...businessFields, ...systemFields];
  }, [tableColumns, modelFieldMap, resolveColumnLabel, SYSTEM_FIELD_DEFS]);

  const handleAnalysisDrillDown = useCallback(
    (drillFilters: import('~/framework/smart/types/chart').FilterConfig[]) => {
      const next = drillFilters.map<ViewFilterConfig>((filter) => ({
        fieldCode: filter.field,
        operator: 'eq',
        value: filter.value,
      }));
      const fields = new Set(next.map((filter) => filter.fieldCode));
      const nextChipFilters = [
        ...chipFilters.filter((filter) => !fields.has(filter.fieldCode)),
        ...next,
      ];
      chipFiltersRef.current = nextChipFilters;
      setLocalChipFilters(nextChipFilters);
      void loadData({
        page: 0,
        size: pagination.pageSize,
        filters,
        chipFilters: nextChipFilters,
      });
      setAnalysisOpen(false);
    },
    [chipFilters, filters, loadData, pagination.pageSize, setLocalChipFilters],
  );

  // Personal-only baseline: changes to an explicit personal view are staged as
  // a visible local draft. The user chooses save-current, save-as-new, or discard.
  const [viewSavedHintOn, flashViewSavedHint] = useTransientFlag(2200);

  const ensureViewAndUpdateConfig = useCallback(
    async (
      config: Partial<import('~/framework/smart/types/savedView').ViewConfig>,
      options?: { isStale?: () => boolean; rethrow?: boolean },
    ) => {
      try {
        const persistenceMode = getSavedViewPersistenceMode(currentView);
        if (persistenceMode === 'personal-persist' || persistenceMode === 'shared-draft') {
          setPendingViewConfig((prev) =>
            pruneNoopViewConfigPatch(currentView?.viewConfig, mergeViewConfigPatch(prev, config)),
          );
        } else {
          // No explicit view — use backend auto-save (atomic upsert of implicit view)
          const view = await savedViewService.autoSave({
            modelCode,
            pageKey,
            viewConfig: config,
          });
          // Apply the returned implicit view immediately; reloading should not be required.
          if (view) {
            upsertView(view);
          }
        }
        if (!options?.isStale?.() && persistenceMode === 'implicit-autosave') {
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
    [currentView, modelCode, pageKey, upsertView, flashViewSavedHint],
  );

  const handleSaveCurrentViewDraft = useCallback(async () => {
    if (!currentView || !hasPendingViewConfig || !pendingViewConfig) return;

    setSavingViewDraft(true);
    try {
      await updateView({
        viewConfig: mergeViewConfigPatch(currentView.viewConfig, pendingViewConfig),
      });
      setPendingViewConfig(null);
      flashViewSavedHint();
      showSuccessToast(translateCommon('common.saved_view_current_saved', 'Current view saved'));
    } catch (err) {
      showErrorToast(
        err instanceof Error
          ? err.message
          : translateCommon('common.saved_view_shared_save_failed', 'Failed to save view'),
      );
    } finally {
      setSavingViewDraft(false);
    }
  }, [
    currentView,
    flashViewSavedHint,
    hasPendingViewConfig,
    pendingViewConfig,
    showErrorToast,
    showSuccessToast,
    translateCommon,
    updateView,
  ]);

  const handleDiscardViewDraft = useCallback(() => {
    if (!currentView) {
      setPendingViewConfig(null);
      return;
    }

    const restoredFilters = applyViewConfigToListState(currentView.viewConfig);
    const restoredSorts = currentView.viewConfig?.sorts ?? [];
    setPendingViewConfig(null);
    setActiveQuickFilter(null);
    clearKeyword();
    setSearchParams(
      (prev) => {
        const p = new URLSearchParams(prev);
        p.set('view', currentView.pid);
        clearTransientViewSearchParams(p);
        return p;
      },
      { replace: true },
    );
    loadData({
      page: 0,
      size: currentView.viewConfig?.pagination?.pageSize ?? pagination.pageSize,
      filters: restoredFilters,
      sorts: restoredSorts,
    });
  }, [
    applyViewConfigToListState,
    clearKeyword,
    currentView,
    loadData,
    pagination.pageSize,
    setSearchParams,
  ]);

  const handleSaveDraftAsPersonalView = useCallback(async () => {
    if (!currentView || !hasPendingViewConfig || !pendingViewConfig) return;
    if (!canCopyCurrentView) {
      showErrorToast(translateCommon('common.saved_view_copy_disabled_reason', '当前视图不能复制'));
      return;
    }
    setCopyingViewDraft(true);
    try {
      const mergedConfig = mergeViewConfigPatch(currentView.viewConfig, pendingViewConfig);
      const copiedView =
        currentView.scope === 'personal'
          ? await createView({
              name: buildPersonalCopyName(currentView.name),
              modelCode,
              pageKey,
              scope: 'personal',
              viewType: currentView.viewType ?? activeViewType ?? 'table',
              viewConfig: mergedConfig,
            })
          : await copyToPersonal(currentView.pid, {
              name: buildPersonalCopyName(currentView.name),
              viewConfig: mergedConfig,
            });
      setPendingViewConfig(null);
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
      showSuccessToast(translateCommon('common.saved_view_copied_to_personal', '已另存为个人视图'));
    } catch (err) {
      showErrorToast(
        err instanceof Error
          ? err.message
          : translateCommon('common.saved_view_copy_failed', '复制视图失败'),
      );
    } finally {
      setCopyingViewDraft(false);
    }
  }, [
    activeViewType,
    canCopyCurrentView,
    copyToPersonal,
    createView,
    currentView,
    hasPendingViewConfig,
    modelCode,
    pageKey,
    pendingViewConfig,
    setSearchParams,
    showErrorToast,
    showSuccessToast,
    translateCommon,
  ]);

  const handleSaveSharedDraft = useCallback(async () => {
    if (!currentView || !hasPendingSharedViewConfig || !pendingViewConfig) return;

    const targetName =
      currentView.scope === 'team'
        ? currentView.teamName || translateCommon('common.saved_view_scope_team', 'Team')
        : translateCommon('common.saved_view_scope_global', 'Global');
    const summaryText =
      pendingViewSummary.length > 0
        ? pendingViewSummary.join(', ')
        : translateCommon('common.saved_view_shared_changes', 'view configuration');
    const confirmed = await confirmDialog({
      title: translateCommon('common.saved_view_save_shared_confirm_title', '保存到共享视图？'),
      content: translateCommon(
        'common.saved_view_save_shared_confirm_content',
        `This will update the shared view for ${targetName}. Changes: ${summaryText}.`,
      )
        .replace('{target}', targetName)
        .replace('{changes}', summaryText),
      confirmText: translateCommon('common.saved_view_save_shared', 'Save Shared View'),
      cancelText: translateCommon('common.cancel', '取消'),
    });
    if (!confirmed) return;

    setSavingViewDraft(true);
    try {
      await updateView({
        viewConfig: mergeViewConfigPatch(currentView.viewConfig, pendingViewConfig),
      });
      setPendingViewConfig(null);
      flashViewSavedHint();
      showSuccessToast(translateCommon('common.saved_view_shared_saved', 'Shared view updated'));
    } catch (err) {
      showErrorToast(
        err instanceof Error
          ? err.message
          : translateCommon('common.saved_view_shared_save_failed', 'Failed to save shared view'),
      );
    } finally {
      setSavingViewDraft(false);
    }
  }, [
    currentView,
    flashViewSavedHint,
    hasPendingSharedViewConfig,
    pendingViewConfig,
    pendingViewSummary,
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

  const buildCurrentColumnSettings = useCallback(
    () =>
      serializeColumnSettings(buildColumnSettingsRows(allColumnDefs, effectiveViewConfig?.columns)),
    [allColumnDefs, effectiveViewConfig?.columns],
  );

  // Handle column reorder via drag-and-drop
  const handleColumnReorder = useCallback(
    (newOrder: string[]) => {
      setColumnOrder(newOrder);
      const existing = buildCurrentColumnSettings();
      const byField = new Map(existing.map((column) => [column.fieldCode, column]));
      const reordered = newOrder.map((fieldCode, order) => ({
        ...(byField.get(fieldCode) || { fieldCode }),
        fieldCode,
        order,
      }));
      const trailing = existing
        .filter((column) => !newOrder.includes(column.fieldCode))
        .map((column, index) => ({ ...column, order: newOrder.length + index }));
      autoSave({ columns: [...reordered, ...trailing] });
    },
    [autoSave, buildCurrentColumnSettings],
  );

  // Handle column resize
  const handleColumnResize = useCallback(
    (field: string, width: number) => {
      const cols = buildCurrentColumnSettings();
      const idx = cols.findIndex((c) => c.fieldCode === field);
      if (idx >= 0) {
        cols[idx] = { ...cols[idx], width };
      } else {
        cols.push({ fieldCode: field, width });
      }
      autoSave({ columns: cols });
    },
    [autoSave, buildCurrentColumnSettings],
  );

  // Handle column settings save -> update SavedView
  const handleColumnSettingsSave = useCallback(
    async ({ columns, rowHeight }: ColumnSettingsSavePayload) => {
      await ensureViewAndUpdateConfig({ columns, rowHeight }, { rethrow: true });
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

  const bulkEditColumns = useMemo(
    () => selectBulkEditableColumns(tableColumns, tableBulkCapabilities !== undefined),
    [tableBulkCapabilities, tableColumns],
  );
  const builtInBulkCapabilities = useMemo(
    () =>
      resolveBuiltInBulkCapabilities(tableBulkCapabilities, hasPermission, bulkEditColumns.length),
    [bulkEditColumns.length, hasPermission, tableBulkCapabilities],
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
    for (const filter of chipFilters) {
      const condition = viewFilterToQueryCondition(filter);
      if (condition) conditions.push(queryConditionToExportCondition(condition));
    }
    return conditions.length > 0 ? conditions : undefined;
  }, [chipFilters, filters, getTabFilter]);

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
            keyword: keywordRef.current.trim() || undefined,
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
        const conditions = [
          ...(exportFilterConditions ?? []),
          ...(allMatchingExcludedIds.length > 0
            ? [{ field: 'pid', operator: 'NOT_IN', value: allMatchingExcludedIds }]
            : []),
        ];
        await runExport(format, conditions.length > 0 ? conditions : undefined);
        return;
      }
      if (explicitSelectedIds.length === 0) return;
      const conditions = [
        ...(exportFilterConditions ?? []),
        { field: 'pid', operator: 'IN', value: explicitSelectedIds },
      ];
      await runExport(format, conditions);
    },
    [
      allMatchingExcludedIds,
      allMatchingSelected,
      runExport,
      exportFilterConditions,
      explicitSelectedIds,
    ],
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
  // survives reload; pass null to clear it. A quick-filter preset belongs to
  // the default list state, so selecting one clears any SavedView identity and
  // transient URL state that came from a personal view.
  const syncPresetToUrl = useCallback(
    (key: QuickFilterKey | null) => {
      setSearchParams(
        (prev) => {
          const p = new URLSearchParams(prev);
          if (key) {
            p.delete('view');
            clearTransientViewSearchParams(p);
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
      if (activeQuickFilterRef.current === key) {
        // Toggle off — clear preset filter and reload
        activeQuickFilterRef.current = null;
        setActiveQuickFilter(null);
        setFilters({});
        syncPresetToUrl(null);
        loadData({ page: 0, size: pagination.pageSize, filters: {} });
        return;
      }
      if (currentView && !isImplicitSavedView(currentView)) {
        selectDefaultView();
        setPendingViewConfig(null);
        setActiveViewType('table');
        clearKeyword();
      }
      const qf = buildQuickFilterPreset(key, { userId: user?.id, now: new Date() }) ?? {};
      activeQuickFilterRef.current = key;
      setActiveQuickFilter(key);
      setFilters(qf);
      syncPresetToUrl(key);
      loadData({ page: 0, size: pagination.pageSize, filters: qf });
    },
    [
      clearKeyword,
      currentView,
      loadData,
      pagination.pageSize,
      selectDefaultView,
      setFilters,
      syncPresetToUrl,
      user,
    ],
  );

  // Assemble the toolbar chip row: built-in filter presets + pinned views. A
  // pin only resolves to a chip if its SavedView is in the pool, so team views
  // (fetched separately from the personal-only list) are merged in — otherwise a
  // team-pinned view could never render as a chip.
  const quickFilterChips = useMemo<QuickFilterChip[]>(
    () =>
      assembleQuickFilterChips({
        presets: getQuickFilterPresetDefinitions(),
        t,
        savedViews: teamViews.length > 0 ? [...savedViews, ...teamViews] : savedViews,
        pins: chipPins,
      }),
    [t, savedViews, teamViews, chipPins],
  );

  // Select a SavedView: clear any active preset, switch the view, sync the URL
  // (?view=), and set the non-table view type. Shared by the header selector and
  // the toolbar view chips so both take the exact same path.
  const handleSelectView = useCallback(
    (pid: string) => {
      activeQuickFilterRef.current = null;
      setActiveQuickFilter(null);
      selectView(pid);
      setSearchParams(
        (prev) => {
          const p = new URLSearchParams(prev);
          p.set('view', pid);
          clearTransientViewSearchParams(p);
          return p;
        },
        { replace: true },
      );
      const view = savedViews.find((v) => v.pid === pid);
      if (view?.viewType && view.viewType !== 'table') {
        setActiveViewType(view.viewType as ViewType);
      }
    },
    [selectView, setSearchParams, savedViews, setActiveViewType, setActiveQuickFilter],
  );

  // Activate a chip: a filter-preset chip toggles its preset; a view chip
  // switches to that SavedView (columns / sort / viewType).
  const handleActivateChip = useCallback(
    (chip: QuickFilterChip) => {
      if (chip.kind === 'view') {
        handleSelectView(chip.viewPid);
        return;
      }
      handleQuickFilter(chip.key);
    },
    [handleSelectView, handleQuickFilter],
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
    const presetName = translateCommon(presetDefinition.i18nKey, presetDefinition.fallbackLabel);
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

  // Save current filters to SavedView
  const handleSaveFilters = useCallback(async () => {
    const formFilters = Object.entries(filters)
      .filter(([, v]) => v != null && v !== '')
      .map(([field, value]) => ({
        fieldCode: field,
        operator: 'eq' as const,
        value: String(value),
      }));
    await ensureViewAndUpdateConfig({ filters: [...formFilters, ...chipFilters] });
  }, [filters, chipFilters, ensureViewAndUpdateConfig]);

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
        className="bg-subtle min-h-[calc(100vh-3.5rem)] w-full px-4 py-5 sm:px-6 lg:px-8"
        data-testid="dynamic-list"
        data-ab-testid={deriveTestId('list', modelCode, 'container')}
      >
        <div className="rounded-card border-border bg-panel relative overflow-hidden border shadow-sm">
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
              {((s) => (s && s !== 'common.view_saved' ? s : '已保存到当前视图'))(
                t('common.view_saved'),
              )}
            </div>
          )}
          {hasPendingViewConfig && currentView && (
            <div
              className="absolute top-2 right-3 z-10 flex items-center gap-2 rounded-md border border-amber-200 bg-amber-50 px-3 py-1.5 text-xs text-amber-900 shadow-sm"
              role="status"
              data-testid={
                hasPendingPersonalViewConfig
                  ? 'personal-view-draft-banner'
                  : 'shared-view-draft-banner'
              }
            >
              <span>
                {hasPendingPersonalViewConfig
                  ? translateCommon('common.saved_view_personal_draft', '当前个人视图有本地变更')
                  : translateCommon('common.saved_view_shared_draft', '共享视图有本地变更')}
                {pendingViewSummary.length > 0 && (
                  <span className="ml-1 text-amber-700">{pendingViewSummary.join(', ')}</span>
                )}
              </span>
              <button
                type="button"
                className="rounded px-2 py-1 font-medium text-amber-900 hover:bg-amber-100 disabled:cursor-not-allowed disabled:opacity-60"
                onClick={
                  hasPendingPersonalViewConfig ? handleSaveCurrentViewDraft : handleSaveSharedDraft
                }
                disabled={(hasPendingSharedViewConfig && !canSaveSharedView) || savingViewDraft}
                title={
                  hasPendingPersonalViewConfig
                    ? translateCommon('common.saved_view_save_current', '保存当前视图')
                    : canSaveSharedView
                      ? translateCommon(
                          'common.saved_view_save_shared_confirm_title',
                          '保存到共享视图？',
                        )
                      : translateCommon(
                          isCurrentViewLockedPreset
                            ? 'common.saved_view_locked_preset_reason'
                            : 'common.saved_view_save_shared_disabled_reason',
                          isCurrentViewLockedPreset
                            ? '插件预置视图不能直接保存，请先复制为个人视图。'
                            : '你可以调整当前视图，但暂不能保存给团队或全员。',
                        )
                }
                data-testid={
                  hasPendingPersonalViewConfig
                    ? 'personal-view-save-current'
                    : canSaveSharedView
                      ? 'shared-view-save'
                      : 'shared-view-save-disabled'
                }
              >
                {savingViewDraft
                  ? translateCommon('common.saving', '保存中...')
                  : hasPendingPersonalViewConfig
                    ? translateCommon('common.saved_view_save_current', '保存当前视图')
                    : translateCommon('common.saved_view_save_shared', '保存到共享视图')}
              </button>
              {hasPendingSharedViewConfig && !canSaveSharedView && (
                <span className="text-amber-700">
                  {translateCommon(
                    isCurrentViewLockedPreset
                      ? 'common.saved_view_locked_preset_reason'
                      : 'common.saved_view_save_shared_disabled_reason',
                    isCurrentViewLockedPreset
                      ? '插件预置视图不能直接保存，请先复制为个人视图。'
                      : '你可以调整当前视图，但暂不能保存给团队或全员。',
                  )}
                </span>
              )}
              <button
                type="button"
                className="rounded px-2 py-1 font-medium text-blue-700 hover:bg-blue-50 disabled:opacity-60"
                onClick={handleSaveDraftAsPersonalView}
                disabled={copyingViewDraft || !canCopyCurrentView}
                title={
                  canCopyCurrentView
                    ? translateCommon('common.saved_view_save_as_personal', '另存为新视图')
                    : translateCommon('common.saved_view_copy_disabled_reason', '当前视图不能复制')
                }
                data-testid="personal-view-save-as-new"
              >
                {copyingViewDraft
                  ? translateCommon('common.saving', '保存中...')
                  : translateCommon('common.saved_view_save_as_personal', '另存为新视图')}
              </button>
              <button
                type="button"
                className="rounded px-2 py-1 text-amber-800 hover:bg-amber-100"
                onClick={handleDiscardViewDraft}
                data-testid={
                  hasPendingPersonalViewConfig
                    ? 'personal-view-discard-draft'
                    : 'shared-view-dismiss-draft'
                }
              >
                {translateCommon('common.saved_view_dismiss_draft', '放弃')}
              </button>
            </div>
          )}
          <SavedViewOverlayStatusBanner
            status={overlayMeta?.overlayStatus}
            reasonCodes={overlayMeta?.overlayReasonCodes}
            stalePaths={overlayMeta?.overlayStalePaths}
            canRepair={canRepairViewOverlay}
            repairing={repairingViewOverlay}
            onRepair={handleRepairViewOverlay}
            repairUnavailableReason={repairViewOverlayUnavailableReason}
            t={translateCommon}
          />
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
            onSelectDefaultView={handleSelectDefaultView}
            onSelectView={handleSelectView}
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
            }}
            enableMultiView={Boolean(schemaExtension.enableMultiView)}
            availableViewTypes={availableViewTypes}
            buttons={actionBlock?.buttons || []}
            toolbarActions={effectiveViewConfig?.toolbarActions}
            onAction={handleAction}
            onToolbarConfigChange={handleToolbarConfigChange}
            resolveLabel={resolveButtonLabel}
            t={t}
            evaluateVisible={evaluateButtonVisible}
            onImport={() => setImportOpen(true)}
            onExport={handleExport}
            exportFilters={exportFilterConditions}
            isTenantMemberPage={isTenantMemberPage}
            onInvite={() => setInviteDialogOpen(true)}
            onImportMembers={() => setMemberImportDialogOpen(true)}
            hideSavedViews={hideSavedViews}
            hideBuiltInImport={
              skipListData ? true : (listExtensions?.hideBuiltInImport ?? !canImport)
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

          {isTenantMemberPage && (
            <TenantMemberAccountImportDialog
              open={memberImportDialogOpen}
              token={token}
              onClose={() => setMemberImportDialogOpen(false)}
              onImported={async () => {
                await loadDataRef.current?.({
                  page: pagination.current - 1,
                  size: pagination.pageSize,
                  filters,
                });
              }}
            />
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
            <div data-aura-block-id={listTabsBlock.id} data-aura-element-id={listTabsBlock.id}>
              <ListTabs
                tabs={listTabsBlock.tabs as any[]}
                activeTab={activeTab}
                onTabChange={handleTabChange}
                locale={locale}
                t={t}
              />
            </div>
          )}

          {/* Filter area - Using Smart Components with collapse/expand (hidden in print) */}
          {filterFormVisible &&
            filterBlock &&
            filterBlock.fields &&
            filterBlock.fields.length > 0 && (
              <div
                data-testid="search-area"
                data-aura-block-id={filterBlock.id}
                data-aura-element-id={filterBlock.id}
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
                      data-authoring-node-id={(field as any).id || field.field}
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
                        title={translateCommon(
                          'common.saved_view_save_filters',
                          '保存筛选到当前视图',
                        )}
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
                          data-authoring-node-id={(button as any).id || button.code}
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
            <div
              className="relative"
              data-aura-block-id={tableBlock?.id}
              data-aura-element-id={tableBlock?.id}
            >
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
                  // Enter is an explicit commit. Do not rely on flush(): the
                  // debounce may already have fired (or been consumed by a
                  // concurrent list-state update), in which case flush is a
                  // no-op and a stale URL writer can drop the keyword. Commit
                  // from the synchronous ref with a functional update so
                  // pagination/filter params changed in the same tick survive.
                  syncKeywordToUrl.cancel();
                  setSearchParams(
                    (prev) => {
                      const p = new URLSearchParams(prev);
                      const value = keywordRef.current.trim();
                      if (value) p.set('keyword', value);
                      else p.delete('keyword');
                      return p;
                    },
                    { replace: true },
                  );
                  debouncedSearch.cancel();
                  loadData({ page: 0, size: pagination.pageSize });
                }}
                filterFormVisible={filterFormVisible}
                onFilterFormToggle={() => setFilterFormVisible((prev) => !prev)}
                hasFilterBlock={
                  !!(filterBlock && filterBlock.fields && filterBlock.fields.length > 0)
                }
                chips={quickFilterChips}
                activeQuickFilter={activeQuickFilter}
                currentViewPid={currentView?.pid ?? null}
                onActivateChip={handleActivateChip}
                onSaveActivePreset={handleSaveActivePreset}
                activeSorts={activeSorts}
                onSortsChange={setLocalActiveSorts}
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
                onAnalysisOpen={
                  namedQueryCode || isApiDatasourcePage ? undefined : () => setAnalysisOpen(true)
                }
                chipFilters={chipFilters}
                onChipFiltersChange={(nextChipFilters) => {
                  chipFiltersRef.current = nextChipFilters;
                  setLocalChipFilters(nextChipFilters);
                  void loadData({
                    page: 0,
                    size: pagination.pageSize,
                    filters,
                    chipFilters: nextChipFilters,
                  });
                }}
                fieldMetadata={filterFieldMetadata}
                resolveChipValueLabel={(filter) => {
                  if (filter.isExpression && filter.expression) {
                    if (
                      filter.expression === '#currentUser' ||
                      filter.expression === '${system.currentUser}'
                    ) {
                      return (
                        user?.name ||
                        user?.nickname ||
                        user?.username ||
                        translateCommon('common.current_user', '当前用户')
                      );
                    }
                    if (
                      filter.expression === '#currentDepartmentOwners' ||
                      filter.expression === '${system.currentDepartmentOwners}'
                    ) {
                      return translateCommon('common.current_department', '当前部门');
                    }
                    if (
                      filter.expression === '#currentSharedRecords' ||
                      filter.expression === '${system.currentSharedRecords}'
                    ) {
                      return translateCommon('common.collaborative_records', '协作记录');
                    }
                    return filter.expression;
                  }
                  const dc = filterFieldMetadata.find(
                    (field) => field.fieldCode === filter.fieldCode,
                  )?.dictCode;
                  if (!dc) return undefined;
                  const values = Array.isArray(filter.value) ? filter.value : [filter.value];
                  const items = dictDataCache.current.get(dc);
                  const labels = values.map(
                    (value) =>
                      items?.find((item) => String(item.value) === String(value))?.label ??
                      String(value),
                  );
                  return labels.join('、');
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
                  chipFiltersRef.current = [];
                  setLocalChipFilters([]);
                  setLocalActiveSorts([]);
                  void loadData({
                    page: 0,
                    size: pagination.pageSize,
                    filters,
                    sorts: [],
                    chipFilters: [],
                  });
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

              <ViewAnalysisDrawer
                open={analysisOpen}
                onClose={() => setAnalysisOpen(false)}
                modelCode={schema?.modelCode || tableName}
                viewName={getLocalizedText(currentView?.name, locale, t)}
                keyword={keyword}
                filters={activeRuntimeViewFilters}
                fields={filterFieldMetadata}
                onDrillDown={handleAnalysisDrillDown}
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
                locale={locale}
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
                locale={locale}
                selectedCount={effectiveSelectedCount}
                selectedIds={selectedIdList}
                modelCode={modelCode}
                onBulkEdit={
                  builtInBulkCapabilities.edit && !allMatchingSelected
                    ? () => setBulkEditOpen(true)
                    : undefined
                }
                onBulkDelete={
                  builtInBulkCapabilities.delete && !allMatchingSelected
                    ? handleBulkDelete
                    : undefined
                }
                onBulkExport={
                  builtInBulkCapabilities.export ? () => handleExportSelected('xlsx') : undefined
                }
                bulkActions={allMatchingSelected ? [] : visibleBulkActions}
                onBulkAction={handleBulkAction}
                resolveBulkActionLabel={resolveButtonLabel}
                onClearSelection={clearAllSelection}
              />
            </div>
          ) : (
            <div data-aura-block-id={tableBlock?.id} data-aura-element-id={tableBlock?.id}>
              <SmartViewRenderer
                view={
                  {
                    ...(activeViewTemplate || currentView || {}),
                    modelCode,
                    viewType: activeViewType,
                    viewConfig: activeViewConfig,
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
            </div>
          )}

          <ListModals
            // BulkEditModal
            bulkEditOpen={bulkEditOpen}
            onBulkEditClose={() => setBulkEditOpen(false)}
            selectedIds={selectedIdList}
            modelCode={modelCode}
            bulkEditFields={bulkEditColumns.map((c) => ({
              code: c.field,
              name: resolveColumnLabel(c),
              dataType: c.valueType || 'string',
            }))}
            onBulkEditComplete={handleBulkEditComplete}
            locale={locale}
            bulkFieldCommand={bulkFieldCommand}
            bulkFieldCommandContext={pageContext}
            onBulkFieldCommandClose={() => setBulkFieldCommand(null)}
            onBulkFieldCommandSubmit={handleBulkFieldCommandSubmit}
            // ImportModal
            importOpen={importOpen}
            importConfig={importConfig}
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
              activeQuickFilterRef.current = null;
              setActiveQuickFilter(null);
              setActiveViewType(newType);
              setStartCreateViewMode(false);
              if (view.pid) {
                setSearchParams(
                  (prev) => {
                    const p = new URLSearchParams(prev);
                    p.set('view', view.pid);
                    clearTransientViewSearchParams(p);
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
              activeQuickFilterRef.current = null;
              setActiveQuickFilter(null);
              selectView(pid);
              setSearchParams(
                (prev) => {
                  const p = new URLSearchParams(prev);
                  p.set('view', pid);
                  clearTransientViewSearchParams(p);
                  return p;
                },
                { replace: true },
              );
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
            pinnedViewPids={chipPins.map((p) => p.viewPid)}
            onPinView={async (pid: string) => {
              await savedViewService.pinView(pid);
              await loadChipPins();
            }}
            onUnpinView={async (pid: string) => {
              await savedViewService.unpinView(pid);
              await loadChipPins();
            }}
            canManageTeamPins={canManageTeamPins}
            teamViews={teamViews}
            teamPinnedViewPids={chipPins.map((p) => p.viewPid)}
            onTeamPinView={async (pid: string, teamId: string) => {
              await savedViewService.pinView(pid, { scope: 'team', teamId });
              await loadChipPins();
            }}
            onTeamUnpinView={async (pid: string, teamId: string) => {
              await savedViewService.unpinView(pid, { scope: 'team', teamId });
              await loadChipPins();
            }}
            viewManageFields={viewManageFields}
            // ColumnSettingsPanel
            columnSettingsOpen={columnSettingsOpen}
            onColumnSettingsClose={() => setColumnSettingsOpen(false)}
            allColumnDefs={allColumnDefs}
            viewColumns={effectiveViewConfig?.columns}
            columnSettingsRowHeight={effectiveRowHeight}
            onColumnSettingsSave={handleColumnSettingsSave}
            t={t}
            // FilterFieldPicker
            fieldPickerOpen={fieldPickerOpen}
            fieldPickerAnchor={fieldPickerAnchor}
            filterFieldMetadata={filterFieldMetadata}
            chipFilterFieldCodes={chipFilters.map((f) => f.fieldCode)}
            onFieldPickerSelect={(fieldCode) => {
              // Add a new empty filter chip and immediately open the value popover
              const newFilter: ViewFilterConfig = {
                fieldCode,
                operator: 'eq',
                value: '',
              };
              setLocalChipFilters((prev) => {
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
              const nextChipFilters = chipFilters.map((filter, index) =>
                index === editingChipIdx
                  ? { ...filter, operator: operator as ViewFilterConfig['operator'], value }
                  : filter,
              );
              chipFiltersRef.current = nextChipFilters;
              setLocalChipFilters(nextChipFilters);
              setEditingChipIdx(null);
              void loadData({
                page: 0,
                size: pagination.pageSize,
                filters,
                chipFilters: nextChipFilters,
              });
            }}
            onFilterCancel={() => setEditingChipIdx(null)}
            // ColumnContextMenu
            contextMenu={contextMenu}
            activeSorts={activeSorts}
            onSort={(dir) => {
              if (!contextMenu) return;
              if (dir === 'clear') {
                setLocalActiveSorts((prev) =>
                  prev.filter((s) => s.fieldCode !== contextMenu.column.field),
                );
              } else {
                setLocalActiveSorts([
                  { fieldCode: contextMenu.column.field, direction: dir, priority: 0 },
                ]);
              }
            }}
            onFreeze={(position) => {
              if (!contextMenu) return;
              const fieldCode = contextMenu.column.field;
              const columns = buildCurrentColumnSettings().map((column) => {
                if (column.fieldCode === fieldCode) {
                  return position === 'none'
                    ? { ...column, frozen: false, frozenPosition: undefined }
                    : {
                        ...column,
                        visible: true,
                        frozen: true,
                        frozenPosition: position,
                      };
                }
                return column;
              });
              void ensureViewAndUpdateConfig({ columns });
            }}
            onHide={() => {
              if (!contextMenu) return;
              if (tableColumns.filter((column) => !column.isActionColumn).length <= 1) return;
              const columns = buildCurrentColumnSettings().map((column) =>
                column.fieldCode === contextMenu.column.field
                  ? { ...column, visible: false, frozen: false, frozenPosition: undefined }
                  : column,
              );
              void ensureViewAndUpdateConfig({ columns });
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
      <BulkActionResultDialog
        result={bulkActionResult}
        onClose={() => setBulkActionResult(null)}
        locale={locale}
        t={t}
      />
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
