import { widgetRegistry } from '../widgets/widgetRegistry';
import type { WidgetDefinition, WidgetTier } from '../types';

export interface GetWidgetDefinitionsOptions {
  tier: WidgetTier | 'all';
}

/**
 * Return widget definitions filtered by tier.
 *
 * - `tier: 'oss'`        — only OSS-tier widgets
 * - `tier: 'enterprise'` — only enterprise-tier widgets
 * - `tier: 'all'`        — all registered widgets regardless of tier
 */
export function getWidgetDefinitions(
  options: GetWidgetDefinitionsOptions,
): WidgetDefinition[] {
  const all = widgetRegistry.getAll();
  if (options.tier === 'all') return all;
  return all.filter((def) => def.tier === options.tier);
}
