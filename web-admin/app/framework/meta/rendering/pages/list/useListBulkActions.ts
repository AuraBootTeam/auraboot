import {
  commandBusinessRejectionError,
  createPromptSubmissionFeedback,
  hasAsyncCommandReceipt,
} from '~/framework/meta/runtime/actions/promptSubmissionFeedback';
import { commandExecutionError } from '~/framework/meta/utils/commandExecutionError';
import React, { useCallback } from 'react';
import { resolveCommandErrorMessage } from '~/framework/meta/hooks/useActionHandler';
import { promptInputForm } from '~/framework/meta/runtime/actions/ActionRegistry';
import type { ButtonConfig, FieldConfig } from '~/framework/meta/schemas/types';
import { resolveConfirmDialog } from '~/framework/meta/utils/i18nResolver';
import {
  buildCommandTargetParams,
  getPublicRecordKey,
} from '~/framework/meta/utils/publicRecordId';
import { dynamicService } from '~/shared/services/dynamicService';
import { fetchResult } from '~/shared/services/http-client';
import { confirmDialog } from '~/utils/confirmDialog';
import { ResultHelper } from '~/utils/type';
import { type BulkActionFailure, type BulkActionResult } from './BulkActionResultDialog';
import { translateOrFallback } from './listFieldMetadata';
import type { BulkFieldCommandState, ListLoadDataParams } from './listPageStateUtils';
import type { DynamicEntity } from './listPageTypes';
import { buildBulkFieldCommandPayload } from './listSchemaPolicy';
import {
  clearPage as selectionClearPage,
  clearSelection as selectionClearSelection,
  enterAllMatching as selectionEnterAllMatching,
  isPageFullySelected as selectionIsPageFullySelected,
  selectPage as selectionSelectPage,
  toggleRow as selectionToggleRow,
  type SelectionState,
} from './selectionModel';

interface BulkPromptOutcome {
  successCount: number;
  failures: BulkActionFailure[];
  firstError?: unknown;
  acceptedReceipt?: unknown;
}
interface ListBulkActionsInput {
  setImportOpen: React.Dispatch<React.SetStateAction<boolean>>;
  loadData: (params?: ListLoadDataParams) => Promise<void>;
  pagination: { current: number; pageSize: number; total: number };
  filters: Record<string, any>;
  setSelectionState: React.Dispatch<React.SetStateAction<SelectionState>>;
  pageRowIds: string[];
  schema: any;
  tableName: string;
  resolveButtonLabel: (button: ButtonConfig) => string;
  locale: string;
  data: DynamicEntity[];
  token: string | null | undefined;
  t: (key: string) => string;
  showToast: (message: string, type: 'success' | 'error' | 'warning' | 'info') => void;
  setBulkActionResult: React.Dispatch<React.SetStateAction<BulkActionResult | null>>;
  canUseButton: (button: ButtonConfig) => boolean;
  setBulkFieldCommand: React.Dispatch<React.SetStateAction<BulkFieldCommandState | null>>;
  modelCode: any;
  bulkFieldCommand: BulkFieldCommandState | null;
  setBulkEditOpen: React.Dispatch<React.SetStateAction<boolean>>;
}

