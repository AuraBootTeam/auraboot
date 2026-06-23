import { createPublicTracker, type PublicTrackerOptions } from './public';
import { sanitizeRoute } from './envelope';
import type { Tracker } from './tracker';

export interface InitOptions extends PublicTrackerOptions {
  /** Auto-start click capture + fire an initial pageview. Default true. */
  auto?: boolean;
}

/**
 * Embeddable entry for a customer's PUBLISHED low-code app. Built into an IIFE
 * bundle (`dist/aura-track.global.js`) that exposes `window.AuraTrack`:
 *
 *   <script src="https://.../aura-track.global.js"></script>
 *   <script>AuraTrack.init({ siteKey: 'abk_...' });</script>
 *
 * Zero platform dependency — pure browser fetch + cookie/localStorage (see public.ts).
 */
export function init(options: InitOptions): Tracker {
  const tracker = createPublicTracker(options);
  if (options.auto !== false) {
    tracker.init();
    const loc = window.location;
    tracker.pageview(sanitizeRoute(loc.pathname + loc.search));
  }
  return tracker;
}

// Re-export the building blocks for advanced/manual embedders.
export { createPublicTracker } from './public';
export type { PublicTrackerOptions } from './public';
