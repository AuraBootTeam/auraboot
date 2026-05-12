/**
 * WidgetRenderer
 *
 * Central mapping from WidgetType to chart component.
 * Now uses SharedChartFactory (unified registry) instead of local WIDGET_COMPONENTS.
 * All components are lazy-loaded for code splitting.
 */

import React, { Suspense } from 'react';
import type { Widget, WidgetType } from '../types';
import type { FilterConfig } from '~/framework/smart/types/chart';
import { getChartComponent, normalizeChartType } from '~/framework/smart/charts/SharedChartFactory';
import { useI18n } from '~/contexts/I18nContext';
import {
  getLocalizedText,
  type LocalizedText,
  type TranslateFunction,
} from '~/framework/meta/runtime/expression/i18n-renderer';

/**
 * Recursively resolve $i18n:* prefixed strings in a value tree using the supplied
 * translate function. Plain (non-prefixed) strings, numbers, booleans, nulls,
 * arrays, and objects are returned/walked as-is. Used to translate widget.config
 * before passing to the underlying chart component so widgets that consume their
 * config keys verbatim (title, axis labels, column labels, etc.) get translated
 * text instead of raw $i18n: keys.
 */
// Match $i18n:<key> where the key is dotted alnum/underscore. Used to resolve
// keys that are embedded inside HTML strings (smart-rich-text content), not
// just keys that occupy a whole config string.
const I18N_KEY_PATTERN = /\$i18n:([a-zA-Z_][a-zA-Z0-9_.]*)/g;

const KNOWN_LOCALE_KEYS = new Set(['zh-CN', 'zh', 'en-US', 'en', 'ja-JP', 'ja', 'ko-KR', 'ko']);
const REGION_LOCALE_PATTERN = /^[a-z]{2,3}-[A-Z]{2}$/;

function isLocalizedTextRecord(value: unknown): value is LocalizedText {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  const entries = Object.entries(value as Record<string, unknown>);
  if (entries.length === 0) return false;
  const hasLocaleKey = entries.some(([key]) => KNOWN_LOCALE_KEYS.has(key) || REGION_LOCALE_PATTERN.test(key));
  return hasLocaleKey && entries.every(([, v]) => v === undefined || typeof v === 'string');
}

function resolveI18nDeep<T>(value: T, locale: string, t: TranslateFunction): T {
  if (typeof value === 'string') {
    if (!value.includes('$i18n:')) return value;
    // Whole-string shortcut
    if (/^\$i18n:[a-zA-Z_][a-zA-Z0-9_.]*$/.test(value)) {
      return t(value.slice(6)) as unknown as T;
    }
    // Embedded substitution (e.g. inside HTML content)
    return value.replace(I18N_KEY_PATTERN, (_match, key) => t(key)) as unknown as T;
  }
  if (isLocalizedTextRecord(value)) {
    return getLocalizedText(value, locale, t) as unknown as T;
  }
  if (Array.isArray(value)) {
    return value.map((v) => resolveI18nDeep(v, locale, t)) as unknown as T;
  }
  if (value && typeof value === 'object') {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
      out[k] = resolveI18nDeep(v, locale, t);
    }
    return out as T;
  }
  return value;
}

interface WidgetRenderProps {
  /** The widget to render */
  widget: Widget;
  /** Linkage filters to pass to the widget (already resolved for the widget's group) */
  linkageFilters?: FilterConfig[];
  /** Callback when the widget emits linkage filters */
  onLinkageEmit?: (filters: FilterConfig[]) => void;
  /** Callback when drill-down is triggered */
  onDrillDown?: (filters: FilterConfig[]) => void;
}

/**
 * Render a widget by looking up its component via SharedChartFactory.
 *
 * Builds the common props from widget.config and spreads visualization / style
 * overrides on top, then delegates to the matched component. Resolves any
 * $i18n:* strings in the config tree using the active i18n locale so widgets
 * that consume config values directly (title, axis labels, column labels, etc.)
 * see translated text rather than raw keys.
 */
export function renderWidget(props: WidgetRenderProps): React.ReactNode {
  return <RenderedWidget {...props} />;
}

function RenderedWidget({
  widget,
  linkageFilters,
  onLinkageEmit,
  onDrillDown,
}: WidgetRenderProps): React.ReactElement {
  const { locale, t } = useI18n();
  const chartType = normalizeChartType(widget.type);
  const Component = getChartComponent(chartType);

  if (!Component) {
    return (
      <div data-widget-id={widget.id} className="h-full flex items-center justify-center text-sm text-gray-400">
        Unknown widget: {widget.type}
      </div>
    );
  }

  const config = resolveI18nDeep(widget.config, locale, t);

  // Spread the entire (i18n-resolved) config so widgets receive every key —
  // title, content, shortcuts, columns, metricField, prefix, suffix,
  // seriesConfig, dataSource, linkage, drillDown, refreshInterval, etc.
  // visualization/style sub-objects flatten on top for chart presentation
  // (xField/yField/etc. live there). Renderer-supplied props (linkageFilters,
  // emit callbacks, h-full className) come last so config can never clobber
  // them.
  const componentProps = {
    ...config,
    ...(config.visualization || {}),
    ...(config.style || {}),
    linkageFilters,
    onLinkageEmit,
    onDrillDown,
    className: 'h-full',
  };

  return (
    <div data-widget-id={widget.id} className="h-full">
      <Suspense
        fallback={
          <div className="flex h-full items-center justify-center text-sm text-gray-400">
            Loading...
          </div>
        }
      >
        <Component {...componentProps} />
      </Suspense>
    </div>
  );
}

/**
 * @deprecated Use SharedChartFactory.getChartComponent() instead.
 * Kept for backward compatibility with existing consumers.
 */
export const WIDGET_COMPONENTS: Record<string, React.ComponentType<any>> = {};
