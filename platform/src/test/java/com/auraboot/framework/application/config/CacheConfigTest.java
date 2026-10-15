package com.auraboot.framework.application.config;

import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CacheConfigTest {
    @Test
    void productionCachesHaveBoundedPassiveExpiryWithoutRedis() {
        CacheConfig config = new CacheConfig();
        assertPolicy(config.cacheManager(), "modelDefinitions", Duration.ofMinutes(30), 10_000);
        assertPolicy(config.permissionCacheManager(), "permission-catalog", Duration.ofMinutes(5), 5_000);
        assertPolicy(config.permissionCacheManager(), "user-permissions", Duration.ofMinutes(5), 5_000);
    }

    private void assertPolicy(CacheManager manager, String name, Duration ttl, long maximum) {
        Cache<?, ?> cache = (Cache<?, ?>) manager.getCache(name).getNativeCache();
        assertThat(cache.policy().expireAfterWrite().orElseThrow().getExpiresAfter()).isEqualTo(ttl);
        assertThat(cache.policy().eviction().orElseThrow().getMaximum()).isEqualTo(maximum);
    }
}
