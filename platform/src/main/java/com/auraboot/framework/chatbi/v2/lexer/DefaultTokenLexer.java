package com.auraboot.framework.chatbi.v2.lexer;

import com.auraboot.framework.chatbi.v2.dto.SearchToken;
import com.auraboot.framework.chatbi.v2.provider.IntentResult;
import com.auraboot.framework.semantic.dto.SemanticMetaResponse;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * W2 default {@link TokenLexer}: pass-through when the LLM gave us tokens,
 * empty list otherwise.
 *
 * <p>The full implementation (W3) will additionally:
 * <ul>
 *   <li>Resolve synonyms via {@code chatbi_token_dict};</li>
 *   <li>Verify each {@code resolvedCode} actually exists in {@code catalog};</li>
 *   <li>Drop / log tokens with missing references;</li>
 *   <li>Fall back to v1 {@code ChatBIService.analyzeQuestion} when the LLM
 *       returns empty (PRD 17 §4.1 — 30% reuse).</li>
 * </ul>
 *
 * <p>For now, the W2 stub keeps the API surface compilable and lets the
 * Compiler be unit-tested end-to-end with synthetic Token lists.
 */
@Component
public class DefaultTokenLexer implements TokenLexer {

    @Override
    public List<SearchToken> lex(String nlQuery, SemanticMetaResponse catalog, IntentResult llmHint) {
        if (llmHint == null || llmHint.tokens() == null || llmHint.tokens().isEmpty()) {
            // TODO(v0.1.1): wire v1 ChatBIService keyword fallback here.
            return Collections.emptyList();
        }
        return List.copyOf(llmHint.tokens());
    }
}
