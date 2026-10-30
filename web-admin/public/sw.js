/**
 * AuraBoot Service Worker — Offline Cache (GAP-026)
 *
 * Strategy: Browser-owned authenticated APIs, cache-first static assets.
 * Provides basic offline capability for previously visited pages.
 *
 * PDA offline shell (WMS D3, feat/pda-offline-shell):
 *  - PDA page shells (five routes) are network-first with cache fallback, so
 *    previously visited PDA pages stay reachable in weak-network zones.
 *  - Command POSTs that fail on the network (offline) while capturing an
 *    offline enqueue (`inv:pda_queue_offline`) are stashed in the
 *    `pda-offline-outbox` cache; the page-side replay executor drains it on
 *    reconnect (create_key idempotency + version CAS per plugins#779).
 */

const CACHE_NAME = 'auraboot-v3';
const OUTBOX_CACHE = 'pda-offline-outbox';
const STATIC_ASSETS = ['/manifest.json', '/favicon.ico'];

// PDA H5 shells (WMS D3): network-first navigation with cache fallback so a
// previously visited PDA page keeps rendering offline.
const PDA_SHELL_ROUTES = [
  '/inventory/pda-tasks',
  '/inventory/pda-receive',
  '/inventory/pda-putaway',
  '/inventory/pda-pick',
  '/inventory/pda-stockcheck',
];

// Offline capture: PDA command POSTs (incl. the offline enqueue itself) that
// fail at the network level are stashed verbatim; the page executor turns each
// into an inv_pda_offline_queue row and replays it with the row's create_key
// (plugins#779 semantics).
const PDA_COMMAND_EXECUTE_PREFIX = '/api/meta/commands/execute/';

// Install: pre-cache essential assets
self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE_NAME).then((cache) => cache.addAll(STATIC_ASSETS)));
  self.skipWaiting();
});

// Activate: clean old caches (keep the offline outbox)
self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((names) =>
        Promise.all(
          names.filter((n) => n !== CACHE_NAME && n !== OUTBOX_CACHE).map((n) => caches.delete(n)),
        ),
      ),
  );
  self.clients.claim();
});

// Fetch: network-first for API, cache-first for assets
self.addEventListener('fetch', (event) => {
  const { request } = event;
  const url = new URL(request.url);

  // Skip WebSocket, SSE, and external URLs
  if (url.pathname.includes('/ws') || url.pathname.includes('/stream')) return;
  if (url.origin !== self.location.origin) return;

  // PDA shell navigations: network-first, cache fallback (D3 offline reach)
  if (
    request.method === 'GET' &&
    request.mode === 'navigate' &&
    PDA_SHELL_ROUTES.some((p) => url.pathname === p || url.pathname.startsWith(p + '/'))
  ) {
    event.respondWith(
      fetch(request)
        .then((response) => {
          if (response.ok) {
            const clone = response.clone();
            caches.open(CACHE_NAME).then((cache) => cache.put(request, clone));
          }
          return response;
        })
        .catch(() => caches.match(request).then((cached) => cached || offlineResponse())),
    );
    return;
  }

  // Offline capture: command POST that failed at the network level.
  if (
    request.method === 'POST' &&
    url.pathname.startsWith(PDA_COMMAND_EXECUTE_PREFIX) &&
    decodeURIComponent(url.pathname.slice(PDA_COMMAND_EXECUTE_PREFIX.length)).startsWith('inv:')
  ) {
    const snapshot = request.clone();
    const commandCode = decodeURIComponent(url.pathname.slice(PDA_COMMAND_EXECUTE_PREFIX.length));
    event.respondWith(
      fetch(request).catch(async () => {
        await stashOfflineCommand(snapshot, commandCode);
        return new Response(
          JSON.stringify({
            code: 'OFFLINE_QUEUED',
            message: 'Captured for offline replay (PDA D3)',
            data: { offlineQueued: true },
          }),
          { status: 503, headers: { 'Content-Type': 'application/json' } },
        );
      }),
    );
    return;
  }

  // Skip other non-GET requests
  if (request.method !== 'GET') return;

  // Authenticated documents and redirects must use the browser's navigation
  // pipeline. A cached redirected root response cannot satisfy a navigation
  // request with redirect=manual and can also retain an anonymous login shell.
  // Only the explicit PDA shells above opt in to offline document caching.
  if (request.mode === 'navigate') return;

  // API results depend on the current user and tenant. Never serve responses
  // cached by another session, or proxy unrelated products through PDA replay.
  if (url.pathname.startsWith('/api/')) return;

  // Static assets: cache-first with network fallback
  event.respondWith(
    caches
      .match(request)
      .then((cached) => {
        if (cached) return cached;
        return fetch(request).then((response) => {
          if (response.ok && shouldCache(url.pathname)) {
            const clone = response.clone();
            caches.open(CACHE_NAME).then((cache) => cache.put(request, clone));
          }
          return response;
        });
      })
      .catch(() => offlineResponse()),
  );
});

async function stashOfflineCommand(requestSnapshot, commandCode) {
  try {
    const body = await requestSnapshot.text();
    const entry = {
      capturedAt: new Date().toISOString(),
      commandCode,
      url: requestSnapshot.url,
      headers: {
        'Content-Type': requestSnapshot.headers.get('Content-Type') || 'application/json',
      },
      body,
    };
    const cache = await caches.open(OUTBOX_CACHE);
    await cache.put(
      new Request(
        `${self.location.origin}/__pda-outbox/${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      ),
      new Response(JSON.stringify(entry), { headers: { 'Content-Type': 'application/json' } }),
    );
    const count = (await cache.keys()).length;
    self.clients.matchAll({ type: 'window' }).then((clients) => {
      clients.forEach((client) =>
        client.postMessage({ type: 'PDA_OUTBOX_STASHED', pending: count }),
      );
    });
  } catch (err) {
    // Never break the response path because stashing failed.
    console.warn('[sw] pda offline stash failed', err);
  }
}

function shouldCache(pathname) {
  return (
    pathname.endsWith('.js') ||
    pathname.endsWith('.css') ||
    pathname.endsWith('.png') ||
    pathname.endsWith('.svg') ||
    pathname.endsWith('.woff2')
  );
}

function offlineResponse() {
  return new Response(
    '<html><body style="font-family:sans-serif;text-align:center;padding:60px"><h1>Offline</h1><p>You are currently offline. Please check your connection.</p></body></html>',
    { headers: { 'Content-Type': 'text/html' }, status: 503 },
  );
}
