package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.plugin.extension.BusinessResourceAuthorization;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommandExecutionTransactionTest {
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final ExtensionRegistry extensions = mock(ExtensionRegistry.class);
    private final CommandExecutionTransaction transaction = new CommandExecutionTransaction(manager, extensions);
    private final TransactionStatus status = mock(TransactionStatus.class);

    @AfterEach void clear() { TransactionSynchronizationManager.clear(); }

    private void owner() {
        var handler = mock(CommandHandlerExtension.class, withSettings().extraInterfaces(BusinessResourceAuthorization.class));
        when(extensions.getBusinessResourceOwner("orders:export")).thenReturn(Optional.of(handler));
        when(((BusinessResourceAuthorization) handler).resourceReadIsolation()).thenReturn(BusinessResourceAuthorization.ReadIsolation.REPEATABLE_READ);
    }

    @Test void owner_isolation_is_selected_before_any_pipeline_operation() {
        owner(); when(manager.getTransaction(any())).thenReturn(status);
        assertThat(transaction.execute("orders:export", false, () -> {
            verify(manager).getTransaction(argThat(definition -> definition.getIsolationLevel() == TransactionDefinition.ISOLATION_REPEATABLE_READ
                    && definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRED));
            return "completed";
        })).isEqualTo("completed");
        verify(manager).commit(status);
    }

    @Test void ordinary_commands_keep_default_required_transaction_semantics() {
        when(manager.getTransaction(any())).thenReturn(status);
        assertThat(transaction.execute("orders:update", false, () -> "completed")).isEqualTo("completed");
        verify(manager).getTransaction(argThat(definition -> definition.getIsolationLevel() == TransactionDefinition.ISOLATION_DEFAULT
                && definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRED));
    }

    @Test void dry_run_marks_the_status_before_the_first_phase() {
        when(manager.getTransaction(any())).thenReturn(status);
        transaction.execute("orders:update", true, () -> {
            verify(status).setRollbackOnly(); return null;
        });
    }

    @Test void incompatible_existing_transaction_is_rejected_without_suspending_or_running_work() {
        owner(); TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        var operation = mock(java.util.function.Supplier.class);
        assertThatThrownBy(() -> transaction.execute("orders:export", false, operation)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(manager, operation);
    }

    @Test void execution_failure_rolls_back_the_same_transaction() {
        when(manager.getTransaction(any())).thenReturn(status);
        var failure = new IllegalStateException("failed");
        assertThatThrownBy(() -> transaction.execute("orders:update", false, () -> {throw failure;})).isSameAs(failure);
        verify(manager).rollback(status);
        verify(manager, never()).commit(any());
    }
}
