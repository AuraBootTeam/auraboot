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

function formatCandidateValue(value: unknown): string {
  if (value === undefined || value === null || value === '') return '-';
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}

function readCandidateDetail(row: any, fieldConfig: any): unknown {
  const source = fieldConfig.sourceField ? readPath(row, fieldConfig.sourceField) : row;
  return readPath(source, fieldConfig.field);
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
  const detailFields = Array.isArray(item.detailFields) ? item.detailFields : [];
  const selection = (block as any).selection;
  const bindKey = selection?.bind;
  const actions = Array.isArray((block as any).actions) ? (block as any).actions : [];
  const maxHeight = (block as any).maxHeight || item.maxHeight;
  const containerStyle =
    maxHeight === undefined
      ? undefined
      : {
          maxHeight: typeof maxHeight === 'number' ? `${maxHeight}px` : String(maxHeight),
        };
  const [selectedKey, setSelectedKey] = useState<string | null>(null);

  if (rows.length === 0) {
    return (
      <div
        className="rounded-control border-border bg-panel text-text-2 border p-4 text-sm"
        data-testid="candidate-list-empty"
      >
        {t('common.noData') !== 'common.noData' ? t('common.noData') : 'No data'}
      </div>
    );
  }

  return (
    <div
      className={`candidate-list flex flex-col gap-3 ${maxHeight ? 'min-h-0' : ''}`}
      data-testid="candidate-list"
      style={containerStyle}
    >
      <div className={`space-y-2 ${maxHeight ? 'min-h-0 flex-1 overflow-y-auto pr-1' : ''}`}>
        {rows.map((row: any, index: number) => {
    const rowKey = String(row.pid ?? index);
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
              className={`rounded-card w-full border p-3 text-left ${
                active ? 'bg-accent-weak border-blue-400' : 'border-border bg-panel hover:bg-subtle'
              }`}
            >
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0">
                  <div className="text-text font-mono text-sm font-semibold">{String(title)}</div>
                  {subtitle && <div className="text-text-2 mt-1 text-sm">{String(subtitle)}</div>}
                </div>
                {score !== undefined && (
                  <span className="rounded-full bg-emerald-50 px-2 py-1 text-xs text-emerald-700">
                    {String(score)}
                  </span>
                )}
              </div>
              {detailFields.length > 0 ? (
                <dl className="mt-3 grid grid-cols-1 gap-2 text-xs sm:grid-cols-2">
                  {detailFields.map((fieldConfig: any, fieldIndex: number) => {
                    const fieldKey = String(fieldConfig.key || fieldConfig.field || fieldIndex);
                    const label = getLocalizedText(fieldConfig.label || fieldKey, locale, t);
                    const value = formatCandidateValue(readCandidateDetail(row, fieldConfig));
                    const fullWidth = fieldConfig.span === 2 || fieldConfig.layout?.colSpan >= 12;
                    return (
                      <div
                        key={fieldKey}
                        className={fullWidth ? 'sm:col-span-2' : undefined}
                        data-testid={`candidate-list-item-${rowKey}-field-${fieldKey}`}
                      >
                        <dt className="text-text-2">{label}</dt>
                        <dd className="text-text mt-0.5 break-words">{value}</dd>
                      </div>
                    );
                  })}
                </dl>
              ) : (
                description && (
                  <div className="text-text-2 mt-2 text-sm break-words">{String(description)}</div>
                )
              )}
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
                  void executeSimpleWorkbenchAction(runtime, actionConfig.onClick).catch(
                    (error) => {
                      console.error('[CandidateListBlockRenderer] action failed:', error);
                    },
                  );
                }}
                className={`rounded-control px-3 py-2 text-sm font-medium ${
                  actionConfig.variant === 'secondary'
                    ? 'border-border-strong bg-panel text-text-2 hover:bg-subtle border'
                    : 'bg-accent hover:bg-accent-hover text-white'
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
