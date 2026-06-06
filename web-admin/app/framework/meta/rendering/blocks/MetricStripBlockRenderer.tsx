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

  return (
    <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4 xl:grid-cols-6" data-testid="metric-strip">
      {metrics.map((metric: any) => {
        const key = String(metric.key || metric.valueField || metric.label);
        const label = getLocalizedText(metric.label || key, locale, t);
        const value = metric.valueField ? readPath(record, metric.valueField) : metric.value;
        const unit = metric.unitField ? readPath(record, metric.unitField) : metric.unit;
        const subText = metric.subTextField ? readPath(record, metric.subTextField) : metric.subText;
        const tone = metric.tone || 'default';
        const clickable = Boolean(metric.onClick);

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
              clickable ? 'cursor-pointer hover:shadow' : 'cursor-default'
            }`}
          >
            <div className="text-xs font-medium text-gray-500">{label}</div>
            <div className="mt-1 flex items-baseline gap-1">
              <span className="text-2xl font-semibold">{value ?? '-'}</span>
              {unit && <span className="text-xs text-gray-500">{String(unit)}</span>}
            </div>
            {subText && <div className="mt-1 text-xs text-gray-500">{String(subText)}</div>}
          </button>
        );
      })}
    </div>
  );
};

export default MetricStripBlockRenderer;
