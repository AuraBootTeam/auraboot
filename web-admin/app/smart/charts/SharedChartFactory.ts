/**
 * SharedChartFactory — unified chart component registry.
 *
 * Single source of truth for all chart type → component mappings.
 * Used by both DSL ChartBlockRenderer and Dashboard WidgetRenderer.
 * All components are lazy-loaded for code splitting.
 */

import React from 'react';
import { InboxWidget } from '~/dashboard-designer/widgets/workbench/InboxWidget';
import { RecentWidget } from '~/dashboard-designer/widgets/workbench/RecentWidget';
import { ShortcutsWidget } from '~/dashboard-designer/widgets/workbench/ShortcutsWidget';
import { StatsRowWidget } from '~/dashboard-designer/widgets/workbench/StatsRowWidget';
import { MyProcessWidget } from '~/dashboard-designer/widgets/workbench/MyProcessWidget';
import { ProcessStatsWidget } from '~/dashboard-designer/widgets/workbench/ProcessStatsWidget';
import { PipelineWidget } from '~/dashboard-designer/widgets/workbench/PipelineWidget';
import { LeadsWidget } from '~/dashboard-designer/widgets/workbench/LeadsWidget';
import { ActivitiesWidget } from '~/dashboard-designer/widgets/workbench/ActivitiesWidget';
import { CalendarWidget } from '~/dashboard-designer/widgets/workbench/CalendarWidget';
import { AnnouncementWidget } from '~/dashboard-designer/widgets/workbench/AnnouncementWidget';
import { QuickNoteWidget } from '~/dashboard-designer/widgets/workbench/QuickNoteWidget';
import { StatsCardWidget } from '~/dashboard-designer/widgets/workbench/StatsCardWidget';

type LazyComponent = React.LazyExoticComponent<React.ComponentType<any>>;

const CHART_REGISTRY = new Map<string, LazyComponent>();

// Register all 23 chart types (lazy imports with named export resolution)
function reg(type: string, loader: () => Promise<{ default: React.ComponentType<any> }>) {
  CHART_REGISTRY.set(type, React.lazy(loader));
}

function regSync(type: string, component: React.ComponentType<any>) {
  CHART_REGISTRY.set(
    type,
    React.lazy(async () => ({
      default: component,
    })),
  );
}

