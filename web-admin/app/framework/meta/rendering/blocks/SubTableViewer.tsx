/**
 * SubTableViewer - Sub-table viewer with inline editing, row-level validation,
 * and summary row support.
 *
 * Features:
 * - Read-only display of child records in a table format
 * - Inline row editing (double-click or edit button) with keyboard navigation
 *   (Enter = save, Esc = cancel, Tab = next field)
 * - Row-level validation (required, min/max, pattern, custom rules)
 * - Cross-row validation (e.g., SUM(amount) <= budget)
 * - Summary/aggregation footer row (SUM, AVG, COUNT, MIN, MAX) — reactive during editing
 * - DnD drag sorting and tree hierarchical display
 * - Command-based CRUD (create, update, delete)
 */

import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import type {
  ColumnConfig,
  SubTableConfig,
  ValidationRule,
} from '~/framework/meta/schemas/types';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useTreeData } from '~/framework/meta/hooks/useTreeData';
import { useDictCache } from '~/framework/meta/rendering/pages/hooks/useDictCache';
import { DndSubTableWrapper } from '~/framework/meta/components/subtable/DndSubTableWrapper';
import { SortableSubTableRow } from '~/framework/meta/components/subtable/SortableSubTableRow';
import { InlineEditableCell } from '~/framework/meta/components/subtable/InlineEditableCell';
import { SubTableSummaryRow } from '~/framework/meta/components/subtable/SubTableSummaryRow';
import { isDescendant } from '~/framework/meta/components/subtable/dndUtils';

export interface SubTableViewerProps {
  config: SubTableConfig;
  parentRecordId: string;
  parentRecordData?: Record<string, any>;
  token?: string;
  locale?: string;
  t?: (key: string) => string;
  isEditable?: boolean;
  onDataChange?: () => void;
}

