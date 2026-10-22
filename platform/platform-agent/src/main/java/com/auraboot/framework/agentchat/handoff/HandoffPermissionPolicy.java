package com.auraboot.framework.agentchat.handoff;

import com.auraboot.framework.agentchat.spi.AgentMemberDto;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Computes permission boundaries for handoff hops.
 */
public final class HandoffPermissionPolicy {

    public enum ContextTransferPolicy {
        HANDOFF_CONTEXT_ONLY
    }

    public enum StateTransferPolicy {
        PARENT_TASK_ONLY
    }

    public record Decision(boolean allowed,
                           String reasonCode,
                           Set<String> effectivePermissions,
                           ContextTransferPolicy contextTransferPolicy,
                           StateTransferPolicy stateTransferPolicy,
                           String auditReason) {

        public static Decision allow(Set<String> effectivePermissions, String auditReason) {
            return new Decision(true,
                    "allowed",
                    effectivePermissions == null ? null : copy(effectivePermissions),
                    ContextTransferPolicy.HANDOFF_CONTEXT_ONLY,
                    StateTransferPolicy.PARENT_TASK_ONLY,
                    auditReason);
        }

        public static Decision deny(String reasonCode, String auditReason) {
            return new Decision(false,
                    reasonCode,
                    Set.of(),
                    ContextTransferPolicy.HANDOFF_CONTEXT_ONLY,
                    StateTransferPolicy.PARENT_TASK_ONLY,
                    auditReason);
        }
    }

    private HandoffPermissionPolicy() {
    }

    public static Decision decide(AgentMemberDto sourceAgent,
                                  AgentMemberDto targetAgent,
                                  Set<String> inheritedPermissions) {
        if (targetAgent == null || targetAgent.getAgentId() == null) {
            return Decision.deny("target_not_allowed", "Handoff target is outside the current agent roster.");
        }
        Set<String> sourceBounded = intersect(inheritedPermissions,
                sourceAgent != null ? sourceAgent.getProfilePermissions() : null);
        Set<String> targetBounded = intersect(sourceBounded, targetAgent.getProfilePermissions());
        return Decision.allow(targetBounded, "permission_intersection");
    }

    public static Set<String> intersect(Set<String> inheritedPermissions, Set<String> profilePermissions) {
        if (inheritedPermissions == null && profilePermissions == null) {
            return null;
        }
        if (inheritedPermissions == null) {
            return copy(profilePermissions);
        }
        if (profilePermissions == null) {
            return copy(inheritedPermissions);
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String permission : inheritedPermissions) {
            if (permission != null && profilePermissions.contains(permission)) {
                result.add(permission);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private static Set<String> copy(Set<String> permissions) {
        if (permissions == null) {
            return null;
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String permission : permissions) {
            if (permission != null && !permission.isBlank()) {
                result.add(permission);
            }
        }
        return Collections.unmodifiableSet(result);
    }
}