reg('bar', () =>
  import('~/smart/components/charts/SmartBarChart').then((m) => ({ default: m.SmartBarChart })),
);
reg('line', () =>
  import('~/smart/components/charts/SmartLineChart').then((m) => ({ default: m.SmartLineChart })),
);
reg('pie', () =>
  import('~/smart/components/charts/SmartPieChart').then((m) => ({ default: m.SmartPieChart })),
);
reg('area', () =>
  import('~/smart/components/charts/SmartAreaChart').then((m) => ({ default: m.SmartAreaChart })),
);
reg('radar', () =>
  import('~/smart/components/charts/SmartRadarChart').then((m) => ({ default: m.SmartRadarChart })),
);
reg('scatter', () =>
  import('~/smart/components/charts/SmartScatterChart').then((m) => ({
    default: m.SmartScatterChart,
  })),
);
reg('funnel', () =>
  import('~/smart/components/charts/SmartFunnelChart').then((m) => ({
    default: m.SmartFunnelChart,
  })),
);
reg('gauge', () =>
  import('~/smart/components/charts/SmartGaugeChart').then((m) => ({ default: m.SmartGaugeChart })),
);
reg('heatmap', () =>
  import('~/smart/components/charts/SmartHeatmapChart').then((m) => ({
    default: m.SmartHeatmapChart,
  })),
);
reg('treemap', () =>
  import('~/smart/components/charts/SmartTreemapChart').then((m) => ({
    default: m.SmartTreemapChart,
  })),
);
reg('map', () =>
  import('~/smart/components/charts/SmartMapChart').then((m) => ({ default: m.SmartMapChart })),
);
reg('spc', () =>
  import('~/smart/components/charts/SmartSPCChart').then((m) => ({ default: m.SmartSPCChart })),
);
reg('pareto', () =>
  import('~/smart/components/charts/SmartParetoChart').then((m) => ({
    default: m.SmartParetoChart,
  })),
);
reg('gantt', () =>
  import('~/smart/components/charts/SmartGanttChart').then((m) => ({ default: m.SmartGanttChart })),
);
reg('table', () =>
  import('~/smart/components/charts/SmartTableChart').then((m) => ({ default: m.SmartTableChart })),
);
reg('number-card', () =>
  import('~/smart/components/charts/SmartNumberCard').then((m) => ({ default: m.SmartNumberCard })),
);
reg('progress', () =>
  import('~/smart/components/charts/SmartProgress').then((m) => ({ default: m.SmartProgress })),
);
reg('leaderboard', () =>
  import('~/smart/components/charts/SmartLeaderboard').then((m) => ({
    default: m.SmartLeaderboard,
  })),
);
reg('rich-text', () =>
  import('~/smart/components/charts/SmartRichText').then((m) => ({ default: m.SmartRichText })),
);
reg('image', () =>
  import('~/smart/components/charts/SmartImage').then((m) => ({ default: m.SmartImage })),
);
reg('iframe', () =>
  import('~/smart/components/charts/SmartIframe').then((m) => ({ default: m.SmartIframe })),
);
reg('countdown', () =>
  import('~/smart/components/charts/SmartCountdown').then((m) => ({ default: m.SmartCountdown })),
);
reg('calendar', () =>
  import('~/smart/components/charts/SmartCalendar').then((m) => ({ default: m.SmartCalendar })),
);
reg('wordcloud', () =>
  import('~/smart/components/charts/SmartWordCloudChart').then((m) => ({
    default: m.SmartWordCloudChart,
  })),
);
reg('combo', () =>
  import('~/smart/components/charts/SmartComboChart').then((m) => ({
    default: m.SmartComboChart,
  })),
);
reg('nps', () =>
  import('~/smart/components/charts/SmartNpsChart').then((m) => ({
    default: m.SmartNpsChart,
  })),
);
reg('gallery', () =>
  import('~/smart/components/charts/SmartGallery').then((m) => ({
    default: m.SmartGallery,
  })),
);
reg('kanban', () =>
  import('~/smart/components/charts/SmartKanban').then((m) => ({
    default: m.SmartKanban,
  })),
);

// Workbench widgets
regSync('inbox', InboxWidget);
regSync('recent', RecentWidget);
regSync('shortcuts', ShortcutsWidget);
regSync('stats-row', StatsRowWidget);
regSync('my-process', MyProcessWidget);
regSync('process-stats', ProcessStatsWidget);
regSync('pipeline', PipelineWidget);
regSync('leads', LeadsWidget);
regSync('activities', ActivitiesWidget);
regSync('calendar', CalendarWidget);
regSync('announcement', AnnouncementWidget);
regSync('quick-note', QuickNoteWidget);
regSync('stats-card', StatsCardWidget);

/**
 * Get a lazy-loaded chart component by type.
 * Returns null if type is not registered.
 */
export function getChartComponent(chartType: string): LazyComponent | null {
  return CHART_REGISTRY.get(chartType) ?? null;
}

/**
 * Get all supported chart type names.
 */
export function getSupportedChartTypes(): string[] {
  return [...CHART_REGISTRY.keys()];
}

/**
 * Check if a chart type is registered.
 */
export function isValidChartType(type: string): boolean {
  return CHART_REGISTRY.has(type);
}

/**
 * Normalize chart type from Dashboard widget format to registry key.
 * 'smart-bar-chart' → 'bar'
 * 'smart-number-card' → 'number-card'
 * 'smart-progress' → 'progress'
 * 'bar' → 'bar' (no-op for DSL format)
 */
export function normalizeChartType(type: string): string {
  let normalized = type;
  if (normalized.startsWith('smart-')) {
    normalized = normalized.slice(6); // Remove 'smart-'
  }
  if (normalized.endsWith('-chart')) {
    normalized = normalized.slice(0, -6); // Remove '-chart'
  }
  return normalized;
}
