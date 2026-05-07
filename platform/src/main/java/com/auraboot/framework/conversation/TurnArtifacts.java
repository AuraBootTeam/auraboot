package com.auraboot.framework.conversation;

/**
 * D.1 (2026-05-07): bundle of side-channel artifacts gathered during a turn
 * that {@link TurnSideEffects.Persistence#persistOutbound(TurnContext,
 * TurnOutcome, TurnArtifacts)} writes alongside the assistant row.
 *
 * <p>Currently carries Anthropic Extended Thinking prose + verification
 * signature captured by {@link ThinkingCapturingResponseSink}. Both fields
 * are nullable; null = "no artifact of this kind for this turn" (do not
 * write empty strings — see {@code ab_im_message.thinking_content} schema
 * red line).
 *
 * <p>Future expansion: per-tool token usage, ResultContract aggregates,
 * etc. — when added, follow the same nullable-field discipline so legacy
 * call sites that pass {@link #EMPTY} keep compiling unchanged.
 */
public record TurnArtifacts(String thinkingContent, String thinkingSignature) {

    /** Sentinel for turns that produced no artifacts. */
    public static final TurnArtifacts EMPTY = new TurnArtifacts(null, null);

    public static TurnArtifacts of(String thinkingContent, String thinkingSignature) {
        if ((thinkingContent == null || thinkingContent.isEmpty())
                && (thinkingSignature == null || thinkingSignature.isEmpty())) {
            return EMPTY;
        }
        return new TurnArtifacts(thinkingContent, thinkingSignature);
    }
}
