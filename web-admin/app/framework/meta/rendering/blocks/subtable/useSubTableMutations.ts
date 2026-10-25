import { useCallback } from 'react';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { buildCommandTargetParams } from '~/framework/meta/utils/publicRecordId';
import type { ResolvedSubTableViewerProps } from './subTableTypes';
import type { useSubTableColumns } from './useSubTableColumns';
import type { useSubTableValidation } from './useSubTableValidation';
import type { useSubTableState } from './useSubTableState';
import type { useSubTableData } from './useSubTableData';

type UseSubTableMutationsArgs = Pick<
  ResolvedSubTableViewerProps,
  'config' | 'parentRecordPid' | 'token' | 'onDataChange'
> &
  Pick<ReturnType<typeof useSubTableColumns>, 'effectiveColumns'> &
  Pick<ReturnType<typeof useSubTableValidation>, 'validateFieldValue'> &
  Pick<
    ReturnType<typeof useSubTableState>,
    'newRowData' | 'setAddErrors' | 'setSaving' | 'setNewRowData' | 'setIsAdding' | 'setDeletingId'
  > &
  Pick<ReturnType<typeof useSubTableData>, 'resolvedDefaultValues' | 'reloadRows'>;

export function useSubTableMutations({
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
}: UseSubTableMutationsArgs) {
  // Create command handler
  const handleAddRow = useCallback(async () => {
    if (!config.commands?.create) return;

    // Validate all editable fields (required, min/max, pattern, custom rules)
    const errors: Record<string, string> = {};
    for (const col of effectiveColumns) {
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
        ...resolvedDefaultValues,
        ...newRowData,
        [config.parentField]: parentRecordPid,
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
  }, [
    config,
    effectiveColumns,
    newRowData,
    parentRecordPid,
    token,
    reloadRows,
    onDataChange,
    resolvedDefaultValues,
    validateFieldValue,
    setAddErrors,
    setIsAdding,
    setNewRowData,
    setSaving,
  ]);

  // Delete command handler
  const handleDeleteRow = useCallback(
    async (rowPid: string) => {
      if (!config.commands?.delete || !rowPid) return;

      setDeletingId(rowPid);
      try {
        const result = await fetchResult(`/api/meta/commands/execute/${config.commands.delete}`, {
          method: 'post',
          params: {
            ...buildCommandTargetParams(rowPid),
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
    [config.commands, token, reloadRows, onDataChange, setDeletingId],
  );
  return { handleAddRow, handleDeleteRow };
}