export const SubTableViewer: React.FC<SubTableViewerProps> = ({
  config,
  parentRecordId,
  parentRecordData,
  token,
  locale = 'zh-CN',
  t = (key: string) => key,
  isEditable = false,
  onDataChange,
}) => {
  const [rows, setRows] = useState<Record<string, any>[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [refreshCounter, setRefreshCounter] = useState(0);

  // Inline add form state
  const [isAdding, setIsAdding] = useState(false);
  const [newRowData, setNewRowData] = useState<Record<string, any>>({});
  const [addErrors, setAddErrors] = useState<Record<string, string>>({});
  const [saving, setSaving] = useState(false);
  const [deletingId, setDeletingId] = useState<string | null>(null);

  // Inline edit state
  const [editingRowId, setEditingRowId] = useState<string | null>(null);
  const [editingValues, setEditingValues] = useState<Record<string, any>>({});
  const [editErrors, setEditErrors] = useState<Record<string, string>>({});
  const [editSaving, setEditSaving] = useState(false);
  const [crossRowErrors, setCrossRowErrors] = useState<string[]>([]);
  const originalEditDataRef = useRef<Record<string, any>>({});

  // Determine if inline edit is enabled
  const canInlineEdit = isEditable && config.commands?.update && config.allowInlineEdit !== false;

  // Check if a column is editable for inline edit
  const isColumnEditable = useCallback(
    (col: ColumnConfig): boolean => {
      if (col.editable === false || col.readOnly) return false;
      if (config.editableColumns && config.editableColumns.length > 0) {
        return config.editableColumns.includes(col.field);
      }
      // REFERENCE fields are too complex for inline editing
      if (col.valueType === 'user_identity' || col.valueType === 'image') return false;
      return true;
    },
    [config.editableColumns],
  );

  // Ordered list of editable field names (for Tab navigation)
  const editableFields = useMemo(
    () => config.columns.filter((c) => isColumnEditable(c)).map((c) => c.field),
    [config.columns, isColumnEditable],
  );

  // Dict cache for columns with dictCode (used in inline edit dropdowns)
  const dictCodes = useMemo(
    () => config.columns.filter((c) => c.dictCode).map((c) => c.dictCode!),
    [config.columns],
  );
  const { getDictItems, getDictLabel } = useDictCache({ dictCodes, token });

  const reloadRows = useCallback(() => {
    setRefreshCounter((c) => c + 1);
  }, []);

  const interpolateRecordValue = useCallback(
    (raw: string): string => {
      let value = raw.replace(/\$\{recordId\}/g, parentRecordId);
      value = value.replace(/\$\{record\.(\w+)\}/g, (_: string, field: string) => {
        return String(parentRecordData?.[field] ?? parentRecordId);
      });
      value = value.replace(/\$\{(\w+)\}/g, (_: string, field: string) => {
        return String(parentRecordData?.[field] ?? '');
      });
      return value;
    },
    [parentRecordData, parentRecordId],
  );

  useEffect(() => {
    if (!parentRecordId) return;

    const loadData = async () => {
      setLoading(true);
      setError(null);

      try {
        let endpoint: string;
        let filters: Record<string, any> = {};

        if ((config as any).dataSource) {
          // API data source mode (e.g., NamedQuery)
          const ds = (config as any).dataSource;
          // Interpolate ${recordId} and ${record.field} placeholders in URL
          let rawUrl = ds.endpoint || ds.url || '/api/datasource/list';
          rawUrl = interpolateRecordValue(rawUrl);
          endpoint = rawUrl;
          const params: Record<string, string> = {};
          if (ds.params) {
            for (const [k, v] of Object.entries(ds.params)) {
              params[k] = interpolateRecordValue(String(v));
            }
          }
          if ((ds.kind === 'namedQuery' || ds.type === 'namedQuery') && ds.queryCode) {
            params.datasourceId = `nq:${String(ds.queryCode)}`;
            params.format = params.format || 'records';
          }
          const result = await fetchResult<any>(endpoint, {
            method: 'get',
            params,
            token,
          });
          // Support both paginated (data.records) and direct array (data) responses
          const rawData = result.data;
          const resultRecords = Array.isArray(rawData) ? rawData : (rawData?.records ?? []);
          if (ResultHelper.isSuccess(result) && Array.isArray(resultRecords)) {
            setRows(resultRecords);
          } else {
            setRows([]);
          }
          setLoading(false);
          return;
        } else if (config.resolveVia) {
          // Indirect query
          const rv = config.resolveVia;
          const intermediateModel = rv.model;
          const intermediateFilters: Array<{ fieldName: string; operator: string; value: string }> =
            [{ fieldName: rv.parentField, operator: 'EQ', value: parentRecordId }];
          if (rv.filterField && rv.filterValue) {
            intermediateFilters.push({
              fieldName: rv.filterField,
              operator: 'EQ',
              value: rv.filterValue,
            });
          }
          const intermediateRes = await fetchResult<any>(`/api/dynamic/${intermediateModel}/list`, {
            method: 'get',
            params: {
              pageNum: 1,
              pageSize: 100,
              filters: JSON.stringify(intermediateFilters),
            },
            token,
          });

          const intermediateRecords = intermediateRes.data?.records ?? [];
          if (
            !ResultHelper.isSuccess(intermediateRes) ||
            !Array.isArray(intermediateRecords) ||
            intermediateRecords.length === 0
          ) {
            setRows([]);
            setLoading(false);
            return;
          }

          const intermediatePid = intermediateRecords[0].pid;
          const childModel = config.childModel;
          endpoint = `/api/dynamic/${childModel}/list`;
          filters = {
            pageNum: 1,
            pageSize: 200,
            filters: JSON.stringify([
              { fieldName: config.parentField, operator: 'EQ', value: intermediatePid },
            ]),
          };
        } else {
          // Direct query
          const childModel = config.childModel;
          endpoint = `/api/dynamic/${childModel}/list`;
          filters = {
            pageNum: 1,
            pageSize: 200,
            filters: JSON.stringify([
              { fieldName: config.parentField, operator: 'EQ', value: parentRecordId },
            ]),
          };
        }

        const result = await fetchResult<any>(endpoint, {
          method: 'get',
          params: filters,
          token,
        });

        const resultRecords = result.data?.records ?? [];
        if (ResultHelper.isSuccess(result) && Array.isArray(resultRecords)) {
          setRows(resultRecords);
        } else {
          setRows([]);
        }
      } catch (err) {
        console.error('[SubTableViewer] Failed to load data:', err);
        setError(err instanceof Error ? err.message : 'Failed to load data');
      } finally {
        setLoading(false);
      }
    };

    loadData();
  }, [parentRecordId, parentRecordData, config, token, refreshCounter, interpolateRecordValue]);

  // Resolve column label from i18n with model-qualified fallback
  const resolveColumnLabel = useCallback(
    (fieldCode: string): string => {
      if (config.childModel) {
        const modelKey = `model.${config.childModel}.${fieldCode}.label`;
        const resolved = t(modelKey);
        if (resolved !== modelKey) return resolved;
      }
      const fieldKey = `field.${fieldCode}.label`;
      const fieldResolved = t(fieldKey);
      if (fieldResolved !== fieldKey) return fieldResolved;
      return fieldCode;
    },
    [config.childModel, t],
  );

  // ------- Field Validation -------
  const validateFieldValue = useCallback(
    (col: ColumnConfig, value: any): string | null => {
      // Required
      if (col.required && (value === undefined || value === null || value === '')) {
        const msg = t('common.validation.required');
        return msg !== 'common.validation.required' ? msg : 'This field is required';
      }

      if (value === undefined || value === null || value === '') return null;

      // Numeric min/max
      const numVal = typeof value === 'number' ? value : Number(value);
      if (!isNaN(numVal)) {
        if (col.min !== undefined && numVal < col.min) {
          const msg = t('validation.min');
          return msg !== 'validation.min' ? `${msg}: ${col.min}` : `Minimum value: ${col.min}`;
        }
        if (col.max !== undefined && numVal > col.max) {
          const msg = t('validation.max');
          return msg !== 'validation.max' ? `${msg}: ${col.max}` : `Maximum value: ${col.max}`;
        }
      }

      // Pattern
      if (col.pattern && typeof value === 'string') {
        if (!new RegExp(col.pattern).test(value)) {
          const msg = t('validation.pattern');
          return msg !== 'validation.pattern' ? msg : 'Invalid format';
        }
      }

      // Custom validation rules
      if (col.validation) {
        for (const rule of col.validation) {
          const ruleError = evaluateValidationRule(rule, value, t);
          if (ruleError) return ruleError;
        }
      }

      return null;
    },
    [t],
  );

  // ------- Cross-Row Validation -------
  const validateCrossRowRules = useCallback(
    (allRows: Record<string, any>[]): string[] => {
      if (!config.crossRowRules || config.crossRowRules.length === 0) return [];

      const errors: string[] = [];
      for (const rule of config.crossRowRules) {
        const values = allRows.map((r) => Number(r[rule.field]) || 0);
        let aggregated = 0;
        switch (rule.aggregation) {
          case 'sum':
            aggregated = values.reduce((a, b) => a + b, 0);
            break;
          case 'avg':
            aggregated = values.length > 0 ? values.reduce((a, b) => a + b, 0) / values.length : 0;
            break;
          case 'count':
            aggregated = values.length;
            break;
          case 'min':
            aggregated = values.length > 0 ? Math.min(...values) : 0;
            break;
          case 'max':
            aggregated = values.length > 0 ? Math.max(...values) : 0;
            break;
        }

        let threshold: number;
        const recordFieldMatch =
          typeof rule.value === 'string'
            ? rule.value.match(/^\$\{record\.([A-Za-z0-9_.-]+)\}$/)
            : null;
        if (recordFieldMatch) {
          const fieldName = recordFieldMatch[1];
          threshold = Number(parentRecordData?.[fieldName]) || 0;
        } else {
          threshold = Number(rule.value) || 0;
        }

        let violated = false;
        switch (rule.operator) {
          case 'lte':
            violated = aggregated > threshold;
            break;
          case 'gte':
            violated = aggregated < threshold;
            break;
          case 'LT':
            violated = aggregated >= threshold;
            break;
          case 'GT':
            violated = aggregated <= threshold;
            break;
          case 'EQ':
            violated = aggregated !== threshold;
            break;
        }

        if (violated) {
          const msg =
            typeof rule.message === 'string'
              ? t(rule.message) !== rule.message
                ? t(rule.message)
                : rule.message
              : (rule.message as any)?.['zh-CN'] || (rule.message as any)?.['en-US'] || '';
          errors.push(msg);
        }
      }
      return errors;
    },
    [config.crossRowRules, parentRecordData, t],
  );

  // Create command handler
  const handleAddRow = useCallback(async () => {
    if (!config.commands?.create) return;

    // Validate all editable fields (required, min/max, pattern, custom rules)
    const errors: Record<string, string> = {};
    for (const col of config.columns) {
      if (col.editable !== false) {
        const fieldError = validateFieldValue(col, newRowData[col.field]);
        if (fieldError) errors[col.field] = fieldError;
      }
    }
    if (Object.keys(errors).length > 0) {
      setAddErrors(errors);
      return;
    }

    setSaving(true);
    setAddErrors({});
    try {
      const payload: Record<string, any> = {
        [config.parentField]: parentRecordId,
        ...newRowData,
      };

      const result = await fetchResult(`/api/meta/commands/execute/${config.commands.create}`, {
        method: 'post',
        params: {
          payload,
          operationType: 'create',
        },
        token,
      });

      if (!ResultHelper.isSuccess(result)) {
        throw new Error(result.desc || result.message || 'Failed to add line');
      }

      setNewRowData({});
      setIsAdding(false);
      reloadRows();
      onDataChange?.();
    } catch (err) {
      setAddErrors({ _form: err instanceof Error ? err.message : 'Failed to add' });
    } finally {
      setSaving(false);
    }
  }, [config, newRowData, parentRecordId, token, reloadRows, onDataChange, resolveColumnLabel]);

  // Delete command handler
  const handleDeleteRow = useCallback(
    async (rowPid: string) => {
      if (!config.commands?.delete || !rowPid) return;

      setDeletingId(rowPid);
      try {
        const result = await fetchResult(`/api/meta/commands/execute/${config.commands.delete}`, {
          method: 'post',
          params: {
            targetRecordId: rowPid,
            operationType: 'delete',
          },
          token,
        });

        if (!ResultHelper.isSuccess(result)) {
          throw new Error(result.desc || result.message || 'Failed to delete');
        }

        reloadRows();
        onDataChange?.();
      } catch (err) {
        console.error('[SubTableViewer] Delete failed:', err);
      } finally {
        setDeletingId(null);
      }
    },
    [config.commands, token, reloadRows, onDataChange],
  );

  // Enter edit mode for a row
  const handleStartEdit = useCallback(
    (row: Record<string, any>) => {
      if (!canInlineEdit) return;
      const rowId = row.pid || String(row.id);
      const values: Record<string, any> = {};
      for (const col of config.columns) {
        values[col.field] = row[col.field] ?? '';
      }
      setEditingRowId(rowId);
      setEditingValues(values);
      originalEditDataRef.current = { ...values };
      setEditErrors({});
      setCrossRowErrors([]);
    },
    [config.columns, canInlineEdit],
  );

  // Cancel edit mode
  const handleCancelEdit = useCallback(() => {
    setEditingRowId(null);
    setEditingValues({});
    setEditErrors({});
    setCrossRowErrors([]);
  }, []);

  // Save edited row via update command with full validation
  const handleSaveEdit = useCallback(async () => {
    if (!editingRowId || !config.commands?.update) return;

    // Row-level field validation (required, min/max, pattern, custom rules)
    const errors: Record<string, string> = {};
    for (const col of config.columns) {
      if (isColumnEditable(col)) {
        const fieldError = validateFieldValue(col, editingValues[col.field]);
        if (fieldError) errors[col.field] = fieldError;
      }
    }
    if (Object.keys(errors).length > 0) {
      setEditErrors(errors);
      return;
    }

    // Cross-row validation (merge edited row into full dataset)
    if (config.crossRowRules && config.crossRowRules.length > 0) {
      const mergedRows = rows.map((r) => {
        if ((r.pid || String(r.id)) === editingRowId) {
          return { ...r, ...editingValues };
        }
        return r;
      });
      const crossErrors = validateCrossRowRules(mergedRows);
      if (crossErrors.length > 0) {
        setCrossRowErrors(crossErrors);
        return;
      }
    }

    // Compute changed fields only (skip unchanged)
    const changed: Record<string, any> = {};
    for (const [key, val] of Object.entries(editingValues)) {
      if (val !== originalEditDataRef.current[key]) {
        changed[key] = val;
      }
    }
    if (Object.keys(changed).length === 0) {
      handleCancelEdit();
      return;
    }

    setEditSaving(true);
    setEditErrors({});
    setCrossRowErrors([]);
    try {
      const result = await fetchResult(`/api/meta/commands/execute/${config.commands.update}`, {
        method: 'post',
        params: {
          targetRecordId: editingRowId,
          payload: changed,
          operationType: 'update',
        },
        token,
      });

      if (!ResultHelper.isSuccess(result)) {
        throw new Error(result.desc || result.message || 'Failed to update');
      }

      // Optimistically update local row data
      setRows((prev) =>
        prev.map((r) => {
          if ((r.pid || String(r.id)) === editingRowId) {
            return { ...r, ...editingValues };
          }
          return r;
        }),
      );
      setEditingRowId(null);
      setEditingValues({});
      setCrossRowErrors([]);
      reloadRows();
      onDataChange?.();
    } catch (err) {
      setEditErrors({ _form: err instanceof Error ? err.message : 'Failed to update' });
    } finally {
      setEditSaving(false);
    }
  }, [
    editingRowId,
    editingValues,
    config,
    rows,
    token,
    reloadRows,
    onDataChange,
    isColumnEditable,
    validateFieldValue,
    validateCrossRowRules,
    handleCancelEdit,
  ]);

  // Keyboard handler for edit mode with Tab navigation
  const handleEditKeyDown = useCallback(
    (e: React.KeyboardEvent, fieldName?: string) => {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        handleSaveEdit();
      }
      if (e.key === 'Escape') {
        e.preventDefault();
        handleCancelEdit();
      }
      // Tab: navigate between editable fields within the row
      if (e.key === 'Tab' && fieldName) {
        const currentIdx = editableFields.indexOf(fieldName);
        if (currentIdx !== -1) {
          const nextIdx = e.shiftKey ? currentIdx - 1 : currentIdx + 1;
          if (nextIdx >= 0 && nextIdx < editableFields.length) {
            e.preventDefault();
            const nextField = editableFields[nextIdx];
            const nextInput = document.querySelector(
              `[data-testid="inline-edit-${nextField}"]`,
            ) as HTMLElement;
            if (nextInput) {
              setTimeout(() => nextInput.focus(), 0);
            }
          } else if (!e.shiftKey && nextIdx >= editableFields.length) {
            // Tab past last field = save
            e.preventDefault();
            handleSaveEdit();
          }
        }
      }
    },
    [handleSaveEdit, handleCancelEdit, editableFields],
  );

  const hasActions =
    isEditable && (config.commands?.create || config.commands?.delete || canInlineEdit);
  const isSortable = config.sortable && isEditable;
  const sortField = config.sortField || 'sort_order';
  const totalCols = config.columns.length + (hasActions ? 1 : 0) + (isSortable ? 1 : 0);
  const extraColCount = (hasActions ? 1 : 0) + (isSortable ? 1 : 0);
  const emptyStateTitle = config.emptyState?.title
    ? getLocalizedText(config.emptyState.title, locale, t)
    : t('common.noData') || 'No data';
  const emptyStateDescription = config.emptyState?.description
    ? getLocalizedText(config.emptyState.description, locale, t)
    : '';
  const emptyStateActionLabel = config.emptyState?.actionLabel
    ? getLocalizedText(config.emptyState.actionLabel, locale, t)
    : t('common.addLine') !== 'common.addLine'
      ? t('common.addLine')
      : 'Add Line';

  // Tree data management
  const { visibleRows: treeRows, toggleExpand } = useTreeData(rows, config.treeConfig);
  const displayRows = config.treeConfig ? treeRows : rows;

  // DnD item IDs
  const dndItems = useMemo(
    () => displayRows.map((r) => r.pid || String(r.id || '')),
    [displayRows],
  );

  // DnD drop handler → batch API update
  const handleDragEnd = useCallback(
    async (activeId: string, overId: string, _deltaX: number) => {
      if (!isSortable) return;

      // Simple flat sort: swap positions
      const oldIndex = displayRows.findIndex((r) => (r.pid || r.id) === activeId);
      const newIndex = displayRows.findIndex((r) => (r.pid || r.id) === overId);
      if (oldIndex === -1 || newIndex === -1 || oldIndex === newIndex) return;

      // Tree mode: check for cycle
      if (config.treeConfig) {
        if (isDescendant(activeId, overId, treeRows)) return;
      }

      // Reorder locally
      const reordered = [...displayRows];
      const [moved] = reordered.splice(oldIndex, 1);
      reordered.splice(newIndex, 0, moved);

      // Compute batch update payload
      const updates = reordered.map((r, i) => ({
        pid: r.pid || r.id,
        [sortField]: (i + 1) * 1000,
      }));

      // Optimistic update
      setRows(reordered.map((r, i) => ({ ...r, [sortField]: (i + 1) * 1000 })));

      // Persist via batch API
      try {
        await fetchResult(`/api/dynamic/${config.childModel}/batch`, {
          method: 'put',
          token,
          params: updates,
        });
      } catch (err) {
        console.error('[SubTableViewer] Batch sort update failed:', err);
        reloadRows(); // Rollback by reloading
      }
    },
    [isSortable, displayRows, treeRows, config, sortField, token, reloadRows],
  );

  if (loading) {
    return (
      <div className="py-8 text-center text-sm text-gray-400">
        {t('common.loading') || 'Loading...'}
      </div>
    );
  }

  if (error) {
    return <div className="py-4 text-center text-sm text-red-500">{error}</div>;
  }

  return (
    <div className="overflow-hidden rounded-lg border border-gray-200" data-testid="subtable-viewer">
      {/* Cross-row validation errors banner */}
      {crossRowErrors.length > 0 && (
        <div
          className="border-b border-red-200 bg-red-50 px-4 py-2 text-sm text-red-700"
          data-testid="subtable-cross-row-errors"
          role="alert"
        >
          {crossRowErrors.map((msg, i) => (
            <p key={i}>{msg}</p>
          ))}
        </div>
      )}

      {displayRows.length === 0 && !isAdding ? (
        <div
          className="flex flex-col items-center justify-center px-6 py-10 text-center"
          data-testid="subtable-empty-state"
        >
          <div className="text-sm font-medium text-gray-700">{emptyStateTitle}</div>
          {emptyStateDescription ? (
            <div className="mt-2 max-w-md text-sm leading-6 text-gray-500">
              {emptyStateDescription}
            </div>
          ) : null}
          {isEditable && config.commands?.create ? (
            <button
              onClick={() => setIsAdding(true)}
              data-testid="subtable-empty-action"
              className="mt-5 rounded-md border border-blue-200 bg-blue-50 px-4 py-2 text-sm font-medium text-blue-700 transition-colors hover:border-blue-300 hover:bg-blue-100"
            >
              {emptyStateActionLabel}
            </button>
          ) : null}
        </div>
      ) : (
      <table className="min-w-full divide-y divide-gray-200" data-testid="subtable-table">
        <thead className="bg-gray-50">
          <tr>
            {isSortable && <th className="w-10 px-1 py-2.5 text-xs font-medium text-gray-400"></th>}
            {config.columns.map((col: ColumnConfig) => (
              <th
                key={col.field}
                className={`px-4 py-2.5 text-xs font-medium tracking-wider text-gray-500 uppercase ${
                  col.align === 'right'
                    ? 'text-right'
                    : col.align === 'center'
                      ? 'text-center'
                      : 'text-left'
                }`}
                style={
                  col.width
                    ? { width: typeof col.width === 'number' ? `${col.width}px` : col.width }
                    : undefined
                }
              >
                {col.label ? getLocalizedText(col.label, locale, t) : resolveColumnLabel(col.field)}
                {col.required && <span className="ml-0.5 text-red-500">*</span>}
              </th>
            ))}
            {hasActions && (
              <th className="w-20 px-4 py-2.5 text-center text-xs font-medium text-gray-500">
                {t('common.actions') !== 'common.actions' ? t('common.actions') : 'Actions'}
              </th>
            )}
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-100 bg-white">
          <DndSubTableWrapper
              items={dndItems}
              onDragEnd={handleDragEnd}
              disabled={!isSortable || !!editingRowId}
            >
              {displayRows.map((row, rowIndex) => {
                const rowId = row.pid || String(rowIndex);
                const isEditingThis = editingRowId === rowId;
                return (
                <SortableSubTableRow
                  key={rowId}
                  id={rowId}
                  depth={row._depth || 0}
                  hasChildren={row._hasChildren || false}
                  expanded={row._expanded || false}
                  onToggleExpand={() => toggleExpand(rowId)}
                  sortable={!!isSortable && !editingRowId}
                >
                    {config.columns.map((col: ColumnConfig) => {
                      const cellAlign =
                        col.align === 'right'
                          ? 'text-right'
                          : col.align === 'center'
                            ? 'text-center'
                            : 'text-left';

                      // --- Editing mode ---
                      if (isEditingThis) {
                        const editable = isColumnEditable(col);
                        const dictOptions = col.dictCode
                          ? (getDictItems(col.dictCode) || []).map((d) => ({
                              value: d.value,
                              label: d.label,
                            }))
                          : undefined;
                        return (
                          <td
                            key={col.field}
                            className={`px-4 py-1.5 ${cellAlign} bg-blue-50/40 transition-colors`}
                          >
                            <InlineEditableCell
                              col={col}
                              value={editable ? editingValues[col.field] : row[col.field]}
                              displayValue={formatCellValue(row[col.field], col, getDictLabel)}
                              isEditing={editable}
                              error={editErrors[col.field]}
                              onChange={(val) => {
                                setEditingValues((prev) => ({ ...prev, [col.field]: val }));
                                if (editErrors[col.field]) {
                                  setEditErrors((prev) => {
                                    const n = { ...prev };
                                    delete n[col.field];
                                    return n;
                                  });
                                }
                              }}
                              onKeyDown={(e) => handleEditKeyDown(e, col.field)}
                              dictOptions={dictOptions}
                              autoFocus={editableFields[0] === col.field}
                              t={t}
                            />
                          </td>
                        );
                      }

                      // --- Read mode ---
                      return (
                        <td
                          key={col.field}
                          className={`px-4 py-2 text-sm text-gray-700 ${cellAlign}${
                            canInlineEdit ? 'cursor-pointer hover:bg-gray-50/50' : ''
                          }`}
                          onDoubleClick={canInlineEdit ? () => handleStartEdit(row) : undefined}
                        >
                          {formatCellValue(row[col.field], col, getDictLabel)}
                        </td>
                      );
                    })}
                    {hasActions && (
                      <td className="px-4 py-2 text-center whitespace-nowrap">
                        {isEditingThis ? (
                          <>
                            <button
                              onClick={handleSaveEdit}
                              disabled={editSaving}
                              data-testid={`subtable-edit-save-${rowIndex}`}
                              className="mr-2 text-xs text-blue-600 hover:text-blue-800 disabled:opacity-50"
                            >
                              {editSaving
                                ? '...'
                                : t('common.save') !== 'common.save'
                                  ? t('common.save')
                                  : 'Save'}
                            </button>
                            <button
                              onClick={handleCancelEdit}
                              data-testid={`subtable-edit-cancel-${rowIndex}`}
                              className="text-xs text-gray-500 hover:text-gray-700"
                            >
                              {t('common.cancel') !== 'common.cancel'
                                ? t('common.cancel')
                                : 'Cancel'}
                            </button>
                          </>
                        ) : (
                          <div className="flex items-center justify-center gap-2">
                            {canInlineEdit && (
                              <button
                                onClick={() => handleStartEdit(row)}
                                disabled={!!editingRowId}
                                data-testid={`subtable-edit-${rowIndex}`}
                                className="text-xs text-blue-500 hover:text-blue-700 disabled:cursor-not-allowed disabled:opacity-30"
                                title={
                                  t('common.edit') !== 'common.edit' ? t('common.edit') : 'Edit'
                                }
                              >
                                <svg
                                  className="inline h-3.5 w-3.5"
                                  fill="none"
                                  viewBox="0 0 24 24"
                                  strokeWidth={1.5}
                                  stroke="currentColor"
                                >
                                  <path
                                    strokeLinecap="round"
                                    strokeLinejoin="round"
                                    d="M16.862 4.487l1.687-1.688a1.875 1.875 0 112.652 2.652L10.582 16.07a4.5 4.5 0 01-1.897 1.13L6 18l.8-2.685a4.5 4.5 0 011.13-1.897l8.932-8.931zm0 0L19.5 7.125M18 14v4.75A2.25 2.25 0 0115.75 21H5.25A2.25 2.25 0 013 18.75V8.25A2.25 2.25 0 015.25 6H10"
                                  />
                                </svg>
                              </button>
                            )}
                            {config.commands?.delete && (
                              <button
                                onClick={() => handleDeleteRow(row.pid)}
                                disabled={deletingId === row.pid || !!editingRowId}
                                data-testid={`subtable-delete-${rowIndex}`}
                                className="text-xs text-red-500 hover:text-red-700 disabled:opacity-50"
                              >
                                {deletingId === row.pid
                                  ? '...'
                                  : t('common.delete') !== 'common.delete'
                                    ? t('common.delete')
                                    : 'Delete'}
                              </button>
                            )}
                          </div>
                        )}
                      </td>
                    )}
                  </SortableSubTableRow>
                );
              })}
            </DndSubTableWrapper>

          {/* Inline add form row */}
          {isEditable && isAdding && (
            <tr className="border-t border-blue-100 bg-blue-50/30" data-testid="subtable-add-form">
              {config.columns.map((col: ColumnConfig) => (
                <td
                  key={col.field}
                  className={`px-4 py-2 ${col.align === 'right' ? 'text-right' : 'text-left'}`}
                >
                  {col.editable === false ? (
                    <span className="text-xs text-gray-400">-</span>
                  ) : (
                    <div>
                      <input
                        type={isNumericField(col) ? 'number' : 'text'}
                        value={newRowData[col.field] ?? ''}
                        onChange={(e) => {
                          const val =
                            isNumericField(col) && e.target.value
                              ? Number(e.target.value)
                              : e.target.value;
                          setNewRowData((prev) => ({ ...prev, [col.field]: val }));
                          if (addErrors[col.field]) {
                            setAddErrors((prev) => {
                              const next = { ...prev };
                              delete next[col.field];
                              return next;
                            });
                          }
                        }}
                        placeholder={
                          col.required
                            ? `${resolveColumnLabel(col.field)} *`
                            : resolveColumnLabel(col.field)
                        }
                        data-testid={`subtable-add-${col.field}`}
                        className={`w-full rounded border px-2 py-1 text-sm focus:ring-1 focus:outline-none ${
                          addErrors[col.field]
                            ? 'border-red-300 focus:ring-red-400'
                            : 'border-gray-300 focus:ring-blue-400'
                        }`}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter') handleAddRow();
                          if (e.key === 'Escape') {
                            setIsAdding(false);
                            setNewRowData({});
                            setAddErrors({});
                          }
                        }}
                      />
                      {addErrors[col.field] && (
                        <p
                          className="mt-0.5 text-xs text-red-500"
                          data-testid={`subtable-error-${col.field}`}
                        >
                          {addErrors[col.field]}
                        </p>
                      )}
                    </div>
                  )}
                </td>
              ))}
              <td className="px-4 py-2 text-center whitespace-nowrap">
                <button
                  onClick={handleAddRow}
                  disabled={saving}
                  data-testid="subtable-save-btn"
                  className="mr-2 text-xs text-blue-600 hover:text-blue-800 disabled:opacity-50"
                >
                  {saving ? '...' : t('common.save') !== 'common.save' ? t('common.save') : 'Save'}
                </button>
                <button
                  onClick={() => {
                    setIsAdding(false);
                    setNewRowData({});
                    setAddErrors({});
                  }}
                  data-testid="subtable-cancel-btn"
                  className="text-xs text-gray-500 hover:text-gray-700"
                >
                  {t('common.cancel') !== 'common.cancel' ? t('common.cancel') : 'Cancel'}
                </button>
              </td>
            </tr>
          )}
          {addErrors._form && (
            <tr>
              <td
                colSpan={totalCols}
                className="px-4 py-2 text-sm text-red-500"
                data-testid="subtable-form-error"
              >
                {addErrors._form}
              </td>
            </tr>
          )}
          {editErrors._form && (
            <tr>
              <td
                colSpan={totalCols}
                className="px-4 py-2 text-sm text-red-500"
                data-testid="subtable-edit-form-error"
              >
                {editErrors._form}
              </td>
            </tr>
          )}
        </tbody>

        {/* Summary row — reactive during inline editing */}
        {config.summary && (
          <SubTableSummaryRow
            columns={config.columns}
            rows={
              editingRowId
                ? rows.map((r) =>
                    (r.pid || String(r.id)) === editingRowId ? { ...r, ...editingValues } : r,
                  )
                : rows
            }
            summary={config.summary}
            locale={locale}
            t={t}
            extraColCount={extraColCount}
          />
        )}
      </table>
      )}

      {/* Add Line button */}
      {isEditable && config.commands?.create && !isAdding && displayRows.length > 0 && (
        <div className="border-t border-gray-200">
          <button
            onClick={() => setIsAdding(true)}
            data-testid="subtable-add-row"
            className="w-full py-2 text-xs text-gray-400 transition-colors hover:bg-gray-50 hover:text-blue-500"
          >
            + {t('common.addLine') !== 'common.addLine' ? t('common.addLine') : 'Add Line'}
          </button>
        </div>
      )}
    </div>
  );
};

