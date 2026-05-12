/**
 * Dashboard Viewer
 * Read-only rendering of a dashboard - used outside the designer (e.g., Reports Overview).
 * Supports chart linkage and drill-down but no editing, dragging, or resizing.
 */

import React, { useCallback, useRef, useMemo, useState, useEffect } from 'react';
import GridLayout from 'react-grid-layout';
import 'react-grid-layout/css/styles.css';
import 'react-resizable/css/styles.css';
import { renderWidget } from './WidgetRenderer';
import type { Widget, LayoutConfig } from '../types';
import type { Layout } from '~/framework/smart/types/dashboard';
import type { FilterConfig } from '~/framework/smart/types/chart';
import { ChartWidgetWrapper } from '~/framework/smart/components/dashboard/ChartWidgetWrapper';
import { ExportPdfButton } from '~/framework/smart/components/data-tools/ExportPdfButton';
import { DashboardExportExcel } from './DashboardExportExcel';
import { useI18n } from '~/contexts/I18nContext';
import { DESIGNER_I18N, resolveDesignerText } from '~/shared/designer';
import { deriveTestId } from '~/framework/meta/rendering/utils/deriveTestId';
import { getLocalizedText } from '~/framework/meta/runtime/expression/i18n-renderer';

type LinkageFiltersMap = Record<string, FilterConfig[]>;

interface DashboardViewerProps {
  widgets: Widget[];
  layoutConfig: LayoutConfig;
  className?: string;
  /** Dashboard title for export file names */
  title?: string;
  /** Show export toolbar */
  showExport?: boolean;
}

