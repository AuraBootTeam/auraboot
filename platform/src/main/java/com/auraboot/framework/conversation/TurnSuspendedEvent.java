package com.auraboot.framework.conversation;

/**
 * Spring application event fired when a turn enters the {@code suspendTurn} state
 * because the chat impl produced a {@link TurnOutcome.PendingConfirmation}. A given
 * turn may emit this event arbitrarily many times across resume cycles (each
 * confirm-required → resume → confirm-required pair is one suspension), but it
 * will emit {@link TurnCompletedEvent} exactly once across the full lifecycle.
 *
 * <p>Phase A injects {@link TurnSideEffects.EventEmitter#NOOP}; listeners are
 * not invoked.
 */
public record TurnSuspendedEvent(TurnContext ctx, TurnOutcome.PendingConfirmation pc) {}
