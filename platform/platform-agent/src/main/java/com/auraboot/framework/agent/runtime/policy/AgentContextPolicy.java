package com.auraboot.framework.agent.runtime.policy;

import java.util.Set;

/**
 * Profile-level context loading boundary for an agent.
 */
public record AgentContextPolicy(
        Set<String> scopes,
        boolean allowSensitiveContext,
        ToolCapabilityCeiling capabilityCeiling,
        ToolExposure toolExposure,
        DurabilityPreference durabilityPreference) {

    public AgentContextPolicy {
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }

    public AgentContextPolicy(Set<String> scopes, boolean allowSensitiveContext) {
        this(scopes, allowSensitiveContext, null, null, null);
    }

    public static AgentContextPolicy defaults() {
        return new AgentContextPolicy(Set.of(), false, null, null, null);
    }
}
