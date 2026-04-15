import type { WidgetTier } from '../types';

/**
 * Current edition's widget tier filter.
 *
 * - OSS build  (`VITE_EDITION` absent or `'oss'`): returns `'oss'`
 *   — only base widgets are shown in palettes / pickers.
 * - Enterprise build (`VITE_EDITION === 'enterprise'`): returns `'all'`
 *   — every registered widget is available.
 *
 * Defaults to `'oss'` for safety so unlabelled builds never accidentally
 * expose enterprise widgets.
 */
export function getCurrentEditionTier(): WidgetTier | 'all' {
  const edition = import.meta.env.VITE_EDITION;
  return edition === 'enterprise' ? 'all' : 'oss';
}