/** Apply cross-page selection and bulk command workflows. */
export function useListBulkActions({
  setImportOpen,
  loadData,
  pagination,
  filters,
  setSelectionState,
  pageRowIds,
  schema,
  tableName,
  resolveButtonLabel,
  locale,
  data,
  token,
  t,
  showToast,
  setBulkActionResult,
  canUseButton,
  setBulkFieldCommand,
  modelCode,
  bulkFieldCommand,
  setBulkEditOpen,
}: ListBulkActionsInput) {
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
      onOutcome?: (outcome: BulkPromptOutcome) => void,
    ) => {
      const label = resolveButtonLabel(button);
      const isZhLocale = locale.toLowerCase().startsWith('zh');
      let successCount = 0;
      const failures: BulkActionFailure[] = [];
      let firstError: unknown = undefined,
        acceptedReceipt: unknown = undefined;
      const recordLabelById = new Map(
        data.map((record) => {
          const id = getPublicRecordKey(record) || '';
          const recordLabel = record.name || record.title || record.code || id;
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
          if (ResultHelper.isSuccess(result) && !commandBusinessRejectionError(result)) {
            successCount += 1;
            if (hasAsyncCommandReceipt(result.data)) acceptedReceipt ??= result.data;
          } else {
            firstError ??=
              commandBusinessRejectionError(result) ??
              commandExecutionError(result, command, t, locale);
            failures.push({
              recordPid: id,
              recordLabel: recordLabelById.get(id) || id,
              reason:
                commandBusinessRejectionError(result)?.message ??
                resolveCommandErrorMessage(result, command, t),
            });
          }
        } catch (error) {
          firstError ??= error;
          failures.push({
            recordPid: id,
            recordLabel: recordLabelById.get(id) || id,
            reason: error instanceof Error ? error.message : String(error),
          });
        }
      }

      const outcome = { successCount, failures, firstError, acceptedReceipt };
      onOutcome?.(outcome);
      if (successCount > 0) {
        setSelectionState(selectionClearSelection);
        await loadData({ page: 0, size: pagination.pageSize, filters });
      }

      if (
        acceptedReceipt &&
        (button.action as any)?.inputFieldsPresentation?.waitForResult === true
      ) {
        showToast(
          isZhLocale
            ? `已受理 ${successCount} 条请求，拒绝 ${failures.length} 条；请刷新核对最终结果。`
            : `${successCount} requests accepted; ${failures.length} rejected. Refresh to review final results.`,
          failures.length > 0 ? 'warning' : 'info',
        );
      } else if (failures.length === 0) {
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
      return outcome;
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

      const submissionFeedback = createPromptSubmissionFeedback({ t, locale });
      try {
        let commandPayload: Record<string, unknown> = {};
        const inputFields = Array.isArray((actionDef as any)?.inputFields)
          ? (actionDef as any).inputFields
          : [];
        if (inputFields.length > 0) {
          try {
            commandPayload = await promptInputForm(
              inputFields,
              (actionDef as any)?.inputFieldsTitle,
              fetchResult,
              (actionDef as any)?.inputFieldsSubmitLabel,
              {
                selectedCount: ids.length,
                selectedIds: ids,
                modelCode,
              },
              { t, locale },
              (actionDef as any)?.inputFieldsPresentation,
              undefined,
              submissionFeedback,
            );
          } catch {
            return;
          }
        }

        if (actionType === 'bulk_state_transition' || actionType === 'bulk_record_command') {
          await executeTargetedBulkCommand(
            button,
            ids,
            commandPayload,
            (actionDef as any)?.operationType || 'UPDATE',
            (outcome) => {
              if (outcome.failures.length > 0) {
                const prefix =
                  outcome.successCount > 0
                    ? locale.toLowerCase().startsWith('zh')
                      ? `已有 ${outcome.successCount} 条请求被接受；`
                      : `${outcome.successCount} requests accepted; `
                    : '';
                submissionFeedback.fail(
                  outcome.firstError ?? new Error(outcome.failures[0].reason),
                  prefix + outcome.failures[0].reason,
                );
              } else submissionFeedback.completeResult(outcome.acceptedReceipt);
            },
          );
          return;
        }

        const label = resolveButtonLabel(button);
        const isZhLocale = locale.toLowerCase().startsWith('zh');
        let successCount = 0;
        const failures: BulkActionFailure[] = [];
        let acceptedReceipt: unknown = undefined;

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
          if (ResultHelper.isSuccess(result) && !commandBusinessRejectionError(result)) {
            successCount = ids.length;
            if (hasAsyncCommandReceipt(result.data)) acceptedReceipt = result.data;
            submissionFeedback.completeResult(result);
          } else {
            submissionFeedback.fail(
              commandBusinessRejectionError(result) ??
                commandExecutionError(result, command, t, locale),
            );
            failures.push({
              recordPid: command,
              recordLabel: command,
              reason:
                commandBusinessRejectionError(result)?.message ??
                resolveCommandErrorMessage(result, command, t),
            });
          }
        } else {
          submissionFeedback.fail(
            new Error(`Unsupported bulk action type: ${actionType || 'unknown'}`),
          );
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

        if (
          acceptedReceipt &&
          failures.length === 0 &&
          (actionDef as any)?.inputFieldsPresentation?.waitForResult === true
        ) {
          showToast(
            isZhLocale
              ? '命令已受理，请刷新核对执行结果。'
              : 'Commands accepted. Refresh to review their final results.',
            'info',
          );
        } else if (failures.length === 0) {
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
      } catch (error) {
        submissionFeedback.fail(error);
        throw error;
      } finally {
        submissionFeedback.finalize();
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

  return {
    handleImportComplete,
    toggleRowSelection,
    toggleSelectAll,
    handleSelectAllMatching,
    clearAllSelection,
    handleBulkDelete,
    handleBulkAction,
    handleBulkFieldCommandSubmit,
    handleBulkEditComplete,
  };
}
