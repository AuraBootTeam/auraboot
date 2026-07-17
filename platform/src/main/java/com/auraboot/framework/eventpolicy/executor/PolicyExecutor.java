package com.auraboot.framework.eventpolicy.executor;

import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.eventpolicy.model.EventPolicyResult;
import com.auraboot.framework.eventpolicy.model.FailureStrategy;
import com.auraboot.framework.eventpolicy.model.ResolvedActionPlan;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Executes the resolved action plans of an {@link EventPolicyResult} (docs/2.md §7, §8): in order,
 * skipping plans whose idempotency key already succeeded, dispatching each to the supporting
 * {@link ActionHandler}, and applying the policy's {@link FailureStrategy}. This is the side-effect
 * half of the boundary — the runtime decided WHAT; this runs it safely (idempotent, ordered).
 *
 * <p>{@code ALL_OR_NOTHING} throws {@link PolicyExecutionException} on the first failure so the
 * caller's transaction rolls back (including the just-written idempotency rows).
 */
@Slf4j
public class PolicyExecutor {

    public static class PolicyExecutionException extends RuntimeException {
        public PolicyExecutionException(String message) {
            super(message);
        }
    }

    private final java.util.function.Supplier<List<ActionHandler>> handlersSupplier;
    private final IdempotencyStore idempotencyStore;

    /** Fixed handler list (unit tests / static wiring). */
    public PolicyExecutor(List<ActionHandler> handlers, IdempotencyStore idempotencyStore) {
        this(() -> handlers != null ? handlers : List.<ActionHandler>of(), idempotencyStore);
    }

    /**
     * Lazily-resolved handlers (Spring wiring): the supplier is invoked per execution so handlers
     * registered after this executor was constructed are still seen — a fixed snapshot at bean
     * creation can miss beans depending on init order.
     */
    public PolicyExecutor(java.util.function.Supplier<List<ActionHandler>> handlersSupplier, IdempotencyStore idempotencyStore) {
        this.handlersSupplier = handlersSupplier != null ? handlersSupplier : List::of;
        this.idempotencyStore = idempotencyStore;
    }

    public PolicyExecutionResult execute(EventPolicyResult policyResult, DecisionContext context,
                                         FailureStrategy strategy, Long tenantId) {
        return execute(policyResult, context, strategy, tenantId, null, null);
    }

    public PolicyExecutionResult execute(EventPolicyResult policyResult,
                                         DecisionContext context,
                                         FailureStrategy strategy,
                                         Long tenantId,
                                         String decisionTraceId,
                                         String correlationId) {
        List<ResolvedActionPlan> plans = policyResult.actionPlans();
        if (plans == null || plans.isEmpty()) {
            return new PolicyExecutionResult(policyResult.policyCode(),
                    PolicyExecutionResult.OverallStatus.NOTHING_TO_DO, List.of());
        }
        FailureStrategy fs = strategy != null ? strategy : FailureStrategy.CONTINUE_ON_ERROR;

        List<ActionExecutionResult> results = new ArrayList<>();
        boolean stopped = false;
        for (ResolvedActionPlan plan : plans) {
            if (stopped) {
                ActionExecutionResult skipped = ActionExecutionResult.of(
                        plan.ruleCode(), plan.type(), plan.idempotencyKey(),
                        ActionExecutionStatus.NOT_EXECUTED);
                recordSafe(tenantId, policyResult.policyCode(), skipped, decisionTraceId, correlationId,
                        plan, fs, context);
                results.add(skipped);
                continue;
            }
            ActionExecutionResult r = executeOne(
                    policyResult.policyCode(),
                    plan,
                    context,
                    fs,
                    tenantId,
                    decisionTraceId,
                    correlationId);
            results.add(r);
            if (r.isFailure() && (fs == FailureStrategy.FAIL_FAST || fs == FailureStrategy.ALL_OR_NOTHING)) {
                if (fs == FailureStrategy.ALL_OR_NOTHING) {
                    throw new PolicyExecutionException(
                            "ALL_OR_NOTHING: action " + plan.type() + " failed: " + r.error());
                }
                stopped = true; // FAIL_FAST: mark the rest NOT_EXECUTED
            }
        }
        return new PolicyExecutionResult(policyResult.policyCode(), overall(results), results);
    }

