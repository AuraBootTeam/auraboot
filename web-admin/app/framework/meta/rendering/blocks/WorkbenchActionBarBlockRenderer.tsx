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

  return (
    <div
      className="flex flex-wrap items-center justify-end gap-2 rounded-md border border-gray-200 bg-white p-3"
      data-testid="workbench-action-bar"
    >
      {visibleActions.map((actionConfig: any) => {
        const code = String(actionConfig.code || actionConfig.id || actionConfig.label);
        const label = getLocalizedText(actionConfig.label || code, locale, t);
        const variant = actionConfig.variant || 'secondary';
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
            className={`rounded-md px-3 py-2 text-sm font-medium ${
              variantClass[variant] || variantClass.secondary
            } disabled:cursor-not-allowed disabled:opacity-50`}
          >
            {runningAction === code ? t('common.loading') : label}
          </button>
        );
      })}
    </div>
  );
};

export default WorkbenchActionBarBlockRenderer;
