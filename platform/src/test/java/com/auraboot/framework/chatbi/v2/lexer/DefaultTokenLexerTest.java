package com.auraboot.framework.chatbi.v2.lexer;

import com.auraboot.framework.chatbi.v2.dto.SearchToken;
import com.auraboot.framework.chatbi.v2.provider.IntentResult;
import com.auraboot.framework.chatbi.v2.provider.LlmUsage;
import com.auraboot.framework.semantic.dto.SemanticMetaResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DefaultTokenLexer} W2 contract: LLM tokens pass through; empty
 * LLM result yields empty list (v1 fallback wired in W3).
 */
class DefaultTokenLexerTest {

    private DefaultTokenLexer lexer;
    private SemanticMetaResponse catalog;

    @BeforeEach
    void setUp() {
        lexer = new DefaultTokenLexer();
        catalog = new SemanticMetaResponse();
    }

    @Test
    void passesThroughLlmTokens() {
        List<SearchToken> hintTokens = List.of(
                SearchToken.metric("sales.total_sales", "销售额", 0),
                SearchToken.timeRange("ytd", "今年", 1));
        IntentResult hint = new IntentResult(hintTokens, 0.95d, false, null, List.of(), LlmUsage.zero());

        List<SearchToken> out = lexer.lex("今年销售额", catalog, hint);

        assertThat(out).hasSize(2);
        assertThat(out.get(0).resolvedCode()).isEqualTo("sales.total_sales");
        assertThat(out.get(1).resolvedCode()).isEqualTo("ytd");
    }

    @Test
    void emptyHintYieldsEmptyList() {
        List<SearchToken> out = lexer.lex("anything", catalog, IntentResult.empty());
        assertThat(out).isEmpty();
    }

    @Test
    void nullHintYieldsEmptyList() {
        List<SearchToken> out = lexer.lex("anything", catalog, null);
        assertThat(out).isEmpty();
    }
}
