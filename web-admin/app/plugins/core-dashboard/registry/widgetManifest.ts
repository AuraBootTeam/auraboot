import type { WidgetTier } from '../types';

/**
 * OSS tier widget types. Widgets listed here are shipped in the open-source
 * edition. Anything not in this list is treated as enterprise-tier.
 *
 * Keep this list alphabetical within groups for easier review.
 */
export const OSS_WIDGETS: readonly string[] = [
  // ── Chart & display widgets ──────────────────────────────────────────────
  'smart-area-chart',
  'smart-bar-chart',
  'smart-countdown',
  'smart-filter-bar',
  'smart-iframe',
  'smart-image',
  'smart-line-chart',
  'smart-number-card',
  'smart-pie-chart',
  'smart-progress',
  'smart-rich-text',
  'smart-table-chart',
  // ── Workbench widgets ────────────────────────────────────────────────────
  'smart-announcement',
  'smart-calendar',
  'smart-inbox',
  'smart-quick-note',
  'smart-recent',
  'smart-shortcuts',
  'smart-stats-card',
  'smart-stats-row',
] as const;

/**
 * Resolve a widget type string to its tier. Unknown or unregistered types
 * default to 'enterprise' as a safe fallback (new widgets are opt-in to OSS).
 */
export function resolveWidgetTier(type: string): WidgetTier {
  return OSS_WIDGETS.includes(type) ? 'oss' : 'enterprise';
}

/**
 * Widget types whose renderer honours the drill-down configuration.
 *
 * The renderer hands every widget the `drillDown` config and the `onDrillDown`
 * callback. A widget that ignores both would save a drill-down that never opens
 * anything, so the designer offers the configuration only for these types. A
 * product gate reads the same list (`plugins/schemas/dashboard-widget-capabilities.json`)
 * to tell a missing drill-down from a widget that has none.
 *
 * `registry/__tests__/widgetManifest.test.ts` reads each renderer's source and
 * fails when this list and the renderers disagree.
 *
 * Keep this list alphabetical for easier review.
 */
export const DRILL_DOWN_WIDGETS: readonly string[] = [
  'smart-area-chart',
  'smart-bar-chart',
  'smart-combo-chart',
  'smart-funnel-chart',
  'smart-gantt-chart',
  'smart-kanban',
  'smart-line-chart',
  'smart-number-card',
  'smart-pareto-chart',
  'smart-pie-chart',
  'smart-radar-chart',
  'smart-scatter-chart',
  'smart-table-chart',
  'smart-treemap-chart',
  'smart-waterfall-chart',
  'smart-wordcloud-chart',
] as const;

/** Whether a widget type's renderer honours the drill-down configuration. */
export function supportsDrillDown(type: string): boolean {
  return DRILL_DOWN_WIDGETS.includes(type);
}
