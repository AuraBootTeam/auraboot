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
public class CommandExecutionTransaction {
    private final PlatformTransactionManager transactions;
    private final ExtensionRegistry extensions;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.auraboot.framework.permission.service.DomainCommandAuthorization domains;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.auraboot.framework.meta.mapper.CommandDefinitionMapper definitions;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.auraboot.framework.permission.service.impl.PermissionSnapshotCache snapshots;
    private boolean domainCommand(String commandCode) {
        if (definitions == null || snapshots == null) return false;
        var command = definitions.findCurrentByCode(commandCode);
        if (command == null || command.getExecutionConfig() == null) return false;
        var config = com.auraboot.framework.common.util.JsonUtil.parse(command.getExecutionConfig(), new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String,Object>>() {});
        if (!(config.get("permissions") instanceof java.util.List<?> codes)) return false;
        return codes.stream().anyMatch(code -> code instanceof String value && !com.auraboot.framework.permission.service.DomainPermissionPolicy.models(
                snapshots.resolvePermissionDefinition(com.auraboot.framework.application.tenant.MetaContext.getCurrentTenantId(), value)).isEmpty());
    }

    public <T> T execute(String commandCode, boolean dryRun, Supplier<T> operation) {
        return domains == null ? executeTransaction(commandCode, dryRun, operation)
                : domains.withinInvocation(() -> executeTransaction(commandCode, dryRun, operation));
    }

    private <T> T executeTransaction(String commandCode, boolean dryRun, Supplier<T> operation) {
        var owner = extensions.getBusinessResourceOwner(commandCode).orElse(null);
        var resourceIsolation = owner instanceof BusinessResourceAuthorization resource ? resource.resourceReadIsolation() : null;
        var handler = extensions.getCommandHandler(commandCode).orElse(null);
        var handlerIsolation = handler == null ? null : handler.commandReadIsolation();
        if (resourceIsolation != null && handlerIsolation != null && resourceIsolation != handlerIsolation)
            throw new AccessDeniedException("Conflicting command owner transaction requirements");
        var isolation = resourceIsolation != null ? resourceIsolation : handlerIsolation;
        var template = new TransactionTemplate(transactions);
        if (isolation == null && domainCommand(commandCode)) isolation = BusinessResourceAuthorization.ReadIsolation.REPEATABLE_READ;
        if (isolation != null) {
            // Preserve the caller's atomic unit: never suspend it just to satisfy an owner.
            // An existing transaction must already attest the exact required isolation.
            if (TransactionSynchronizationManager.isActualTransactionActive()
                    && !Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(), isolation.jdbcLevel())) {
                throw new AccessDeniedException("The existing command transaction does not satisfy the resource isolation contract");
            }
            template.setIsolationLevel(isolation.jdbcLevel());
        }
        return template.execute(status -> {
            if (dryRun) status.setRollbackOnly();
            return operation.get();
        });
    }
}
