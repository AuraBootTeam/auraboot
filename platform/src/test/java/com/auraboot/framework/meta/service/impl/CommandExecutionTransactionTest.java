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

    @Test void domainEntryReusesTheDeclaredSerializableTransactionInsteadOfRequiringRepeatableRead() {
        var definitions=mock(com.auraboot.framework.meta.mapper.CommandDefinitionMapper.class);
        var snapshots=mock(com.auraboot.framework.permission.service.impl.PermissionSnapshotCache.class);
        org.springframework.test.util.ReflectionTestUtils.setField(transaction,"definitions",definitions);
        org.springframework.test.util.ReflectionTestUtils.setField(transaction,"snapshots",snapshots);
        com.auraboot.framework.application.tenant.MetaContext.setContext(100L,1L,"actor","actor");
        var command=new com.auraboot.framework.meta.entity.CommandDefinition();
        command.setExecutionConfig("{\"permissions\":[\"example.cap.manage\"],\"handlerParams\":{\"transactionIsolation\":\"serializable\"}}");
        when(definitions.findCurrentByCode("example:seed")).thenReturn(command);
        var permission=new com.auraboot.framework.permission.entity.Permission();permission.setResourceType("operation");permission.setAction("manage");
        permission.setExtension(java.util.Map.of("domainModels",java.util.Map.of("example",java.util.List.of("read","update"))));
        when(snapshots.resolvePermissionDefinition(100L,"example.cap.manage")).thenReturn(permission);
        when(manager.getTransaction(any())).thenReturn(status);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        try {
            assertThat(transaction.execute("example:seed",false,()->"completed")).isEqualTo("completed");
            verify(manager).getTransaction(argThat(value->value.getIsolationLevel()==TransactionDefinition.ISOLATION_SERIALIZABLE));
        } finally {com.auraboot.framework.application.tenant.MetaContext.clear();}
    }

    @Test void documentDomainReusesConfigurationOnlyInsideItsPhysicalTransaction() {
        var definitions=mock(com.auraboot.framework.meta.mapper.CommandDefinitionMapper.class);
        var snapshots=mock(com.auraboot.framework.permission.service.impl.PermissionSnapshotCache.class);
        var authorization=mock(com.auraboot.framework.permission.service.BusinessCommandRecordAuthorization.class);
        org.springframework.test.util.ReflectionTestUtils.setField(transaction,"definitions",definitions);
        org.springframework.test.util.ReflectionTestUtils.setField(transaction,"snapshots",snapshots);
        org.springframework.test.util.ReflectionTestUtils.setField(transaction,"authorization",authorization);
        var command=new com.auraboot.framework.meta.entity.CommandDefinition();
        command.setExecutionConfig("{\"permissions\":[\"example.cap.read\"],\"handlerParams\":{\"documentDefinition\":{}}}");
        when(definitions.findCurrentByCode("example:export")).thenReturn(command);
        var permission=new com.auraboot.framework.permission.entity.Permission();
        permission.setResourceType("operation");permission.setAction("read");
        permission.setExtension(java.util.Map.of("domainModels",java.util.Map.of("example",java.util.List.of("read"))));
        when(snapshots.resolvePermissionDefinition(100L,"example.cap.read")).thenReturn(permission);
        when(manager.getTransaction(any())).thenReturn(status);
        when(authorization.withReadProjectionSnapshot(any())).thenAnswer(call -> {
            verify(manager).getTransaction(argThat(value -> value.getIsolationLevel()==TransactionDefinition.ISOLATION_REPEATABLE_READ));
            return ((java.util.function.Supplier<?>)call.getArgument(0)).get();
        });
        com.auraboot.framework.application.tenant.MetaContext.setContext(100L,1L,"actor","actor");
        com.auraboot.framework.application.tenant.MetaContext.setMemberId(5L);
        try {
            assertThat(transaction.execute("example:export",false,()->"document")).isEqualTo("document");
            verify(authorization).withReadProjectionSnapshot(any());
            clearInvocations(authorization);
            command.setExecutionConfig("{\"permissions\":[\"example.cap.read\"]}");
            assertThat(transaction.execute("example:export",false,()->"ordinary")).isEqualTo("ordinary");
            verifyNoInteractions(authorization);
        } finally {com.auraboot.framework.application.tenant.MetaContext.clear();}
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

    @Test void ordinary_plugin_owner_can_require_a_snapshot_before_its_first_source_read() {
        var handler = mock(CommandHandlerExtension.class);
        when(extensions.getCommandHandler("bom:start_conversion")).thenReturn(Optional.of(handler));
        when(handler.commandReadIsolation()).thenReturn(BusinessResourceAuthorization.ReadIsolation.REPEATABLE_READ);
        when(manager.getTransaction(any())).thenReturn(status);
        assertThat(transaction.execute("bom:start_conversion",false,()->"queued")).isEqualTo("queued");
        verify(manager).getTransaction(argThat(definition -> definition.getIsolationLevel() == TransactionDefinition.ISOLATION_REPEATABLE_READ));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        var operation = mock(java.util.function.Supplier.class);
        assertThatThrownBy(()->transaction.execute("bom:start_conversion",false,operation)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(operation);
    }

    @Test void conflicting_handler_and_resource_owners_cannot_weaken_the_transaction() {
        owner();
        var handler = mock(CommandHandlerExtension.class);
        when(extensions.getCommandHandler("orders:export")).thenReturn(Optional.of(handler));
        when(handler.commandReadIsolation()).thenReturn(BusinessResourceAuthorization.ReadIsolation.SERIALIZABLE);
        assertThatThrownBy(()->transaction.execute("orders:export",false,()->"bad")).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(manager);
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
