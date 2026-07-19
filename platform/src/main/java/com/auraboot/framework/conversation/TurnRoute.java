package com.auraboot.framework.conversation;

import com.auraboot.framework.agent.runtime.TurnExecutionPlanner;

import java.util.List;

/**
 * Route-decision snapshot carried on {@link TurnCompletedEvent} for the
 * observation seam (execution-architecture review 2026-07-17, G1/G8).
 *
 * <p>{@link TurnContext#triageBucket()} alone cannot reconstruct which engine
 * actually ran: the planner's named-agent rule and caller-supplied flags both
 * override the bucket (review G8). Recording the resolved
 * {@code initialMode} + {@code reason} + {@code policySignals} next to the
 * bucket makes bucket-vs-engine divergence (rule shadowing) visible in
 * production telemetry instead of only in code review.
 *
 * <p>Nullable on {@link TurnCompletedEvent}: resume paths finalize without a
 * fresh planner decision and legacy emitters use the two-arg event
 * constructor. Listeners must treat an absent route as "not planned this
 * turn", not as an error.
 */
public record TurnRoute(String initialMode, String decisionReason, List<String> policySignals) {

    public TurnRoute {
        policySignals = policySignals == null ? List.of() : List.copyOf(policySignals);
    }

    /** Snapshot of a real planner decision; null-safe for defensive call sites. */
    public static TurnRoute from(TurnExecutionPlanner.TurnExecutionPlan plan) {
        if (plan == null) {
            return null;
        }
        return new TurnRoute(
                plan.initialMode() != null ? plan.initialMode().name() : null,
                plan.reason() != null ? plan.reason().name() : null,
                plan.policySignals().stream().map(Enum::name).toList());
    }

    /**
     * RAG-only channels (embeddable CS widget) bypass the planner entirely —
     * {@code runTurnDispatch} forces SYNC before {@code decide()} runs. Record
     * that as an explicit forced route so these turns don't show up as
     * "no route info" in telemetry.
     */
    public static TurnRoute ragOnlyForced() {
        return new TurnRoute(
                TurnExecutionPlanner.InitialExecutionMode.SYNC_AGENT_TURN.name(),
                "RAG_ONLY_CHANNEL_FORCED",
                List.of("RAG_ONLY_CHANNEL"));
    }
}
