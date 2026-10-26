import { createPromptSubmissionFeedback } from '~/framework/meta/runtime/actions/promptSubmissionFeedback';
import { commandExecutionError } from '~/framework/meta/utils/commandExecutionError';
import { hasButtonPermissions } from '~/framework/meta/utils/buttonPermissions';
import { useCallback } from 'react';
import type { ButtonConfig } from '~/framework/meta/schemas/types';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { evaluateVisibleWhen as evaluateVisibleWhenExpression } from '~/framework/meta/rendering/pages/utils/visibleWhen';
import {
  buildCommandTargetParams,
  getLegacyCompatibleRecordPid,
} from '~/framework/meta/utils/publicRecordId';
import { promptInputForm } from '~/framework/meta/runtime/actions/ActionRegistry';
import { ActionCancelledError } from '~/framework/meta/runtime/actions/ActionCancelledError';
import { createExpressionContext } from '~/framework/meta/runtime/expression/context';
import { expressionEvaluator } from '~/framework/meta/runtime/expression/evaluator';
import { resolveCommandErrorMessage } from '~/framework/meta/utils/commandResponseErrors';
import { confirmDialog } from '~/utils/confirmDialog';
import { resolveConfirmDialog } from '~/framework/meta/utils/i18nResolver';
import type { ResolvedSubTableViewerProps } from './subTableTypes';
import type { useSubTableState } from './useSubTableState';
import type { useSubTableData } from './useSubTableData';

type UseSubTableRowActionsArgs = Pick<
  ResolvedSubTableViewerProps,
  'locale' | 't' | 'parentRecordData' | 'token' | 'onDataChange'
> &
  Pick<
    ReturnType<typeof useSubTableState>,
    'hasPermission' | 'navigate' | 'setEditErrors' | 'setRowActionLoadingKey'
  > &
  Pick<ReturnType<typeof useSubTableData>, 'reloadRows'>;

