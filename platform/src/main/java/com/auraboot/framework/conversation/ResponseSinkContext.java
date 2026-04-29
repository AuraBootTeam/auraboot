package com.auraboot.framework.conversation;

/**
 * ThreadLocal carrier for the current chat turn's {@link ResponseSink}, so
 * tool-execution code (e.g. {@code ResultContractEmitter} from
 * {@link com.auraboot.framework.agent.service}) can publish events without
 * threading the sink as a parameter through every layer.
 *
 * <p>Set by the chat impl ({@code AuraBotChatService.executeAuraBotTurn} /
 * {@code resumeApprovedTurnFromPending}) at the start of a turn and cleared
 * in finally. Read by {@code ResultContractEmitter}. When no context is set
 * (non-chat callers — ad-hoc skill invocations, tests), reads return
 * {@code null} and the emitter no-ops, preserving the safe-when-absent
 * semantics that the legacy {@code ChatSseContext} ThreadLocal had.
 *
 * <p>Phase C.3b (2026-04-30): replaces the SSE-specific
 * {@code com.auraboot.framework.agent.service.ChatSseContext} so the agent
 * layer no longer reaches into Spring's {@code SseEmitter} directly. Any sink
 * type ({@link SseResponseSink} for HTTP, future WS / sync-JSON adapters)
 * works through this same context. Per design v3.3 §3.4 — "ResultContract is
 * a sink-level event, not a transport-level concept."
 */
public final class ResponseSinkContext {

    private static final ThreadLocal<ResponseSink> CURRENT = new ThreadLocal<>();

    private ResponseSinkContext() {}

    /** Bind the current turn's sink. Must be paired with {@link #clear()} in a finally block. */
    public static void set(ResponseSink sink) {
        CURRENT.set(sink);
    }

    /** Returns the current sink, or {@code null} when no turn is bound (test / cron caller). */
    public static ResponseSink get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
