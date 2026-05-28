package com.auraboot.framework.chatbi.v2.lexer;

import com.auraboot.framework.chatbi.v2.dto.SearchToken;
import com.auraboot.framework.chatbi.v2.provider.IntentResult;
import com.auraboot.framework.semantic.dto.SemanticMetaResponse;

import java.util.List;

/**
 * Lexes a natural-language query into the canonical Token sequence.
 *
 * <p>PRD 17 §4.2 — the Lexer is positioned <em>after</em> the LLM Provider:
 * the LLM produces a tentative {@link IntentResult}; the Lexer validates
 * (dictionary lookup, code existence in {@code catalog}, synonym resolution)
 * and fills gaps. The Compiler then consumes the verified Tokens.
 *
 * <p>Contracts:
 * <ul>
 *   <li>Never throws — invalid tokens are dropped (and W3's audit log
 *       records the drop).</li>
 *   <li>{@code llmHint} may be {@link IntentResult#empty()}; implementations
 *       must degrade gracefully (W2 returns an empty list, W3 swaps in
 *       v1 keyword fallback via {@code ChatBIService}).</li>
 * </ul>
 */
public interface TokenLexer {

    List<SearchToken> lex(String nlQuery, SemanticMetaResponse catalog, IntentResult llmHint);
}
