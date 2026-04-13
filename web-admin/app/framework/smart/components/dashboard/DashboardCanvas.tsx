/**
 * DashboardCanvas Component
 *
 * A dashboard canvas component that integrates react-grid-layout for
 * free-form drag and resize layout of chart components.
 * Supports chart linkage for interactive filtering between charts.
 */

import React, { useCallback, useMemo, useState } from 'react';
import GridLayout from 'react-grid-layout';
import 'react-grid-layout/css/styles.css';
import 'react-resizable/css/styles.css';
import type { EnhancedGridConfig, EnhancedGridCellConfig, Layout } from '~/framework/smart/types/dashboard';
import { cellToLayout, layoutToCell } from '~/framework/smart/types/dashboard';
import type { FilterConfig } from '~/framework/smart/types/chart';
import {
  SmartNumberCard,
  SmartBarChart,
  SmartLineChart,
  SmartPieChart,
  SmartAreaChart,
  SmartFunnelChart,
  SmartScatterChart,
  SmartRadarChart,
  SmartTableChart,
  SmartGaugeChart,
  SmartProgress,
  SmartHeatmapChart,
  SmartTreemapChart,
  SmartMapChart,
  SmartRichText,
  SmartImage,
  SmartIframe,
  SmartCountdown,
  SmartLeaderboard,
  SmartParetoChart,
  SmartSPCChart,
  SmartGanttChart,
  SmartCalendar,
} from '../charts';

/**
 * Props for DashboardCanvas component
 */
export interface DashboardCanvasProps {
  /** Grid layout schema configuration */
  schema: EnhancedGridConfig;
  /** Callback when layout changes (drag/resize) */
  onLayoutChange?: (cells: EnhancedGridCellConfig[]) => void;
  /** Enable edit mode for drag and resize */
  editable?: boolean;
  /** Canvas width in pixels */
  width?: number;
  /** Custom CSS class */
  className?: string;
}

/**
 * DashboardCanvas - A grid-based dashboard layout component
 *
 * @example
 * // Basic dashboard with two charts
 * <DashboardCanvas
 *   schema={{
 *     type: 'grid',
 *     id: 'dashboard-1',
 *     columns: 12,
 *     rowHeight: 100,
 *     gap: 16,
 *     cells: [
 *       {
 *         id: 'cell-1',
 *         componentType: 'smart-bar-chart',
 *         x: 0, y: 0, w: 6, h: 3,
 *         props: { title: 'Sales', dataSource: { ... } }
 *       },
 *       {
 *         id: 'cell-2',
 *         componentType: 'smart-pie-chart',
 *         x: 6, y: 0, w: 6, h: 3,
 *         props: { title: 'Distribution', dataSource: { ... } }
 *       }
 *     ]
 *   }}
 *   editable={true}
 *   width={1200}
 * />
 */
