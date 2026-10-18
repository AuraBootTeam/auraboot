package com.auraboot.framework.application.cache;

import org.springframework.cache.Cache;
import org.springframework.cache.caffeine.CaffeineCacheManager;

/**
 * CaffeineCacheManager whose caches broadcast evictions cross-instance when a
 * {@link CacheEvictPublisher} is available; without one (single-instance
 * deployments, Redis not configured) it behaves exactly like the stock
 * manager. The class stays concrete on purpose: the Redis subscriber injects
 * this type to reach the wrapped caches for receive-side eviction.
 */
public class BroadcastableCaffeineCacheManager extends CaffeineCacheManager {

    private final CacheEvictPublisher publisher;

    public BroadcastableCaffeineCacheManager(CacheEvictPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public Cache getCache(String name) {
        Cache cache = super.getCache(name);
        if (publisher == null || cache == null || cache instanceof BroadcastingCache) {
            return cache;
        }
        return new BroadcastingCache(name, cache, publisher);
    }
}
