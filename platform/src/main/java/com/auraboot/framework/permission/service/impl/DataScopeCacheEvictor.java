package com.auraboot.framework.permission.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Post-commit eviction for the member-keyed {@code dataScopeCondition} cache region.
 *
 * <p>Writes whose inputs feed resolved row filters — role data scopes, team membership —
 * frequently run inside a longer transaction: a plugin import applying many grants, or an
 * admin save that also writes other tables. An eviction that fires at method-return time
 * sits BEFORE that commit, so a concurrent request re-caches the pre-change filters, which
 * then survive the actual commit for a full TTL. Deferring the clear to afterCommit closes
 * that window; with no Spring-managed transaction to defer to there is nothing to wait for,
 * so eviction is immediate — the same fallbackExecution semantic the AFTER_COMMIT eviction
 * listeners carry.
 */
@Slf4j
@Component
public class DataScopeCacheEvictor {

    public static final String DATA_SCOPE_CONDITION_CACHE = "dataScopeCondition";

    private final CacheManager permissionCacheManager;

    public DataScopeCacheEvictor(
            @Qualifier("permissionCacheManager") CacheManager permissionCacheManager) {
        this.permissionCacheManager = permissionCacheManager;
    }

    /** Evict every cached data-scope condition once the surrounding write has committed. */
    public void evictAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    clearRegion();
                }
            });
        } else {
            clearRegion();
        }
    }

    private void clearRegion() {
        Cache cache = permissionCacheManager.getCache(DATA_SCOPE_CONDITION_CACHE);
        if (cache != null) {
            cache.clear();
            log.info("dataScopeCondition cache evicted after write commit");
        }
    }
}
