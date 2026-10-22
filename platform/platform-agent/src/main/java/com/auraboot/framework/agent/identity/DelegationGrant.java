package com.auraboot.framework.agent.identity;

import java.time.Instant;
import java.util.Set;

/**
 * Describes why an actor may execute work initiated by another subject.
 *
 * <p>This record does not grant permission by itself. Runtime authorization
 * still intersects invocation, actor, resource and policy gates. It gives that
 * decision a stable, auditable delegation input.
 */
public record DelegationGrant(
        Mode mode,
        String grantPid,
        Set<String> scopes,
        Instant expiresAt
) {

    public enum Mode {
        DIRECT_USER,
        EMPLOYEE_AUTONOMOUS,
        EXPLICIT_GRANT,
        AGENT_HANDOFF,
        SCHEDULE,
        EVENT
    }

    public DelegationGrant {
        if (mode == null) {
            throw new IllegalArgumentException("delegation mode is required");
        }
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }

    public static DelegationGrant directUser() {
        return new DelegationGrant(Mode.DIRECT_USER, null, Set.of(), null);
    }

    public static DelegationGrant employeeAutonomous() {
        return new DelegationGrant(Mode.EMPLOYEE_AUTONOMOUS, null, Set.of(), null);
    }

    public static DelegationGrant schedule() {
        return new DelegationGrant(Mode.SCHEDULE, null, Set.of(), null);
    }

    public static DelegationGrant event() {
        return new DelegationGrant(Mode.EVENT, null, Set.of(), null);
    }

    public static DelegationGrant agentHandoff() {
        return new DelegationGrant(Mode.AGENT_HANDOFF, null, Set.of(), null);
    }
}
