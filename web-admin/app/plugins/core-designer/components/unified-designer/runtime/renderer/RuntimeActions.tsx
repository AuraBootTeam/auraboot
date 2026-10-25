import type { DslBlockV3 } from '../../types';
import { normalizeRuntimeExecutionError, type RuntimeExecutionIssue } from '../runtimeExecution';
import { DESIGNER_I18N } from '~/shared/designer';
import React from 'react';
import {
  RuntimeFormValueContext,
  RuntimeListSelectionContext,
  RuntimeLocaleContext,
  RuntimePermissionContext,
  useRuntimeText,
  type RuntimeActionProps,
  type RuntimeActionTestIds,
} from './runtimeContexts';
import { isRuntimeBlockDisabled, isRuntimeBlockVisible } from './runtimeFormLogic';
import {
  getActionStatus,
  getBlockLabel,
  getRuntimeActionConditionValues,
  getRuntimeBlockContext,
  getRuntimePermissionCode,
  getStringProp,
  shouldExecuteLiveAction,
} from './runtimePresentation';





export function RuntimeAction({
  block,
  runtimeServices,
  pageContext,
  blockPath,
  currentRow,
  currentRowId,
  testIds,
}: RuntimeActionProps) {
  const formContext = React.useContext(RuntimeFormValueContext);
  const selectionContext = React.useContext(RuntimeListSelectionContext);
  const hasPermission = React.useContext(RuntimePermissionContext);
  const locale = React.useContext(RuntimeLocaleContext);
  const t = useRuntimeText();
  const [pendingConfirm, setPendingConfirm] = React.useState(false);
  const [status, setStatus] = React.useState('');
  const [error, setError] = React.useState<RuntimeExecutionIssue | null>(null);
  const [executing, setExecuting] = React.useState(false);
  const [overlayKind, setOverlayKind] = React.useState<'modal' | 'drawer' | null>(null);
  const actionType = block.actionType || 'command';
  const href = getStringProp(block.props?.to);
  const permissionCode = getRuntimePermissionCode(block);
  const permissionAllowed = permissionCode ? hasPermission(permissionCode) : true;
  const conditionValues = getRuntimeActionConditionValues({
    formValues: formContext?.values,
    currentRow,
    currentRowId,
  });
  const visibleByCondition = isRuntimeBlockVisible(block, conditionValues);
  const disabledByCondition = isRuntimeBlockDisabled(block, conditionValues);
  const liveExecution = Boolean(
    runtimeServices?.executeAction &&
      shouldExecuteLiveAction(block) &&
      (actionType === 'command' || actionType === 'workflow'),
  );

  const executeAction = () => {
    if (!permissionAllowed || disabledByCondition || !visibleByCondition) return;

    if (formContext && shouldValidateFormBeforeAction(block) && !formContext.validate()) {
      setPendingConfirm(false);
      setStatus('');
      setError(null);
      setOverlayKind(null);
      return;
    }

    if (Boolean(block.props?.confirm) && !pendingConfirm) {
      setPendingConfirm(true);
      setStatus('');
      setError(null);
      setOverlayKind(null);
      return;
    }

    setPendingConfirm(false);
    setError(null);
    if (actionType === 'modal' || actionType === 'drawer') {
      setOverlayKind(actionType);
      setStatus(getActionStatus(block));
      return;
    }

    setOverlayKind(null);
    if (liveExecution && runtimeServices?.executeAction) {
      setStatus('');
      setExecuting(true);
      void runtimeServices
        .executeAction(
          block,
          getRuntimeBlockContext(block, pageContext, blockPath, {
            formValues: formContext?.values,
            selectedRows: selectionContext?.selectedRows,
            selectedRowIds: selectionContext?.selectedRowIds,
            currentRow,
            currentRowId,
            permissionCode: permissionCode || undefined,
          }),
        )
        .then((result) => {
          setStatus(result.status);
        })
        .catch((executionError: unknown) => {
          setError(normalizeRuntimeExecutionError(executionError));
        })
        .finally(() => {
          setExecuting(false);
        });
      return;
    }

    setStatus(getActionStatus(block));
  };
  const actionTestIds = getRuntimeActionTestIds(block, testIds);
  if (!visibleByCondition) return null;

  const disabled = executing || !permissionAllowed || disabledByCondition;

  return (
    <div className="min-w-0" data-testid={actionTestIds.wrapper}>
      <button
        type="button"
        className={`rounded-md px-3 py-1.5 text-sm font-medium ${
          disabled
            ? 'cursor-not-allowed bg-slate-300 text-slate-500'
            : pendingConfirm
              ? 'bg-amber-600 text-white'
              : 'bg-blue-600 text-white'
        }`}
        data-testid={actionTestIds.button}
        data-action-type={actionType}
        data-live-execution={liveExecution ? 'true' : undefined}
        data-href={href || undefined}
        data-permission-code={permissionCode || undefined}
        data-permission-allowed={permissionCode ? String(permissionAllowed) : undefined}
        data-condition-disabled={disabledByCondition ? 'true' : undefined}
        disabled={disabled}
        onClick={executeAction}
      >
        {pendingConfirm
          ? t(DESIGNER_I18N.unified.runtime.confirm)
          : executing
          ? t(DESIGNER_I18N.unified.runtime.running)
          : getBlockLabel(block, locale)}
      </button>
      {permissionCode ? (
        <div
          className={`mt-1 max-w-56 truncate text-xs ${
            permissionAllowed ? 'text-slate-500' : 'font-medium text-amber-700'
          }`}
          data-testid={actionTestIds.permission}
          data-permission-allowed={String(permissionAllowed)}
        >
          {permissionAllowed
            ? `Permission: ${permissionCode}`
            : `Requires permission: ${permissionCode}`}
        </div>
      ) : null}
      {pendingConfirm ? (
        <div
          className="mt-1 text-xs font-medium text-amber-700"
          data-testid={actionTestIds.confirm}
        >
          Click again to confirm
        </div>
      ) : null}
      {status ? (
        <div
          className="mt-1 max-w-56 truncate text-xs text-slate-500"
          data-testid={actionTestIds.status}
        >
          {status}
        </div>
      ) : null}
      {error ? (
        <div
          className="mt-1 max-w-56 truncate text-xs font-medium text-red-600"
          data-testid={actionTestIds.error}
          data-error-kind={error.kind}
          data-error-code={error.code || undefined}
        >
          {error.message}
          {error.hint ? (
            <div
              className="font-normal text-red-500"
              data-testid={actionTestIds.errorHint}
            >
              {error.hint}
            </div>
          ) : null}
        </div>
      ) : null}
      {overlayKind ? (
        <RuntimeActionOverlay block={block} kind={overlayKind} testId={actionTestIds.overlay} />
      ) : null}
    </div>
  );
}

