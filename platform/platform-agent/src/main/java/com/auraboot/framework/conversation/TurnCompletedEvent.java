package com.auraboot.framework.conversation;

/**
 * Spring application event fired exactly once per turn at the {@code endTurn} boundary
 * (covers {@link TurnOutcome.Success}, {@link TurnOutcome.Interrupted}, and
 * {@link TurnOutcome.Failed}). {@link TurnOutcome.PendingConfirmation} fires
 * {@link TurnSuspendedEvent} instead.
 *
 * <p>{@code route} is the planner's decision snapshot for this turn (G1/G8
 * observation seam). Nullable: resume paths finalize without a fresh planner
 * decision, and legacy emitters use the two-arg constructor.
 *
 * <p>Phase A injects {@link TurnSideEffects.EventEmitter#NOOP}, so listeners
 * are not invoked. Phase B switches to a real Spring publisher.
 */
public record TurnCompletedEvent(TurnContext ctx, TurnOutcome outcome, TurnRoute route) {

    /** Legacy/resume emitters: no route decision available for this turn. */
    public TurnCompletedEvent(TurnContext ctx, TurnOutcome outcome) {
        this(ctx, outcome, null);
    }
}
