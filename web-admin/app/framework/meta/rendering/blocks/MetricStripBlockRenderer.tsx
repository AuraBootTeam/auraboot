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

function mappedMetricValue(value: any, metric: any, locale: string, t: (key: string) => string): string {
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
    const fallback = value
      ? { 'zh-CN': '是', en: 'Yes' }
      : { 'zh-CN': '否', en: 'No' };
    return getLocalizedText(fallback, locale, t);
  }
  if (value === null || value === undefined || value === '') return '-';
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
  const dataSourceState = readDataSourceState(runtime, dataSourceId);
  const record = readDataSourceRecord(runtime, dataSourceId);
  const metrics = Array.isArray((block as any).metrics) ? (block as any).metrics : [];
  const variant = (block as any).variant || 'cards';
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
        className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-700"
      >
        {message || 'Failed to load metrics'}
      </div>
    );
  }

  if (dataSourceState?.loading && !dataSourceState.data) {
    return (
      <div
        data-testid="metric-strip-loading"
        className="rounded-md border border-gray-200 bg-white p-3 text-sm text-gray-500"
      >
        {t('common.loading') !== 'common.loading' ? t('common.loading') : 'Loading...'}
      </div>
    );
  }

  if (metrics.length === 0) {
    return (
      <div
        className="rounded-md border border-gray-200 bg-white p-3 text-sm text-gray-500"
        data-testid="metric-strip-empty"
      >
        {t('common.noData') !== 'common.noData' ? t('common.noData') : 'No data'}
      </div>
    );
  }

  const metricItems = metrics.map((metric: any) => {
    const key = String(metric.key || metric.valueField || metric.label);
    const label = getLocalizedText(metric.label || key, locale, t);
    const value = metric.valueField ? readPath(record, metric.valueField) : metric.value;
    const unit = metric.unitField ? readPath(record, metric.unitField) : metric.unit;
    const subText = metric.subTextField ? readPath(record, metric.subTextField) : metric.subText;
    const tone = metric.tone || 'default';
    const clickable = Boolean(metric.onClick);
    const active = metric.activeWhen ? evaluator.evaluateCondition(metric.activeWhen, context) : false;
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
          className={`inline-flex min-h-9 items-center gap-2 rounded-full border px-3 py-1.5 text-sm ${
            toneClass[tone] || toneClass.default
          } ${active ? activeToneClass[tone] || activeToneClass.default : ''} ${
            clickable ? 'cursor-pointer hover:shadow-sm' : 'cursor-default'
          }`}
        >
          <span className="font-medium">{label}</span>
          <span className="rounded-full bg-white/70 px-2 py-0.5 text-xs font-semibold">
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
        className={`min-h-20 rounded-lg border p-3 text-left shadow-sm ${toneClass[tone] || toneClass.default} ${
          active ? activeToneClass[tone] || activeToneClass.default : ''
        } ${clickable ? 'cursor-pointer hover:shadow' : 'cursor-default'
        }`}
      >
        <div className="text-xs font-medium text-gray-500">{label}</div>
        <div className="mt-1 flex items-baseline gap-1">
          <span className="text-2xl font-semibold">{displayValue}</span>
          {unit && <span className="text-xs text-gray-500">{String(unit)}</span>}
        </div>
        {subText && <div className="mt-1 text-xs text-gray-500">{String(subText)}</div>}
      </button>
    );
  });

  return (
    <section data-testid={`metric-strip-${block.id || 'block'}`}>
      {title && <h3 className="mb-2 text-sm font-medium text-gray-700">{title}</h3>}
      <div
        className={
          variant === 'chips'
            ? 'flex flex-wrap gap-2'
            : 'grid gap-3 sm:grid-cols-2 lg:grid-cols-4 xl:grid-cols-6'
        }
        data-testid="metric-strip"
      >
        {metricItems}
      </div>
    </section>
  );
};

export default MetricStripBlockRenderer;
