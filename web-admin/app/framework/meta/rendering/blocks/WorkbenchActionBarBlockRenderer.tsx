import React, { useState } from 'react';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { resolveConfirmDialog } from '~/framework/meta/utils/i18nResolver';
import {
  executeSimpleWorkbenchAction,
  readPath,
  useRuntimeStateSubscription,
} from './workbenchBlockUtils';
import { LoadingOverlay } from '~/ui/LoadingOverlay';
import { confirmDialog } from '~/utils/confirmDialog';
import { useAuth } from '~/contexts/AuthContext';

export interface WorkbenchActionBarBlockRendererProps {
  block: BlockConfig;
  runtime: SchemaRuntime;
}

const variantClass: Record<string, string> = {
  primary: 'bg-accent text-white hover:bg-accent-hover',
  secondary: 'border border-border bg-panel text-text-2 hover:bg-hover',
  danger: 'bg-status-red text-white hover:opacity-90',
  ghost: 'bg-transparent text-text-2 hover:bg-hover',
};

const activeVariantClass: Record<string, string> = {
  primary: 'ring-2 ring-accent ring-offset-1',
  secondary: 'border-accent bg-accent-weak text-accent ring-2 ring-accent ring-offset-1',
  danger: 'ring-2 ring-status-red ring-offset-1',
  ghost: 'bg-accent-weak text-accent ring-2 ring-accent ring-offset-1',
};

