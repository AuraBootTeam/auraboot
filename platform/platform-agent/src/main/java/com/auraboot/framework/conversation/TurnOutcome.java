package com.auraboot.framework.conversation;

import java.util.Map;

/**
 * Sealed outcome of {@link ConversationTurnService#runTurn}. Drives
 * {@link ConversationTurnService} finalize dispatch:
 * <ul>
 *   <li>{@link Success} / {@link Interrupted} / {@link Failed} → endTurn → COMPLETED → TurnCompletedEvent
 *   <li>{@link PendingConfirmation} → suspendTurn → SUSPENDED → TurnSuspendedEvent
 * </ul>
 *
 * <p>One turn emits exactly one {@code TurnCompletedEvent} across its full
 * lifecycle (including resume); arbitrary {@code TurnSuspendedEvent} count.
 *
 * <p>Contract reference: conversation-turn-service-design v3.3 §3.4 / TurnPhase state machine.
 */
public sealed interface TurnOutcome
        permits TurnOutcome.Success,
                TurnOutcome.Interrupted,
                TurnOutcome.PendingConfirmation,
                TurnOutcome.Failed {

    record Success(String finalResponse, Map<String, Object> meta) implements TurnOutcome {}

    record Interrupted(String partialResponse, String reason) implements TurnOutcome {}

    record PendingConfirmation(
            String pendingTurnId,
            String partialResponse,
            String pendingToolId
    ) implements TurnOutcome {}

    record Failed(String errorMessage, Throwable cause) implements TurnOutcome {}
}
