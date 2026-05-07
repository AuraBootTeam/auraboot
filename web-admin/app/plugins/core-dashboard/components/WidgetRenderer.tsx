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
 * overrides on top, then delegates to the matched component.
 */
export function renderWidget({
  widget,
  linkageFilters,
  onLinkageEmit,
  onDrillDown,
}: WidgetRenderProps): React.ReactNode {
  const chartType = normalizeChartType(widget.type);
  const Component = getChartComponent(chartType);

  if (!Component) {
    return (
      <div data-widget-id={widget.id} className="h-full flex items-center justify-center text-sm text-gray-400">
        Unknown widget: {widget.type}
      </div>
    );
  }

  const props = {
    title: widget.config.title,
    dataSource: widget.config.dataSource,
    linkage: widget.config.linkage,
    drillDown: widget.config.drillDown,
    linkageFilters,
    onLinkageEmit,
    onDrillDown,
    refreshInterval: widget.config.refreshInterval,
    className: 'h-full',
    ...(widget.config.visualization || {}),
    ...(widget.config.style || {}),
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
        <Component {...props} />
      </Suspense>
    </div>
  );
}

/**
 * @deprecated Use SharedChartFactory.getChartComponent() instead.
 * Kept for backward compatibility with existing consumers.
 */
export const WIDGET_COMPONENTS: Record<string, React.ComponentType<any>> = {};
