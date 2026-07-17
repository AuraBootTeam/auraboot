import React from 'react';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { resolveIcon, hasIcon } from '~/utils/icon-resolver';
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

// Design-system semantic tones (§1.3). Cards are white-surfaced (bg-panel) per
// §1.2 "no large tinted fills"; the tone tints only the icon chip and the active
// ring, so the strip reads as a set of quiet metric cards, not color blocks.
interface ToneToken {
  icon: string; // text color for the icon glyph
  iconBg: string; // soft chip background behind the icon
  ring: string; // ring color when the metric is the active filter
}

const toneToken: Record<string, ToneToken> = {
  green: { icon: 'text-status-green', iconBg: 'bg-status-green-bg', ring: 'ring-status-green' },
  amber: { icon: 'text-status-amber', iconBg: 'bg-status-amber-bg', ring: 'ring-status-amber' },
  red: { icon: 'text-status-red', iconBg: 'bg-status-red-bg', ring: 'ring-status-red' },
  blue: { icon: 'text-status-blue', iconBg: 'bg-status-blue-bg', ring: 'ring-status-blue' },
  gray: { icon: 'text-status-gray', iconBg: 'bg-status-gray-bg', ring: 'ring-status-gray' },
  default: { icon: 'text-text-2', iconBg: 'bg-subtle', ring: 'ring-border-strong' },
};

// purple/violet are not among the 5 semantic status colors; fold onto blue so
// existing configs keep a stable hue instead of silently greying out.
const toneAlias: Record<string, string> = { purple: 'blue', violet: 'blue' };

function resolveTone(tone: string): ToneToken {
  const key = toneAlias[tone] || tone;
  return toneToken[key] || toneToken.default;
}

// Trend delta color follows the direction, not the metric tone: up is good
// (green), down is bad (red), flat is neutral — same language as StatCard.
const trendClass: Record<string, string> = {
  up: 'text-status-green',
  down: 'text-status-red',
  flat: 'text-text-2',
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
  const precision = Number(metric?.precision);
  const numericValue = typeof value === 'number' ? value : Number(String(value).trim());
  if (
    Number.isInteger(precision) &&
    precision >= 0 &&
    precision <= 20 &&
    Number.isFinite(numericValue)
  ) {
    return new Intl.NumberFormat(locale, {
      maximumFractionDigits: precision,
      useGrouping: false,
    }).format(numericValue);
  }
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
        className="rounded-control bg-status-red-bg text-status-red border-status-red border p-3 text-sm"
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
      const tk = resolveTone(tone);
      const iconName: string | undefined = typeof metric.icon === 'string' ? metric.icon : undefined;
      const showIcon = Boolean(iconName && hasIcon(iconName));
      const clickable = Boolean(metric.onClick);
      const active = metric.activeWhen
        ? evaluator.evaluateCondition(metric.activeWhen, context)
        : false;
      const displayValue = mappedMetricValue(value, metric, locale, t);

      const trendRaw = metric.trendField ? readPath(record, metric.trendField) : metric.trend;
      const trend = renderMetricAuxText(trendRaw, locale, t);
      const trendDirection: string = String(
        (metric.trendDirectionField
          ? readPath(record, metric.trendDirectionField)
          : metric.trendDirection) || 'flat',
      );

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
            className={`rounded-pill border-border bg-panel text-text inline-flex min-h-9 items-center gap-2 border px-3 py-1.5 text-sm ${
              active ? `ring-2 ring-offset-1 ${tk.ring}` : ''
            } ${clickable ? 'hover:border-border-strong cursor-pointer' : 'cursor-default'} ${
              metric.align === 'end' ? 'ml-auto' : ''
            }`}
          >
            {showIcon && (
              <span className={tk.icon} aria-hidden="true">
                {resolveIcon(iconName, label, 15)}
              </span>
            )}
            <span className="font-medium">{label}</span>
            <span
              className={`rounded-pill px-2 py-0.5 text-xs font-semibold ${tk.iconBg} ${tk.icon}`}
            >
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
          className={`rounded-card border-border bg-panel shadow-card h-28 overflow-hidden border p-4 text-left transition-shadow ${
            active ? `ring-2 ring-offset-1 ${tk.ring}` : ''
          } ${clickable ? 'hover:shadow-pop cursor-pointer' : 'cursor-default'}`}
        >
          <div className="flex items-center gap-2">
            {showIcon && (
              <span
                className={`rounded-control inline-flex h-7 w-7 flex-none items-center justify-center ${tk.iconBg} ${tk.icon}`}
                aria-hidden="true"
              >
                {resolveIcon(iconName, label, 16)}
              </span>
            )}
            <div className="text-text-2 min-w-0 flex-1 truncate text-xs font-medium" title={label}>
              {label}
            </div>
          </div>
          <div className="mt-2 flex min-w-0 items-baseline gap-1.5 overflow-hidden">
            <span
              className="text-text min-w-0 truncate text-2xl font-semibold tabular-nums"
              data-testid={`metric-strip-value-${key}`}
              title={displayValue}
            >
              {displayValue}
            </span>
            {unit && <span className="text-text-2 flex-shrink-0 text-xs">{String(unit)}</span>}
            {trend && (
              <span
                className={`ml-auto flex-shrink-0 text-xs font-medium ${trendClass[trendDirection] || trendClass.flat}`}
                data-testid={`metric-strip-trend-${key}`}
              >
                {trend}
              </span>
            )}
          </div>
          {displaySubText && (
            <div
              className="text-text-3 mt-1 line-clamp-2 text-xs break-words"
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
