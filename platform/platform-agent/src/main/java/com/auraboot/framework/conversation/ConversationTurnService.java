package com.auraboot.framework.conversation;

/**
 * L5 chokepoint over the chat layer. Turns transport-level requests
 * (SSE / WS / sync) into a uniform turn lifecycle backed by chat impls
 * (AuraBotChatService / future ACPRuntime / future group-chat-adapter).
 *
 * <p>Phase A (this commit) defines the SPI; Phase A.2-A.7 wires the
 * existing AuraBotController.{@code /chat/stream} endpoint to call
 * {@link #runTurn} with {@link TurnSideEffects#observeOnly} injected
 * so persistence/events/audit stay NOOP and behavior is unchanged.
 *
 * <p>Phase B replaces the NOOP persistence with real
 * {@link TurnSideEffects.Persistence}; Phase B+ adds the group-chat
 * adapter for async paths.
 *
 * <p>Contract reference: {@code auraboot/docs/plans/2026-04/2026-04-26-conversation-turn-service-design.md}
 */
public interface ConversationTurnService {

    /**
     * Single-orchestrator entry. Internal try/catch/finally guarantees the
     * {@code endTurn}/{@code suspendTurn} dispatch fires on every code path
     * including SSE exceptions, provider errors, and client disconnects.
     */
    TurnOutcome runTurn(TurnRequest request, ResponseSink sink);

    /**
     * Continuation entry for confirm-required suspended turns. Looks up the
     * pending state via {@code pendingTurnId} and resumes the original
     * {@link TurnContext}; does NOT create a new inbound message.
     *
     * <p>Wired to {@code POST /api/ai/aurabot/execute} after Phase B's
     * pendingTurnId payload migration lands (per design §3.10).
     */
    TurnOutcome resumeTurn(String pendingTurnId, ConfirmDecision decision, ResponseSink sink);

    enum ConfirmDecision { APPROVED, DENIED, CANCELLED }
}
