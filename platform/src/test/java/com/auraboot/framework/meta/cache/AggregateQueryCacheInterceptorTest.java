package com.auraboot.framework.meta.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Invocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The aggregate cache must be dropped when dynamic-model data changes.
 *
 * <p>Before this interceptor, {@code aggregateQuery} was never evicted: dashboard charts and
 * the chat_bi skill kept serving a pre-change aggregate for up to the 30-minute TTL after
 * rows were inserted/updated/deleted (found by scripts/check-cache-eviction.mjs, same class
 * of bug as the dict cache in #1226).
 */
@DisplayName("AggregateQueryCacheInterceptor — a data write invalidates cached aggregates")
class AggregateQueryCacheInterceptorTest {

    private CacheManager cacheManager;
    private AggregateQueryCacheInterceptor interceptor;

    @BeforeEach
    void setUp() {
        CaffeineCacheManager manager = new CaffeineCacheManager("aggregateQuery");
        manager.setCaffeine(Caffeine.newBuilder().maximumSize(100));
        this.cacheManager = manager;

        @SuppressWarnings("unchecked")
        ObjectProvider<CacheManager> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(manager);
        this.interceptor = new AggregateQueryCacheInterceptor(provider);
    }

    private void primeCache() {
        cacheManager.getCache("aggregateQuery").put("tenant:1:12345", "stale-aggregate");
        assertThat(cacheManager.getCache("aggregateQuery").get("tenant:1:12345")).isNotNull();
    }

    private String cached() {
        var hit = cacheManager.getCache("aggregateQuery").get("tenant:1:12345");
        return hit == null ? null : (String) hit.get();
    }

    /**
     * MyBatis's Invocation only accepts its own plugin targets (Executor, StatementHandler,
     * ...) — a stand-in class is rejected at construction with "not supported as a plugin
     * target". So mock the real Executor.
     */
    private Invocation invocationFor(String statementId, SqlCommandType type) throws Exception {
        MappedStatement ms = mock(MappedStatement.class);
        when(ms.getId()).thenReturn(statementId);
        when(ms.getSqlCommandType()).thenReturn(type);

        Executor executor = mock(Executor.class);
        when(executor.update(any(MappedStatement.class), any())).thenReturn(1);
        Method update = Executor.class.getMethod("update", MappedStatement.class, Object.class);
        return new Invocation(executor, update, new Object[] {ms, new Object()});
    }

    @Test
    @DisplayName("an INSERT into a dynamic model table drops the cached aggregates")
    void insertEvicts() throws Throwable {
        primeCache();
        interceptor.intercept(
                invocationFor(
                        "com.auraboot.framework.meta.mapper.DynamicDataMapper.insert",
                        SqlCommandType.INSERT));
        assertThat(cached())
                .as("a new row changes every count/sum over that model — the cached aggregate is dead")
                .isNull();
    }

    @Test
    @DisplayName("UPDATE and DELETE evict too")
    void updateAndDeleteEvict() throws Throwable {
        primeCache();
        interceptor.intercept(
                invocationFor(
                        "com.auraboot.framework.meta.mapper.DynamicDataMapper.update",
                        SqlCommandType.UPDATE));
        assertThat(cached()).isNull();

        primeCache();
        interceptor.intercept(
                invocationFor(
                        "com.auraboot.framework.meta.mapper.DynamicDataMapper.deleteByQuery",
                        SqlCommandType.DELETE));
        assertThat(cached()).isNull();
    }

    @Test
    @DisplayName("writes to platform tables (ab_*) leave the aggregate cache alone")
    void unrelatedMapperDoesNotEvict() throws Throwable {
        primeCache();
        interceptor.intercept(
                invocationFor(
                        "com.auraboot.framework.meta.mapper.DictItemMapper.insert",
                        SqlCommandType.INSERT));
        assertThat(cached())
                .as("aggregateQuery only aggregates dynamic-model rows; unrelated writes must not "
                        + "flush it, or the cache would be useless")
                .isEqualTo("stale-aggregate");
    }

    @Test
    @DisplayName("a SELECT through the same mapper does not evict")
    void selectDoesNotEvict() throws Throwable {
        primeCache();
        interceptor.intercept(
                invocationFor(
                        "com.auraboot.framework.meta.mapper.DynamicDataMapper.selectByQuery",
                        SqlCommandType.SELECT));
        assertThat(cached()).isEqualTo("stale-aggregate");
    }

    @Test
    @DisplayName("no cache manager configured → writes still succeed")
    void missingCacheManagerIsHarmless() throws Throwable {
        @SuppressWarnings("unchecked")
        ObjectProvider<CacheManager> empty = mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);
        AggregateQueryCacheInterceptor noCache = new AggregateQueryCacheInterceptor(empty);

        Object result =
                noCache.intercept(
                        invocationFor(
                                "com.auraboot.framework.meta.mapper.DynamicDataMapper.insert",
                                SqlCommandType.INSERT));
        assertThat(result).isEqualTo(1);
    }
}