export const DashboardCanvas: React.FC<DashboardCanvasProps> = ({
  schema,
  onLayoutChange,
  editable = false,
  width = 1200,
  className,
}) => {
  // Track linkage filters by group ID
  const [linkageFilters, setLinkageFilters] = useState<Record<string, FilterConfig[]>>({});

  /**
   * Convert cell configurations to react-grid-layout Layout array
   */
  const layout = useMemo(() => schema.cells.map(cellToLayout), [schema.cells]);

  /**
   * Handle layout changes from drag/resize operations
   */
  const handleLayoutChange = useCallback(
    (newLayout: Layout[]) => {
      if (!onLayoutChange) return;

      const updatedCells = newLayout
        .map((layoutItem) => {
          const existingCell = schema.cells.find((c) => c.id === layoutItem.i);
          if (!existingCell) return null;
          return layoutToCell(layoutItem, existingCell);
        })
        .filter(Boolean) as EnhancedGridCellConfig[];

      onLayoutChange(updatedCells);
    },
    [schema.cells, onLayoutChange],
  );

  /**
   * Handle linkage filter emission from a chart
   */
  const handleLinkageEmit = useCallback((groupId: string, filters: FilterConfig[]) => {
    setLinkageFilters((prev) => ({ ...prev, [groupId]: filters }));
  }, []);

  /**
   * Render a chart component based on cell configuration
   */
  const renderCell = (cell: EnhancedGridCellConfig) => {
    const props = cell.props as Record<string, unknown>;
    const linkage = props.linkage as { groupId?: string; receiveFilter?: boolean } | undefined;

    // Get linkage filters if this cell is configured to receive them
    const cellLinkageFilters =
      linkage?.receiveFilter && linkage?.groupId ? linkageFilters[linkage.groupId] : undefined;

    // Build common props with linkage support
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const commonProps: any = {
      ...props,
      linkageFilters: cellLinkageFilters,
      onLinkageEmit: linkage?.groupId
        ? (filters: FilterConfig[]) => handleLinkageEmit(linkage.groupId!, filters)
        : undefined,
    };

    switch (cell.componentType) {
      case 'smart-number-card':
        return (
          <SmartNumberCard {...(commonProps as React.ComponentProps<typeof SmartNumberCard>)} />
        );
      case 'smart-bar-chart':
        return (
          <SmartBarChart
            {...(commonProps as React.ComponentProps<typeof SmartBarChart>)}
            className="h-full"
          />
        );
      case 'smart-line-chart':
        return (
          <SmartLineChart
            {...(commonProps as React.ComponentProps<typeof SmartLineChart>)}
            className="h-full"
          />
        );
      case 'smart-pie-chart':
        return (
          <SmartPieChart
            {...(commonProps as React.ComponentProps<typeof SmartPieChart>)}
            className="h-full"
          />
        );
      case 'smart-area-chart':
        return (
          <SmartAreaChart
            {...(commonProps as React.ComponentProps<typeof SmartAreaChart>)}
            className="h-full"
          />
        );
      case 'smart-funnel-chart':
        return (
          <SmartFunnelChart
            {...(commonProps as React.ComponentProps<typeof SmartFunnelChart>)}
            className="h-full"
          />
        );
      case 'smart-scatter-chart':
        return (
          <SmartScatterChart
            {...(commonProps as React.ComponentProps<typeof SmartScatterChart>)}
            className="h-full"
          />
        );
      case 'smart-radar-chart':
        return (
          <SmartRadarChart
            {...(commonProps as React.ComponentProps<typeof SmartRadarChart>)}
            className="h-full"
          />
        );
      case 'smart-table-chart':
        return (
          <SmartTableChart
            {...(commonProps as React.ComponentProps<typeof SmartTableChart>)}
            className="h-full"
          />
        );
      case 'smart-gauge-chart':
        return (
          <SmartGaugeChart
            {...(commonProps as React.ComponentProps<typeof SmartGaugeChart>)}
            className="h-full"
          />
        );
      case 'smart-progress':
        return (
          <SmartProgress
            {...(commonProps as React.ComponentProps<typeof SmartProgress>)}
            className="h-full"
          />
        );
      case 'smart-heatmap-chart':
        return (
          <SmartHeatmapChart
            {...(commonProps as React.ComponentProps<typeof SmartHeatmapChart>)}
            className="h-full"
          />
        );
      case 'smart-treemap-chart':
        return (
          <SmartTreemapChart
            {...(commonProps as React.ComponentProps<typeof SmartTreemapChart>)}
            className="h-full"
          />
        );
      case 'smart-map-chart':
        return (
          <SmartMapChart
            {...(commonProps as React.ComponentProps<typeof SmartMapChart>)}
            className="h-full"
          />
        );
      case 'smart-rich-text':
        return <SmartRichText {...(commonProps as React.ComponentProps<typeof SmartRichText>)} />;
      case 'smart-image':
        return <SmartImage {...(commonProps as React.ComponentProps<typeof SmartImage>)} />;
      case 'smart-iframe':
        return (
          <SmartIframe
            {...(commonProps as React.ComponentProps<typeof SmartIframe>)}
            className="h-full"
          />
        );
      case 'smart-countdown':
        return <SmartCountdown {...(commonProps as React.ComponentProps<typeof SmartCountdown>)} />;
      case 'smart-leaderboard':
        return (
          <SmartLeaderboard
            {...(commonProps as React.ComponentProps<typeof SmartLeaderboard>)}
            className="h-full"
          />
        );
      case 'smart-pareto-chart':
        return (
          <SmartParetoChart
            {...(commonProps as React.ComponentProps<typeof SmartParetoChart>)}
            className="h-full"
          />
        );
      case 'smart-spc-chart':
        return (
          <SmartSPCChart
            {...(commonProps as React.ComponentProps<typeof SmartSPCChart>)}
            className="h-full"
          />
        );
      case 'smart-gantt-chart':
        return (
          <SmartGanttChart
            {...(commonProps as React.ComponentProps<typeof SmartGanttChart>)}
            className="h-full"
          />
        );
      case 'smart-calendar':
        return (
          <SmartCalendar
            {...(commonProps as React.ComponentProps<typeof SmartCalendar>)}
            className="h-full"
          />
        );
      default:
        return (
          <div className="flex h-full items-center justify-center bg-gray-100 text-gray-500">
            Unknown: {cell.componentType}
          </div>
        );
    }
  };

  // Type assertion needed because our Layout interface is structurally compatible
  // with react-grid-layout's Layout, but TypeScript doesn't recognize this
  const gridLayoutProps = {
    className: 'layout',
    layout,
    cols: schema.columns,
    rowHeight: schema.rowHeight,
    width,
    margin: [schema.gap, schema.gap] as [number, number],
    containerPadding: [schema.gap, schema.gap] as [number, number],
    onLayoutChange: handleLayoutChange,
    isDraggable: editable,
    isResizable: editable,
    compactType: schema.compactType || 'vertical',
    preventCollision: false,
    useCSSTransforms: true,
  };

  return (
    <div className={className}>
      {/* eslint-disable-next-line @typescript-eslint/no-explicit-any */}
      <GridLayout {...(gridLayoutProps as any)}>
        {schema.cells.map((cell) => (
          <div key={cell.id} className="overflow-hidden rounded-lg border bg-white shadow-sm">
            {renderCell(cell)}
          </div>
        ))}
      </GridLayout>
    </div>
  );
};

export default DashboardCanvas;
