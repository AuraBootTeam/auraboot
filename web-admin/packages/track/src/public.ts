import { createTracker, type PostFn, type Tracker } from './tracker';
import { generateEventId } from './envelope';

/**
 * Public / anonymous tracking mode for the AuraBoot behavior SDK.
 *
 * Embedded in a customer's PUBLISHED low-code app (their own domain, no platform
 * login). An anonymous visitor's browser sends behavior events to our keyed
 * ingestion endpoint, authenticating the tenant via a public `site_key`
 * (GA `measurementId` style — SP1/SP2). Unlike the authenticated tracker
 * (`trackerInstance.ts`), this has ZERO platform dependency: no http-client, no
 * JWT, no ApiService — only browser-native `fetch` + cookie/localStorage. It is
 * therefore buildable into a standalone script that drops into any published app.
 *
 * Contract (SP2, shipped):
 *   POST <collectUrl>   Header: X-Site-Key: abk_…   Body: { events: [...] }
 *   default collectUrl = /api/collect/keyed (cross-origin absolute URL supported).
 */

/** Persistent anonymous-visitor id (1y). first-party to the published-app domain. */
export const ANON_ID_KEY = '_aura_anon';
/** Browser-tab session id (sessionStorage scope). */
export const SESSION_ID_KEY = '_aura_sid';

const ONE_YEAR_SECONDS = 60 * 60 * 24 * 365;

/** Pluggable persistence for the anonymous id (test seam; defaults to browser). */
export interface AnonIdStore {
  get(): string | null;
  set(id: string): void;
}

function readCookie(name: string): string | null {
  const escaped = name.replace(/[.$?*|{}()[\]\\/+^]/g, '\\$&');
  const m = document.cookie.match(new RegExp('(?:^|; )' + escaped + '=([^;]*)'));
  return m ? decodeURIComponent(m[1]) : null;
}

function writeCookie(name: string, value: string, maxAgeSeconds: number): void {
  // first-party, lax: survives top-level navigations on the published-app domain.
  document.cookie =
    `${name}=${encodeURIComponent(value)};max-age=${maxAgeSeconds};path=/;SameSite=Lax`;
}

/**
 * Default browser-backed store: cookie primary (cross-tab stable, GA-style) with
 * a localStorage fallback for cookie-blocked environments. Both writes/reads are
 * defensive — a blocked cookie or storage never throws out of the SDK.
 */
export function browserAnonIdStore(): AnonIdStore {
  return {
    get(): string | null {
      try {
        const c = readCookie(ANON_ID_KEY);
        if (c) return c;
      } catch {
        /* cookie access blocked — fall through to localStorage */
      }
      try {
        return localStorage.getItem(ANON_ID_KEY);
      } catch {
        return null;
      }
    },
    set(id: string): void {
      try {
        writeCookie(ANON_ID_KEY, id, ONE_YEAR_SECONDS);
      } catch {
        /* cookie blocked — localStorage still carries it */
      }
      try {
        localStorage.setItem(ANON_ID_KEY, id);
      } catch {
        /* storage blocked — cookie still carries it */
      }
    },
  };
}

function defaultGenerateId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return generateEventId();
}

/** Reads the persisted anonymous id, generating + persisting one on first visit. */
export function ensureAnonId(store: AnonIdStore, generateId: () => string): string {
  const existing = store.get();
  if (existing) return existing;
  const id = generateId();
  store.set(id);
  return id;
}

function ensureSessionId(generateId: () => string): string {
  try {
    const existing = sessionStorage.getItem(SESSION_ID_KEY);
    if (existing) return existing;
    const id = generateId();
    sessionStorage.setItem(SESSION_ID_KEY, id);
    return id;
  } catch {
    // sessionStorage blocked → ephemeral per-load id (still groups one page load).
    return generateId();
  }
}

export interface PublicTrackerOptions {
  /** Public site key issued by the site-key registry (SP1), prefix `abk_`. */
  siteKey: string;
  /** Keyed ingestion endpoint. Default `/api/collect/keyed`; absolute URL for cross-origin. */
  collectUrl?: string;
  batchSize?: number;
  /** Test seam: override anonId persistence. */
  anonIdStore?: AnonIdStore;
  /** Test seam: override fetch. */
  fetchImpl?: typeof fetch;
  /** Test seam: override id generation. */
  generateId?: () => string;
}

/**
 * Creates a standalone anonymous tracker bound to a public `site_key`.
 * Returns a {@link Tracker}; call `.init()` to start auto-capture, or use
 * `.pageview()` / `.trackClick()` directly.
 */
export function createPublicTracker(opts: PublicTrackerOptions): Tracker {
  const collectUrl = opts.collectUrl ?? '/api/collect/keyed';
  const generateId = opts.generateId ?? defaultGenerateId;
  const store = opts.anonIdStore ?? browserAnonIdStore();
  const anonId = ensureAnonId(store, generateId);
  const sessionId = ensureSessionId(generateId);

  const post: PostFn = (url, body, o) => {
    const fetchImpl = opts.fetchImpl ?? globalThis.fetch.bind(globalThis);
    return fetchImpl(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Site-Key': opts.siteKey,
      },
      body: JSON.stringify(body),
      keepalive: o.keepalive,
    });
  };

  return createTracker({
    post,
    getSessionId: () => sessionId,
    getAnonId: () => anonId,
    endpoint: collectUrl,
    batchSize: opts.batchSize,
  });
}
