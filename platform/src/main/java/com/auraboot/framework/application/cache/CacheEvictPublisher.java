package com.auraboot.framework.application.cache;

/**
 * Abstraction over the cross-instance eviction transport, so cache wiring does
 * not depend on Redis being present. The Redis implementation is registered
 * only when {@code spring.data.redis.host} is set; without it caches stay
 * single-instance exactly as before.
 */
public interface CacheEvictPublisher {

    void publish(CacheEvictMessage message);
}
