import React, { useEffect, useMemo, useState } from 'react';
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

function formatInlineValue(value: unknown): string {
  if (value === undefined || value === null) return '';
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}

function readCandidateDetail(row: any, fieldConfig: any): unknown {
  const source = fieldConfig.sourceField ? readPath(row, fieldConfig.sourceField) : row;
  return readPath(source, fieldConfig.field);
}

function isPresent(value: unknown): boolean {
  return value !== undefined && value !== null && String(value).trim() !== '';
}

function renderTemplate(
  template: any,
  row: any,
  context: any,
  locale: string,
  t: (key: string) => string,
): string {
  const text = getLocalizedText(template, locale, t);
  if (!text || typeof text !== 'string') return formatInlineValue(text);
  const templateContext = { ...context, row, record: row };
  return text.replace(/\$\{([^}]+)\}/g, (_match, expr) => {
    return formatInlineValue(readPath(templateContext, String(expr).trim()));
  });
}

function readFirstConfiguredPath(row: any, paths: unknown): unknown {
  if (!Array.isArray(paths)) return undefined;
  for (const path of paths) {
    if (typeof path !== 'string') continue;
    const value = readPath(row, path);
    if (isPresent(value)) return value;
  }
  return undefined;
}

function resolveRuleText(
  row: any,
  rules: unknown,
  evaluator: ReturnType<SchemaRuntime['getEvaluator']>,
  context: any,
  locale: string,
  t: (key: string) => string,
): string | undefined {
  if (!Array.isArray(rules)) return undefined;
  const rowContext = { ...context, row, record: row };
  for (const rule of rules) {
    if (!rule || typeof rule !== 'object') continue;
    const config = rule as any;
    const matched = config.when ? evaluator.evaluateCondition(config.when, rowContext) : true;
    if (matched) {
      return renderTemplate(config.text ?? config.label ?? config.value, row, context, locale, t);
    }
  }
  return undefined;
}

function pathFromUrl(value: unknown): string | undefined {
  if (!isPresent(value)) return undefined;
  try {
    const url = new URL(String(value));
    return `${url.pathname}${url.search}`;
  } catch {
    return String(value);
  }
}

function resolveMappedLabel(
  value: unknown,
  valueMap: Record<string, any> | undefined,
  locale: string,
  t: (key: string) => string,
): string {
  if (!isPresent(value)) return '-';
  const key = String(value);
  const mapped = valueMap?.[key];
  return String(getLocalizedText(mapped ?? key, locale, t));
}

const statusToneClass: Record<string, string> = {
  default: 'border-status-gray bg-status-gray-bg text-status-gray',
  gray: 'border-status-gray bg-status-gray-bg text-status-gray',
  blue: 'border-status-blue bg-status-blue-bg text-status-blue',
  green: 'border-status-green bg-status-green-bg text-status-green',
  amber: 'border-status-amber bg-status-amber-bg text-status-amber',
  red: 'border-status-red bg-status-red-bg text-status-red',
  purple: 'border-status-blue bg-status-blue-bg text-status-blue',
};

