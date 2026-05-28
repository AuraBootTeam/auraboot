package com.auraboot.framework.chatbi.v2.provider;

import com.auraboot.framework.semantic.dto.SemanticMetaResponse;

/**
 * SPI for LLM-based natural-language → Token translation. PRD 17 §4.2 + §7.1.
 *
 * <p>Implementations must:
 * <ul>
 *   <li>Be deterministic w.r.t. {@code (nlQuery, catalog, ctx)} <em>OR</em>
 *       log the chosen model + temperature into {@link LlmUsage};</li>
 *   <li>Never throw — failures must surface as {@code IntentResult.confidence=0}
 *       so the caller can fall back to v1 keyword path;</li>
 *   <li>Honour the platform's ACP runtime guardrails (PRD §15 — multi-turn=5,
 *       Claude Haiku 4.5 default). Direct HTTP to Anthropic / OpenAI is
 *       only allowed via the ACP-managed key + cost gate.</li>
 * </ul>
 *
 * <p>W2 ships {@link NoopLlmProvider} only; real providers land in W4.
 */
public interface LlmProvider {

    /**
     * Translate a natural-language query into a Token sequence, given the
     * full semantic catalog as grounding and (optionally) recent conversation
     * context for follow-ups.
     *
     * @param nlQuery user-facing prompt, untrimmed
     * @param catalog full {@code /api/semantic/meta} payload — never null,
     *                may be empty
     * @param ctx     conversation memory (use {@link ConversationContext#empty}
     *                for single-turn callers)
     * @return non-null result; see {@link IntentResult#empty} for the contract
     *         when the provider has no opinion.
     */
    IntentResult translate(String nlQuery, SemanticMetaResponse catalog, ConversationContext ctx);
}
