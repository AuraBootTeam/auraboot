package com.auraboot.framework.application.config;

import com.auraboot.framework.application.cache.BroadcastableCaffeineCacheManager;
import com.auraboot.framework.application.cache.CacheEvictPublisher;
import com.auraboot.framework.application.cache.BroadcastingCache;
import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CacheConfigTest {
    @Test
    void productionCachesHaveBoundedPassiveExpiryWithoutRedis() {
        CacheConfig config = new CacheConfig();
        assertPolicy(config.cacheManager(noPublisher()), "modelDefinitions", Duration.ofMinutes(30), 10_000);
        assertPolicy(config.permissionCacheManager(noPublisher()), "permission-catalog", Duration.ofMinutes(5), 5_000);
        assertPolicy(config.permissionCacheManager(noPublisher()), "user-permissions", Duration.ofMinutes(5), 5_000);
    }

    @Test
    void withPublisherPresentCachesAreBroadcastWrapped() {
        CacheConfig config = new CacheConfig();
        CacheEvictPublisher publisher = message -> { };
        BroadcastableCaffeineCacheManager manager = config.cacheManager(fixedProvider(publisher));

        assertThat(manager.getCache("modelDefinitions")).isInstanceOf(BroadcastingCache.class);
        // getNativeCache stays the plain Caffeine cache, so policy inspection and
        // anything reading the native cache keeps working through the wrapper.
        assertThat(manager.getCache("modelDefinitions").getNativeCache())
            .isInstanceOf(com.github.benmanes.caffeine.cache.Cache.class);
    }

    private void assertPolicy(BroadcastableCaffeineCacheManager manager, String name, Duration ttl, long maximum) {
        Cache<?, ?> cache = (Cache<?, ?>) manager.getCache(name).getNativeCache();
        assertThat(cache.policy().expireAfterWrite().orElseThrow().getExpiresAfter()).isEqualTo(ttl);
        assertThat(cache.policy().eviction().orElseThrow().getMaximum()).isEqualTo(maximum);
    }

    private static ObjectProvider<CacheEvictPublisher> noPublisher() {
        return fixedProvider(null);
    }

    private static ObjectProvider<CacheEvictPublisher> fixedProvider(CacheEvictPublisher publisher) {
        return new ObjectProvider<>() {
            @Override
            public CacheEvictPublisher getObject() {
                return publisher;
            }

            @Override
            public CacheEvictPublisher getIfAvailable() {
                return publisher;
            }
        };
    }
}
