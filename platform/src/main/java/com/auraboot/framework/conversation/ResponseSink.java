package com.auraboot.framework.conversation;

import java.util.Map;

/**
 * Transport-agnostic streaming output. Backed by SSE / WS / sync-JSON adapters.
 *
 * <p>This is the only thing the chat impl writes to during a turn. The orchestrator
 * (ConversationTurnService) handles persistence + audit + event emission externally,
 * keeping this surface purely about transport.
 *
 * <p>v4: parameter shape aligned with OSS source send* helpers
 * (AuraBotChatService line 1389-1466). traceId travels on done/error events
 * (set inside doStreamChatInner; sink does not hold it). description is a
 * first-class field on confirm_required to match sendConfirmRequired.
 *
 * <p>Contract reference: enterprise/docs/agent/contracts/pre-grounding-triage.md
 * (sister contract); design v3.3 §3.4.
 */
public interface ResponseSink {

    void onTextChunk(String text);

    void onToolStart(String toolId, String toolName, Map<String, Object> input);

    void onToolResult(String toolId, Map<String, Object> result, boolean success);

    /**
     * v4: 4-arg sendConfirmRequired alignment + B.6: {@code pendingTurnId}
     * (the {@code TurnContext.turnId()} of the suspended turn). The frontend
     * receives this value on the {@code confirm_required} SSE event payload
     * and echoes it back in {@code POST /execute} so {@code resumeTurn}
     * looks up the suspended turn state by turnId rather than sessionId
     * (per design v3.3 §3.10 step 1).
     */
    void onConfirmRequired(String toolId, String toolName, String description,
                            Map<String, Object> input, String pendingTurnId);

    /** v4: traceId passed on terminal event (sink does not pre-hold traceId). */
    void onError(String message, String traceId);

    /** v4: traceId passed on terminal event. */
    void onDone(String finalResponse, String traceId);

    /** Adapter-specific liveness check; defaults to true for sinks without disconnect signals. */
    default boolean isClientConnected() {
        return true;
    }
}
