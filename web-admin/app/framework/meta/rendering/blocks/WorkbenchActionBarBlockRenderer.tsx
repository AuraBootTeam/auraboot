import React, { useState } from 'react';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { executeSimpleWorkbenchAction, useRuntimeStateSubscription } from './workbenchBlockUtils';

export interface WorkbenchActionBarBlockRendererProps {
  block: BlockConfig;
  runtime: SchemaRuntime;
}

const variantClass: Record<string, string> = {
  primary: 'bg-blue-600 text-white hover:bg-blue-700',
  secondary: 'border border-gray-300 bg-white text-gray-700 hover:bg-gray-50',
  danger: 'bg-rose-600 text-white hover:bg-rose-700',
  ghost: 'bg-transparent text-gray-700 hover:bg-gray-100',
};

const activeVariantClass: Record<string, string> = {
  primary: 'ring-2 ring-blue-300 ring-offset-1',
  secondary: 'border-blue-500 bg-blue-50 text-blue-700 ring-2 ring-blue-200 ring-offset-1',
  danger: 'ring-2 ring-rose-300 ring-offset-1',
  ghost: 'bg-blue-50 text-blue-700 ring-2 ring-blue-200 ring-offset-1',
};

export const WorkbenchActionBarBlockRenderer: React.FC<WorkbenchActionBarBlockRendererProps> = ({
  block,
  runtime,
}) => {
  const context = runtime.getContext();
  const locale = context.locale || 'zh-CN';
  const t = context.t || ((key: string) => key);
  const evaluator = runtime.getEvaluator();
  const actions = Array.isArray((block as any).actions) ? (block as any).actions : [];
  const [runningAction, setRunningAction] = useState<string | null>(null);
  useRuntimeStateSubscription(runtime);

  const visibleActions = actions.filter((actionConfig: any) => {
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
    const variant = actionConfig.variant || 'secondary';
    const active = actionConfig.activeWhen
      ? evaluator.evaluateCondition(actionConfig.activeWhen, context)
      : false;
    const disabledByCondition = actionConfig.disabledWhen
      ? evaluator.evaluateCondition(actionConfig.disabledWhen, context)
      : false;
    const disabled = Boolean(disabledByCondition || runningAction);

    return (
      <button
        key={code}
        type="button"
        data-testid={`workbench-action-${code}`}
        disabled={disabled}
        onClick={() => {
          setRunningAction(code);
          void executeSimpleWorkbenchAction(runtime, actionConfig.onClick)
            .catch((error) => {
              console.error('[WorkbenchActionBarBlockRenderer] action failed:', error);
            })
            .finally(() => setRunningAction(null));
        }}
        className={`rounded-control font-medium ${
          variantClass[variant] || variantClass.secondary
        } ${active ? activeVariantClass[variant] || activeVariantClass.secondary : ''} ${
          buttonSizeClass
        } disabled:cursor-not-allowed disabled:opacity-50`}
      >
        {runningAction === code ? t('common.loading') : label}
      </button>
    );
  });

  if (title) {
    return (
      <div className={`${surfaceClass} justify-between`} data-testid="workbench-action-bar">
        <h3 className="text-text text-base font-semibold">{title}</h3>
        <div className={`flex flex-wrap items-center gap-2 ${alignClass}`}>{actionButtons}</div>
      </div>
    );
  }

  return (
    <div className={`${surfaceClass} ${alignClass}`} data-testid="workbench-action-bar">
      {actionButtons}
    </div>
  );
};

export default WorkbenchActionBarBlockRenderer;
