package com.auraboot.framework.conversation;

/**
 * Spring application event fired exactly once per turn at the {@code endTurn} boundary
 * (covers {@link TurnOutcome.Success}, {@link TurnOutcome.Interrupted}, and
 * {@link TurnOutcome.Failed}). {@link TurnOutcome.PendingConfirmation} fires
 * {@link TurnSuspendedEvent} instead.
 *
 * <p>Phase A injects {@link TurnSideEffects.EventEmitter#NOOP}, so listeners
 * are not invoked. Phase B switches to a real Spring publisher.
 */
public record TurnCompletedEvent(TurnContext ctx, TurnOutcome outcome) {}
