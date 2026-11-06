package com.auraboot.framework.permission.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The member-keyed dataScopeCondition region must not be evicted while the writing
 * transaction is still open: a pre-commit clear lets a concurrent request re-cache the
 * pre-change row filters, which then survive the commit for a full TTL.
 */
class DataScopeCacheEvictorTest {

    private Cache cache;
    private DataScopeCacheEvictor evictor;

    @BeforeEach
    void setUp() {
        CacheManager cacheManager = mock(CacheManager.class);
        cache = mock(Cache.class);
        when(cacheManager.getCache(DataScopeCacheEvictor.DATA_SCOPE_CONDITION_CACHE)).thenReturn(cache);
        evictor = new DataScopeCacheEvictor(cacheManager);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void evictsImmediatelyOutsideATransaction() {
        evictor.evictAfterCommit();

        verify(cache).clear();
    }

    @Test
    void defersEvictionUntilTheWritingTransactionCommits() {
        TransactionSynchronizationManager.initSynchronization();

        evictor.evictAfterCommit();
        verify(cache, never()).clear();

        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }
        verify(cache).clear();
    }

    @Test
    void aRolledBackWriteLeavesTheCacheAlone() {
        TransactionSynchronizationManager.initSynchronization();

        evictor.evictAfterCommit();
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }

        verify(cache, never()).clear();
    }

    @Test
    void missingRegionIsNotAnError() {
        CacheManager emptyManager = mock(CacheManager.class);
        when(emptyManager.getCache(DataScopeCacheEvictor.DATA_SCOPE_CONDITION_CACHE)).thenReturn(null);

        assertDoesNotThrow(() -> new DataScopeCacheEvictor(emptyManager).evictAfterCommit());
    }
}
