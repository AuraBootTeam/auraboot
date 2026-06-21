import type { RawEventInput } from './types';

// Allowlisted safe aria attributes — never include value/innerHTML/textContent/href.
const SAFE_ARIA = new Set(['aria-label', 'aria-labelledby', 'role']);

/**
 * Derives a privacy-safe UI element identity from the given DOM element.
 *
 * Priority:
 *   1. Stable: nearest ancestor with [data-aura-element-id] — reads only
 *      data attributes explicitly emitted by BlockRenderer.
 *   2. Heuristic: tag name + allowlisted aria attrs (role).
 *      NEVER captures: input value, innerHTML, textContent, href, query params,
 *      or any record/content identifiers.
 */
export function deriveUiElement(el: Element): RawEventInput['ui'] | undefined {
  const host = el.closest('[data-aura-element-id]') as HTMLElement | null;
  if (host) {
    return {
      uiElementId: host.dataset.auraElementId!,
      appId: host.dataset.auraAppId,
      pageId: host.dataset.auraPageId,
      blockId: host.dataset.auraBlockId,
      elementCode: host.dataset.auraElementCode,
      identityQuality: 'stable',
    };
  }

  // Heuristic fallback: only safe structural attributes.
  // Explicitly forbidden: value, innerHTML, textContent, href, src, name, placeholder.
  const role = el.getAttribute('role') || el.tagName.toLowerCase();
  return {
    uiElementId: `heuristic:${role}`,
    identityQuality: 'heuristic',
  };
}
