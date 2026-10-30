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
 * <p>Result contracts are captured for persistence of authorized analytics query
 * references and non-analytics historical snapshots. Analytics rows remain reference-only.
 */
public record TurnArtifacts(
        String thinkingContent,
        String thinkingSignature,
        java.util.List<com.auraboot.framework.aurabot.service.RagContextProvider.RetrievalEvidence>
                retrievalEvidence,
        java.util.List<com.auraboot.framework.agent.dto.ResultContract> resultContracts,
        String traceId) {

    public TurnArtifacts(String content, String signature,
            java.util.List<com.auraboot.framework.aurabot.service.RagContextProvider.RetrievalEvidence> evidence,
            java.util.List<com.auraboot.framework.agent.dto.ResultContract> contracts) {
        this(content, signature, evidence, contracts, null);
    }

    /** Sentinel for turns that produced no artifacts. */
    public static final TurnArtifacts EMPTY = new TurnArtifacts(null, null, java.util.List.of());

    public TurnArtifacts(String thinkingContent, String thinkingSignature) {
        this(thinkingContent, thinkingSignature, java.util.List.of());
    }

    public TurnArtifacts(String content, String signature, java.util.List<com.auraboot.framework.aurabot.service.RagContextProvider.RetrievalEvidence> evidence) {
        this(content, signature, evidence, java.util.List.of());
    }

    public TurnArtifacts {
        resultContracts = resultContracts == null ? java.util.List.of() : java.util.List.copyOf(resultContracts);
        retrievalEvidence = retrievalEvidence == null
                ? java.util.List.of()
                : java.util.List.copyOf(retrievalEvidence);
    }

    public static TurnArtifacts of(String thinkingContent, String thinkingSignature) {
        if ((thinkingContent == null || thinkingContent.isEmpty())
                && (thinkingSignature == null || thinkingSignature.isEmpty())) {
            return EMPTY;
        }
        return new TurnArtifacts(thinkingContent, thinkingSignature);
    }

    public static TurnArtifacts of(
            String thinkingContent,
            String thinkingSignature,
            java.util.List<com.auraboot.framework.aurabot.service.RagContextProvider.RetrievalEvidence>
                    retrievalEvidence) {
        if ((thinkingContent == null || thinkingContent.isEmpty())
                && (thinkingSignature == null || thinkingSignature.isEmpty())
                && (retrievalEvidence == null || retrievalEvidence.isEmpty())) {
            return EMPTY;
        }
        return new TurnArtifacts(thinkingContent, thinkingSignature, retrievalEvidence);
    }
    public static TurnArtifacts of(String content, String signature,
            java.util.List<com.auraboot.framework.aurabot.service.RagContextProvider.RetrievalEvidence> evidence,
            java.util.List<com.auraboot.framework.agent.dto.ResultContract> contracts) {
        return new TurnArtifacts(content, signature, evidence, contracts);
    }
    public static TurnArtifacts of(String content, String signature,
            java.util.List<com.auraboot.framework.aurabot.service.RagContextProvider.RetrievalEvidence> evidence,
            java.util.List<com.auraboot.framework.agent.dto.ResultContract> contracts, String traceId) {
        return new TurnArtifacts(content, signature, evidence, contracts, traceId);
    }
}
