package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.CALLS_REAL_METHODS;

class DynamicDataAfterCommitContextTest {
    private final DynamicDataServiceImpl service = mock(DynamicDataServiceImpl.class, CALLS_REAL_METHODS);

    @AfterEach void cleanup() {
        MetaContext.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private TransactionSynchronization register(Runnable callback) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        ReflectionTestUtils.invokeMethod(service, "triggerAutomationAfterCommit", "owned fixture", callback);
        return TransactionSynchronizationManager.getSynchronizations().getFirst();
    }

    @Test void committedCallbackUsesRegistrationIdentityAfterRequestCleanup() {
        MetaContext.setContext(99L, 11L, "actor", "Actor");
        AtomicReference<MetaContext.Snapshot> observed = new AtomicReference<>();
        MetaContext.Snapshot expected = MetaContext.snapshot();
        TransactionSynchronization synchronization = register(() -> observed.set(MetaContext.snapshot()));
        MetaContext.clear();
        synchronization.afterCommit();
        assertThat(observed.get()).isEqualTo(expected);
        assertThat(MetaContext.snapshot()).isNull();
    }

    @Test void anotherExecutingTenantAndItsBypassAreNotInheritedByCommittedCallback() {
        MetaContext.setContext(99L, 11L, "actor", "Actor");
        AtomicReference<Long> tenant = new AtomicReference<>();
        AtomicReference<Boolean> bypass = new AtomicReference<>();
        TransactionSynchronization synchronization = register(() -> {
            tenant.set(MetaContext.getCurrentTenantId());
            bypass.set(MetaContext.isTenantFilterBypassed());
            throw new IllegalStateException("fixture automation failed");
        });
        MetaContext.setContext(199L, 22L, "worker", "Worker");
        MetaContext.Snapshot previous = MetaContext.snapshot();
        MetaContext.runWithoutTenantFilter(() -> {
            synchronization.afterCommit();
            assertThat(MetaContext.isTenantFilterBypassed()).isTrue();
        });
        assertThat(tenant.get()).isEqualTo(99L);
        assertThat(bypass.get()).isFalse();
        assertThat(MetaContext.snapshot()).isEqualTo(previous);
    }

    @Test void rollbackNeverDispatchesAutomation() {
        MetaContext.setContext(99L, 11L, "actor", "Actor");
        AtomicInteger calls = new AtomicInteger();
        TransactionSynchronization synchronization = register(calls::incrementAndGet);
        synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThat(calls.get()).isZero();
    }
}
