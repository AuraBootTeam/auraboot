import { createTracker, generateEventId, type Tracker } from '@auraboot/track';
import { post } from '~/shared/services/http-client';

const CLIENT_SESSION_KEY = 'aura.client_session_id';

/**
 * Run-scoped isolation identity (ab_behavior_event.run_id). Hosts/tests set this
 * sessionStorage key so one execution's events can be asserted and cleaned up
 * without touching shared data (AMOS cockpit gap G10).
 */
const BEHAVIOR_RUN_ID_KEY = 'aura.behavior_run_id';

export function getBehaviorRunId(): string | undefined {
  return sessionStorage.getItem(BEHAVIOR_RUN_ID_KEY) ?? undefined;
}

/**
 * Returns a stable browser-tab-scoped session id stored in sessionStorage.
 * Generates a new one (via crypto.randomUUID or generateEventId fallback) if
 * none is present yet.
 */
export function getClientSessionId(): string {
  const existing = sessionStorage.getItem(CLIENT_SESSION_KEY);
  if (existing) return existing;
  const id =
    typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
      ? crypto.randomUUID()
      : generateEventId();
  sessionStorage.setItem(CLIENT_SESSION_KEY, id);
  return id;
}

let instance: Tracker | null = null;

/**
 * Returns the singleton Tracker bound to the platform http-client.
 * Initialised lazily on first call; subsequent calls return the same object.
 */
export function getTracker(): Tracker {
  if (!instance) {
    instance = createTracker({
      post: (url, body, opts) => post(url, body, { keepalive: opts.keepalive }),
      getSessionId: getClientSessionId,
      getRunId: getBehaviorRunId,
    });
  }
  return instance;
}
