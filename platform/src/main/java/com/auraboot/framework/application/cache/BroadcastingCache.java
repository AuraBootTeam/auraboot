package com.auraboot.framework.application.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;

import java.util.concurrent.Callable;

/**
 * Cache decorator that publishes local evictions to the other instances.
 * Delegates everything to the native Spring cache; only {@link #evict},
 * {@link #evictIfPresent} and {@link #clear} additionally broadcast after the
 * local mutation succeeded, so a broken transport can never block or undo an
 * eviction. Reads and writes are deliberately not broadcast: regions are
 * read-through from the database, and evictions dominate the invalidation
 * surface ({@code @CacheEvict}).
 *
 * <p>{@link #evictRemotely} / {@link #clearRemotely} are the receive-side
 * entry points for {@link CacheEvictRemoteSubscriber}: they mutate the native
 * cache without re-publishing, which is what keeps the broadcast from
 * looping.
 */
@Slf4j
@RequiredArgsConstructor
public class BroadcastingCache implements Cache {

    private final String name;
    private final Cache nativeCache;
    private final CacheEvictPublisher publisher;

    @Override
    public String getName() {
        return nativeCache.getName();
    }

    @Override
    public Object getNativeCache() {
        return nativeCache.getNativeCache();
    }

    @Override
    public ValueWrapper get(Object key) {
        return nativeCache.get(key);
    }

    @Override
    public <T> T get(Object key, Class<T> type) {
        return nativeCache.get(key, type);
    }

    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
        return nativeCache.get(key, valueLoader);
    }

    @Override
    public void put(Object key, Object value) {
        nativeCache.put(key, value);
    }

    @Override
    public ValueWrapper putIfAbsent(Object key, Object value) {
        return nativeCache.putIfAbsent(key, value);
    }

    @Override
    public void evict(Object key) {
        nativeCache.evict(key);
        publish(new CacheEvictMessage(name, String.valueOf(key), false));
    }

    @Override
    public boolean evictIfPresent(Object key) {
        boolean present = nativeCache.evictIfPresent(key);
        if (present) {
            publish(new CacheEvictMessage(name, String.valueOf(key), false));
        }
        return present;
    }

    @Override
    public void clear() {
        nativeCache.clear();
        publish(new CacheEvictMessage(name, null, true));
    }

    /** Receive-side eviction: mutates the native cache without re-publishing. */
    public void evictRemotely(Object key) {
        nativeCache.evict(key);
    }

    /** Receive-side invalidation: mutates the native cache without re-publishing. */
    public void clearRemotely() {
        nativeCache.clear();
    }

    private void publish(CacheEvictMessage message) {
        try {
            publisher.publish(message);
        } catch (Exception e) {
            log.warn("Cache broadcast: failed to publish eviction for cache={}: {}",
                message.cache(), e.getMessage());
        }
    }
}
