package com.auraboot.framework.application.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.Test;
import org.springframework.cache.caffeine.CaffeineCache;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class BroadcastingCacheTest {

    private final CaffeineCache nativeCache = new CaffeineCache("dictData",
        Caffeine.newBuilder().build());
    private final List<CacheEvictMessage> published = new ArrayList<>();

    private BroadcastingCache cache() {
        return new BroadcastingCache("dictData", nativeCache, published::add);
    }

    @Test
    void evictDropsEntryAndBroadcastsKey() {
        nativeCache.put("gender", "G");
        cache().evict("gender");

        assertThat(nativeCache.get("gender")).isNull();
        assertThat(published).containsExactly(new CacheEvictMessage("dictData", "gender", false));
    }

    @Test
    void evictStringifiesNonStringKeys() {
        nativeCache.put(42L, "v");
        cache().evict(42L);

        assertThat(published).containsExactly(new CacheEvictMessage("dictData", "42", false));
    }

    @Test
    void clearInvalidatesRegionAndBroadcastsAll() {
        nativeCache.put("a", "1");
        nativeCache.put("b", "2");
        cache().clear();

        assertThat(nativeCache.get("a")).isNull();
        assertThat(nativeCache.get("b")).isNull();
        assertThat(published).containsExactly(new CacheEvictMessage("dictData", null, true));
    }

    @Test
    void evictIfPresentBroadcastsOnlyWhenPresent() {
        nativeCache.put("k", "v");
        boolean first = cache().evictIfPresent("k");
        boolean second = cache().evictIfPresent("k");

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(published).containsExactly(new CacheEvictMessage("dictData", "k", false));
    }

    @Test
    void brokenTransportNeverBlocksOrUndoesLocalEviction() {
        nativeCache.put("k", "v");
        AtomicBoolean broken = new AtomicBoolean(true);
        BroadcastingCache cache = new BroadcastingCache("dictData", nativeCache, message -> {
            if (broken.get()) {
                throw new IllegalStateException("redis down");
            }
        });

        assertThatCode(() -> cache.evict("k")).doesNotThrowAnyException();
        assertThat(nativeCache.get("k")).isNull();
    }

    @Test
    void remoteEvictionMutatesWithoutRepublishing() {
        nativeCache.put("k", "v");
        nativeCache.put("other", "v");
        BroadcastingCache cache = cache();

        cache.evictRemotely("k");
        cache.clearRemotely();

        assertThat(nativeCache.get("k")).isNull();
        assertThat(nativeCache.get("other")).isNull();
        assertThat(published).isEmpty();
    }

    @Test
    void readsAndWritesDelegateWithoutBroadcasting() {
        BroadcastingCache cache = cache();

        cache.put("k", "v");
        assertThat(cache.get("k")).isNotNull();
        assertThat(cache.getName()).isEqualTo("dictData");
        assertThat(cache.getNativeCache()).isSameAs(nativeCache.getNativeCache());

        assertThat(published).isEmpty();
    }
}
