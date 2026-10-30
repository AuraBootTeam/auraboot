import { isButtonDisabled } from '~/framework/meta/utils/buttonPermissions';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import React, { useCallback, useMemo } from 'react';
import { createExpressionContext } from '~/framework/meta/runtime/expression/context';
import { evaluateCondition } from '~/framework/meta/runtime/expression/evaluator';
import type { ButtonConfig, ColumnConfig } from '~/framework/meta/schemas/types';
import {
  DEFAULT_ROW_HEIGHT,
  type RowHeight,
  type ViewConfig,
  type ViewFilterConfig,
} from '~/framework/smart/types/savedView';
import { ResultHelper } from '~/utils/type';
import {
  resolveBuiltInBulkCapabilities,
  selectBulkEditableColumns,
  type BuiltInBulkCapabilitiesConfig,
} from './bulkCapabilities';
import {
  queryConditionToExportCondition,
  viewFilterToQueryCondition,
  type ListQueryFilterCondition,
} from './listFilterConditions';
import type { DynamicEntity } from './listPageTypes';
import { buildToolbarConditionContext } from './listSchemaPolicy';

interface ListExportInput {
  runtime: SchemaRuntime | null;
  canUseButton: (button: ButtonConfig) => boolean;
  pagination: { current: number; pageSize: number; total: number };
  data: DynamicEntity[];
  filters: Record<string, any>;
  tableBulkActions: ButtonConfig[];
  selectedIdList: string[];
  allMatchingSelected: boolean;
  effectiveSelectedCount: number;
  tableColumns: ColumnConfig[];
  tableBulkCapabilities: BuiltInBulkCapabilitiesConfig | undefined;
  hasPermission: (permissionCode: string) => boolean;
  getTabFilter: () => ListQueryFilterCondition | null;
  chipFilters: ViewFilterConfig[];
  modelCode: any;
  keywordRef: React.RefObject<string>;
  showErrorToast: (message: string, duration?: number) => void;
  allMatchingExcludedIds: string[];
  explicitSelectedIds: string[];
  effectiveViewConfig: ViewConfig | undefined;
  schemaExtension: any;
  ensureViewAndUpdateConfig: (
    config: Partial<import('~/framework/smart/types/savedView').ViewConfig>,
    options?: { isStale?: () => boolean; rethrow?: boolean },
  ) => Promise<void>;
}

/** Resolve action capabilities and export the current query or selection. */
export function useListExport({
  runtime,
  canUseButton,
  pagination,
  data,
  filters,
  tableBulkActions,
  selectedIdList,
  allMatchingSelected,
  effectiveSelectedCount,
  tableColumns,
  tableBulkCapabilities,
  hasPermission,
  getTabFilter,
  chipFilters,
  modelCode,
  keywordRef,
  showErrorToast,
  allMatchingExcludedIds,
  explicitSelectedIds,
  effectiveViewConfig,
  schemaExtension,
  ensureViewAndUpdateConfig,
}: ListExportInput) {
  // Evaluate toolbar button visibility (visibleWhen expression).
  // Exposes the loaded list's recordCount/total so conditions like
  // `recordCount == 0` (singleton "新建" button) work.
  const toolbarRuntimeContext = runtime?.getContext() ?? {};
  const evaluateButtonVisible = useCallback(
    (button: ButtonConfig): boolean => {
      if (!canUseButton(button)) return false;
      // If no visibleWhen expression, always visible
      if (!button.visibleWhen) return true;
      const conditionContext = buildToolbarConditionContext(
        { total: pagination.total, records: data },
        createExpressionContext({ state: { filters } }),
        toolbarRuntimeContext,
      );
      return evaluateCondition(button.visibleWhen, conditionContext as any);
    },
    [canUseButton, pagination.total, data, filters, toolbarRuntimeContext],
  );

  const evaluateButtonDisabled = useCallback(
    (button: ButtonConfig): boolean => {
      const conditionContext = buildToolbarConditionContext(
        { total: pagination.total, records: data },
        createExpressionContext({
          state: { filters, selectedIds: selectedIdList, selectedCount: effectiveSelectedCount },
        }),
        toolbarRuntimeContext,
      );
      return isButtonDisabled(button, (expression) =>
        evaluateCondition(expression, conditionContext as any),
      );
    },
    [
      pagination.total,
      data,
      filters,
      selectedIdList,
      effectiveSelectedCount,
      toolbarRuntimeContext,
    ],
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
        value: tabCondition.values ?? tabCondition.value,
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
      scope: 'selected' | 'filtered',
      selectedPids?: string[],
    ) => {
      try {
        const res = await fetch(`/api/dynamic/${modelCode}/export`, {
          method: 'post',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            scope,
            format: format === 'xlsx' ? 'excel' : 'csv',
            conditions,
            selectedPids,
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
      await runExport(format, exportFilterConditions, 'filtered');
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
        await runExport(format, conditions.length > 0 ? conditions : undefined, 'filtered');
        return;
      }
      if (explicitSelectedIds.length === 0) return;
      await runExport(format, exportFilterConditions, 'selected', explicitSelectedIds);
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
  const effectiveRowHeight: RowHeight =
    effectiveViewConfig?.rowHeight || schemaExtension.rowHeight || DEFAULT_ROW_HEIGHT;

  // Handle row height change -> update SavedView
  const handleRowHeightChange = useCallback(
    async (height: RowHeight) => {
      await ensureViewAndUpdateConfig({ rowHeight: height });
    },
    [ensureViewAndUpdateConfig],
  );

  return {
    evaluateButtonVisible,
    evaluateButtonDisabled,
    visibleBulkActions,
    bulkEditColumns,
    builtInBulkCapabilities,
    exportFilterConditions,
    handleExport,
    handleExportSelected,
    effectiveRowHeight,
    handleRowHeightChange,
  };
}
