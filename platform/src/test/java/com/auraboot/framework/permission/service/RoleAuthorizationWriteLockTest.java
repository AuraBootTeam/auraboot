package com.auraboot.framework.permission.service;

import com.auraboot.framework.application.tenant.MetaContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RoleAuthorizationWriteLockTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ConcurrentMapCacheManager caches = new ConcurrentMapCacheManager("dataScopeCondition", "dataPermissionMaskRules");
    private final RoleAuthorizationWriteLock lock = new RoleAuthorizationWriteLock(jdbc, Map.of("permissions", caches));

    @AfterEach void cleanup() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.getSynchronizations().forEach(sync ->
                    sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        }
        TransactionSynchronizationManager.clear();
        MetaContext.clear();
    }

    @Test void refusesMissingAndReadonlyTransactionsBeforeDatabaseIo() {
        MetaContext.setCurrentTenantId(100L);
        assertThatThrownBy(lock::mutate).isInstanceOf(AccessDeniedException.class);
        transaction();
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThatThrownBy(lock::mutate).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(jdbc);
    }

    @Test void evictsDerivedCachesOnlyAfterCommitAndRegistersOnce() {
        transaction();
        MetaContext.setCurrentTenantId(100L);
        when(jdbc.queryForList("SELECT id FROM ab_tenant WHERE id = ? FOR UPDATE", Long.class, 100L)).thenReturn(List.of(100L));
        caches.getCache("dataScopeCondition").put("subject", "old-scope");
        caches.getCache("dataPermissionMaskRules").put("subject", "old-fields");
        lock.mutate();
        lock.mutate();
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
        assertThat(caches.getCache("dataScopeCondition").get("subject").get()).isEqualTo("old-scope");
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertThat(caches.getCache("dataScopeCondition").get("subject")).isNull();
        assertThat(caches.getCache("dataPermissionMaskRules").get("subject")).isNull();
    }

    @Test void rollbackKeepsCommittedCacheValues() {
        transaction();
        MetaContext.setCurrentTenantId(100L);
        when(jdbc.queryForList("SELECT id FROM ab_tenant WHERE id = ? FOR UPDATE", Long.class, 100L)).thenReturn(List.of(100L));
        caches.getCache("dataScopeCondition").put("subject", "committed");
        lock.mutate();
        TransactionSynchronizationManager.getSynchronizations().forEach(sync ->
                sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        assertThat(caches.getCache("dataScopeCondition").get("subject").get()).isEqualTo("committed");
    }

    @Test void rejectsCrossTenantMutationInTheSameTransaction() {
        transaction();
        when(jdbc.queryForList("SELECT id FROM ab_tenant WHERE id = ? FOR UPDATE", Long.class, 100L)).thenReturn(List.of(100L));
        when(jdbc.queryForList("SELECT id FROM ab_tenant WHERE id = ? FOR UPDATE", Long.class, 200L)).thenReturn(List.of(200L));
        lock.mutateForTenant(100L);
        assertThatThrownBy(() -> lock.mutateForTenant(200L)).isInstanceOf(AccessDeniedException.class);
    }

    private static void transaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }
}
