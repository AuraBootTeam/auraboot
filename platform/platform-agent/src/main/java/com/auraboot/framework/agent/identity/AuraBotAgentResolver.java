package com.auraboot.framework.agent.identity;

import java.util.Optional;

/**
 * Resolves the {@code ab_agent_definition.id} for a given tenant + agentCode.
 *
 * <p>For the canonical AuraBot agent (agentCode = "aurabot"), missing rows are
 * lazy-seeded on first resolve to handle tenants created after the bootstrap
 * SQL ran. For custom agentCodes, missing rows raise {@link AgentNotResolvedException}.
 *
 * <p>Contract: {@code auraboot-enterprise/docs/agent/contracts/aurabot-agent-resolver.md}
 */
public interface AuraBotAgentResolver {

    String DEFAULT_AGENT_CODE = "aurabot";

    /**
     * Resolves agentId for the given tenant + agentCode.
     * Lazy-seeds when agentCode = "aurabot" and the row is missing.
     *
     * @throws AgentNotResolvedException if agentCode is not "aurabot" and no active row exists
     */
    Long resolve(long tenantId, String agentCode);

    /** Pure lookup; returns empty when no active row exists. Never seeds. */
    Optional<Long> tryResolve(long tenantId, String agentCode);
}