export const WorkbenchActionBarBlockRenderer: React.FC<WorkbenchActionBarBlockRendererProps> = ({
  block,
  runtime,
}) => {
  const context = runtime.getContext();
  const locale = context.locale || 'zh-CN';
  const t = context.t || ((key: string) => key);
  const evaluator = runtime.getEvaluator();
  const { hasPermission } = useAuth();
  const actions = Array.isArray((block as any).actions) ? (block as any).actions : [];
  const [runningAction, setRunningAction] = useState<string | null>(null);
  const [resultReceipt, setResultReceipt] = useState<{ config: any; data: any } | null>(null);
  useRuntimeStateSubscription(runtime);

  const visibleActions = actions.filter((actionConfig: any) => {
    if (actionConfig?.permissionCode && !hasPermission(actionConfig.permissionCode)) return false;
    if (!actionConfig?.visibleWhen) return true;
    return evaluator.evaluateCondition(actionConfig.visibleWhen, context);
  });

  if (visibleActions.length === 0) {
    return null;
  }

  const surface =
    (block as any).surface || ((block as any).detailPlacement === 'header' ? 'bare' : 'card');
  const density = (block as any).density || 'default';
  const align = (block as any).align || 'end';
  const alignClass =
    align === 'start' ? 'justify-start' : align === 'center' ? 'justify-center' : 'justify-end';
  const title = block.title ? getLocalizedText(block.title, locale, t) : '';
  const surfaceClass =
    surface === 'bare'
      ? 'flex flex-wrap items-center gap-2'
      : 'flex flex-wrap items-center gap-2 rounded-control border border-border bg-panel p-3';
  const buttonSizeClass = density === 'compact' ? 'px-2.5 py-1.5 text-xs' : 'px-3 py-2 text-sm';

  const actionButtons = visibleActions.map((actionConfig: any) => {
    const code = String(actionConfig.code || actionConfig.id || actionConfig.label);
    const label = getLocalizedText(actionConfig.label || code, locale, t);
    const feedback =
      actionConfig.onClick?.args?.feedback || actionConfig.onClick?.args?.resultFeedback || {};
    const loadingLabel = getLocalizedText(
      feedback.loadingLabel || t('common.loading') || 'Loading...',
      locale,
      t,
    );
    const variant = actionConfig.variant || 'secondary';
    const active = actionConfig.activeWhen
      ? evaluator.evaluateCondition(actionConfig.activeWhen, context)
      : false;
    const disabledByCondition = actionConfig.disabledWhen
      ? evaluator.evaluateCondition(actionConfig.disabledWhen, context)
      : false;
    const disabled = Boolean(disabledByCondition || runningAction);
    const runAction = async () => {
      if (actionConfig.confirm) {
        const confirmation =
          typeof actionConfig.confirm === 'object'
            ? {
                title: t('common.confirm') || 'Confirm',
                content: getLocalizedText(actionConfig.confirm, locale, t),
              }
            : resolveConfirmDialog(actionConfig.confirm, t);
        const confirmed = await confirmDialog({
          ...confirmation,
          variant: variant === 'danger' ? 'danger' : 'default',
        });
        if (!confirmed) return;
      }

      setRunningAction(code);
      try {
        const result = await executeSimpleWorkbenchAction(runtime, actionConfig.onClick);
        const receiptConfig = actionConfig.onClick?.args?.resultReceipt;
        if (receiptConfig && result && result.success !== false && result.applied !== false) {
          setResultReceipt({ config: receiptConfig, data: result });
        }
      } catch (error) {
        console.error('[WorkbenchActionBarBlockRenderer] action failed:', error);
      } finally {
        setRunningAction(null);
      }
    };

    return (
      <button
        key={code}
        type="button"
        data-testid={`workbench-action-${code}`}
        disabled={disabled}
        onClick={() => {
          void runAction();
        }}
        className={`rounded-control font-medium ${
          variantClass[variant] || variantClass.secondary
        } ${active ? activeVariantClass[variant] || activeVariantClass.secondary : ''} ${
          buttonSizeClass
        } disabled:cursor-not-allowed disabled:opacity-50`}
      >
        {runningAction === code ? loadingLabel : label}
      </button>
    );
  });

  const actionBar = title ? (
    <div className={`${surfaceClass} justify-between`} data-testid="workbench-action-bar">
      <h3 className="text-text text-base font-semibold">{title}</h3>
      <div className={`flex flex-wrap items-center gap-2 ${alignClass}`}>{actionButtons}</div>
    </div>
  ) : (
    <div className={`${surfaceClass} ${alignClass}`} data-testid="workbench-action-bar">
      {actionButtons}
    </div>
  );

  const receiptLinks = resultReceipt
    ? (Array.isArray(resultReceipt.config?.links) ? resultReceipt.config.links : [])
        .map((link: any) => {
          const value = readPath(resultReceipt.data, String(link.resultField || link.field || ''));
          if (value === undefined || value === null || value === '') return null;
          const path = String(link.to || '').replace(
            /\$\{value\}/g,
            encodeURIComponent(String(value)),
          );
          if (!path) return null;
          return {
            key: String(link.key || link.resultField || path),
            label: getLocalizedText(link.label || link.key || path, locale, t),
            path,
          };
        })
        .filter(Boolean)
    : [];

  return (
    <>
      <LoadingOverlay visible={runningAction !== null} label={t('common.loading')} />
      {actionBar}
      {resultReceipt ? (
        <section
          className="border-status-green bg-status-green-bg text-status-green mt-3 rounded-lg border px-4 py-3"
          data-testid="workbench-result-receipt"
          role="status"
        >
          <div className="flex items-start justify-between gap-3">
            <div className="min-w-0">
              <h4 className="text-sm font-semibold">
                {getLocalizedText(resultReceipt.config.title || 'Completed', locale, t)}
              </h4>
              {resultReceipt.config.description ? (
                <p className="mt-1 text-xs opacity-80">
                  {getLocalizedText(resultReceipt.config.description, locale, t)}
                </p>
              ) : null}
            </div>
            <button
              type="button"
              className="shrink-0 rounded px-2 py-1 text-xs font-medium hover:bg-white/60"
              onClick={() => setResultReceipt(null)}
              aria-label={t('action.close') !== 'action.close' ? t('action.close') : 'Close'}
            >
              ×
            </button>
          </div>
          {receiptLinks.length > 0 ? (
            <div className="mt-3 flex flex-wrap gap-2">
              {receiptLinks.map((link: any) => (
                <button
                  key={link.key}
                  type="button"
                  className="rounded-control border-status-green bg-panel hover:bg-hover border px-3 py-1.5 text-xs font-medium"
                  onClick={() => runtime.navigateTo(link.path)}
                >
                  {link.label}
                </button>
              ))}
            </div>
          ) : null}
        </section>
      ) : null}
    </>
  );
};

export default WorkbenchActionBarBlockRenderer;
