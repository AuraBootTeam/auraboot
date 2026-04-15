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
  'smart-iframe',
  'smart-image',
  'smart-line-chart',
  'smart-number-card',
  'smart-pie-chart',
  'smart-progress',
  'smart-rich-text',
  'smart-table-chart',
  // ── Workbench widgets ────────────────────────────────────────────────────
  'smart-activities',
  'smart-announcement',
  'smart-calendar',
  'smart-inbox',
  'smart-leads',
  'smart-my-process',
  'smart-pipeline',
  'smart-process-stats',
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
