package com.auraboot.framework.eventpolicy.executor;

import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.eventpolicy.model.FailureStrategy;
import com.auraboot.framework.eventpolicy.model.ResolvedActionPlan;

/**
 * Records executed action plans so a replayed event / retry does not re-run a side effect
 * (docs/2.md §8.2). Keyed by tenant + idempotency key. A DB-backed implementation persists to
 * {@code ab_drt_policy_exec_log}; an in-memory one is used in unit tests.
 */
public interface IdempotencyStore {

    /** True if this idempotency key already executed successfully for the tenant. */
    boolean alreadySucceeded(Long tenantId, String idempotencyKey);

    /** Record the outcome of an action execution (idempotency key is unique per tenant). */
    default void record(Long tenantId, ActionExecutionResult result) {
        record(tenantId, null, result);
    }

    /** Record the outcome of an action execution with the owning policy for traceability. */
    void record(Long tenantId, String policyCode, ActionExecutionResult result);

    /** Record the outcome and link it to the decision trace / policy run that caused it. */
    default void record(Long tenantId,
                        String policyCode,
                        ActionExecutionResult result,
                        String decisionTraceId,
                        String correlationId) {
        record(tenantId, policyCode, result);
    }

    /** Record the outcome with a retry-safe action/context envelope. */
    default void record(Long tenantId,
                        String policyCode,
                        ActionExecutionResult result,
                        String decisionTraceId,
                        String correlationId,
                        ResolvedActionPlan plan,
                        FailureStrategy failureStrategy,
                        DecisionContext context) {
        record(tenantId, policyCode, result, decisionTraceId, correlationId);
    }
}
