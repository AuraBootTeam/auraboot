import { buildEvent } from './envelope';
import { deriveUiElement } from './identity';
import type { BehaviorEventInput } from './types';

export type PostFn = (
  url: string,
  body: { events: BehaviorEventInput[] },
  opts: { keepalive?: boolean },
) => Promise<unknown>;

export interface Tracker {
  pageview(path: string): void;
  trackClick(el: Element): void;
  flush(): Promise<void>;
  init(): void;
}

export function createTracker(opts: {
  post: PostFn;
  getSessionId: () => string;
  /**
   * Optional persistent anonymous visitor id provider. When present, every event
   * carries `anonId` (public/anonymous mode). When absent, no `anonId` is emitted
   * and the server enriches identity from the JWT (authenticated mode — unchanged).
   */
  getAnonId?: () => string;
  endpoint?: string;
  batchSize?: number;
}): Tracker {
  const endpoint = opts.endpoint ?? '/api/collect';
  const batchSize = opts.batchSize ?? 10;
  let queue: BehaviorEventInput[] = [];

  const flush = async (): Promise<void> => {
    if (!queue.length) return;
    // Bound to 50 events to stay under the keepalive 64KB payload cap.
    const events = queue.slice(0, 50);
    queue = queue.slice(events.length);
    try {
      await opts.post(endpoint, { events }, { keepalive: true });
    } catch {
      // Drop on failure; idempotent eventId guards server-side double-counting.
    }
  };

  const enqueue = (e: BehaviorEventInput): void => {
    queue.push(e);
    if (queue.length >= batchSize) void flush();
  };

  // Assign to `api` first so `init` can reference `api.trackClick` without a
  // forward-reference cast. This avoids the `(api as Tracker).trackClick` hack
  // shown in the brief's sketch.
  const api: Tracker = {
    pageview(path: string): void {
      enqueue(
        buildEvent({
          eventName: 'page_view',
          eventCategory: 'navigation',
          clientSessionId: opts.getSessionId(),
          anonId: opts.getAnonId?.(),
          props: { routeTemplate: path },
        }),
      );
    },

    trackClick(el: Element): void {
      const ui = deriveUiElement(el);
      enqueue(
        buildEvent({
          eventName: 'element_click',
          eventCategory: 'ui_interaction',
          clientSessionId: opts.getSessionId(),
          anonId: opts.getAnonId?.(),
          ui,
          props: {},
        }),
      );
    },

    flush,

    init(): void {
      document.addEventListener(
        'click',
        (ev) => {
          if (ev.target instanceof Element) api.trackClick(ev.target);
        },
        { capture: true },
      );

      document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'hidden') void flush();
      });

      window.addEventListener('pagehide', () => void flush());
    },
  };

  return api;
}
