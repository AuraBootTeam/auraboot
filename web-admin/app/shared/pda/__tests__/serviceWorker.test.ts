// @vitest-environment node
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import { describe, expect, it, vi } from 'vitest';

function worker() {
  const listeners = new Map<string, (event: any) => void>();
  const cache = { addAll: vi.fn().mockResolvedValue(undefined), put: vi.fn() };
  const caches = {
    open: vi.fn().mockResolvedValue(cache),
    match: vi.fn().mockResolvedValue(new Response('stale anonymous shell')),
  };
  const fetch = vi.fn().mockResolvedValue(new Response('fresh document'));
  runInNewContext(readFileSync(new URL('../../../../public/sw.js', import.meta.url), 'utf8'), {
    self: {
      location: { origin: 'https://example.test' },
      addEventListener: (name: string, listener: (event: any) => void) =>
        listeners.set(name, listener),
      skipWaiting: vi.fn(),
      clients: { claim: vi.fn() },
    },
    caches,
    fetch,
    URL,
    Response,
    Request,
    console,
  });
  return { listeners, cache, caches, fetch };
}

describe('service worker document navigation boundary', () => {
  it('does not pre-cache the authenticated root redirect', async () => {
    const { listeners, cache } = worker();
    let installed: Promise<unknown> | undefined;
    listeners.get('install')!({
      waitUntil: (promise: Promise<unknown>) => {
        installed = promise;
      },
    });
    await installed;
    expect(cache.addAll).toHaveBeenCalledOnce();
    expect(cache.addAll.mock.calls[0][0]).not.toContain('/');
    expect(cache.addAll.mock.calls[0][0]).toContain('/manifest.json');
  });

  it.each(['/', '/login', '/home', '/merchant/catalog'])(
    'leaves %s navigation to the browser even with a stale cache',
    (path) => {
      const { listeners, caches, fetch } = worker();
      const respondWith = vi.fn();
      listeners.get('fetch')!({
        request: { url: `https://example.test${path}`, mode: 'navigate', method: 'GET' },
        respondWith,
      });
      expect(respondWith).not.toHaveBeenCalled();
      expect(caches.match).not.toHaveBeenCalled();
      expect(fetch).not.toHaveBeenCalled();
    },
  );

  it('preserves the explicit PDA network-first shell and offline fallback', async () => {
    const { listeners, caches, fetch } = worker();
    fetch.mockRejectedValueOnce(new TypeError('offline'));
    let response: Promise<Response> | undefined;
    listeners.get('fetch')!({
      request: { url: 'https://example.test/inventory/pda-pick', mode: 'navigate', method: 'GET' },
      respondWith: (promise: Promise<Response>) => {
        response = promise;
      },
    });
    expect(await (await response)!.text()).toBe('stale anonymous shell');
    expect(fetch).toHaveBeenCalledOnce();
    expect(caches.match).toHaveBeenCalledOnce();
  });
});
