import React, { useCallback } from 'react';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import {
  buildCommandTargetParams,
  getLegacyCompatibleRecordPid,
} from '~/framework/meta/utils/publicRecordId';
import type { useSubTableColumns } from './useSubTableColumns';
import type { useSubTableState } from './useSubTableState';
import type { ResolvedSubTableViewerProps } from './subTableTypes';
import type { useSubTableValidation } from './useSubTableValidation';
import type { useSubTableData } from './useSubTableData';

type UseSubTableInlineEditingArgs = Pick<
  ReturnType<typeof useSubTableColumns>,
  'canInlineEdit' | 'effectiveColumns' | 'isColumnEditable' | 'editableFields'
> &
  Pick<
    ReturnType<typeof useSubTableState>,
    | 'setEditingRowId'
    | 'setEditingValues'
    | 'originalEditDataRef'
    | 'setEditErrors'
    | 'setCrossRowErrors'
    | 'editingRowId'
    | 'editingValues'
    | 'rows'
    | 'setEditSaving'
    | 'setRows'
  > &
  Pick<ResolvedSubTableViewerProps, 'config' | 'token' | 'onDataChange'> &
  Pick<ReturnType<typeof useSubTableValidation>, 'validateFieldValue' | 'validateCrossRowRules'> &
  Pick<ReturnType<typeof useSubTableData>, 'reloadRows'>;

export function useSubTableInlineEditing({
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
}: UseSubTableInlineEditingArgs) {
  // Enter edit mode for a row
  const handleStartEdit = useCallback(
    (row: Record<string, any>) => {
      if (!canInlineEdit) return;
      const rowId = getLegacyCompatibleRecordPid(row);
      if (!rowId) return;
      const values: Record<string, any> = {};
      for (const col of effectiveColumns) {
        values[col.field] = row[col.field] ?? '';
      }
      setEditingRowId(rowId);
      setEditingValues(values);
      originalEditDataRef.current = { ...values };
      setEditErrors({});
      setCrossRowErrors([]);
    },
    [
      effectiveColumns,
      canInlineEdit,
      originalEditDataRef,
      setCrossRowErrors,
      setEditErrors,
      setEditingRowId,
      setEditingValues,
    ],
  );

  // Cancel edit mode
  const handleCancelEdit = useCallback(() => {
    setEditingRowId(null);
    setEditingValues({});
    setEditErrors({});
    setCrossRowErrors([]);
  }, [setCrossRowErrors, setEditErrors, setEditingRowId, setEditingValues]);

  // Save edited row via update command with full validation
  const handleSaveEdit = useCallback(async () => {
    if (!editingRowId || !config.commands?.update) return;

    // Row-level field validation (required, min/max, pattern, custom rules)
    const errors: Record<string, string> = {};
    for (const col of effectiveColumns) {
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
        if (getLegacyCompatibleRecordPid(r) === editingRowId) {
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
          ...buildCommandTargetParams(editingRowId),
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
    effectiveColumns,
    rows,
    token,
    reloadRows,
    onDataChange,
    isColumnEditable,
    validateFieldValue,
    validateCrossRowRules,
    handleCancelEdit,
    originalEditDataRef,
    setCrossRowErrors,
    setEditErrors,
    setEditSaving,
    setEditingRowId,
    setEditingValues,
    setRows,
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
  return { handleStartEdit, handleCancelEdit, handleSaveEdit, handleEditKeyDown };
}
