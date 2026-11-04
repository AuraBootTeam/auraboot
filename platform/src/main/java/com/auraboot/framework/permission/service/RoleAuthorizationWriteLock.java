package com.auraboot.framework.permission.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.cache.DataAccessCacheEpoch;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.Set;

/** Serializes tenant authorization authoring; ordinary business reads take no authoring lock. */
@Component
@RequiredArgsConstructor
public class RoleAuthorizationWriteLock {
    private static final Set<String> DERIVED_CACHES = Set.of("dataScopeCondition", "dataPermissionMaskRules");
    private final JdbcTemplate jdbc;
    private final Map<String, CacheManager> cacheManagers;
    private final Object mutationKey = new Object();

    /** Must be the first database operation in every supported authorization mutation. */
    public void lock() {
        lockTenant(MetaContext.getCurrentTenantId());
    }

    private void lockTenant(Long tenant) {
        if (TransactionSynchronizationManager.hasResource(mutationKey)
                && !java.util.Objects.equals(tenant, TransactionSynchronizationManager.getResource(mutationKey))) {
            throw new AccessDeniedException("One authorization mutation cannot span tenants");
        }
        if (tenant == null || !TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new AccessDeniedException("Tenant authorization authoring requires a writable transaction");
        }
        if (jdbc.queryForList("SELECT id FROM ab_tenant WHERE id = ? FOR UPDATE", Long.class, tenant).size() != 1) {
            throw new AccessDeniedException("Authorization tenant is unavailable");
        }
    }

    /** Reentrant within a transaction. Rollback never evicts committed authorization snapshots. */
    public void mutate() {
        mutateForTenant(MetaContext.getCurrentTenantId());
    }

    /** Explicit host-owned tenant for metadata registration outside an HTTP member context. */
    public void mutateForTenant(Long tenant) {
        lockTenant(tenant);
        if (TransactionSynchronizationManager.hasResource(mutationKey)) {
            if (!java.util.Objects.equals(tenant, TransactionSynchronizationManager.getResource(mutationKey))) {
                throw new AccessDeniedException("One authorization mutation cannot span tenants");
            }
            return;
        }
        TransactionSynchronizationManager.bindResource(mutationKey, tenant);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                DataAccessCacheEpoch.bump();
                for (CacheManager manager : cacheManagers.values()) {
                    for (String name : DERIVED_CACHES) {
                        if (manager.getCacheNames().contains(name)) {
                            var cache = manager.getCache(name);
                            if (cache != null) cache.clear();
                        }
                    }
                }
            }

            @Override public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResourceIfPossible(mutationKey);
            }
        });
    }
}
