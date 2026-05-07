package com.auraboot.framework.agent.dto;

/**
 * Single increment in a streaming LLM response (E.1 Phase 1).
 *
 * <p>Emitted by {@link com.auraboot.framework.agent.provider.LlmProvider#streamChat}
 * as a {@code Flux<LlmChunk>}. Subscribers observe a monotonic sequence of
 * deltas and finally one terminal chunk where {@code done=true} and
 * {@code aggregateResponse} carries the full {@link LlmChatResponse}
 * equivalent to the legacy synchronous {@code chat()} return value.
 *
 * <p>Contract:
 * <ul>
 *   <li>{@code seq} starts at 0 and increases by 1 per chunk on a single Flux.
 *   <li>{@code delta} is the user-visible text increment for this chunk; may
 *       be empty (e.g. {@code message_start} carries no text).
 *   <li>{@code thinkingDelta} is the Extended Thinking trace increment; null
 *       on non-thinking chunks. The {@code thinking_delta} Anthropic event
 *       maps here, the {@code text_delta} event maps to {@code delta}.
 *   <li>{@code done=true} signals end-of-stream; only the terminal chunk
 *       carries {@code aggregateResponse} so callers that subscribe purely
 *       for the final value can read it once and stop.
 *   <li>Error paths surface via {@code Flux.error(...)}, NOT via a chunk with
 *       {@code done=true} + null aggregate. Per spec Q5 there is no fallback
 *       to sync — streaming failures are hard errors.
 * </ul>
 *
 * <p>Records are intentionally immutable; chunks are passed as event payloads
 * across thread boundaries (provider Flux → executor → async event publisher).
 */
public record LlmChunk(
        long seq,
        String delta,
        String thinkingDelta,
        boolean done,
        LlmChatResponse aggregateResponse) {

    /** Convenience for {@code text_delta} chunks: text-only, in-progress. */
    public static LlmChunk delta(long seq, String text) {
        return new LlmChunk(seq, text == null ? "" : text, null, false, null);
    }

    /** Convenience for {@code thinking_delta} chunks: thinking-only, in-progress. */
    public static LlmChunk thinking(long seq, String thinkingText) {
        return new LlmChunk(seq, "", thinkingText, false, null);
    }

    /**
     * Terminal chunk wrapping a fully-aggregated {@link LlmChatResponse}. Used
     * by both (a) the default {@code streamChat} wrapper around sync
     * {@code chat}, and (b) the real Anthropic streaming impl after it
     * receives {@code message_stop}.
     */
    public static LlmChunk fromFinal(LlmChatResponse response) {
        return new LlmChunk(0L, "", null, true, response);
    }

    /** Terminal chunk at a known seq; used by streaming impls that emit deltas first. */
    public static LlmChunk done(long seq, LlmChatResponse response) {
        return new LlmChunk(seq, "", null, true, response);
    }
}