    private ActionExecutionResult executeOne(String policyCode, ResolvedActionPlan plan, DecisionContext context,
                                             FailureStrategy fs, Long tenantId,
                                             String decisionTraceId, String correlationId) {
        String key = plan.idempotencyKey();
        if (key != null && idempotencyStore != null && idempotencyStore.alreadySucceeded(tenantId, key)) {
            return ActionExecutionResult.of(plan.ruleCode(), plan.type(), key, ActionExecutionStatus.SKIPPED);
        }
        ActionHandler handler = select(plan.type());
        if (handler == null) {
            ActionExecutionResult r = new ActionExecutionResult(plan.ruleCode(), plan.type(), key,
                    ActionExecutionStatus.NO_HANDLER, "no handler for action type " + plan.type());
            recordSafe(tenantId, policyCode, r, decisionTraceId, correlationId, plan, fs, context);
            return r;
        }
        try {
            Map<String, Object> resultPayload = handler.executeWithResult(plan, context);
            ActionExecutionResult r = ActionExecutionResult.success(plan.ruleCode(), plan.type(), key, resultPayload);
            recordSafe(tenantId, policyCode, r, decisionTraceId, correlationId, plan, fs, context);
            return r;
        } catch (Exception e) {
            ActionExecutionStatus status = switch (fs) {
                case RETRY_ASYNC -> ActionExecutionStatus.RETRY_PENDING;
                case DEAD_LETTER -> ActionExecutionStatus.DEAD_LETTER;
                default -> ActionExecutionStatus.FAILED;
            };
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("Action {} (rule {}) failed: {}", plan.type(), plan.ruleCode(), msg);
            Map<String, Object> resultPayload = e instanceof ActionExecutionException actionFailure
                    ? actionFailure.resultPayload()
                    : Map.of();
            ActionExecutionResult r = new ActionExecutionResult(plan.ruleCode(), plan.type(), key,
                    status, msg, resultPayload);
            recordSafe(tenantId, policyCode, r, decisionTraceId, correlationId, plan, fs, context);
            return r;
        }
    }

    private void recordSafe(Long tenantId, String policyCode, ActionExecutionResult r,
                            String decisionTraceId, String correlationId,
                            ResolvedActionPlan plan, FailureStrategy failureStrategy, DecisionContext context) {
        if (idempotencyStore != null) {
            idempotencyStore.record(tenantId, policyCode, r, decisionTraceId, correlationId,
                    plan, failureStrategy, context);
        }
    }

    private ActionHandler select(String type) {
        List<ActionHandler> handlers = handlersSupplier.get();
        if (handlers == null) {
            return null;
        }
        for (ActionHandler h : handlers) {
            if (h.supports(type)) {
                return h;
            }
        }
        return null;
    }

    private PolicyExecutionResult.OverallStatus overall(List<ActionExecutionResult> results) {
        boolean anySuccess = false;
        boolean anyFailure = false;
        for (ActionExecutionResult r : results) {
            switch (r.status()) {
                case SUCCESS -> anySuccess = true;
                case FAILED, NO_HANDLER, DEAD_LETTER, RETRY_PENDING, NOT_EXECUTED -> anyFailure = true;
                case SKIPPED -> { /* neutral */ }
            }
        }
        if (anyFailure && anySuccess) {
            return PolicyExecutionResult.OverallStatus.PARTIAL_SUCCESS;
        }
        if (anyFailure) {
            return PolicyExecutionResult.OverallStatus.FAILED;
        }
        return PolicyExecutionResult.OverallStatus.ALL_SUCCESS;
    }
}
