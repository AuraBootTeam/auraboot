import React, { useState } from 'react';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import {
  executeSimpleWorkbenchAction,
  readDataSourceRows,
  readPath,
  useDataSourceSubscription,
  writeRuntimeState,
} from './workbenchBlockUtils';

export interface CandidateListBlockRendererProps {
  block: BlockConfig;
  runtime: SchemaRuntime;
}

export const CandidateListBlockRenderer: React.FC<CandidateListBlockRendererProps> = ({
  block,
  runtime,
}) => {
  const context = runtime.getContext();
  const locale = context.locale || 'zh-CN';
  const t = context.t || ((key: string) => key);
  const evaluator = runtime.getEvaluator();
  const dataSourceId = typeof block.dataSource === 'string' ? block.dataSource : undefined;
  useDataSourceSubscription(runtime, dataSourceId);
  const rows = readDataSourceRows(runtime, dataSourceId);
  const item = (block as any).item || {};
  const selection = (block as any).selection;
  const bindKey = selection?.bind;
  const actions = Array.isArray((block as any).actions) ? (block as any).actions : [];
  const [selectedKey, setSelectedKey] = useState<string | null>(null);

  if (rows.length === 0) {
    return (
      <div
        className="rounded-md border border-gray-200 bg-white p-4 text-sm text-gray-500"
        data-testid="candidate-list-empty"
      >
        {t('common.noData') !== 'common.noData' ? t('common.noData') : 'No data'}
      </div>
    );
  }

  return (
    <div className="space-y-3" data-testid="candidate-list">
      <div className="space-y-2">
        {rows.map((row: any, index: number) => {
          const rowKey = String(row.pid ?? row.id ?? index);
          const title = readPath(row, item.titleField) ?? rowKey;
          const subtitle = readPath(row, item.subtitleField);
          const description = readPath(row, item.descriptionField);
          const score = readPath(row, item.scoreField);
          const active = selectedKey === rowKey;

          return (
            <button
              key={rowKey}
              type="button"
              data-testid={`candidate-list-item-${rowKey}`}
              onClick={() => {
                setSelectedKey(rowKey);
                if (bindKey) writeRuntimeState(runtime, bindKey, row);
              }}
              className={`w-full rounded-lg border p-3 text-left ${
                active ? 'border-blue-400 bg-blue-50' : 'border-gray-200 bg-white hover:bg-gray-50'
              }`}
            >
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0">
                  <div className="font-mono text-sm font-semibold text-gray-900">{String(title)}</div>
                  {subtitle && <div className="mt-1 text-sm text-gray-700">{String(subtitle)}</div>}
                </div>
                {score !== undefined && (
                  <span className="rounded-full bg-emerald-50 px-2 py-1 text-xs text-emerald-700">
                    {String(score)}
                  </span>
                )}
              </div>
              {description && <div className="mt-2 text-sm text-gray-600">{String(description)}</div>}
            </button>
          );
        })}
      </div>
      {actions.length > 0 && (
        <div className="flex flex-wrap gap-2 border-t border-gray-100 pt-3">
          {actions.map((actionConfig: any) => {
            if (actionConfig.visibleWhen) {
              const visible = evaluator.evaluateCondition(actionConfig.visibleWhen, context);
              if (!visible) return null;
            }
            const disabled =
              !selectedKey ||
              (actionConfig.disabledWhen
                ? evaluator.evaluateCondition(actionConfig.disabledWhen, context)
                : false);
            const label = getLocalizedText(actionConfig.label || actionConfig.code, locale, t);

            return (
              <button
                key={actionConfig.code}
                type="button"
                data-testid={`candidate-list-action-${actionConfig.code}`}
                disabled={disabled}
                onClick={() => {
                  void executeSimpleWorkbenchAction(runtime, actionConfig.onClick).catch((error) => {
                    console.error('[CandidateListBlockRenderer] action failed:', error);
                  });
                }}
                className={`rounded-md px-3 py-2 text-sm font-medium ${
                  actionConfig.variant === 'secondary'
                    ? 'border border-gray-300 bg-white text-gray-700 hover:bg-gray-50'
                    : 'bg-blue-600 text-white hover:bg-blue-700'
                } disabled:cursor-not-allowed disabled:opacity-50`}
              >
                {label}
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
};

export default CandidateListBlockRenderer;
