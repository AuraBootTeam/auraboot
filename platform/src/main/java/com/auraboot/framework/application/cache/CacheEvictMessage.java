package com.auraboot.framework.application.cache;

import java.io.Serializable;

/**
 * Message published to Redis Pub/Sub when a local cache region is evicted,
 * consumed by other instances to drop the same entries from their Caffeine
 * caches. Keyed evictions carry the key in its {@link String} form; consumers
 * evict by that string, so exact-match cross-instance invalidation applies to
 * String-keyed regions (region-wide clears cover everything).
 */
public record CacheEvictMessage(
    String cache,
    String key,   // null when all=true
    boolean all   // true = whole region invalidated (@CacheEvict allEntries)
) implements Serializable {}
