package com.auraboot.framework.agent.integration.context;

import com.auraboot.framework.agent.identity.ExecutionPrincipalContext;
import com.auraboot.framework.agent.runtime.context.ContextEnvelopeContext;
import com.auraboot.framework.event.context.ThreadContextContributor;
import org.springframework.stereotype.Component;

/** Propagates agent authority only within an explicitly bound platform scope. */
@Component
public class AgentThreadContextContributor implements ThreadContextContributor {
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
