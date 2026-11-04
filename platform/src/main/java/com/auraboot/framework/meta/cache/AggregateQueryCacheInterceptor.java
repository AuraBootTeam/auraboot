package com.auraboot.framework.meta.cache;

import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

/**
 * Compatibility invalidator for installations that explicitly register aggregate result caches.
 *
 * <p>Default aggregate queries are intentionally uncached: authorization and data
 * scopes execute inside the query method and must run again after permission
 * changes. No default {@code aggregateQuery} region is registered. When an
 * installation has no such explicit region, this interceptor does nothing.
 *
 * <p>For compatible custom installations, dynamic-model writes still invalidate
 * that region through the MyBatis executor, including rolled-back writes.
 */
@Slf4j
@Component
@Intercepts({
    @Signature(
            type = Executor.class,
            method = "update",
            args = {MappedStatement.class, Object.class})
})
public class AggregateQueryCacheInterceptor implements Interceptor {

    private static final String CACHE_NAME = "aggregateQuery";
    /** Mapper whose statements write dynamic-model (mt_*) tables. */
    private static final String DYNAMIC_DATA_MAPPER = "DynamicDataMapper";

    /**
     * ObjectProvider rather than a hard dependency: the cache manager is absent in some
     * slices/tests, and a missing cache must not break writes.
     */
    private final ObjectProvider<CacheManager> cacheManager;

    public AggregateQueryCacheInterceptor(ObjectProvider<CacheManager> cacheManager) {
        this.cacheManager = cacheManager;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        Object result = invocation.proceed();

        MappedStatement ms = (MappedStatement) invocation.getArgs()[0];
        if (writesDynamicData(ms)) {
            evictAggregates(ms.getId());
        }
        return result;
    }

    private boolean writesDynamicData(MappedStatement ms) {
        SqlCommandType type = ms.getSqlCommandType();
        if (type != SqlCommandType.INSERT
                && type != SqlCommandType.UPDATE
                && type != SqlCommandType.DELETE) {
            return false;
        }
        // ms.getId() is the fully-qualified mapper method, e.g.
        // com.auraboot.framework.meta.mapper.DynamicDataMapper.insert
        return ms.getId().contains(DYNAMIC_DATA_MAPPER);
    }

    private void evictAggregates(String statementId) {
        CacheManager manager = cacheManager.getIfAvailable();
        if (manager == null) {
            return;
        }
        Cache cache = manager.getCache(CACHE_NAME);
        if (cache == null) {
            return;
        }
        cache.clear();
        log.debug("Evicted '{}' cache after dynamic-data write via {}", CACHE_NAME, statementId);
    }
}
