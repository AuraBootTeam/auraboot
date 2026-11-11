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
            verify(manager,atLeastOnce()).getTransaction(argThat(value -> value.getIsolationLevel()==TransactionDefinition.ISOLATION_REPEATABLE_READ));
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

    @Test void currentStateLockConflictRestartsAfterRollbackWithAFreshInvocationAndMetadata() {
        var domains = mock(com.auraboot.framework.permission.service.DomainCommandAuthorization.class);
        var definitions = mock(com.auraboot.framework.meta.mapper.CommandDefinitionMapper.class);
        org.springframework.test.util.ReflectionTestUtils.setField(transaction, "domains", domains);
        org.springframework.test.util.ReflectionTestUtils.setField(transaction, "definitions", definitions);
        when(domains.withinInvocation(any())).thenAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(0)).get());
        var first = mock(TransactionStatus.class);
        var second = mock(TransactionStatus.class);
        when(manager.getTransaction(any())).thenReturn(first, second);
        var handler = mock(CommandHandlerExtension.class);
        when(extensions.getCommandHandler("bom:cancel_task")).thenReturn(Optional.of(handler));
        when(handler.commandReadIsolation()).thenReturn(BusinessResourceAuthorization.ReadIsolation.REPEATABLE_READ);
        var conflict = new com.auraboot.framework.meta.exception.CurrentStateTargetLockConflictException(
                new org.springframework.dao.CannotSerializeTransactionException("concurrent chunk committed"));
        var attempts = new java.util.concurrent.atomic.AtomicInteger();

        assertThat(transaction.execute("bom:cancel_task", false, () -> {
            if (attempts.incrementAndGet() == 1) throw conflict;
            verify(manager).rollback(first);
            return "cancelled";
        })).isEqualTo("cancelled");

        assertThat(attempts).hasValue(2);
        verify(domains, times(2)).withinInvocation(any());
        verify(definitions, times(2)).findCurrentByCode("bom:cancel_task");
        verify(manager, times(2)).getTransaction(argThat(definition ->
                definition.getIsolationLevel() == TransactionDefinition.ISOLATION_REPEATABLE_READ));
        verify(manager).commit(second);
        verify(manager, never()).commit(first);
    }

    @Test void anOrdinarySerializationConflictIsNeverReexecuted() {
        when(manager.getTransaction(any())).thenReturn(status);
        var conflict = new org.springframework.dao.CannotSerializeTransactionException("ordinary edit conflict");
        assertThatThrownBy(() -> transaction.execute("orders:update", false, () -> { throw conflict; }))
                .isSameAs(conflict);
        verify(manager).getTransaction(any());
        verify(manager).rollback(status);
    }

    @Test void aGrantRevokedBetweenAttemptsCannotSurviveInThePreviousInvocationCache() {
        var snapshots = mock(com.auraboot.framework.permission.service.impl.PermissionSnapshotCache.class);
        var bindings = mock(com.auraboot.framework.rbac.mapper.RolePermissionMapper.class);
        var domains = new com.auraboot.framework.permission.service.DomainCommandAuthorization(snapshots, bindings);
        org.springframework.test.util.ReflectionTestUtils.setField(transaction, "domains", domains);
        var permission = new com.auraboot.framework.permission.entity.Permission();
        permission.setResourceType("operation");
        permission.setAction("write");
        permission.setExtension(java.util.Map.of("domainModels", java.util.Map.of("bom_task", java.util.List.of("read", "update"))));
        when(snapshots.resolvePermissionDefinition(100L, "bom.cap.write")).thenReturn(permission);
        var grant = new com.auraboot.framework.rbac.mapper.RolePermissionMapper.BusinessGrantRow();
        grant.setId(10L);
        grant.setGrantType("grant");
        when(bindings.findBusinessGrants(100L, 1L, 5L, "bom.cap.write"))
                .thenReturn(java.util.List.of(grant), java.util.List.of());
        when(manager.getTransaction(any())).thenReturn(status);
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var denied = new AccessDeniedException("grant revoked before the fresh attempt");
        com.auraboot.framework.application.tenant.MetaContext.setContext(100L, 1L, "actor", "actor");
        com.auraboot.framework.application.tenant.MetaContext.setMemberId(5L);
        try {
            assertThatThrownBy(() -> transaction.execute("bom:cancel_task", false, () -> {
                if (attempts.incrementAndGet() == 1) {
                    assertThat(domains.authorize("bom.cap.write")).isTrue();
                    throw new com.auraboot.framework.meta.exception.CurrentStateTargetLockConflictException(
                            new org.springframework.dao.CannotSerializeTransactionException("chunk commit"));
                }
                assertThat(domains.authorize("bom.cap.write")).isFalse();
                throw denied;
            })).isSameAs(denied);
            verify(bindings, times(2)).findBusinessGrants(100L, 1L, 5L, "bom.cap.write");
            verify(manager, never()).commit(any());
        } finally { com.auraboot.framework.application.tenant.MetaContext.clear(); }
    }

    @Test void currentStateLockConflictIsReexecutedAtMostOnce() {
        when(manager.getTransaction(any())).thenReturn(status);
        var conflict = new com.auraboot.framework.meta.exception.CurrentStateTargetLockConflictException(
                new org.springframework.dao.CannotSerializeTransactionException("another writer committed"));
        assertThatThrownBy(() -> transaction.execute("bom:cancel_task", false, () -> { throw conflict; }))
                .isSameAs(conflict);
        verify(manager, times(2)).getTransaction(any());
        verify(manager, times(2)).rollback(status);
        verify(manager, never()).commit(any());
    }

    @Test void nestedCurrentStateConflictCannotReplayAnAlreadyStartedOuterHandler() {
        when(manager.getTransaction(any())).thenReturn(status);
        var conflict = new com.auraboot.framework.meta.exception.CurrentStateTargetLockConflictException(
                new org.springframework.dao.CannotSerializeTransactionException("nested target changed"));
        var outerInvocations = new java.util.concurrent.atomic.AtomicInteger();
        assertThatThrownBy(() -> transaction.execute("bom:cancel_task", false, () -> {
            outerInvocations.incrementAndGet();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                return transaction.execute("bom:cancel_task", false, () -> { throw conflict; });
            } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        })).isInstanceOf(org.springframework.dao.CannotSerializeTransactionException.class)
                .isNotInstanceOf(com.auraboot.framework.meta.exception.CurrentStateTargetLockConflictException.class)
                .hasCause(conflict);
        assertThat(outerInvocations).hasValue(1);
        verify(manager, never()).commit(any());
    }

    @Test void currentStateLockConflictCannotReplayInsideACallersTransactionOrDryRun() {
        var conflict = new com.auraboot.framework.meta.exception.CurrentStateTargetLockConflictException(
                new org.springframework.dao.CannotSerializeTransactionException("concurrent update"));
        for (boolean outer : new boolean[]{false, true}) {
            for (boolean dryRun : new boolean[]{false, true}) {
                if (!outer && !dryRun) continue;
                clearInvocations(manager, status);
                when(manager.getTransaction(any())).thenReturn(status);
                TransactionSynchronizationManager.setActualTransactionActive(outer);
                var failure = assertThatThrownBy(() -> transaction.execute("bom:cancel_task", dryRun, () -> { throw conflict; }));
                if (outer) {
                    failure.isInstanceOf(org.springframework.dao.CannotSerializeTransactionException.class)
                            .isNotInstanceOf(com.auraboot.framework.meta.exception.CurrentStateTargetLockConflictException.class)
                            .hasCause(conflict);
                } else {
                    failure.isSameAs(conflict);
                }
                verify(manager).getTransaction(any());
                verify(manager).rollback(status);
            }
        }
    }
}
