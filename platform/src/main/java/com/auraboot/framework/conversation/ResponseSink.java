package com.auraboot.framework.conversation;

import com.auraboot.framework.agent.dto.ResultContract;

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

    /**
     * P0-2 (Anthropic Extended Thinking): chain-of-thought trace produced by
     * the assistant alongside its final answer. The SSE adapter serialises this
     * as the {@code thinking} event (see frontend ThinkingBlock); other sinks
     * may choose to ignore it.
     *
     * <p>{@code content} is the full thinking prose for the block (we emit on
     * block boundaries, not per delta, so the frontend renders a single
     * collapsible card per turn). {@code tokens} is the precise output token
     * count when known (best-effort; -1 when the upstream stream did not
     * surface a per-block usage figure). {@code signature} is Anthropic's
     * opaque resume token — non-null only on the Anthropic streaming path.
     *
     * <p>Default no-op so non-chat sinks (tests, future WS / sync-JSON) need
     * not implement it. Anthropic-only — OpenAI-compatible providers never
     * call it because their reasoning lives in a different stream shape.
     */
    default void onThinking(String content, int tokens, String signature) {
        // default no-op
    }

    /**
     * D.2 (ACP P0/P1 follow-up): non-fatal advisories produced by an LLM
     * provider during a turn — e.g. Anthropic's
     * {@code AnthropicLlmProvider.buildAnthropicRequest} auto-extends
     * {@code max_tokens} when Extended Thinking budget exceeds the caller's
     * value and records a human-readable explanation in
     * {@link com.auraboot.framework.agent.dto.LlmChatResponse#getWarnings()}.
     * Previously these strings only landed in {@code log.warn}; the SSE
     * adapter now forwards them to the frontend as a {@code warning} event
     * so users see a yellow toast instead of silently relying on server logs.
     *
     * <p>Contract: {@code warnings} is a non-empty list (callers MUST skip
     * the call when {@link com.auraboot.framework.agent.dto.LlmChatResponse#getWarnings()}
     * is null/empty so we never emit empty {@code warning} frames). Each
     * string is one toast on the frontend.
     *
     * <p>Default no-op so non-chat sinks (tests, future WS / sync-JSON) need
     * not implement this — IM channels would clutter the conversation row
     * with provider-side advisories so the {@link BroadcastResponseSink}
     * keeps the no-op default.
     */
    default void onWarnings(java.util.List<String> warnings) {
        // default no-op
    }

    /**
     * Phase C.3b: structured tool-result envelope produced by
     * {@code ResultContractEmitter} after each {@code dsl_query} /
     * {@code dsl_command} execution. SSE adapter serialises as the
     * {@code result_contract} event (design v3.3 §3.4); other sinks may
     * choose to ignore it.
     *
     * <p>Default no-op so non-chat sinks (tests, future WS / sync-JSON) need
     * not implement this — the emitter's existing skip-when-no-context
     * semantics now manifest as "default no-op on the resolved sink".
     */
    default void onResultContract(ResultContract contract) {
        // default no-op
    }

    /** Adapter-specific liveness check; defaults to true for sinks without disconnect signals. */
    default boolean isClientConnected() {
        return true;
    }
}
