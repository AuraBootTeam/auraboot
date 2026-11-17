package com.auraboot.framework.agent.integration.context;

import com.auraboot.framework.agent.identity.ExecutionPrincipalContext;
import com.auraboot.framework.agent.runtime.context.ContextEnvelopeContext;
import com.auraboot.framework.event.context.ThreadContextContributor;
import org.springframework.stereotype.Component;

/** Propagates agent authority only within an explicitly bound platform scope. */
@Component
public class AgentThreadContextContributor implements ThreadContextContributor {
    @Override
    public void requireActor(Long tenantId, Long userId, Long memberId) {
        var principal = ExecutionPrincipalContext.current().orElse(null);
        if (principal != null && (!java.util.Objects.equals(tenantId, principal.tenantId())
                || !java.util.Objects.equals(userId, principal.actorUserId())
                || !java.util.Objects.equals(memberId, principal.actorMemberId())))
            throw new org.springframework.security.access.AccessDeniedException("Agent principal does not match the host actor");
    }

    @Override
    public Snapshot capture() {
        var principal = ExecutionPrincipalContext.current().orElse(null);
        var envelope = ContextEnvelopeContext.current().orElse(null);
        return () -> {
            ExecutionPrincipalContext.restore(principal);
            ContextEnvelopeContext.restore(envelope);
        };
    }

    @Override
    public Snapshot empty() {
        return () -> {
            ExecutionPrincipalContext.clear();
            ContextEnvelopeContext.clear();
        };
    }
}
