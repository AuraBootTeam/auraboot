import React from 'react';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import {
  executeSimpleWorkbenchAction,
  readDataSourceRecord,
  readDataSourceState,
  readPath,
  useDataSourceSubscription,
  useRuntimeStateSubscription,
} from './workbenchBlockUtils';

export interface MetricStripBlockRendererProps {
  block: BlockConfig;
  runtime: SchemaRuntime;
}

const toneClass: Record<string, string> = {
  green: 'border-emerald-200 bg-emerald-50 text-emerald-900',
  amber: 'border-amber-200 bg-amber-50 text-amber-900',
  red: 'border-rose-200 bg-rose-50 text-rose-900',
  blue: 'border-blue-200 bg-blue-50 text-blue-900',
  purple: 'border-violet-200 bg-violet-50 text-violet-900',
  default: 'border-gray-200 bg-white text-gray-900',
};

const activeToneClass: Record<string, string> = {
  green: 'ring-2 ring-emerald-300 ring-offset-1',
  amber: 'ring-2 ring-amber-300 ring-offset-1',
  red: 'ring-2 ring-rose-300 ring-offset-1',
  blue: 'ring-2 ring-blue-300 ring-offset-1',
  purple: 'ring-2 ring-violet-300 ring-offset-1',
  default: 'ring-2 ring-gray-300 ring-offset-1',
};

function mappedMetricValue(
  value: any,
  metric: any,
  locale: string,
  t: (key: string) => string,
): string {
  const valueMap = metric?.valueMap;
  if (valueMap && typeof valueMap === 'object') {
    const keys = [
      value === undefined || value === null || value === '' ? '__empty' : String(value),
      typeof value === 'boolean' ? String(value) : undefined,
    ].filter(Boolean) as string[];
    for (const key of keys) {
      if (Object.prototype.hasOwnProperty.call(valueMap, key)) {
        return getLocalizedText(valueMap[key], locale, t);
      }
    }
  }
  if (typeof value === 'boolean') {
    const fallback = value ? { 'zh-CN': '是', en: 'Yes' } : { 'zh-CN': '否', en: 'No' };
    return getLocalizedText(fallback, locale, t);
  }
  if (value === null || value === undefined || value === '') return '-';
  return String(value);
}

function renderMetricAuxText(value: any, locale: string, t: (key: string) => string): string {
  if (value === null || value === undefined || value === '') return '';
  if (typeof value === 'object') return getLocalizedText(value, locale, t);
  return String(value);
}