// ------- Utility Functions -------

function formatCellValue(
  value: any,
  col: ColumnConfig,
  getDictLabel?: (code: string, value: string) => string | undefined,
): string {
  if (value == null) return '-';

  // Dict label lookup
  if (col.dictCode && getDictLabel) {
    const label = getDictLabel(col.dictCode, String(value));
    if (label) return label;
  }

  if (col.format === 'currency' || col.valueType === 'currency') {
    return formatNumber(Number(value));
  }

  if (col.format === 'percent') {
    return `${(Number(value) * 100).toFixed(1)}%`;
  }

  if (typeof value === 'boolean') {
    return value ? 'Yes' : 'No';
  }

  if (typeof value === 'number') {
    return formatNumber(value);
  }

  return String(value);
}

function formatNumber(num: number): string {
  return num.toLocaleString('zh-CN', {
    minimumFractionDigits: 0,
    maximumFractionDigits: 2,
  });
}

function isNumericField(col: ColumnConfig): boolean {
  return (
    col.format === 'currency' ||
    col.format === 'number' ||
    col.field.endsWith('_qty') ||
    col.field.endsWith('_price') ||
    col.field.endsWith('_amount') ||
    col.field.endsWith('_count')
  );
}

/** Evaluate a single ValidationRule against a value */
function evaluateValidationRule(
  rule: ValidationRule,
  value: any,
  t: (key: string) => string,
): string | null {
  const getMessage = () => {
    if (typeof rule.message === 'string') {
      const resolved = t(rule.message);
      return resolved !== rule.message ? resolved : rule.message;
    }
    const msg = rule.message as any;
    return msg?.['zh-CN'] || msg?.['en-US'] || '';
  };

  switch (rule.type) {
    case 'required':
      if (value === undefined || value === null || value === '') return getMessage();
      break;
    case 'min':
      if (rule.min !== undefined && Number(value) < rule.min) return getMessage();
      break;
    case 'max':
      if (rule.max !== undefined && Number(value) > rule.max) return getMessage();
      break;
    case 'pattern':
      if (rule.pattern && typeof value === 'string' && !new RegExp(rule.pattern).test(value))
        return getMessage();
      break;
  }
  return null;
}

export default SubTableViewer;