export function useSubTableRowActions({
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
}: UseSubTableRowActionsArgs) {
  const resolveButtonLabel = useCallback(
    (button: ButtonConfig): string => {
      if (button.content) return getLocalizedText(button.content, locale, t);
      if (button.label) return getLocalizedText(button.label, locale, t);
      if (button.commandCode && button.commandCode.includes(':')) {
        const [ns, actionCode] = button.commandCode.split(':');
        const namespaced = `${ns}.action.${actionCode}`;
        const translated = t(namespaced);
        if (translated && translated !== namespaced) return translated;
      }
      if (button.action && typeof button.action === 'string') {
        const key = `action.${button.action}`;
        const translated = t(key);
        if (translated && translated !== key) return translated;
      }
      const codeKey = `action.${button.code}`;
      const codeTranslated = t(codeKey);
      if (codeTranslated && codeTranslated !== codeKey) return codeTranslated;
      return button.code;
    },
    [locale, t],
  );

  const resolveRowActionCommand = useCallback((button: ButtonConfig): string | null => {
    const actionDef = button.action && typeof button.action === 'object' ? button.action : null;
    const command = (actionDef as any)?.command || button.commandCode;
    return typeof command === 'string' && command.length > 0 ? command : null;
  }, []);

  const interpolateRowPath = useCallback((raw: string, row: Record<string, any>): string => {
    const rowId = getLegacyCompatibleRecordPid(row) || '';
    let value = raw.replace(/\{(\w+)\}/g, (_: string, field: string) => {
      if (field === 'pid' || field === 'id') return encodeURIComponent(String(rowId));
      return encodeURIComponent(String(row[field] ?? ''));
    });
    value = value.replace(/\$\{row\.(\w+)\}/g, (_: string, field: string) => {
      return encodeURIComponent(String(row[field] ?? ''));
    });
    value = value.replace(/\$\{(\w+)\}/g, (_: string, field: string) => {
      if (field === 'pid' || field === 'id') return encodeURIComponent(String(rowId));
      return encodeURIComponent(String(row[field] ?? ''));
    });
    return value;
  }, []);

  const resolveNavigatePath = useCallback(
    (target: string, row: Record<string, any>): string => {
      const rowId = getLegacyCompatibleRecordPid(row);
      if (target.startsWith('/')) return interpolateRowPath(target, row);

      const lastUnderscoreIdx = target.lastIndexOf('_');
      if (lastUnderscoreIdx === -1) return `/p/${target}`;

      const suffix = target.substring(lastUnderscoreIdx + 1);
      const modelCodePart = target.substring(0, lastUnderscoreIdx);
      switch (suffix) {
        case 'form':
          return rowId ? `/p/${modelCodePart}/edit/${rowId}` : `/p/${modelCodePart}/new`;
        case 'detail':
        case 'view':
          return `/p/${modelCodePart}/view/${rowId}`;
        case 'list':
          return `/p/${modelCodePart}`;
        default:
          return `/p/${modelCodePart}`;
      }
    },
    [interpolateRowPath],
  );

  const evaluateRowCondition = useCallback(
    (expression: string | undefined, row: Record<string, any>) => {
      return evaluateVisibleWhenExpression(expression, {
        record: row,
        row,
        form: parentRecordData || {},
      });
    },
    [parentRecordData],
  );

  const isRowActionVisible = useCallback(
    (button: ButtonConfig, row: Record<string, any>) => {
      if (!hasButtonPermissions(button, hasPermission)) return false;
      return evaluateRowCondition(button.visibleWhen, row);
    },
    [evaluateRowCondition, hasPermission],
  );

  const isRowActionDisabled = useCallback(
    (button: ButtonConfig, row: Record<string, any>) => {
      if (button.disabled) return true;
      if (button.disableWhen && evaluateRowCondition(button.disableWhen, row)) return true;
      if (button.enableWhen && !evaluateRowCondition(button.enableWhen, row)) return true;
      return false;
    },
    [evaluateRowCondition],
  );

  const handleRowAction = useCallback(
    async (button: ButtonConfig, row: Record<string, any>, rowIndex: number) => {
      if (!hasButtonPermissions(button, hasPermission)) return;
      const confirmConfig = button.confirm ?? button.confirmMessageKey;
      if (confirmConfig) {
        const confirmation =
          typeof confirmConfig === 'object'
            ? { content: getLocalizedText(confirmConfig, locale, t) }
            : resolveConfirmDialog(confirmConfig, t);
        const confirmed = await confirmDialog({
          ...confirmation,
          variant: button.danger || button.variant === 'danger' ? 'danger' : 'default',
        });
        if (!confirmed) return;
      }

      const rowPid = getLegacyCompatibleRecordPid(row);
      const actionDef = button.action && typeof button.action === 'object' ? button.action : null;
      const actionType = (actionDef as any)?.type || (button.commandCode ? 'command' : null);
      if (actionType === 'navigate') {
        const target = (actionDef as any)?.to || button.navigateTo;
        if (!target) return;
        const path = resolveNavigatePath(String(target), row);
        const command = (actionDef as any)?.command || button.commandCode;
        if (command) {
          const sep = path.includes('?') ? '&' : '?';
          navigate(`${path}${sep}commandCode=${encodeURIComponent(command)}`);
        } else {
          navigate(path);
        }
        return;
      }

      const command = resolveRowActionCommand(button);
      if (!rowPid || !command) return;

      if (actionType && !['command', 'state_transition'].includes(actionType)) {
        setEditErrors({
          _form: `Unsupported sub-table row action type: ${actionType}`,
        });
        return;
      }

      const loadingKey = `${rowPid}:${button.code}:${rowIndex}`;
      setRowActionLoadingKey(loadingKey);
      setEditErrors({});
      const submissionFeedback = createPromptSubmissionFeedback({ t, locale });
      try {
        const commandAction =
          actionDef && (actionDef.type === 'command' || actionDef.type === 'state_transition')
            ? actionDef
            : null;
        const expressionContext = createExpressionContext({
          row,
          record: row,
          form: parentRecordData || {},
          locale,
          t,
          hasPermission,
        });
        const invalidRecord = () =>
          new Error(
            t('error.data_load_failed') !== 'error.data_load_failed'
              ? t('error.data_load_failed')
              : 'Unable to load the current record',
          );
        let expectedVersion: number | undefined;
        if (commandAction?.expectedVersion !== undefined) {
          const raw = commandAction.expectedVersion;
          const resolved =
            typeof raw === 'string'
              ? expressionEvaluator.evaluateTemplate(raw, expressionContext)
              : raw;
          const version =
            typeof resolved === 'number' || typeof resolved === 'string'
              ? String(resolved).trim() === ''
                ? NaN
                : Number(resolved)
              : NaN;
          if (!Number.isSafeInteger(version) || version < 0) throw invalidRecord();
          expectedVersion = version;
        }
        const operationType =
          commandAction?.operationType ?? (actionType === 'state_transition' ? 'UPDATE' : 'update');
        if (!['update', 'delete'].includes(String(operationType).toLowerCase()))
          throw invalidRecord();
        let payload =
          commandAction?.payload !== undefined
            ? expressionEvaluator.evaluateObject(commandAction.payload, expressionContext)
            : { ...row };
        if (!payload || typeof payload !== 'object' || Array.isArray(payload))
          throw invalidRecord();
        const inputFields = Array.isArray((actionDef as any)?.inputFields)
          ? (actionDef as any).inputFields
          : Array.isArray((button as any).inputFields)
            ? (button as any).inputFields
            : [];
        if (inputFields.length > 0) {
          let collectedInputs: Record<string, any>;
          try {
            collectedInputs = await promptInputForm(
              inputFields,
              (actionDef as any)?.inputFieldsTitle ?? (button as any).inputFieldsTitle,
              fetchResult,
              (actionDef as any)?.inputFieldsSubmitLabel ?? (button as any).inputFieldsSubmitLabel,
              expressionContext,
              { t, locale },
              (actionDef as any)?.inputFieldsPresentation ??
                (button as any).inputFieldsPresentation,
              undefined,
              submissionFeedback,
            );
          } catch (error) {
            if (error instanceof ActionCancelledError) return;
            throw error;
          }
          payload = { ...payload, ...collectedInputs };
        }
        const result = await fetchResult(`/api/meta/commands/execute/${command}`, {
          method: 'post',
          params: {
            ...buildCommandTargetParams(rowPid),
            payload,
            operationType: String(operationType),
            ...(expectedVersion !== undefined ? { expectedVersion } : {}),
            ...(commandAction?.idempotent === true ? { clientRequestId: crypto.randomUUID() } : {}),
          },
          token,
        });

        if (!ResultHelper.isSuccess(result)) {
          throw commandExecutionError(result, command, t, locale);
        }

        submissionFeedback.completeResult(result);
        if (submissionFeedback.getState().phase === 'failed') return;
        reloadRows();
        onDataChange?.();
      } catch (err) {
        submissionFeedback.fail(err);
        setEditErrors({
          _form: err instanceof Error ? err.message : `Command ${command} failed`,
        });
      } finally {
        submissionFeedback.finalize();
        setRowActionLoadingKey(null);
      }
    },
    [
      hasPermission,
      locale,
      navigate,
      onDataChange,
      parentRecordData,
      reloadRows,
      resolveNavigatePath,
      resolveRowActionCommand,
      t,
      token,
      setEditErrors,
      setRowActionLoadingKey,
    ],
  );
  return { resolveButtonLabel, isRowActionVisible, isRowActionDisabled, handleRowAction };
}
