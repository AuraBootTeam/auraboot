package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.plugin.extension.BusinessResourceAuthorization;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.function.Supplier;

/** Establish the physical command transaction before loading metadata or running any pipeline phase. */
@Component
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class CommandExecutionTransaction {
    private static final ThreadLocal<Boolean> IN_COMMAND_TRANSACTION = new ThreadLocal<>();

    /** True while a command's own (contractually REPEATABLE_READ) transaction is active on this thread. */
    public static boolean insideCommandTransaction() {
        return Boolean.TRUE.equals(IN_COMMAND_TRANSACTION.get());
    }

    private final PlatformTransactionManager transactions;
    private final ExtensionRegistry extensions;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.auraboot.framework.permission.service.DomainCommandAuthorization domains;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.auraboot.framework.meta.mapper.CommandDefinitionMapper definitions;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.auraboot.framework.permission.service.impl.PermissionSnapshotCache snapshots;

    private com.auraboot.framework.meta.entity.CommandDefinition currentCommand(String commandCode) {
        return definitions == null ? null : definitions.findCurrentByCode(commandCode);
    }

    private static java.util.Map<String,Object> configuration(
            com.auraboot.framework.meta.entity.CommandDefinition command) {
        if (command == null || command.getExecutionConfig() == null) return java.util.Map.of();
        return com.auraboot.framework.common.util.JsonUtil.parse(command.getExecutionConfig(), new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String,Object>>() {});
    }
    private boolean domainCommand(java.util.Map<String,Object> config) {
        if (snapshots == null || !(config.get("permissions") instanceof java.util.List<?> codes)) return false;
        return codes.stream().anyMatch(code -> code instanceof String value && !com.auraboot.framework.permission.service.DomainPermissionPolicy.models(
                snapshots.resolvePermissionDefinition(com.auraboot.framework.application.tenant.MetaContext.getCurrentTenantId(), value)).isEmpty());
    }

    private static final java.util.Set<String> CREATING_SIDE_EFFECTS =
            java.util.Set.of("create_record", "batch_create_record", "batch_create_records", "document_flow");
    private static final java.util.Set<String> CREATING_POST_ACTIONS = java.util.Set.of("create_record", "create_children");

    /** True when the command declares an effect that inserts records through the ordinary record write. */
    static boolean declaresRecordCreation(java.util.Map<String,Object> config) {
        if (config == null) return false;
        return declares(config.get("sideEffects"), "action", CREATING_SIDE_EFFECTS)
                || declares(config.get("postActions"), "type", CREATING_POST_ACTIONS);
    }

    // Each executor reads the named key first and falls back to the other one. A side effect is
    // either one action or holds nested `actions`, which name their kind by `type`.
    private static boolean declares(Object effects, String first, java.util.Set<String> creating) {
        if (!(effects instanceof java.util.List<?> list)) return false;
        for (Object effect : list) {
            if (!(effect instanceof java.util.Map<?,?> entry)) continue;
            if (creating.contains(kind(entry, first))) return true;
            if ("action".equals(first) && entry.get("actions") instanceof java.util.List<?> actions)
                for (Object action : actions)
                    if (action instanceof java.util.Map<?,?> item && creating.contains(kind(item, "type"))) return true;
        }
        return false;
    }

    private static String kind(java.util.Map<?,?> effect, String first) {
        Object value = effect.get(first);
        if (!(value instanceof String text)) value = effect.get("action".equals(first) ? "type" : "action");
        return value instanceof String text ? text : "";
    }

    /** The automatic minimum: repeatable read, raised to serializable by declaration or by the caller's transaction. */
    private static BusinessResourceAuthorization.ReadIsolation coherentMinimum(java.util.Map<String,Object> config) {
        boolean serializable = com.auraboot.framework.meta.transaction.CommandTransactionIsolationPolicy.requiresSerializable(config)
                || Integer.valueOf(org.springframework.transaction.TransactionDefinition.ISOLATION_SERIALIZABLE).equals(
                        TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
        return serializable ? BusinessResourceAuthorization.ReadIsolation.SERIALIZABLE
                : BusinessResourceAuthorization.ReadIsolation.REPEATABLE_READ;
    }

    public <T> T execute(String commandCode, boolean dryRun, Supplier<T> operation) {
        boolean ownsTransaction = !TransactionSynchronizationManager.isActualTransactionActive();
        try {
            return invoke(commandCode, dryRun, operation);
        } catch (com.auraboot.framework.meta.exception.CurrentStateTargetLockConflictException conflict) {
            // CATCH: only the pre-handler current-state row lock emits this signal. The owned
            // physical transaction has rolled back, including its idempotency claim. Re-enter
            // once with fresh metadata and authorization; never replay a caller's atomic unit.
            if (!ownsTransaction) {
                // Consume the restart signal at its own invocation. A nested command must
                // not cause an already-started outer handler to be replayed, even at the
                // same command code. Preserve the ordinary serialization-conflict outcome.
                throw new org.springframework.dao.CannotSerializeTransactionException(
                        "Current-state target lock conflicted inside the caller transaction", conflict);
            }
            if (dryRun) throw conflict;
            log.info("Re-executing current-state command {} after target-lock serialization rollback", commandCode);
            return invoke(commandCode, false, operation);
        }
    }

    private <T> T invoke(String commandCode, boolean dryRun, Supplier<T> operation) {
        return domains == null ? executeTransaction(commandCode, dryRun, operation)
                : domains.withinInvocation(() -> executeTransaction(commandCode, dryRun, operation));
    }

    private <T> T executeTransaction(String commandCode, boolean dryRun, Supplier<T> operation) {
        var command = currentCommand(commandCode);
        var config = configuration(command);
        // Handlers are resolved by the same key the pipeline phases use. A command may
        // delegate to a handler registered under another code; looking it up by the
        // command's own code found nothing, so the transaction opened without that
        // handler's isolation and the handler phase rejected the command.
        var handlerCode = CommandExecutorUtils.resolvePluginHandlerCode(commandCode, config);
        // Code-owned resource protection stays bound to the command code even when metadata changes its handler.
        var owner = extensions.getBusinessResourceOwner(commandCode).orElse(null);
        var resourceIsolation = owner instanceof BusinessResourceAuthorization resource ? resource.resourceReadIsolation() : null;
        var handler = extensions.getCommandHandler(handlerCode).orElse(null);
        var handlerIsolation = handler == null ? null : handler.commandReadIsolation();
        if (resourceIsolation != null && handlerIsolation != null && resourceIsolation != handlerIsolation)
            throw new AccessDeniedException("Conflicting command owner transaction requirements");
        var isolation = resourceIsolation != null ? resourceIsolation : handlerIsolation;
        var template = new TransactionTemplate(transactions);
        boolean domain = domainCommand(config);
        if (isolation == null && domain) isolation = coherentMinimum(config);
        // Chained secondaries run in this same transaction and are verified against it before
        // they execute, so their requirement must take part in opening it: a secondary that
        // reads outside the permit scope can neither declare (the guard rejects a transaction
        // it did not shape) nor stay silent (the coherent-read gate rejects a non-snapshot
        // transaction). Checked after the domain default so a secondary cannot relax it.
        for (var secondary : extensions.getSecondaryCommandHandlers(handlerCode)) {
            var required = secondary.commandReadIsolation();
            if (required == null) continue;
            if (isolation != null && isolation != required)
                throw new AccessDeniedException("Conflicting command owner transaction requirements");
            isolation = required;
        }
        // A declared record-creating effect is an ordinary write outside the command's permit
        // scope: the native field authorization decides it on the locked row and admits it only
        // from a coherent transaction. Checked after the secondaries, so a declared requirement
        // is neither contradicted nor relaxed. Update and aggregate effects write through the
        // scoped SQL path and keep their own guards.
        boolean effectDerived = isolation == null && declaresRecordCreation(config);
        if (effectDerived) isolation = coherentMinimum(config);
        // The executor's own read for a query takes the native read projection, which answers a
        // record only from a coherent transaction. A query without a plugin handler has nothing
        // that states that requirement, so the minimum is derived from the command itself. It is
        // the same derived minimum as a declared effect: not an owner contract.
        boolean readDerived = isolation == null && handler == null
                && extensions.getSecondaryCommandHandlers(handlerCode).isEmpty()
                && command != null && command.getModelCode() != null && !command.getModelCode().isBlank()
                && ExecutorReadQuery.isQuery(config);
        if (readDerived) {
            isolation = coherentMinimum(config);
        }
        if (isolation != null) {
            // Preserve the caller's atomic unit: never suspend it just to satisfy an owner.
            // An existing transaction must already attest the exact required isolation.
            if (TransactionSynchronizationManager.isActualTransactionActive()
                    && !Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(), isolation.jdbcLevel())) {
                // Only an owner contract closes a weaker caller transaction. The minimum derived
                // from a declared effect or from the executor's own read does not: a delivery that
                // runs the command inside its own transaction keeps that transaction (machine
                // deliveries never reach the native field authorization), and a write that is
                // gated there is refused with the level it needs.
                if (!effectDerived && !readDerived) {
                    throw new AccessDeniedException("The existing command transaction does not satisfy the resource isolation contract");
                }
                isolation = null;
            } else template.setIsolationLevel(isolation.jdbcLevel());
        }
        Integer previousIsolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        if (isolation != null) {
            // Maintain the isolation ThreadLocal for the transaction this template begins:
            // coherent-read gates (native field projection, serializable resolvers) read it,
            // and template-begun transactions do not populate it by themselves.
            TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(isolation.jdbcLevel());
        }
        IN_COMMAND_TRANSACTION.set(Boolean.TRUE);
        try {
            return template.execute(status -> {
                if (dryRun) status.setRollbackOnly();
                // Boundary phases may suspend this transaction for their own isolated authorization.
                // Handler-local projection caches must start after those boundaries, not around them.
                return operation.get();
            });
        } finally {
            IN_COMMAND_TRANSACTION.remove();
            TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(previousIsolation);
        }
    }
}
