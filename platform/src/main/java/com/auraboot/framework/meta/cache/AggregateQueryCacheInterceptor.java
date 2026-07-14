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
 * Invalidates the {@code aggregateQuery} cache whenever dynamic-model data is written.
 *
 * <p><b>Why an interceptor and not annotations.</b> {@code AggregateQueryService#execute} is
 * {@code @Cacheable("aggregateQuery")} and serves the dashboard chart endpoint
 * ({@code POST /api/meta/chart-data}) and the {@code chat_bi} skill. Nothing evicted it, so
 * after a record was inserted/updated/deleted the charts kept showing the pre-change
 * aggregate for up to the cache TTL (30 min) — the same class of defect as the dict cache
 * (OSS #1226), and found by the gate written for it.
 *
 * <p>Annotating the write paths was not viable: dynamic-table writes go through
 * {@code DynamicDataMapper} from <b>121 call sites across 39 classes</b> — the command
 * pipeline writes via {@code HandlerPhase} directly, not through {@code DynamicDataService}
 * — so any hand-wired approach is one new call site away from being wrong again. Every write
 * does, however, pass through the MyBatis executor. That is the one place where the
 * invalidation cannot be forgotten.
 *
 * <p><b>Why it clears everything rather than the affected model.</b> The cache key is
 * {@code dataAccessContext + ':' + request.hashCode()} — the model is folded into the hash,
 * so a key cannot be matched back to a model. The interceptor, on the other side, only has
 * the physical table name (from {@code model.getTableName()}, not derivable from a model code
 * without a metadata lookup). Bridging the two would mean either restructuring the cache key
 * or doing a table→model reverse lookup on every write. Neither is justified yet: the cache
 * key already includes the caller's data-access context, so entries are per-user to begin
 * with and hit rates are modest. Correct-and-simple first; if profiling later shows the
 * eviction is too broad, put the model code into the key and narrow this to a prefix sweep.
 *
 * <p>Clearing happens even if the surrounding transaction later rolls back. That costs one
 * recomputation and is always safe; the opposite trade (evict on commit only) risks serving
 * stale aggregates and is not.
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
