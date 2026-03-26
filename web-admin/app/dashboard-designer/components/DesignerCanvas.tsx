/**
 * Designer Canvas
 * The main canvas area for dashboard design with drag-and-drop support
 */

import React, { useCallback, useRef, useMemo, useState } from 'react';
import GridLayout from 'react-grid-layout';
import 'react-grid-layout/css/styles.css';
import 'react-resizable/css/styles.css';
import { DesignerEmptyState, DESIGNER_I18N } from '~/shared/designer';
import { useDashboardStore } from '../store/useDashboardStore';
import { widgetRegistry } from '../widgets/widgetRegistry';
import { renderWidget } from './WidgetRenderer';
import type { Widget, WidgetType } from '../types';
import type { Layout } from '~/smart/types/dashboard';
import type { FilterConfig } from '~/smart/types/chart';

/**
 * Linkage filters state - grouped by linkage group ID
 */
type LinkageFiltersMap = Record<string, FilterConfig[]>;

interface DesignerCanvasProps {
  className?: string;
}

/**
 * Generate unique widget ID
 */
function generateWidgetId(): string {
  return `widget_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
}

export const DesignerCanvas: React.FC<DesignerCanvasProps> = ({ className = '' }) => {
  const containerRef = useRef<HTMLDivElement>(null);
  const [containerWidth, setContainerWidth] = useState(1200);
  const [isDragOver, setIsDragOver] = useState(false);
  const [dropPreviewPosition, setDropPreviewPosition] = useState<{ x: number; y: number; w: number; h: number } | null>(null);
  // Linkage filters state - grouped by linkage group ID
  const [linkageFilters, setLinkageFilters] = useState<LinkageFiltersMap>({});

  const { widgets, layoutConfig, selectedWidgetId, selectWidget, addWidget, updateLayout } =
    useDashboardStore();

  /**
   * Handle linkage emit - when a chart emits a filter, update the linkage state
   */
  const handleLinkageEmit = useCallback((groupId: string, filters: FilterConfig[]) => {
    setLinkageFilters((prev) => ({
      ...prev,
      [groupId]: filters,
    }));
  }, []);

  /**
   * Clear linkage filters for a group
   */
  const clearLinkageFilters = useCallback((groupId: string) => {
    setLinkageFilters((prev) => {
      const newFilters = { ...prev };
      delete newFilters[groupId];
      return newFilters;
    });
  }, []);

  // Measure container width
  React.useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    const resizeObserver = new ResizeObserver((entries) => {
      for (const entry of entries) {
        setContainerWidth(entry.contentRect.width - 32); // Account for padding
      }
    });

    resizeObserver.observe(container);
    return () => resizeObserver.disconnect();
  }, []);

  /**
   * Convert widgets to react-grid-layout format
   */
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
        }),
      ),
    [widgets],
  );

  /**
   * Handle layout changes from drag/resize
   */
  const handleLayoutChange = useCallback(
    (newLayout: Layout[]) => {
      const updatedWidgets = widgets.map((widget) => {
        const layoutItem = newLayout.find((l) => l.i === widget.id);
        if (!layoutItem) return widget;
        return {
          ...widget,
          x: layoutItem.x,
          y: layoutItem.y,
          w: layoutItem.w,
          h: layoutItem.h,
        };
      });
      updateLayout(updatedWidgets);
    },
    [widgets, updateLayout],
  );

  /**
   * Handle drop from widget palette
   */
  const handleDrop = useCallback(
    (e: React.DragEvent) => {
      e.preventDefault();
      setIsDragOver(false);
      setDropPreviewPosition(null);

      const widgetType = e.dataTransfer.getData('application/widget-type') as WidgetType;
      if (!widgetType) return;

      const widgetDef = widgetRegistry.get(widgetType);
      if (!widgetDef) return;

      // Calculate drop position
      const rect = containerRef.current?.getBoundingClientRect();
      if (!rect) return;

      const columnWidth = containerWidth / layoutConfig.columns;
      const x = Math.floor((e.clientX - rect.left) / columnWidth);
      const y = Math.floor((e.clientY - rect.top) / layoutConfig.rowHeight);

      // Create new widget
      const newWidget: Omit<Widget, 'id'> = {
        type: widgetType,
        componentType: widgetType,
        x: Math.max(0, Math.min(x, layoutConfig.columns - widgetDef.defaultSize.w)),
        y: Math.max(0, y),
        w: widgetDef.defaultSize.w,
        h: widgetDef.defaultSize.h,
        minW: widgetDef.defaultSize.minW,
        minH: widgetDef.defaultSize.minH,
        maxW: widgetDef.defaultSize.maxW,
        maxH: widgetDef.defaultSize.maxH,
        props: {},
        config: {
          title: widgetDef.defaultConfig.title || widgetDef.label,
          dataSource: widgetDef.defaultConfig.dataSource || {
            type: 'aggregate',
            metrics: [{ field: 'id', aggregation: 'count' }],
          },
        },
      };

      addWidget(newWidget);
    },
    [addWidget, containerWidth, layoutConfig],
  );

  const handleDragOver = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    e.dataTransfer.dropEffect = 'copy';
    setIsDragOver(true);

    // Calculate drop preview position
    const rect = containerRef.current?.getBoundingClientRect();
    if (!rect) return;

    const columnWidth = containerWidth / layoutConfig.columns;
    const x = Math.floor((e.clientX - rect.left) / columnWidth);
    const y = Math.floor((e.clientY - rect.top) / layoutConfig.rowHeight);

    setDropPreviewPosition({
      x: Math.max(0, Math.min(x, layoutConfig.columns - 2)) * columnWidth,
      y: Math.max(0, y) * layoutConfig.rowHeight,
      w: 2 * columnWidth,
      h: 2 * layoutConfig.rowHeight,
    });
  }, [containerWidth, layoutConfig]);

  const handleDragLeave = useCallback((e: React.DragEvent) => {
    if (e.currentTarget === e.target) {
      setIsDragOver(false);
      setDropPreviewPosition(null);
    }
  }, []);

  /**
   * Render a widget with selection styling and linkage wiring
   */
  const renderCanvasWidget = (widget: Widget) => {
    const isSelected = widget.id === selectedWidgetId;
    const linkageConfig = widget.config.linkage;
    const drillDownConfig = widget.config.drillDown;
    const groupId = linkageConfig?.groupId || 'default';

    // Get linkage filters for this widget's group (if widget receives filters)
    const widgetLinkageFilters = linkageConfig?.receiveFilter ? linkageFilters[groupId] : undefined;

    return (
      <div
        key={widget.id}
        onClick={(e) => {
          e.stopPropagation();
          selectWidget(widget.id);
        }}
        className={`h-full cursor-pointer overflow-hidden rounded-lg border bg-white shadow-sm transition-all ${
          isSelected
            ? 'border-blue-500 ring-2 ring-blue-500'
            : 'border-gray-200 hover:border-blue-300'
        }`}
      >
        {renderWidget({
          widget,
          linkageFilters: widgetLinkageFilters,
          onLinkageEmit: linkageConfig?.emitFilter
            ? (filters: FilterConfig[]) => handleLinkageEmit(groupId, filters)
            : undefined,
          onDrillDown: drillDownConfig?.enabled
            ? (filters: FilterConfig[]) => {
                // Handle drilldown based on action type
                if (drillDownConfig.action === 'filter') {
                  // Apply filter to same chart (already handled by the chart)
                } else if (drillDownConfig.action === 'navigate' && drillDownConfig.targetPage) {
                  // Navigate to target page with parameters
                  const params = new URLSearchParams();
                  filters.forEach((f) => {
                    const paramName = drillDownConfig.paramMapping?.[f.field] || f.field;
                    params.set(paramName, String(f.value));
                  });
                  window.location.href = `${drillDownConfig.targetPage}?${params.toString()}`;
                } else if (drillDownConfig.action === 'modal') {
                  // TODO: Open modal with filtered data
                }
              }
            : undefined,
        })}
      </div>
    );
  };

  return (
    <div
      ref={containerRef}
      data-testid="designer-canvas"
      className={`relative flex-1 overflow-auto bg-gray-100 p-4 transition-colors ${
        isDragOver ? 'bg-blue-50 ring-2 ring-blue-300 ring-inset' : ''
      } ${className}`}
      onDrop={handleDrop}
      onDragOver={handleDragOver}
      onDragLeave={handleDragLeave}
      onClick={() => selectWidget(null)}
    >
      {/* Drop position preview */}
      {isDragOver && dropPreviewPosition && (
        <div
          data-testid="drop-preview"
          className="pointer-events-none absolute rounded-lg border-2 border-dashed border-blue-400 bg-blue-100/50"
          style={{
            left: dropPreviewPosition.x,
            top: dropPreviewPosition.y,
            width: dropPreviewPosition.w,
            height: dropPreviewPosition.h,
            transition: 'all 150ms ease-out',
          }}
        />
      )}
      {widgets.length === 0 ? (
        <DesignerEmptyState
          variant="subtle"
          title={DESIGNER_I18N.emptyState.dragToCanvas}
          subtitle={DESIGNER_I18N.emptyState.orClickToAdd}
          testId="dashboard-canvas-empty"
        />
      ) : (
        // Type assertion needed because our Layout interface is structurally compatible
        // with react-grid-layout's Layout, but TypeScript doesn't recognize this
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        <GridLayout
          {...({
            className: 'layout',
            layout,
            cols: layoutConfig.columns,
            rowHeight: layoutConfig.rowHeight,
            width: containerWidth,
            margin: [layoutConfig.gap, layoutConfig.gap] as [number, number],
            containerPadding: [0, 0] as [number, number],
            onLayoutChange: handleLayoutChange,
            isDraggable: true,
            isResizable: true,
            compactType: layoutConfig.compactType || 'vertical',
            preventCollision: false,
            useCSSTransforms: true,
          } as any)}
        >
          {widgets.map((widget) => (
            <div key={widget.id}>{renderCanvasWidget(widget)}</div>
          ))}
        </GridLayout>
      )}
    </div>
  );
};

export default DesignerCanvas;
