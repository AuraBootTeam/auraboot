import { it, expect, beforeEach, vi } from 'vitest';
import {
  createPublicTracker,
  ensureAnonId,
  browserAnonIdStore,
  ANON_ID_KEY,
  SESSION_ID_KEY,
  type AnonIdStore,
} from '../public';

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  // Clear all cookies
  document.cookie.split(';').forEach((c) => {
    const name = c.split('=')[0].trim();
    if (name) document.cookie = `${name}=;expires=Thu, 01 Jan 1970 00:00:00 GMT;path=/`;
  });
});

// In-memory store helper for deterministic post/anonId assertions.
function memStore(initial: string | null = null): AnonIdStore {
  let v = initial;
  return { get: () => v, set: (id) => { v = id; } };
}

it('posts to /api/collect/keyed by default with X-Site-Key header + keepalive', async () => {
  const fetchImpl = vi.fn().mockResolvedValue({ ok: true });
  const t = createPublicTracker({
    siteKey: 'abk_test_key',
    batchSize: 1,
    fetchImpl: fetchImpl as unknown as typeof fetch,
    anonIdStore: memStore('anon-fixed'),
  });
  t.pageview('/p/c/public-app');
  await Promise.resolve();
  await Promise.resolve();

  expect(fetchImpl).toHaveBeenCalledTimes(1);
  const [url, init] = fetchImpl.mock.calls[0];
  expect(url).toBe('/api/collect/keyed');
  expect(init.method).toBe('POST');
  expect(init.headers['X-Site-Key']).toBe('abk_test_key');
  expect(init.headers['Content-Type']).toBe('application/json');
  expect(init.keepalive).toBe(true);
  const body = JSON.parse(init.body);
  expect(body.events[0].eventName).toBe('page_view');
  expect(body.events[0].anonId).toBe('anon-fixed');
});

it('respects a custom absolute collectUrl (cross-origin published-app deployment)', async () => {
  const fetchImpl = vi.fn().mockResolvedValue({ ok: true });
  const t = createPublicTracker({
    siteKey: 'abk_x',
    collectUrl: 'https://telemetry.example.com/api/collect/keyed',
    batchSize: 1,
    fetchImpl: fetchImpl as unknown as typeof fetch,
    anonIdStore: memStore('anon-1'),
  });
  t.pageview('/home');
  await Promise.resolve();
  await Promise.resolve();
  expect(fetchImpl.mock.calls[0][0]).toBe('https://telemetry.example.com/api/collect/keyed');
});

it('emits a stable anonId across multiple events', async () => {
  const fetchImpl = vi.fn().mockResolvedValue({ ok: true });
  const t = createPublicTracker({
    siteKey: 'abk_x',
    batchSize: 2,
    fetchImpl: fetchImpl as unknown as typeof fetch,
    anonIdStore: memStore(), // empty → generated once, reused
  });
  t.pageview('/a');
  t.pageview('/b');
  await Promise.resolve();
  await Promise.resolve();
  const body = JSON.parse(fetchImpl.mock.calls[0][1].body);
  expect(body.events).toHaveLength(2);
  expect(body.events[0].anonId).toBeTruthy();
  expect(body.events[0].anonId).toBe(body.events[1].anonId);
});

it('does not import platform services — works with zero deps (pure fetch + storage)', async () => {
  // Sanity: createPublicTracker runs with only injected fetch + an in-memory store,
  // proving no hard dependency on the platform http-client / JWT.
  const fetchImpl = vi.fn().mockResolvedValue({ ok: true });
  expect(() =>
    createPublicTracker({
      siteKey: 'abk_x',
      fetchImpl: fetchImpl as unknown as typeof fetch,
      anonIdStore: memStore('a'),
    }),
  ).not.toThrow();
});

// ─── anonId persistence (browser store) ────────────────────────────────────────

it('ensureAnonId generates once and persists to cookie + localStorage', () => {
  const store = browserAnonIdStore();
  const first = ensureAnonId(store, () => 'gen-anon-1');
  expect(first).toBe('gen-anon-1');
  // Persisted to localStorage
  expect(localStorage.getItem(ANON_ID_KEY)).toBe('gen-anon-1');
  // Persisted to cookie
  expect(document.cookie).toContain(`${ANON_ID_KEY}=gen-anon-1`);
  // Second call reads back the same id (no regenerate)
  const second = ensureAnonId(store, () => 'gen-anon-2-SHOULD-NOT-BE-USED');
  expect(second).toBe('gen-anon-1');
});

it('browser store falls back to localStorage when cookie is unavailable', () => {
  // Simulate a cookie-blocked environment: only localStorage has the value.
  localStorage.setItem(ANON_ID_KEY, 'ls-only-anon');
  const store = browserAnonIdStore();
  expect(store.get()).toBe('ls-only-anon');
});

it('persisted anonId survives across a new tracker instance (same browser)', async () => {
  const fetchImpl = vi.fn().mockResolvedValue({ ok: true });
  // First instance generates + persists via the real browser store
  const t1 = createPublicTracker({
    siteKey: 'abk_x',
    batchSize: 1,
    fetchImpl: fetchImpl as unknown as typeof fetch,
    generateId: () => 'persist-anon-42',
  });
  t1.pageview('/a');
  await Promise.resolve();
  await Promise.resolve();
  const anon1 = JSON.parse(fetchImpl.mock.calls[0][1].body).events[0].anonId;
  expect(anon1).toBe('persist-anon-42');

  // Second instance (new tracker, same browser storage) must reuse it
  const t2 = createPublicTracker({
    siteKey: 'abk_x',
    batchSize: 1,
    fetchImpl: fetchImpl as unknown as typeof fetch,
    generateId: () => 'DIFFERENT-should-not-be-used',
  });
  t2.pageview('/b');
  await Promise.resolve();
  await Promise.resolve();
  const anon2 = JSON.parse(fetchImpl.mock.calls[1][1].body).events[0].anonId;
  expect(anon2).toBe('persist-anon-42');
});

it('generates and persists a session id in sessionStorage', async () => {
  const fetchImpl = vi.fn().mockResolvedValue({ ok: true });
  const t = createPublicTracker({
    siteKey: 'abk_x',
    batchSize: 1,
    fetchImpl: fetchImpl as unknown as typeof fetch,
    anonIdStore: memStore('a'),
  });
  t.pageview('/a');
  await Promise.resolve();
  await Promise.resolve();
  const sid = sessionStorage.getItem(SESSION_ID_KEY);
  expect(sid).toBeTruthy();
  const body = JSON.parse(fetchImpl.mock.calls[0][1].body);
  expect(body.events[0].clientSessionId).toBe(sid);
});
