package com.auraboot.framework.plugin.rest;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.plugin.extension.IdentityDirectoryAccessor;
import com.auraboot.framework.plugin.extension.RestPublicationCommand;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Component
class RestPublicationCommandExecutor {
    private final IdentityDirectoryAccessor identities;
    private final UserPermissionService permissions;
    private final CommandExecutor commands;

    RestPublicationCommandExecutor(IdentityDirectoryAccessor identities, UserPermissionService permissions,
                                   CommandExecutor commands) {
        this.identities = identities;
        this.permissions = permissions;
        this.commands = commands;
    }

    Map<String, Object> execute(RestPublicationStore.CommandGrant grant, RestPublicationCommand declared, Map<String, Object> payload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !Long.valueOf(grant.tenantId()).equals(MetaContext.getCurrentTenantId())
                || !declared.code().equals(grant.command())
                || payload == null || !declared.inputFields().containsAll(payload.keySet())) {
            throw new AccessDeniedException("Publication command unavailable");
        }
        var member = identities.findEnabledTenantMembership(grant.tenantId(), grant.actorPid());
        if (member == null || member.memberId() == null) throw new AccessDeniedException("Publisher is unavailable");
        var output = new AtomicReference<Map<String, Object>>();
        MetaContext.runWithSnapshot(null, () -> {
            var actor = member.user();
            MetaContext.setContext(grant.tenantId(), actor.id(), actor.pid(), actor.username());
            MetaContext.setMemberId(member.memberId());
            if (!permissions.hasPermission(actor.id(), declared.permission())) {
                throw new AccessDeniedException("Publisher no longer has the required permission");
            }
            var request = new CommandExecuteRequest();
            request.setOperationType("CREATE");
            request.setPayload(payload);
            var result = commands.execute(declared.code(), request);
            if (result == null || result.getData() == null) {
                throw new IllegalStateException("Publication command returned no result");
            }
            var projected = new HashMap<String, Object>();
            for (String field : declared.resultFields()) {
                Object value = result.getData().get(field);
                if (value instanceof String text && !text.isBlank()) projected.put(field, text);
            }
            if (projected.isEmpty()) throw new IllegalStateException("Publication command returned no public result");
            output.set(Map.copyOf(projected));
        });
        return output.get();
    }
}