const statusDotClass: Record<string, string> = {
  default: 'bg-status-gray',
  gray: 'bg-status-gray',
  blue: 'bg-status-blue',
  green: 'bg-status-green',
  amber: 'bg-status-amber',
  red: 'bg-status-red',
  purple: 'bg-status-blue',
};

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
  const rowKeyField = item.rowKey || item.keyField || selection?.rowKey || 'pid';
  const actions = Array.isArray((block as any).actions) ? (block as any).actions : [];
  const maxHeight = (block as any).maxHeight || item.maxHeight;
  const containerStyle =
    maxHeight === undefined
      ? undefined
      : {
          maxHeight: typeof maxHeight === 'number' ? `${maxHeight}px` : String(maxHeight),
        };
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const stateRecord = bindKey ? readPath(context.state || {}, bindKey) : undefined;
  const stateRecordKey = stateRecord ? String(readPath(stateRecord, rowKeyField) ?? '') : '';
  const effectiveSelectedKey = stateRecordKey || selectedKey || null;
  const rowKeySet = useMemo(() => {
    const keys = new Set<string>();
    rows.forEach((row: any, index: number) => {
      keys.add(String(readPath(row, rowKeyField) ?? index));
    });
    return keys;
  }, [rowKeyField, rows]);
  const rowTitleCache = useMemo(() => {
    const map = new Map<string, string>();
    rows.forEach((row: any, index: number) => {
      const rowKey = String(readPath(row, rowKeyField) ?? index);
      const ruleTitle = resolveRuleText(row, item.titleRules, evaluator, context, locale, t);
      const templateTitle = item.titleTemplate
        ? renderTemplate(item.titleTemplate, row, context, locale, t)
        : undefined;
      const fieldTitle = readPath(row, item.titleField);
      const fallbackTitle = readFirstConfiguredPath(row, item.titleFallbackFields);
      map.set(rowKey, String(ruleTitle || templateTitle || fieldTitle || fallbackTitle || rowKey));
    });
    return map;
  }, [context, evaluator, item, locale, rowKeyField, rows, t]);

  useEffect(() => {
    if (!bindKey) return;

    if (rows.length === 0) {
      if (selectedKey !== null) setSelectedKey(null);
      if (stateRecord) writeRuntimeState(runtime, bindKey, null);
      return;
    }

    if (stateRecordKey && rowKeySet.has(stateRecordKey)) {
      if (selectedKey !== stateRecordKey) setSelectedKey(stateRecordKey);
      return;
    }

    if (selectedKey && rowKeySet.has(selectedKey) && !stateRecord) return;

    if (selection?.defaultFirst) {
      const firstRow = rows[0];
      const key = String(readPath(firstRow, rowKeyField) ?? 0);
      if (selectedKey !== key) setSelectedKey(key);
      if (stateRecordKey !== key) writeRuntimeState(runtime, bindKey, firstRow);
      return;
    }

    if (selectedKey !== null) setSelectedKey(null);
    if (stateRecord) writeRuntimeState(runtime, bindKey, null);
  }, [
    bindKey,
    rowKeyField,
    rows,
    rowKeySet,
    runtime,
    selectedKey,
    selection?.defaultFirst,
    stateRecord,
    stateRecordKey,
  ]);

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
          const rowKey = String(readPath(row, rowKeyField) ?? index);
          const title = rowTitleCache.get(rowKey) ?? rowKey;
          const subtitle = readPath(row, item.subtitleField);
          const description = readPath(row, item.descriptionField);
          const reason = readPath(row, item.reasonField) ?? description;
          const domain = readPath(row, item.domainField);
          const path = item.pathFromUrlField
            ? pathFromUrl(readPath(row, item.pathFromUrlField))
            : readPath(row, item.pathField);
          const statusRaw = readPath(row, item.statusField);
          const statusLabel = item.statusField
            ? resolveMappedLabel(
                statusRaw,
                item.statusValueMap || item.statusLabelMap || item.valueMap,
                locale,
                t,
              )
            : undefined;
          const statusTone = String(
            item.statusToneMap?.[String(statusRaw)] || item.statusTone || 'default',
          );
          const score = readPath(row, item.scoreField);
          const active = effectiveSelectedKey === rowKey;

          return (
            <button
              key={rowKey}
              type="button"
              data-testid={`candidate-list-item-${rowKey}`}
              onClick={() => {
                setSelectedKey(rowKey);
                if (bindKey) writeRuntimeState(runtime, bindKey, row);
              }}
              aria-pressed={active}
              className={`rounded-card w-full border p-3 text-left transition ${
                active
                  ? 'bg-accent-weak border-accent shadow-card'
                  : 'border-border bg-panel hover:bg-subtle'
              }`}
            >
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0">
                  <div className="flex min-w-0 flex-wrap items-center gap-2">
                    <div className="text-text truncate text-sm font-semibold">{String(title)}</div>
                    {domain && (
                      <span className="rounded-pill border-border bg-subtle text-text-2 border px-2 py-0.5 text-xs font-medium">
                        {String(domain)}
                      </span>
                    )}
                  </div>
                  {subtitle && <div className="text-text-2 mt-1 text-sm">{String(subtitle)}</div>}
                </div>
                <div className="flex shrink-0 flex-col items-end gap-1">
                  {statusLabel && (
                    <span
                      className={`inline-flex items-center gap-1.5 rounded-full border px-2 py-1 text-xs font-medium ${statusToneClass[statusTone] || statusToneClass.default}`}
                    >
                      <span
                        className={`h-1.5 w-1.5 rounded-full ${statusDotClass[statusTone] || statusDotClass.default}`}
                      />
                      {statusLabel}
                    </span>
                  )}
                  {score !== undefined && (
                    <span className="rounded-pill bg-status-green-bg text-status-green px-2 py-1 text-xs">
                      {String(score)}
                    </span>
                  )}
                </div>
              </div>
              {path && (
                <div className="text-text-2 mt-2 truncate font-mono text-xs">{String(path)}</div>
              )}
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
                reason && (
                  <div className="text-text-2 mt-2 text-sm break-words">{String(reason)}</div>
                )
              )}
            </button>
          );
        })}
      </div>
      {actions.length > 0 && (
        <div className="border-border flex flex-wrap gap-2 border-t pt-3">
          {actions.map((actionConfig: any) => {
            if (actionConfig.visibleWhen) {
              const visible = evaluator.evaluateCondition(actionConfig.visibleWhen, context);
              if (!visible) return null;
            }
            const disabled =
              !effectiveSelectedKey ||
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
