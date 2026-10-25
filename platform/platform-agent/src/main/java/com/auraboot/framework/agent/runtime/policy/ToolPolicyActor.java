package com.auraboot.framework.agent.runtime.policy;

import java.util.Set;

public record ToolPolicyActor(Long tenantId, Long userId, Set<String> permissions) {
    public ToolPolicyActor {
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }
}
