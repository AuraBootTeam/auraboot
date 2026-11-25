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

    @Test void documentDomainDoesNotCarryAHandlerSnapshotAcrossIsolatedBoundaryPhases() {
        var definitions=mock(com.auraboot.framework.meta.mapper.CommandDefinitionMapper.class);
        var snapshots=mock(com.auraboot.framework.permission.service.impl.PermissionSnapshotCache.class);
        var authorization=mock(com.auraboot.framework.permission.service.BusinessCommandRecordAuthorization.class);
        org.springframework.test.util.ReflectionTestUtils.setField(transaction,"definitions",definitions);
        org.springframework.test.util.ReflectionTestUtils.setField(transaction,"snapshots",snapshots);
        var command=new com.auraboot.framework.meta.entity.CommandDefinition();
        command.setExecutionConfig("{\"permissions\":[\"example.cap.read\"],\"handlerParams\":{\"documentDefinition\":{}}}");
        when(definitions.findCurrentByCode("example:export")).thenReturn(command);
        var permission=new com.auraboot.framework.permission.entity.Permission();
        permission.setResourceType("operation");permission.setAction("read");
        permission.setExtension(java.util.Map.of("domainModels",java.util.Map.of("example",java.util.List.of("read"))));
        when(snapshots.resolvePermissionDefinition(100L,"example.cap.read")).thenReturn(permission);
        when(manager.getTransaction(any())).thenReturn(status);
        com.auraboot.framework.application.tenant.MetaContext.setContext(100L,1L,"actor","actor");
        com.auraboot.framework.application.tenant.MetaContext.setMemberId(5L);
        try {
            assertThat(transaction.execute("example:export",false,()->"document")).isEqualTo("document");
            verifyNoInteractions(authorization);
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

    private CommandHandlerExtension secondary(String commandCode, BusinessResourceAuthorization.ReadIsolation isolation) {
        var secondary = mock(CommandHandlerExtension.class);
        when(secondary.chainsAfterPrimary()).thenReturn(true);
        when(secondary.commandReadIsolation()).thenReturn(isolation);
        when(extensions.getSecondaryCommandHandlers(commandCode)).thenReturn(java.util.List.of(secondary));
        return secondary;
    }

    // A chained secondary shares the command transaction and is verified against it before it
    // runs, so its requirement must take part in opening that transaction.
    @Test void aChainedSecondaryRequirementOpensTheTransactionOfAHandlerlessCommand() {
        secondary("crm:create_account_relation", BusinessResourceAuthorization.ReadIsolation.REPEATABLE_READ);
        when(manager.getTransaction(any())).thenReturn(status);
        // The level the coherent-read gate sees before the secondary's first source read.
        assertThat(transaction.execute("crm:create_account_relation", false,
                TransactionSynchronizationManager::getCurrentTransactionIsolationLevel))
                .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        verify(manager).getTransaction(argThat(definition -> definition.getIsolationLevel() == TransactionDefinition.ISOLATION_REPEATABLE_READ));
        verify(manager).commit(status);
    }

    @Test void agreeingPrimaryAndSecondaryRequirementsStillOpenOneTransaction() {
        var handler = mock(CommandHandlerExtension.class);
        when(extensions.getCommandHandler("crm:update_account_relation")).thenReturn(Optional.of(handler));
        when(handler.commandReadIsolation()).thenReturn(BusinessResourceAuthorization.ReadIsolation.REPEATABLE_READ);
        secondary("crm:update_account_relation", BusinessResourceAuthorization.ReadIsolation.REPEATABLE_READ);
        when(manager.getTransaction(any())).thenReturn(status);
        assertThat(transaction.execute("crm:update_account_relation", false, () -> "updated")).isEqualTo("updated");
        verify(manager).getTransaction(argThat(definition -> definition.getIsolationLevel() == TransactionDefinition.ISOLATION_REPEATABLE_READ));
        verify(manager).commit(status);
    }

    @Test void aSecondaryRequirementConflictingWithAnotherOwnerIsRejectedBeforeAnyWorkRuns() {
        owner();
        secondary("orders:export", BusinessResourceAuthorization.ReadIsolation.SERIALIZABLE);
        var operation = mock(java.util.function.Supplier.class);
        assertThatThrownBy(()->transaction.execute("orders:export",false,operation)).isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Conflicting command owner transaction requirements");
        verifyNoInteractions(manager, operation);
    }

    @Test void aChainedSecondaryRequirementCannotEnterAnIncompatibleCallerTransaction() {
        secondary("crm:create_account_relation", BusinessResourceAuthorization.ReadIsolation.REPEATABLE_READ);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        var operation = mock(java.util.function.Supplier.class);
        assertThatThrownBy(()->transaction.execute("crm:create_account_relation",false,operation)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(manager, operation);
    }

    @Test void silentSecondariesLeaveTheTransactionUnchanged() {
        secondary("crm:create_lead_pool", null);
        when(manager.getTransaction(any())).thenReturn(status);
        assertThat(transaction.execute("crm:create_lead_pool", false, () -> "created")).isEqualTo("created");
        verify(manager).getTransaction(argThat(definition -> definition.getIsolationLevel() == TransactionDefinition.ISOLATION_DEFAULT));
    }

    private void domainCommand(String commandCode, String handlerParams) {
        var definitions=mock(com.auraboot.framework.meta.mapper.CommandDefinitionMapper.class);
        var snapshots=mock(com.auraboot.framework.permission.service.impl.PermissionSnapshotCache.class);
        org.springframework.test.util.ReflectionTestUtils.setField(transaction,"definitions",definitions);
        org.springframework.test.util.ReflectionTestUtils.setField(transaction,"snapshots",snapshots);
        com.auraboot.framework.application.tenant.MetaContext.setContext(100L,1L,"actor","actor");
        var command=new com.auraboot.framework.meta.entity.CommandDefinition();
        command.setExecutionConfig("{\"permissions\":[\"example.cap.manage\"],\"handlerParams\":"+handlerParams+"}");
        when(definitions.findCurrentByCode(commandCode)).thenReturn(command);
        var permission=new com.auraboot.framework.permission.entity.Permission();
        permission.setResourceType("operation");permission.setAction("manage");
        permission.setExtension(java.util.Map.of("domainModels",java.util.Map.of("example",java.util.List.of("read","update"))));
        when(snapshots.resolvePermissionDefinition(100L,"example.cap.manage")).thenReturn(permission);
    }

    // The domain default is itself an owner requirement: a secondary may not relax it.
    @Test void aChainedSecondaryCannotRelaxASerializableDomainCommand() {
        domainCommand("example:seed","{\"transactionIsolation\":\"serializable\"}");
        secondary("example:seed", BusinessResourceAuthorization.ReadIsolation.REPEATABLE_READ);
        var operation = mock(java.util.function.Supplier.class);
        try {
            assertThatThrownBy(()->transaction.execute("example:seed",false,operation)).isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("Conflicting command owner transaction requirements");
            verifyNoInteractions(operation, manager);
        } finally {com.auraboot.framework.application.tenant.MetaContext.clear();}
    }

    @Test void aChainedSecondaryAgreeingWithTheDomainDefaultKeepsThatTransaction() {
        domainCommand("example:merge","{}");
        secondary("example:merge", BusinessResourceAuthorization.ReadIsolation.REPEATABLE_READ);
        when(manager.getTransaction(any())).thenReturn(status);
        try {
            assertThat(transaction.execute("example:merge",false,()->"merged")).isEqualTo("merged");
            verify(manager).getTransaction(argThat(definition -> definition.getIsolationLevel() == TransactionDefinition.ISOLATION_REPEATABLE_READ));
            verify(manager).commit(status);
        } finally {com.auraboot.framework.application.tenant.MetaContext.clear();}
    }

    @Test void aStricterSecondaryRequirementIsAConflictNotAnUpgrade() {
        domainCommand("example:merge","{}");
        secondary("example:merge", BusinessResourceAuthorization.ReadIsolation.SERIALIZABLE);
        var operation = mock(java.util.function.Supplier.class);
        try {
            assertThatThrownBy(()->transaction.execute("example:merge",false,operation)).isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("Conflicting command owner transaction requirements");
            verifyNoInteractions(operation, manager);
        } finally {com.auraboot.framework.application.tenant.MetaContext.clear();}
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

    // ---- commands that delegate to a handler registered under another code ---------------------------
    // CRM's opportunity-stage and QDP-release commands name another handler in their
    // configuration ("handler": "crm:close_opportunity"). The handler phase resolves that
    // handler by the configured code; the transaction must resolve it the same way, or it
    // opens without the handler's isolation and the handler-phase guard rejects the command.
    private void delegatedTo(String commandCode, String handlerCode) {
        var definitions=mock(com.auraboot.framework.meta.mapper.CommandDefinitionMapper.class);
        org.springframework.test.util.ReflectionTestUtils.setField(transaction,"definitions",definitions);
        var command=new com.auraboot.framework.meta.entity.CommandDefinition();
        command.setExecutionConfig("{\"type\":\"state_transition\",\"handler\":\""+handlerCode
                +"\",\"handlerParams\":{\"dslPersistence\":false}}");
        when(definitions.findCurrentByCode(commandCode)).thenReturn(command);
    }

    @Test void aCommandDelegatingToAnotherHandlerOpensTheTransactionThatHandlerRequires() {
        delegatedTo("crm:win_opportunity","crm:close_opportunity");
        var handler=mock(CommandHandlerExtension.class);
        when(extensions.getCommandHandler("crm:close_opportunity")).thenReturn(Optional.of(handler));
        when(handler.commandReadIsolation()).thenReturn(BusinessResourceAuthorization.ReadIsolation.REPEATABLE_READ);
        when(manager.getTransaction(any())).thenReturn(status);
        assertThat(transaction.execute("crm:win_opportunity",false,()->"won")).isEqualTo("won");
        verify(manager).getTransaction(argThat(definition -> definition.getIsolationLevel() == TransactionDefinition.ISOLATION_REPEATABLE_READ));
        verify(extensions,never()).getCommandHandler("crm:win_opportunity");
    }

    @Test void aDelegatingCommandIsNotOverTightenedWhenItsHandlerDeclaresNothing() {
        delegatedTo("crm:win_opportunity","crm:close_opportunity");
        when(extensions.getCommandHandler("crm:close_opportunity")).thenReturn(Optional.of(mock(CommandHandlerExtension.class)));
        when(manager.getTransaction(any())).thenReturn(status);
        assertThat(transaction.execute("crm:win_opportunity",false,()->"won")).isEqualTo("won");
        verify(manager).getTransaction(argThat(definition -> definition.getIsolationLevel() == TransactionDefinition.ISOLATION_DEFAULT));
    }

    @Test void secondariesAreResolvedByTheDelegatedHandlerCodeAsWell() {
        delegatedTo("crm:win_opportunity","crm:close_opportunity");
        var handler=mock(CommandHandlerExtension.class);
        when(extensions.getCommandHandler("crm:close_opportunity")).thenReturn(Optional.of(handler));
        when(handler.commandReadIsolation()).thenReturn(BusinessResourceAuthorization.ReadIsolation.REPEATABLE_READ);
        secondary("crm:close_opportunity",BusinessResourceAuthorization.ReadIsolation.REPEATABLE_READ);
        when(manager.getTransaction(any())).thenReturn(status);
        assertThat(transaction.execute("crm:win_opportunity",false,()->"won")).isEqualTo("won");
        verify(manager).getTransaction(argThat(definition -> definition.getIsolationLevel() == TransactionDefinition.ISOLATION_REPEATABLE_READ));
    }

    @Test void aDelegatedHandlerAndItsSecondaryCannotDisagreeBeforeAnyWorkRuns() {
        delegatedTo("crm:win_opportunity","crm:close_opportunity");
        var handler=mock(CommandHandlerExtension.class);
        when(extensions.getCommandHandler("crm:close_opportunity")).thenReturn(Optional.of(handler));
        when(handler.commandReadIsolation()).thenReturn(BusinessResourceAuthorization.ReadIsolation.REPEATABLE_READ);
        secondary("crm:close_opportunity",BusinessResourceAuthorization.ReadIsolation.SERIALIZABLE);
        var operation=mock(java.util.function.Supplier.class);
        assertThatThrownBy(()->transaction.execute("crm:win_opportunity",false,operation)).isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Conflicting command owner transaction requirements");
        verifyNoInteractions(manager,operation);
    }

    @Test void aCommandNamingNoHandlerKeepsResolvingHandlersByItsOwnCode() {
        delegatedTo("crm:qualify_lead","  ");
        var handler=mock(CommandHandlerExtension.class);
        when(extensions.getCommandHandler("crm:qualify_lead")).thenReturn(Optional.of(handler));
        when(handler.commandReadIsolation()).thenReturn(BusinessResourceAuthorization.ReadIsolation.REPEATABLE_READ);
        when(manager.getTransaction(any())).thenReturn(status);
        assertThat(transaction.execute("crm:qualify_lead",false,()->"qualified")).isEqualTo("qualified");
        verify(manager).getTransaction(argThat(definition -> definition.getIsolationLevel() == TransactionDefinition.ISOLATION_REPEATABLE_READ));
    }

    // ---- declared record-creating effects -----------------------------------------------------------------
    // A declared effect that inserts records is an ordinary write outside the command's permit
    // scope. The native field authorization admits it only from a coherent transaction, so a
    // command that owns its transaction must open a coherent one for it.

    private void declares(CommandExecutionTransaction opener, String commandCode, String executionConfig) {
        var definitions=mock(com.auraboot.framework.meta.mapper.CommandDefinitionMapper.class);
        var command=new com.auraboot.framework.meta.entity.CommandDefinition();
        command.setExecutionConfig(executionConfig);
        when(definitions.findCurrentByCode(commandCode)).thenReturn(command);
        org.springframework.test.util.ReflectionTestUtils.setField(opener,"definitions",definitions);
    }

    private static final java.util.List<String> RECORD_CREATING_CONFIGS=java.util.List.of(
            // side effects name their kind by action or type, flat or nested
            "{\"sideEffects\":[{\"action\":\"create_record\",\"targetModel\":\"m\"}]}",
            "{\"sideEffects\":[{\"type\":\"batch_create_record\",\"targetModel\":\"m\"}]}",
            "{\"sideEffects\":[{\"actions\":[{\"modelCode\":\"m\",\"action\":\"batch_create_records\"}]}]}",
            "{\"sideEffects\":[{\"actions\":[{\"type\":\"document_flow\",\"modelCode\":\"m\"}]}]}",
            // post actions name their kind by type or action
            "{\"postActions\":[{\"type\":\"create_record\",\"targetModel\":\"m\"}]}",
            "{\"postActions\":[{\"action\":\"create_children\",\"childModel\":\"m\"}]}");

    @Test void aDeclaredCreateSideEffectOpensARepeatableReadTransactionForAHandlerlessCommand() {
        declares(transaction,"crm:log_lead_activity",
                "{\"sideEffects\":[{\"actions\":[{\"type\":\"create_record\",\"modelCode\":\"crm_activity_relation_common\"}]}]}");
        when(manager.getTransaction(any())).thenReturn(status);
        // The level the native field authorization sees when the effect writes.
        assertThat(transaction.execute("crm:log_lead_activity",false,
                TransactionSynchronizationManager::getCurrentTransactionIsolationLevel))
                .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        verify(manager).getTransaction(argThat(definition -> definition.getIsolationLevel() == TransactionDefinition.ISOLATION_REPEATABLE_READ));
        verify(manager).commit(status);
        assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isNull();
    }

    @Test void everyRecordCreatingEffectKindNeedsTheCoherentMinimum() {
        for (var config : RECORD_CREATING_CONFIGS) {
            var local=mock(PlatformTransactionManager.class);
            var opener=new CommandExecutionTransaction(local,extensions);
            declares(opener,"orders:seed",config);
            when(local.getTransaction(any())).thenReturn(status);
            assertThat(opener.execute("orders:seed",false,()->"done")).as(config).isEqualTo("done");
            verify(local).getTransaction(argThat(definition -> definition.getIsolationLevel() == TransactionDefinition.ISOLATION_REPEATABLE_READ));
        }
    }

    @Test void effectsThatWriteThroughTheScopedSqlPathKeepTheDefaultTransaction() {
        declares(transaction,"orders:ship",
                "{\"sideEffects\":[{\"action\":\"update_record\",\"targetModel\":\"m\"},{\"action\":\"batch_update_record\",\"targetModel\":\"m\"},"
                        +"{\"action\":\"aggregate\",\"targetModel\":\"m\"}],\"postActions\":[{\"type\":\"start_approval_chain\"}]}");
        when(manager.getTransaction(any())).thenReturn(status);
        assertThat(transaction.execute("orders:ship",false,()->"shipped")).isEqualTo("shipped");
        verify(manager).getTransaction(argThat(definition -> definition.getIsolationLevel() == TransactionDefinition.ISOLATION_DEFAULT));
    }

    @Test void declaresRecordCreationIgnoresShapesThatCreateNothing() {
        assertThat(CommandExecutionTransaction.declaresRecordCreation(null)).isFalse();
        for (var config : java.util.List.<java.util.Map<String,Object>>of(
                java.util.Map.of(),
                java.util.Map.of("sideEffects","create_record"),
                java.util.Map.of("sideEffects",java.util.List.of("create_record")),
                java.util.Map.of("sideEffects",java.util.List.of(java.util.Map.of("actions",java.util.List.of()))),
                java.util.Map.of("sideEffects",java.util.List.of(java.util.Map.of("action","update_record"))),
                java.util.Map.of("postActions",java.util.List.of(java.util.Map.of("type","send_notification")))))
            assertThat(CommandExecutionTransaction.declaresRecordCreation(config)).as(String.valueOf(config)).isFalse();
        assertThat(CommandExecutionTransaction.declaresRecordCreation(
                java.util.Map.of("postActions",java.util.List.of(java.util.Map.of("type","create_children"))))).isTrue();
    }

    // A machine delivery runs the command inside its own transaction and its writes never reach
    // the native field authorization. The derived minimum keeps that transaction; it is not an
    // owner contract and must not close it.
    @Test void aDeclaredCreateEffectDoesNotCloseAWeakerCallerTransaction() {
        declares(transaction,"orders:seed",RECORD_CREATING_CONFIGS.getFirst());
        when(manager.getTransaction(any())).thenReturn(status);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThat(transaction.execute("orders:seed",false,
                TransactionSynchronizationManager::getCurrentTransactionIsolationLevel))
                .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
        verify(manager).getTransaction(argThat(definition -> definition.getIsolationLevel() == TransactionDefinition.ISOLATION_DEFAULT));
    }

    @Test void aDeclaredCreateEffectRunsInsideACoherentCallerTransaction() {
        declares(transaction,"orders:seed",RECORD_CREATING_CONFIGS.getFirst());
        when(manager.getTransaction(any())).thenReturn(status);
        for (int level : new int[]{TransactionDefinition.ISOLATION_REPEATABLE_READ,TransactionDefinition.ISOLATION_SERIALIZABLE}) {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(level);
            assertThat(transaction.execute("orders:seed",false,
                    TransactionSynchronizationManager::getCurrentTransactionIsolationLevel)).isEqualTo(level);
        }
        verify(manager,times(2)).getTransaction(argThat(definition -> definition.getIsolationLevel() >= TransactionDefinition.ISOLATION_REPEATABLE_READ));
    }

    // A query without a plugin handler is read by the executor through the native read projection,
    // which answers a record only from a coherent transaction. Nothing states that requirement
    // for the command, so the opener derives the minimum from the command itself.

    private void defines(CommandExecutionTransaction opener, String commandCode, String modelCode, String executionConfig) {
        var definitions=mock(com.auraboot.framework.meta.mapper.CommandDefinitionMapper.class);
        var command=new com.auraboot.framework.meta.entity.CommandDefinition();
        command.setModelCode(modelCode);
        command.setExecutionConfig(executionConfig);
        when(definitions.findCurrentByCode(commandCode)).thenReturn(command);
        org.springframework.test.util.ReflectionTestUtils.setField(opener,"definitions",definitions);
    }

    @Test void aQueryReadByTheExecutorOpensARepeatableReadTransaction() {
        defines(transaction,"crm:list_accounts","crm_account","{\"type\":\"query\"}");
        when(manager.getTransaction(any())).thenReturn(status);
        // The level the native read projection sees when the executor reads.
        assertThat(transaction.execute("crm:list_accounts",false,
                TransactionSynchronizationManager::getCurrentTransactionIsolationLevel))
                .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        verify(manager).getTransaction(argThat(definition -> definition.getIsolationLevel() == TransactionDefinition.ISOLATION_REPEATABLE_READ));
        verify(manager).commit(status);
        assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isNull();
    }

    @Test void aQueryWithAPluginHandlerKeepsThatHandlersTransactionContract() {
        defines(transaction,"crm:list_accounts","crm_account","{\"type\":\"query\"}");
        when(extensions.getCommandHandler("crm:list_accounts")).thenReturn(Optional.of(mock(CommandHandlerExtension.class)));
        when(manager.getTransaction(any())).thenReturn(status);
        assertThat(transaction.execute("crm:list_accounts",false,()->"done")).isEqualTo("done");
        // The handler states nothing, so no level is imposed on it.
        verify(manager).getTransaction(argThat(definition -> definition.getIsolationLevel() == TransactionDefinition.ISOLATION_DEFAULT));
    }

    @Test void aQueryWithAChainedSecondaryKeepsThatSecondariesTransactionContract() {
        defines(transaction,"crm:list_accounts","crm_account","{\"type\":\"query\"}");
        when(extensions.getSecondaryCommandHandlers("crm:list_accounts"))
                .thenReturn(java.util.List.of(mock(CommandHandlerExtension.class)));
        when(manager.getTransaction(any())).thenReturn(status);
        assertThat(transaction.execute("crm:list_accounts",false,()->"done")).isEqualTo("done");
        verify(manager).getTransaction(argThat(definition -> definition.getIsolationLevel() == TransactionDefinition.ISOLATION_DEFAULT));
    }

    @Test void aCommandOfAnotherTypeOrWithoutAModelKeepsTheDefaultTransaction() {
        defines(transaction,"crm:create_activity","crm_activity","{\"type\":\"create\"}");
        when(manager.getTransaction(any())).thenReturn(status);
        transaction.execute("crm:create_activity",false,()->"done");
        verify(manager).getTransaction(argThat(definition -> definition.getIsolationLevel() == TransactionDefinition.ISOLATION_DEFAULT));

        var opener=new CommandExecutionTransaction(manager,extensions);
        defines(opener,"system:ping","","{\"type\":\"query\"}");
        when(manager.getTransaction(any())).thenReturn(status);
        opener.execute("system:ping",false,()->"done");
        verify(manager,times(2)).getTransaction(argThat(definition -> definition.getIsolationLevel() == TransactionDefinition.ISOLATION_DEFAULT));
    }

    @Test void aQueryReadByTheExecutorDoesNotCloseAWeakerCallerTransaction() {
        defines(transaction,"crm:list_accounts","crm_account","{\"type\":\"query\"}");
        when(manager.getTransaction(any())).thenReturn(status);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThat(transaction.execute("crm:list_accounts",false,
                TransactionSynchronizationManager::getCurrentTransactionIsolationLevel))
                .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
        verify(manager).getTransaction(argThat(definition -> definition.getIsolationLevel() == TransactionDefinition.ISOLATION_DEFAULT));
    }

    @Test void aQueryReadByTheExecutorRunsInsideACoherentCallerTransaction() {
        defines(transaction,"crm:list_accounts","crm_account","{\"type\":\"query\"}");
        when(manager.getTransaction(any())).thenReturn(status);
        for (int level : new int[]{TransactionDefinition.ISOLATION_REPEATABLE_READ,TransactionDefinition.ISOLATION_SERIALIZABLE}) {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(level);
            assertThat(transaction.execute("crm:list_accounts",false,
                    TransactionSynchronizationManager::getCurrentTransactionIsolationLevel)).isEqualTo(level);
        }
        verify(manager,times(2)).getTransaction(argThat(definition -> definition.getIsolationLevel() >= TransactionDefinition.ISOLATION_REPEATABLE_READ));
    }

    // The leniency above is for the derived minimum only: a domain command keeps closing a
    // weaker caller transaction, with or without a declared effect.
    @Test void aDomainCommandStillClosesAWeakerCallerTransaction() {
        var snapshots=mock(com.auraboot.framework.permission.service.impl.PermissionSnapshotCache.class);
        org.springframework.test.util.ReflectionTestUtils.setField(transaction,"snapshots",snapshots);
        var permission=new com.auraboot.framework.permission.entity.Permission();permission.setResourceType("operation");permission.setAction("manage");
        permission.setExtension(java.util.Map.of("domainModels",java.util.Map.of("example",java.util.List.of("read","update"))));
        when(snapshots.resolvePermissionDefinition(100L,"example.cap.manage")).thenReturn(permission);
        com.auraboot.framework.application.tenant.MetaContext.setContext(100L,1L,"actor","actor");
        try {
            for (var config : java.util.List.of("{\"permissions\":[\"example.cap.manage\"]}",
                    "{\"permissions\":[\"example.cap.manage\"],\"sideEffects\":[{\"action\":\"create_record\",\"targetModel\":\"other\"}]}")) {
                declares(transaction,"example:seed",config);
                TransactionSynchronizationManager.setActualTransactionActive(true);
                TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
                var operation=mock(java.util.function.Supplier.class);
                assertThatThrownBy(()->transaction.execute("example:seed",false,operation)).isInstanceOf(AccessDeniedException.class)
                        .hasMessageContaining("does not satisfy the resource isolation contract");
                verifyNoInteractions(operation,manager);
            }
        } finally {com.auraboot.framework.application.tenant.MetaContext.clear();}
    }

    // A declared secondary requirement is exactly verified before the secondary runs, so it is
    // neither contradicted nor relaxed by the command's own declared effect.
    @Test void aDeclaredSecondaryRequirementIsNotContradictedByACreateEffect() {
        declares(transaction,"crm:log_lead_activity",RECORD_CREATING_CONFIGS.getFirst());
        secondary("crm:log_lead_activity",BusinessResourceAuthorization.ReadIsolation.READ_COMMITTED);
        when(manager.getTransaction(any())).thenReturn(status);
        assertThat(transaction.execute("crm:log_lead_activity",false,()->"logged")).isEqualTo("logged");
        verify(manager).getTransaction(argThat(definition -> definition.getIsolationLevel() == TransactionDefinition.ISOLATION_READ_COMMITTED));
    }

    // ---- one resolver, used everywhere ------------------------------------------------------------------
    // The bug was four private copies of this rule and a fifth caller without one. Pin a single
    // implementation and require every handler lookup to go through it.
    @Test void theHandlerKeyResolverHasExactlyOneImplementationAndEveryHandlerLookupUsesIt() throws Exception {
        var root=java.nio.file.Path.of("src/main/java/com/auraboot/framework/meta");
        var declarations=new java.util.ArrayList<String>();
        var lookups=java.util.Map.of("CommandExecutionTransaction.java",false,"HandlerPhase.java",false,
                "FieldMapPhase.java",false,"StateCheckPhase.java",false,"PreInvariantPhase.java",false);
        var used=new java.util.HashSet<String>();
        var declaration=java.util.regex.Pattern.compile("\\bString\\s+resolvePluginHandlerCode\\s*\\(");
        try(var files=java.nio.file.Files.walk(root)) {
            for(var file:files.filter(path->path.toString().endsWith(".java")).toList()) {
                var source=java.nio.file.Files.readString(file);
                var name=file.getFileName().toString();
                if(declaration.matcher(source).find()) declarations.add(root.relativize(file).toString());
                if(lookups.containsKey(name)&&source.contains("CommandExecutorUtils.resolvePluginHandlerCode(")) used.add(name);
            }
        }
        assertThat(declarations).singleElement().asString().endsWith("service/impl/CommandExecutorUtils.java");
        assertThat(lookups.keySet()).containsAll(used);
        var missing=new java.util.TreeSet<>(lookups.keySet());missing.removeAll(used);
        assertThat(missing).as("handler lookups that bypass the shared resolver").isEmpty();
    }
}
