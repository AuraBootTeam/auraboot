/**
 * Kind × ModelCapabilities compatibility rules.
 *
 * Each page `kind` requires certain capabilities from the backing model:
 *   - kind=list    => capabilities.list
 *   - kind=detail  => capabilities.detail
 *   - kind=form    => capabilities.create OR capabilities.update
 *
 * When `capabilities` is undefined we treat the check as inconclusive and
 * return `true` (do not block submit while caps are still loading or missing).
 */

import type { ModelCapabilities } from '~/shared/hooks/useModelCapabilities';

export type PageKind = 'list' | 'form' | 'detail';

export interface KindCompatibilityResult {
  compatible: boolean;
  reason?: string;
}

export function isKindCompatible(
  kind: string | undefined | null,
  capabilities: ModelCapabilities | undefined | null,
): boolean {
  return checkKindCompatibility(kind, capabilities).compatible;
}

export function checkKindCompatibility(
  kind: string | undefined | null,
  capabilities: ModelCapabilities | undefined | null,
): KindCompatibilityResult {
  if (!kind || !capabilities) return { compatible: true };
  const normalized = String(kind).toLowerCase();
  switch (normalized) {
    case 'list':
      return capabilities.list
        ? { compatible: true }
        : { compatible: false, reason: 'Model does not support list' };
    case 'detail':
      return capabilities.detail
        ? { compatible: true }
        : { compatible: false, reason: 'Model does not support detail' };
    case 'form':
      return capabilities.create || capabilities.update
        ? { compatible: true }
        : { compatible: false, reason: 'Model does not support create or update' };
    default:
      return { compatible: true };
  }
}

/**
 * Returns the list of disabled kinds given the capabilities, for rendering
 * disabled options in a kind selector. Returns empty array when capabilities
 * are unknown.
 */
export function disabledKindsForCapabilities(
  capabilities: ModelCapabilities | undefined | null,
): PageKind[] {
  if (!capabilities) return [];
  const disabled: PageKind[] = [];
  if (!capabilities.list) disabled.push('list');
  if (!capabilities.detail) disabled.push('detail');
  if (!capabilities.create && !capabilities.update) disabled.push('form');
  return disabled;
}
