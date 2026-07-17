package com.auraboot.framework.eventpolicy.executor;

import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.eventpolicy.model.ResolvedActionPlan;

import java.util.List;
import java.util.Map;

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

    /**
     * Whether the handler can execute in the current runtime configuration.
     * A handler may support an action type while still being unavailable because an external
     * provider is not configured, for example SMS delivery without a real SMS sender.
     */
    default boolean runtimeAvailable() {
        return true;
    }

    /**
     * External or platform provider dependencies that influence runtime availability.
     * The action catalog exposes this as structured design-time evidence instead of
     * forcing UIs to parse a single availability reason string.
     */
    default List<ActionProviderDependency> runtimeProviderDependencies() {
        return List.of();
    }

    /** Perform the side effect. Throw on failure. */
    void execute(ResolvedActionPlan plan, DecisionContext context) throws Exception;

    /**
     * Perform the side effect and return a small structured trace payload for product/runtime logs.
     * Existing handlers can keep implementing {@link #execute(ResolvedActionPlan, DecisionContext)};
     * richer handlers override this method.
     */
    default Map<String, Object> executeWithResult(ResolvedActionPlan plan, DecisionContext context) throws Exception {
        execute(plan, context);
        return Map.of();
    }
}