export const MetricStripBlockRenderer: React.FC<MetricStripBlockRendererProps> = ({
  block,
  runtime,
}) => {
  const context = runtime.getContext();
  const locale = context.locale || 'zh-CN';
  const t = context.t || ((key: string) => key);
  const dataSourceId = typeof block.dataSource === 'string' ? block.dataSource : undefined;
  useDataSourceSubscription(runtime, dataSourceId);
  useRuntimeStateSubscription(runtime);
  const dataSourceState = readDataSourceState(runtime, dataSourceId);
  const record = readDataSourceRecord(runtime, dataSourceId);
  const metrics = Array.isArray((block as any).metrics) ? (block as any).metrics : [];
  const variant = (block as any).variant || 'cards';
  const configuredColumns = Number((block as any).columns);
  const cardColumns =
    Number.isFinite(configuredColumns) && configuredColumns > 0
      ? Math.min(Math.max(Math.floor(configuredColumns), 1), 12)
      : undefined;
  const title = block.title ? getLocalizedText(block.title, locale, t) : '';
  const evaluator = runtime.getEvaluator();

  if (dataSourceState?.error) {
    const message =
      dataSourceState.error instanceof Error
        ? dataSourceState.error.message
        : String(dataSourceState.error);
    return (
      <div
        role="alert"
        data-testid="metric-strip-error"
        className="rounded-control bg-status-red-bg text-status-red border border-red-200 p-3 text-sm"
      >
        {message || 'Failed to load metrics'}
      </div>
    );
  }

  if (dataSourceState?.loading && !dataSourceState.data) {
    return (
      <div
        data-testid="metric-strip-loading"
        className="rounded-control border-border bg-panel text-text-2 border p-3 text-sm"
      >
        {t('common.loading') !== 'common.loading' ? t('common.loading') : 'Loading...'}
      </div>
    );
  }

  if (metrics.length === 0) {
    return (
      <div
        className="rounded-control border-border bg-panel text-text-2 border p-3 text-sm"
        data-testid="metric-strip-empty"
      >
        {t('common.noData') !== 'common.noData' ? t('common.noData') : 'No data'}
      </div>
    );
  }

  const metricItems = metrics
    .filter((metric: any) => {
      return !metric.visibleWhen || evaluator.evaluateCondition(metric.visibleWhen, context);
    })
    .map((metric: any) => {
      const key = String(metric.key || metric.valueField || metric.label);
      const label = getLocalizedText(metric.label || key, locale, t);
      const value = metric.valueField ? readPath(record, metric.valueField) : metric.value;
      const unit = metric.unitField ? readPath(record, metric.unitField) : metric.unit;
      const subText = metric.subTextField ? readPath(record, metric.subTextField) : metric.subText;
      const displaySubText = renderMetricAuxText(subText, locale, t);
      const tone = metric.tone || 'default';
      const clickable = Boolean(metric.onClick);
      const active = metric.activeWhen
        ? evaluator.evaluateCondition(metric.activeWhen, context)
        : false;
      const displayValue = mappedMetricValue(value, metric, locale, t);

      if (variant === 'chips') {
        return (
          <button
            key={key}
            type="button"
            data-testid={`metric-strip-item-${key}`}
            onClick={() => {
              void executeSimpleWorkbenchAction(runtime, metric.onClick).catch((error) => {
                console.error('[MetricStripBlockRenderer] action failed:', error);
              });
            }}
            disabled={!clickable}
            className={`rounded-pill inline-flex min-h-9 items-center gap-2 border px-3 py-1.5 text-sm ${
              toneClass[tone] || toneClass.default
            } ${active ? activeToneClass[tone] || activeToneClass.default : ''} ${
              clickable ? 'cursor-pointer hover:shadow-sm' : 'cursor-default'
            } ${metric.align === 'end' ? 'ml-auto' : ''}`}
          >
            <span className="font-medium">{label}</span>
            <span className="rounded-pill bg-white/70 px-2 py-0.5 text-xs font-semibold">
              {displayValue}
              {unit ? ` ${String(unit)}` : ''}
            </span>
          </button>
        );
      }

      return (
        <button
          key={key}
          type="button"
          data-testid={`metric-strip-item-${key}`}
          onClick={() => {
            void executeSimpleWorkbenchAction(runtime, metric.onClick).catch((error) => {
              console.error('[MetricStripBlockRenderer] action failed:', error);
            });
          }}
          disabled={!clickable}
          className={`rounded-card h-28 min-h-28 overflow-hidden border p-3 text-left shadow-sm ${toneClass[tone] || toneClass.default} ${
            active ? activeToneClass[tone] || activeToneClass.default : ''
          } ${clickable ? 'cursor-pointer hover:shadow' : 'cursor-default'}`}
        >
          <div className="text-text-2 truncate text-xs font-medium" title={label}>
            {label}
          </div>
          <div className="mt-1 flex min-w-0 items-baseline gap-1 overflow-hidden">
            <span
              className="min-w-0 truncate text-2xl font-semibold"
              data-testid={`metric-strip-value-${key}`}
              title={displayValue}
            >
              {displayValue}
            </span>
            {unit && <span className="text-text-2 flex-shrink-0 text-xs">{String(unit)}</span>}
          </div>
          {displaySubText && (
            <div
              className="text-text-2 mt-1 line-clamp-2 text-xs break-words"
              data-testid={`metric-strip-subtext-${key}`}
              title={displaySubText}
            >
              {displaySubText}
            </div>
          )}
        </button>
      );
    });

  return (
    <section data-testid={`metric-strip-${block.id || 'block'}`}>
      {title && <h3 className="text-text-2 mb-2 text-sm font-medium">{title}</h3>}
      <div
        className={
          variant === 'chips'
            ? 'flex flex-wrap gap-2'
            : cardColumns
              ? 'grid items-stretch gap-3'
              : 'grid items-stretch gap-3 sm:grid-cols-2 lg:grid-cols-4 xl:grid-cols-6'
        }
        style={
          variant === 'cards' && cardColumns
            ? { gridTemplateColumns: `repeat(${cardColumns}, minmax(0, 1fr))` }
            : undefined
        }
        data-testid="metric-strip"
      >
        {metricItems}
      </div>
    </section>
  );
};

export default MetricStripBlockRenderer;
