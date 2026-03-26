/**
 * AuraBoot Service Worker — Offline Cache (GAP-026)
 *
 * Strategy: Network-first for API calls, Cache-first for static assets.
 * Provides basic offline capability for previously visited pages.
 */

const CACHE_NAME = 'auraboot-v1';
const STATIC_ASSETS = [
  '/',
  '/manifest.json',
  '/favicon.ico',
];

// Install: pre-cache essential assets
self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => cache.addAll(STATIC_ASSETS))
  );
  self.skipWaiting();
});

// Activate: clean old caches
self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((names) =>
      Promise.all(names.filter((n) => n !== CACHE_NAME).map((n) => caches.delete(n)))
    )
  );
  self.clients.claim();
});

// Fetch: network-first for API, cache-first for assets
self.addEventListener('fetch', (event) => {
  const { request } = event;
  const url = new URL(request.url);

  // Skip non-GET requests
  if (request.method !== 'GET') return;

  // Skip WebSocket, SSE, and external URLs
  if (url.pathname.includes('/ws') || url.pathname.includes('/stream')) return;
  if (url.origin !== self.location.origin) return;

  // API calls: network-first with cache fallback
  if (url.pathname.startsWith('/api/')) {
    event.respondWith(
      fetch(request)
        .then((response) => {
          // Cache successful GET API responses
          if (response.ok) {
            const clone = response.clone();
            caches.open(CACHE_NAME).then((cache) => cache.put(request, clone));
          }
          return response;
        })
        .catch(() => caches.match(request).then((cached) => cached || offlineResponse()))
    );
    return;
  }

  // Static assets: cache-first with network fallback
  event.respondWith(
    caches.match(request).then((cached) => {
      if (cached) return cached;
      return fetch(request).then((response) => {
        if (response.ok && shouldCache(url.pathname)) {
          const clone = response.clone();
          caches.open(CACHE_NAME).then((cache) => cache.put(request, clone));
        }
        return response;
      });
    }).catch(() => offlineResponse())
  );
});

function shouldCache(pathname) {
  return pathname.endsWith('.js') || pathname.endsWith('.css') ||
         pathname.endsWith('.png') || pathname.endsWith('.svg') ||
         pathname.endsWith('.woff2') || pathname === '/';
}

function offlineResponse() {
  return new Response(
    '<html><body style="font-family:sans-serif;text-align:center;padding:60px"><h1>Offline</h1><p>You are currently offline. Please check your connection.</p></body></html>',
    { headers: { 'Content-Type': 'text/html' }, status: 503 }
  );
}
