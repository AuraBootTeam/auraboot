package com.auraboot.framework.behavior.service;

import java.util.Optional;

/** Trusted execution provenance consumed by analytics policy. */
public interface AnalyticsExecutionContext {
    Optional<Principal> principal();
    boolean isAgentExecution();
    String runPid();
    record Principal(boolean sandbox, long tenantId, long actorUserId) {}
}
