package com.auraboot.framework.eventpolicy.model;

import com.auraboot.framework.eventpolicy.executor.PolicyExecutionResult;

/**
 * Combined outcome of running AND executing an event policy (docs/2.md §2 end-to-end flow):
 * the decision half ({@link EventPolicyResult}: matched rules + resolved action plans) and the
 * side-effect half ({@link PolicyExecutionResult}: per-action execution status + idempotency).
 */
public record EventPolicyExecutionResult(
        EventPolicyResult policy,
        PolicyExecutionResult execution
) {}
