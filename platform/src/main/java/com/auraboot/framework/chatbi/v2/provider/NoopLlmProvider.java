package com.auraboot.framework.chatbi.v2.provider;

import com.auraboot.framework.semantic.dto.SemanticMetaResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default {@link LlmProvider} used when no real LLM is wired (W1 / W2 build,
 * unit tests, offline dev).
 *
 * <p>Always returns {@link IntentResult#empty()} — confidence 0, no tokens.
 * Downstream {@code TokenLexer} treats this as the "no LLM hint" path and
 * falls back to v1 keyword behaviour (or, in W2, returns an empty Token list
 * with a TODO marker — see {@link com.auraboot.framework.chatbi.v2.lexer.DefaultTokenLexer}).
 *
 * <p>Activated by {@code aura.chatbi.v2.llm-provider=noop} or absence of the
 * property; W4 will register Anthropic / OpenAI providers under different
 * values and let one win.
 */
@Component
@ConditionalOnProperty(
        name = "aura.chatbi.v2.llm-provider",
        havingValue = "noop",
        matchIfMissing = true)
public class NoopLlmProvider implements LlmProvider {

    @Override
    public IntentResult translate(String nlQuery, SemanticMetaResponse catalog, ConversationContext ctx) {
        return IntentResult.empty();
    }
}
