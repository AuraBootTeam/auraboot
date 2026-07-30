package com.auraboot.framework.event.config;

import com.auraboot.framework.agent.identity.ExecutionPrincipal;
import com.auraboot.framework.agent.identity.ExecutionPrincipalContext;
import com.auraboot.framework.agent.runtime.context.ContextEnvelope;
import com.auraboot.framework.agent.runtime.context.ContextEnvelopeContext;
import com.auraboot.framework.application.tenant.MetaContext;
import org.springframework.core.task.TaskDecorator;

/**
 * Propagates MetaContext (tenant, user) from the calling thread to async threads.
 * Prevents cross-tenant data leakage in @Async methods.
 *
 * @since 6.2.0
 */
public class TenantAwareTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // Snapshot the FULL identity + correlation context, not just the 4 core
        // fields. The old 4-field copy (tenant/user/userPid/username) dropped
        // roleIds, memberId, envId and the OTel trace id — so IM @AI / group-chat
        // @Async workers lost their environment scope and had a broken trace.
        MetaContext.Snapshot snapshot = MetaContext.snapshot();
        ExecutionPrincipal executionPrincipal =
                ExecutionPrincipalContext.current().orElse(null);
        ContextEnvelope contextEnvelope =
                ContextEnvelopeContext.current().orElse(null);
        if (snapshot == null && executionPrincipal == null && contextEnvelope == null) {
            return runnable;
        }

        return () -> {
            if (snapshot != null) {
                MetaContext.restore(snapshot);
            }
            ExecutionPrincipalContext.restore(executionPrincipal);
            ContextEnvelopeContext.restore(contextEnvelope);
            try {
                runnable.run();
            } finally {
                ContextEnvelopeContext.clear();
                ExecutionPrincipalContext.clear();
                MetaContext.clear();
            }
        };
    }
}