function getRuntimeActionTestIds(
  block: DslBlockV3,
  overrides?: Partial<RuntimeActionTestIds>,
): RuntimeActionTestIds {
  return {
    wrapper: `runtime-action-wrapper-${block.id}`,
    button: `runtime-action-${block.id}`,
    permission: `runtime-action-permission-${block.id}`,
    confirm: `runtime-action-confirm-${block.id}`,
    status: `runtime-action-status-${block.id}`,
    error: `runtime-action-error-${block.id}`,
    errorHint: `runtime-action-error-hint-${block.id}`,
    overlay: `runtime-action-overlay-${block.id}`,
    ...overrides,
  };
}

function RuntimeActionOverlay({
  block,
  kind,
  testId,
}: {
  block: DslBlockV3;
  kind: 'modal' | 'drawer';
  testId: string;
}) {
  const title = getStringProp(block.props?.title) || getBlockLabel(block);
  const pageKey = getStringProp(block.props?.pageKey);

  return (
    <div
      className={`mt-2 rounded-md border bg-white p-3 text-xs text-slate-600 shadow-sm ${
        kind === 'drawer' ? 'border-blue-200' : 'border-slate-200'
      }`}
      data-testid={testId}
      data-overlay-kind={kind}
    >
      <div className="font-semibold text-slate-900">{title}</div>
      {pageKey ? <div className="mt-1 font-mono text-slate-500">{pageKey}</div> : null}
    </div>
  );
}

function shouldValidateFormBeforeAction(block: DslBlockV3): boolean {
  if (block.props?.validateForm === false) return false;
  const actionType = block.actionType || 'command';
  return actionType === 'submit' || actionType === 'create' || actionType === 'command' || actionType === 'workflow';
}
