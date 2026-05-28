package com.auraboot.framework.chatbi.v2.provider;

import com.auraboot.framework.semantic.dto.SemanticMetaResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link NoopLlmProvider} must be deterministic: always returns
 * {@link IntentResult#empty()} regardless of input. Confirms the W2 escape
 * hatch is wired correctly before W4 real LLM providers land.
 */
class NoopLlmProviderTest {

    @Test
    void returnsEmptyIntentResultForAnyInput() {
        NoopLlmProvider p = new NoopLlmProvider();
        SemanticMetaResponse catalog = new SemanticMetaResponse();
        ConversationContext ctx = ConversationContext.empty();

        IntentResult r = p.translate("今年华东销售额", catalog, ctx);

        assertThat(r).isNotNull();
        assertThat(r.tokens()).isEmpty();
        assertThat(r.confidence()).isZero();
        assertThat(r.needsClarification()).isFalse();
        assertThat(r.disambiguation()).isNull();
        assertThat(r.suggestedFollowUps()).isEmpty();
        assertThat(r.usage()).isNotNull();
        assertThat(r.usage().model()).isEqualTo("noop");
        assertThat(r.usage().totalTokens()).isZero();
    }

    @Test
    void handlesNullCatalogWithoutThrowing() {
        NoopLlmProvider p = new NoopLlmProvider();
        IntentResult r = p.translate("anything", null, ConversationContext.empty());
        assertThat(r.tokens()).isEmpty();
    }
}
