package com.auraboot.framework.agent.identity;

import java.util.Set;

/**
 * Immutable execution identity for one agent turn or durable run.
 *
 * <p>{@code actor*} fields determine runtime permission and data scope.
 * {@link #initiator} remains the human/system attribution and never silently
 * replaces the actor. This is the canonical answer to "who executed this" and
 * "who asked for it".
 */
public record ExecutionPrincipal(
        long tenantId,
        long actorUserId,
        long actorMemberId,
        String actorUserPid,
        String actorUsername,
        Long actorEmployeeId,
        String actorEmployeePid,
        Initiator initiator,
        DelegationGrant delegation,
        String agentCode,
        String agentReleasePid,
        String deploymentPid,
        String agentReleaseHash,
        String channel,
        Type type,
        Set<Long> roleIds
) {

    public enum Type {
        DIGITAL_EMPLOYEE,
        HUMAN_DELEGATED,
        SYSTEM,
        SANDBOX
    }

    public ExecutionPrincipal {
        if (tenantId <= 0L) {
            throw new IllegalArgumentException("tenantId must be positive");
        }
        if (actorUserId <= 0L || actorMemberId <= 0L) {
            throw new IllegalArgumentException(
                    "execution actor requires positive userId and memberId");
        }
        if (initiator == null) {
            throw new IllegalArgumentException("initiator is required");
        }
        if (delegation == null) {
            throw new IllegalArgumentException("delegation is required");
        }
        if (agentCode == null || agentCode.isBlank()) {
            throw new IllegalArgumentException("agentCode is required");
        }
        if (agentReleasePid == null || agentReleasePid.isBlank()
                || deploymentPid == null || deploymentPid.isBlank()
                || agentReleaseHash == null || agentReleaseHash.isBlank()) {
            throw new IllegalArgumentException(
                    "immutable agent release and deployment are required");
        }
        if (type == null) {
            throw new IllegalArgumentException("principal type is required");
        }
        roleIds = roleIds == null ? Set.of() : Set.copyOf(roleIds);
    }

    public boolean digitalEmployee() {
        return type == Type.DIGITAL_EMPLOYEE;
    }
}
