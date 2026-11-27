package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AtomicMutationTransactionTest {
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final TransactionStatus status = mock(TransactionStatus.class);
    private final AtomicMutationTransaction transactions = new AtomicMutationTransaction(manager);

    @AfterEach void clear() { MetaContext.clear(); TransactionSynchronizationManager.clear(); }

    @Test void kernelCounterOwnsAReadCommittedTransactionWithoutAReplay() {
        MetaContext.setSystemTenantContext(42L);
        when(manager.getTransaction(any())).thenReturn(status);
        var update = mock(java.util.function.Supplier.class);
        when(update.get()).thenReturn(7L);
        assertThat(transactions.execute(update)).isEqualTo(7L);
        verify(manager).getTransaction(argThat(definition -> definition.getIsolationLevel() == TransactionDefinition.ISOLATION_READ_COMMITTED));
        verify(update, times(1)).get();
        verify(manager).commit(status);
    }

    @Test void ordinaryHumanCounterOwnsARepeatableReadAuthorizationSnapshot() {
        MetaContext.setContext(42L, 7L, "user-pid", "counter-user"); MetaContext.setMemberId(8L);
        when(manager.getTransaction(any())).thenReturn(status);
        assertThat(transactions.execute(() -> "updated")).isEqualTo("updated");
        verify(manager).getTransaction(argThat(definition -> definition.getIsolationLevel() == TransactionDefinition.ISOLATION_REPEATABLE_READ));
        verify(manager).commit(status);
    }

    @Test void aNamedIntegrationCarrierDoesNotBecomeAnAnonymousKernelCounter() {
        MetaContext.setContext(42L, 0L, "integration-runtime", "integration-runtime");
        when(manager.getTransaction(any())).thenReturn(status);
        transactions.execute(() -> "updated");
        verify(manager).getTransaction(argThat(definition -> definition.getIsolationLevel() == TransactionDefinition.ISOLATION_REPEATABLE_READ));
    }

    @Test void existingWeakCallerIsPreservedForTheNativeGuardToReject() {
        MetaContext.setContext(42L, 7L, "user-pid", "counter-user"); MetaContext.setMemberId(8L);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThat(transactions.execute(TransactionSynchronizationManager::getCurrentTransactionIsolationLevel))
                .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
        verifyNoInteractions(manager);
    }

    @Test void incompleteHumanIdentityCannotBorrowTheKernelTransaction() {
        MetaContext.setContext(42L, 7L, "user-pid", "counter-user");
        var update = mock(java.util.function.Supplier.class);
        assertThatThrownBy(() -> transactions.execute(update)).isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("NATIVE_ACTOR_INCOMPLETE");
        verifyNoInteractions(manager, update);
    }

    @Test void updateFailureRollsBackWithoutRetryingTheOperation() {
        MetaContext.setSystemTenantContext(42L);
        when(manager.getTransaction(any())).thenReturn(status);
        var update = mock(java.util.function.Supplier.class);
        when(update.get()).thenThrow(new IllegalStateException("counter failure"));
        assertThatThrownBy(() -> transactions.execute(update)).hasMessage("counter failure");
        verify(update, times(1)).get();
        verify(manager).rollback(status);
        verify(manager, never()).commit(any());
    }
}