export const DashboardViewer: React.FC<DashboardViewerProps> = ({
  widgets,
  layoutConfig,
  className = '',
  title = 'dashboard',
  showExport = false,
}) => {
  const { locale, t } = useI18n();
  const containerRef = useRef<HTMLDivElement>(null);
  const [containerWidth, setContainerWidth] = useState(1200);
  const [linkageFilters, setLinkageFilters] = useState<LinkageFiltersMap>({});

  const handleLinkageEmit = useCallback((groupId: string, filters: FilterConfig[]) => {
    setLinkageFilters((prev) => ({
      ...prev,
      [groupId]: filters,
    }));
  }, []);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    const resizeObserver = new ResizeObserver((entries) => {
      for (const entry of entries) {
        const nextWidth = Math.max(0, Math.floor(entry.contentRect.width - 32));
        setContainerWidth((currentWidth) => (
          Math.abs(currentWidth - nextWidth) <= 1 ? currentWidth : nextWidth
        ));
      }
    });

    resizeObserver.observe(container);
    return () => resizeObserver.disconnect();
  }, []);

  const layout = useMemo(
    () =>
      widgets.map(
        (widget): Layout => ({
          i: widget.id,
          x: widget.x,
          y: widget.y,
          w: widget.w,
          h: widget.h,
          minW: widget.minW,
          minH: widget.minH,
          maxW: widget.maxW,
          maxH: widget.maxH,
          static: true,
        }),
      ),
    [widgets],
  );

  const renderViewerWidget = (widget: Widget) => {
    const linkageConfig = widget.config.linkage;
    const drillDownConfig = widget.config.drillDown;
    const groupId = linkageConfig?.groupId || 'default';
    const widgetTitle = getLocalizedText(widget.config.title, locale, t);

    const widgetLinkageFilters = linkageConfig?.receiveFilter ? linkageFilters[groupId] : undefined;

    const chartElement = renderWidget({
      widget,
      linkageFilters: widgetLinkageFilters,
      onLinkageEmit: linkageConfig?.emitFilter
        ? (filters: FilterConfig[]) => handleLinkageEmit(groupId, filters)
        : undefined,
      onDrillDown: drillDownConfig?.enabled
        ? (filters: FilterConfig[]) => {
            if (drillDownConfig.action === 'navigate' && drillDownConfig.targetPage) {
              const params = new URLSearchParams();
              filters.forEach((f) => {
                const paramName = drillDownConfig.paramMapping?.[f.field] || f.field;
                params.set(paramName, String(f.value));
              });
              window.location.href = `${drillDownConfig.targetPage}?${params.toString()}`;
            }
          }
        : undefined,
    });

    return (
      <ChartWidgetWrapper
        title={widgetTitle}
        dataSource={
          widget.config.dataSource as unknown as import('~/framework/smart/types/chart').ChartDataSource
        }
        linkageFilters={
          widgetLinkageFilters as unknown as import('~/framework/smart/types/chart').FilterConfig[]
        }
      >
        {chartElement}
      </ChartWidgetWrapper>
    );
  };

  if (widgets.length === 0) {
    return (
      <div className={`flex h-64 flex-col items-center justify-center text-gray-400 ${className}`}>
        <svg className="mb-3 h-12 w-12" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={1.5}
            d="M3 13.125C3 12.504 3.504 12 4.125 12h2.25c.621 0 1.125.504 1.125 1.125v6.75C7.5 20.496 6.996 21 6.375 21h-2.25A1.125 1.125 0 013 19.875v-6.75zM9.75 8.625c0-.621.504-1.125 1.125-1.125h2.25c.621 0 1.125.504 1.125 1.125v11.25c0 .621-.504 1.125-1.125 1.125h-2.25a1.125 1.125 0 01-1.125-1.125V8.625zM16.5 4.125c0-.621.504-1.125 1.125-1.125h2.25C20.496 3 21 3.504 21 4.125v15.75c0 .621-.504 1.125-1.125 1.125h-2.25a1.125 1.125 0 01-1.125-1.125V4.125z"
          />
        </svg>
        <p className="text-sm">{resolveDesignerText(DESIGNER_I18N.viewer.noData, locale)}</p>
        <p className="mt-1 text-xs text-gray-300">
          {resolveDesignerText(DESIGNER_I18N.viewer.configureHint, locale)}
        </p>
      </div>
    );
  }

  return (
    <div
      ref={containerRef}
      className={`overflow-auto rounded-[28px] bg-[radial-gradient(circle_at_top_left,_rgba(14,165,233,0.08),_transparent_24%),linear-gradient(180deg,_#f8fbff_0%,_#f8fafc_48%,_#ffffff_100%)] p-5 md:p-6 ${className}`}
      data-testid={deriveTestId('dashboard', title.replace(/\s+/g, '_'), 'container')}
    >
      {showExport && widgets.length > 0 && (
        <div
          className="mb-3 flex items-center justify-end gap-2"
          data-testid="viewer-export-toolbar"
        >
          <ExportPdfButton targetRef={containerRef} fileName={title} orientation="landscape" />
          <DashboardExportExcel widgets={widgets} fileName={title} />
        </div>
      )}
      {/* eslint-disable-next-line @typescript-eslint/no-explicit-any */}
      <GridLayout
        {...({
          className: 'layout',
          layout,
          cols: layoutConfig.columns,
          rowHeight: layoutConfig.rowHeight,
          width: containerWidth,
          margin: [layoutConfig.gap, layoutConfig.gap] as [number, number],
          containerPadding: [0, 0] as [number, number],
          isDraggable: false,
          isResizable: false,
          compactType: layoutConfig.compactType || 'vertical',
          useCSSTransforms: true,
        } as any)}
      >
        {widgets.map((widget) => (
          <div key={widget.id}>
            <div className="h-full overflow-hidden rounded-[24px] border border-slate-200/80 bg-white/92 shadow-[0_12px_34px_rgba(15,23,42,0.08)] backdrop-blur-sm">
              {renderViewerWidget(widget)}
            </div>
          </div>
        ))}
      </GridLayout>
    </div>
  );
};

export default DashboardViewer;
