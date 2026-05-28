package com.auraboot.framework.chatbi.v2.provider;

import com.auraboot.framework.chatbi.v2.dto.SearchToken;

import java.util.Collections;
import java.util.List;

/**
 * Output of {@link LlmProvider#translate}. PRD 17 §3.2 + §7.3.
 *
 * <p>The Token sequence is the primary contract; everything else is metadata.
 * The {@link com.auraboot.framework.chatbi.v2.compiler.TokenCompiler} consumes
 * {@link #tokens()} and never reads the rest.
 *
 * <p>{@code needsClarification} is set true when {@link #confidence} &lt; 0.7
 * <em>and</em> {@link #disambiguation} is populated; W3 will short-circuit
 * the answer flow into UI prompt mode.
 *
 * <p>{@code suggestedFollowUps} is for post-answer "next question" chips
 * (W4 surface), included here so the LLM round-trip carries all derived
 * artefacts in one record.
 */
public record IntentResult(
        List<SearchToken> tokens,
        double confidence,
        boolean needsClarification,
        Disambiguation disambiguation,
        List<String> suggestedFollowUps,
        LlmUsage usage) {

    /** Constructs an empty / non-clarifying result; used by {@link NoopLlmProvider}. */
    public static IntentResult empty() {
        return new IntentResult(
                Collections.emptyList(),
                0.0d,
                false,
                null,
                Collections.emptyList(),
                LlmUsage.zero());
    }
}
