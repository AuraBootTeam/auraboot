package com.auraboot.framework.eventpolicy.executor;

import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.eventpolicy.model.ResolvedActionPlan;

/**
 * SPI for executing one action type (docs/2.md §7). Implementations are domain-specific
 * (NOTIFY → notification service, START_PROCESS → BPM, CREATE_TASK → task service, ...) and
 * register as Spring beans; the {@link PolicyExecutor} dispatches by {@link #supports(String)}.
 *
 * <p>A handler performs the actual side effect and throws on failure (the executor records the
 * failure and applies the policy's FailureStrategy). Handlers must honor the action's
 * idempotency key where the underlying service supports it.
 */
public interface ActionHandler {

    boolean supports(String actionType);

    /** Perform the side effect. Throw on failure. */
    void execute(ResolvedActionPlan plan, DecisionContext context) throws Exception;
}
