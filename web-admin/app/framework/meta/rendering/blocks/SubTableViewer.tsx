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

import React from 'react';
import type { SubTableViewerProps } from './subtable/subTableTypes';
import { useSubTableState } from './subtable/useSubTableState';
import { useSubTableFieldMetadata } from './subtable/useSubTableFieldMetadata';
import { useSubTableColumns } from './subtable/useSubTableColumns';
import { useSubTableData } from './subtable/useSubTableData';
import { useSubTableReferenceDisplay } from './subtable/useSubTableReferenceDisplay';
import { useSubTableValidation } from './subtable/useSubTableValidation';
import { useSubTableMutations } from './subtable/useSubTableMutations';
import { useSubTableRowActions } from './subtable/useSubTableRowActions';
import { useSubTableInlineEditing } from './subtable/useSubTableInlineEditing';
import { useSubTableTreeSorting } from './subtable/useSubTableTreeSorting';
import { renderSubTableView } from './subtable/renderSubTableView';

export type { SubTableViewerProps } from './subtable/subTableTypes';
export { formatCellValue } from './subtable/subTableValues';

export const SubTableViewer: React.FC<SubTableViewerProps> = ({
  config,
  parentRecordPid,
  parentRecordData,
  token,
  locale = 'zh-CN',
  t = (key: string) => key,
  isEditable = false,
  onDataChange,
  runtime,
}) => {
  const {
    navigate,
    hasPermission,
    timezone,
    formats,
    rows,
    setRows,
    loading,
    setLoading,
    error,
    setError,
    refreshCounter,
    setRefreshCounter,
    isAdding,
    setIsAdding,
    newRowData,
    setNewRowData,
    addErrors,
    setAddErrors,
    saving,
    setSaving,
    deletingId,
    setDeletingId,
    rowActionLoadingKey,
    setRowActionLoadingKey,
    editingRowId,
    setEditingRowId,
    editingValues,
    setEditingValues,
    editErrors,
    setEditErrors,
    editSaving,
    setEditSaving,
    crossRowErrors,
    setCrossRowErrors,
    originalEditDataRef,
    childFieldMap,
    setChildFieldMap,
    columnMetaLoading,
    setColumnMetaLoading,
    referenceDisplayCache,
    setReferenceDisplayCache,
  } = useSubTableState();
  useSubTableFieldMetadata({ config, setChildFieldMap, setColumnMetaLoading, token });
  const {
    canInlineEdit,
    isColumnEditable,
    effectiveColumns,
    editableFields,
    getDictItems,
    getDictLabel,
    referenceDisplayConfigs,
    referenceDisplayByField,
    referenceInputSources,
  } = useSubTableColumns({ isEditable, config, childFieldMap, token });
  const { reloadRows, resolvedDefaultValues } = useSubTableData({
    setRefreshCounter,
    parentRecordPid,
    parentRecordData,
    config,
    setLoading,
    setError,
    setRows,
    token,
    t,
    refreshCounter,
  });
  const { resolveColumnLabel, getReferenceDisplayValue } = useSubTableReferenceDisplay({
    config,
    t,
    childFieldMap,
    referenceDisplayConfigs,
    rows,
    referenceDisplayCache,
    token,
    setReferenceDisplayCache,
    referenceDisplayByField,
  });
  const { validateFieldValue, validateCrossRowRules } = useSubTableValidation({
    locale,
    t,
    config,
    parentRecordData,
  });
  const { handleAddRow, handleDeleteRow } = useSubTableMutations({
    config,
    effectiveColumns,
    validateFieldValue,
    newRowData,
    setAddErrors,
    setSaving,
    resolvedDefaultValues,
    parentRecordPid,
    token,
    setNewRowData,
    setIsAdding,
    reloadRows,
    onDataChange,
    setDeletingId,
  });
  const { resolveButtonLabel, isRowActionVisible, isRowActionDisabled, handleRowAction } =
    useSubTableRowActions({
      locale,
      t,
      parentRecordData,
      hasPermission,
      navigate,
      setEditErrors,
      setRowActionLoadingKey,
      token,
      reloadRows,
      onDataChange,
    });
  const { handleStartEdit, handleCancelEdit, handleSaveEdit, handleEditKeyDown } =
    useSubTableInlineEditing({
      canInlineEdit,
      effectiveColumns,
      setEditingRowId,
      setEditingValues,
      originalEditDataRef,
      setEditErrors,
      setCrossRowErrors,
      editingRowId,
      config,
      isColumnEditable,
      validateFieldValue,
      editingValues,
      rows,
      validateCrossRowRules,
      setEditSaving,
      token,
      setRows,
      reloadRows,
      onDataChange,
      editableFields,
    });
  const {
    configuredRowActions,
    toolbarBlock,
    hasActions,
    isSortable,
    totalCols,
    extraColCount,
    emptyStateTitle,
    emptyStateDescription,
    emptyStateActionLabel,
    toggleExpand,
    displayRows,
    dndItems,
    handleDragEnd,
  } = useSubTableTreeSorting({
    config,
    parentRecordData,
    parentRecordPid,
    isEditable,
    canInlineEdit,
    effectiveColumns,
    locale,
    t,
    rows,
    setRows,
    token,
    reloadRows,
  });
  return renderSubTableView({
    loading,
    columnMetaLoading,
    t,
    error,
    config,
    displayRows,
    locale,
    timezone,
    parentRecordData,
    emptyStateTitle,
    effectiveColumns,
    getDictLabel,
    formats,
    configuredRowActions,
    isRowActionVisible,
    rowActionLoadingKey,
    isRowActionDisabled,
    handleRowAction,
    resolveButtonLabel,
    isSortable,
    resolveColumnLabel,
    hasActions,
    toolbarBlock,
    runtime,
    crossRowErrors,
    isAdding,
    extraColCount,
    emptyStateDescription,
    isEditable,
    setIsAdding,
    emptyStateActionLabel,
    dndItems,
    handleDragEnd,
    editingRowId,
    toggleExpand,
    isColumnEditable,
    getReferenceDisplayValue,
    getDictItems,
    referenceInputSources,
    editingValues,
    editErrors,
    setEditingValues,
    setEditErrors,
    handleEditKeyDown,
    editableFields,
    canInlineEdit,
    handleStartEdit,
    handleSaveEdit,
    editSaving,
    handleCancelEdit,
    handleDeleteRow,
    deletingId,
    newRowData,
    setNewRowData,
    setAddErrors,
    addErrors,
    handleAddRow,
    saving,
    totalCols,
    rows,
  });
};

export default SubTableViewer;
