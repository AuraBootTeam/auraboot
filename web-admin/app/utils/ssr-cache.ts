/**
 * Simple in-memory LRU cache for SSR loader data on public routes.
 *
 * Only public pages (login, register, etc.) are cached because their
 * response does not depend on user-specific data.  Authenticated
 * requests are never cached.
 *
 * - TTL: 30 seconds (configurable)
 * - Max entries: 100 (LRU eviction)
 * - Key: `${pathname}::${locale}` — different locales produce different i18n bundles
 */

interface CacheEntry<T> {
  value: T;
  timestamp: number;
}

export class LruTtlCache<T> {
  private cache = new Map<string, CacheEntry<T>>();
  private readonly maxSize: number;
  private readonly ttlMs: number;

  constructor(maxSize = 100, ttlMs = 30_000) {
    this.maxSize = maxSize;
    this.ttlMs = ttlMs;
  }

  get(key: string): T | undefined {
    const entry = this.cache.get(key);
    if (!entry) return undefined;

    // TTL expired — remove and return miss
    if (Date.now() - entry.timestamp > this.ttlMs) {
      this.cache.delete(key);
      return undefined;
    }

    // LRU refresh: delete + re-insert moves entry to the end of iteration order
    this.cache.delete(key);
    this.cache.set(key, entry);
    return entry.value;
  }

  set(key: string, value: T): void {
    // If key already exists, delete it first so re-insert refreshes order
    if (this.cache.has(key)) {
      this.cache.delete(key);
    }

    // Evict oldest entry (first key in Map iteration order) when at capacity
    if (this.cache.size >= this.maxSize) {
      const oldestKey = this.cache.keys().next().value;
      if (oldestKey !== undefined) {
        this.cache.delete(oldestKey);
      }
    }

    this.cache.set(key, { value, timestamp: Date.now() });
  }

  get size(): number {
    return this.cache.size;
  }

  clear(): void {
    this.cache.clear();
  }
}

/**
 * Singleton cache instance for SSR public-route loader data.
 * Keyed by `${pathname}::${locale}`.
 */
export const ssrLoaderCache = new LruTtlCache<unknown>(100, 30_000);

/**
 * Build the cache key for a public SSR request.
 */
export function ssrCacheKey(pathname: string, locale: string): string {
  return `${pathname}::${locale}`;
}
