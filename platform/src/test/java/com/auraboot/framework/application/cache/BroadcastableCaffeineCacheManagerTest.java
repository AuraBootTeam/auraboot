package com.auraboot.framework.application.cache;

import org.junit.jupiter.api.Test;
import org.springframework.cache.caffeine.CaffeineCacheManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BroadcastableCaffeineCacheManagerTest {

    @Test
    void withoutPublisherBehavesLikeStockManager() {
        BroadcastableCaffeineCacheManager manager = new BroadcastableCaffeineCacheManager(null);
        manager.setCacheNames(List.of("dictData"));

        assertThat(manager.getCache("dictData")).isNotInstanceOf(BroadcastingCache.class);
        assertThat(manager.getCache("dictData")).isInstanceOf(org.springframework.cache.Cache.class);
    }

    @Test
    void withPublisherWrapsEveryRegion() {
        BroadcastableCaffeineCacheManager manager = new BroadcastableCaffeineCacheManager(message -> { });
        manager.setCacheNames(List.of("dictData", "modelDefinitions"));

        assertThat(manager.getCache("dictData")).isInstanceOf(BroadcastingCache.class);
        assertThat(manager.getCache("modelDefinitions")).isInstanceOf(BroadcastingCache.class);
        assertThat(manager.getCache("unknown-region")).isNull();
    }

    @Test
    void wrappedCachesShareTheUnderlyingNativeCache() {
        BroadcastableCaffeineCacheManager manager = new BroadcastableCaffeineCacheManager(message -> { });
        manager.setCacheNames(List.of("dictData"));

        Object first = manager.getCache("dictData").getNativeCache();
        Object second = manager.getCache("dictData").getNativeCache();

        assertThat(first).isSameAs(second);
        assertThat(((CaffeineCacheManager) manager).getCache("dictData")).isInstanceOf(BroadcastingCache.class);
    }
}
