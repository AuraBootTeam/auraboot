package com.auraboot.framework.agent.runtime.policy;

import java.util.Set;

/**
 * Resolved agent profile boundary used by the generic chat runtime.
 */
public record AgentProfile(
        String agentCode,
        Set<String> profilePermissions,
        AgentContextPolicy contextPolicy,
        boolean evidenceFirst) {

    public AgentProfile {
        profilePermissions = profilePermissions == null ? null : Set.copyOf(profilePermissions);
        contextPolicy = contextPolicy != null ? contextPolicy : AgentContextPolicy.defaults();
    }
}
