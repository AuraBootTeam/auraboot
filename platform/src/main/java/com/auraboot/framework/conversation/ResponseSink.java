package com.auraboot.framework.conversation;

import java.util.Map;

/**
 * Transport-agnostic streaming output. Backed by SSE / WS / sync-JSON adapters.
 *
 * <p>This is the only thing the chat impl writes to during a turn. The orchestrator
 * (ConversationTurnService) handles persistence + audit + event emission externally,
 * keeping this surface purely about transport.
 *
 * <p>{@link #isClientConnected()} lets the chat impl bail early if the client closed
 * the SSE connection; orchestrator may then emit {@link TurnOutcome.Interrupted}.
 */
public interface ResponseSink {

    void onTextChunk(String text);

    void onToolStart(String toolId, String name, Map<String, Object> args);

    void onToolResult(String toolId, Map<String, Object> result, boolean success);

    void onConfirmRequired(String toolId, String name, Map<String, Object> args);

    void onError(String message);

    void onDone(String finalResponse);

    /** Adapter-specific liveness check; defaults to true for sinks without disconnect signals. */
    default boolean isClientConnected() {
        return true;
    }
}
