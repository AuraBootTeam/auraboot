package com.auraboot.framework.agent.identity;

/**
 * Resolves a stable actor for an agent invocation.
 */
public interface ExecutionPrincipalResolver {

    ExecutionPrincipal resolve(ResolveRequest request);

    record ResolveRequest(
            long tenantId,
            Long initiatorUserId,
            Long initiatorMemberId,
            String agentCode,
            String channel,
            Initiator initiatorOverride
    ) {
        public ResolveRequest(
                long tenantId,
                Long initiatorUserId,
                Long initiatorMemberId,
                String agentCode,
                String channel) {
            this(
                    tenantId,
                    initiatorUserId,
                    initiatorMemberId,
                    agentCode,
                    channel,
                    null);
        }
    }
}
