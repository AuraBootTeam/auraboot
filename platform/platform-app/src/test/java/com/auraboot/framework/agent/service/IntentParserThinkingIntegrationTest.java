package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * P0-2 follow-up — verifies that {@link IntentParser} routes long / complex
 * queries to the LLM with Anthropic Extended Thinking enabled, and that
 * short queries continue to take the cheap pattern/keyword/no-thinking path.
 *
 * <p>Wiring:
 * <ul>
 *   <li>{@link LlmProviderFactory} is replaced with a {@code @MockitoBean}.
 *       Inside {@link com.auraboot.framework.intent.service.DefaultLlmClient}
 *       only the thinking-ON branch consults the factory, so:
 *       <ul>
 *         <li>thinking-ON  → factory is used and {@link LlmChatRequest} is
 *             captured for assertion;</li>
 *         <li>thinking-OFF → factory is bypassed (legacy HTTP path); we
 *             assert the factory had no interactions.</li>
 *       </ul>
 *   </li>
 *   <li>Phase 3 (LLM fallback) is reached only when Phase 1 (regex) and
 *       Phase 2 (keyword) miss. We use Greek text — explicitly NOT covered
 *       by any rule in {@link IntentParser} — so each call falls through to
 *       the LLM path under test.</li>
 * </ul>
 *
 * <p>Threshold under test: {@code userMessage.length() > 200} → thinking ON,
 * {@code <= 200} → thinking OFF.
 */
class IntentParserThinkingIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private IntentParser intentParser;

    @MockitoBean
    private LlmProviderFactory providerFactory;

    /**
     * Build a Greek string of exactly {@code length} characters that has no
     * regex/keyword overlap so it always falls through to Phase 3 (LLM).
     * Greek lowercase letters are deliberately absent from
     * {@link IntentParser}'s zh/en/ja rule sets.
     */
    private static String greekOfLength(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append((char) (0x03B1 + (i % 17))); // cycles α..ρ
        }
        return sb.toString();
    }

    /**
     * Stub provider factory + provider so the thinking-ON branch lands in
     * a captured {@link LlmChatRequest} and returns a deterministic
     * "create" answer (one of {@code IntentParser.VALID_INTENTS}).
     */
    private List<LlmChatRequest> stubProviderAndCapture() throws Exception {
        List<LlmChatRequest> captured = new ArrayList<>();

        LlmProvider mockProvider = org.mockito.Mockito.mock(LlmProvider.class);
        when(mockProvider.chat(any(LlmChatRequest.class), anyString(), anyString()))
                .thenAnswer((InvocationOnMock inv) -> {
                    captured.add(inv.getArgument(0));
                    return LlmChatResponse.builder()
                            .stopReason("end_turn")
                            .content(List.of(LlmChatResponse.ContentBlock.builder()
                                    .type("text")
                                    .text("create")
                                    .build()))
                            .build();
                });

        LlmProviderFactory.ProviderConfig config = LlmProviderFactory.ProviderConfig.builder()
                .providerCode("anthropic")
                .apiKey("sk-test-thinking")
                .baseUrl("https://api.anthropic.com")
                .defaultModel("claude-sonnet-4-6")
                .maxTokens(4096)
                .build();

        when(providerFactory.resolveConfig(any(), any())).thenReturn(config);
        when(providerFactory.getProvider(anyString())).thenReturn(mockProvider);

        return captured;
    }

    @Test
    @DisplayName("parse_shortQuery_doesNotEnableThinking")
    void parse_shortQuery_doesNotEnableThinking() throws Exception {
        // Stub the factory anyway — we want to prove it's NOT consulted.
        List<LlmChatRequest> captured = stubProviderAndCapture();

        // Length 50 — strictly below the 200 threshold.
        String shortQuery = greekOfLength(50);
        assertThat(shortQuery.length()).isEqualTo(50);

        // Drive the pipeline. Phases 1/2 miss (Greek), so Phase 3 fires
        // with ChatOptions.defaults() (thinking == null) — which routes to
        // the legacy HTTP path inside DefaultLlmClient and never touches the
        // provider factory mock.
        intentParser.parse(shortQuery);

        // The exact result code depends on whether the legacy LLM HTTP call
        // succeeds or throws (and is swallowed → "default" branch); the
        // *only* invariant we care about for thinking is that the factory
        // mock — which only the thinking branch ever consults — is untouched.
        verifyNoInteractions(providerFactory);
        assertThat(captured)
                .as("Short query must NOT reach the provider path "
                        + "(thinking gated off → captured request count must stay 0)")
                .isEmpty();
    }

    @Test
    @DisplayName("parse_longComplexQuery_enablesThinking")
    void parse_longComplexQuery_enablesThinking() throws Exception {
        List<LlmChatRequest> captured = stubProviderAndCapture();

        // Length 250 — well past the 200 threshold.
        String longQuery = greekOfLength(250);
        assertThat(longQuery.length()).isEqualTo(250);

        IntentParser.IntentResult result = intentParser.parse(longQuery);

        assertThat(result).isNotNull();
        assertThat(result.getMatchType())
                .as("Greek text must reach Phase 3 LLM path; provider mock returns 'create'")
                .isEqualTo("llm");

        verify(providerFactory, atLeastOnce()).getProvider(anyString());
        assertThat(captured)
                .as("Long query must dispatch exactly one thinking-enabled provider call")
                .hasSize(1);

        LlmChatRequest req = captured.get(0);
        assertThat(req.getThinking())
                .as("Long query must enable Extended Thinking")
                .isNotNull();
        assertThat(req.getThinking().isEnabled())
                .as("thinking.enabled must be true for queries longer than 200 chars")
                .isTrue();
        assertThat(req.getThinking().getBudgetTokens())
                .as("thinking.budgetTokens must equal IntentParser THINKING_BUDGET_TOKENS_FOR_INTENT")
                .isEqualTo(8000);
    }

    @Test
    @DisplayName("parse_thresholdEdgeCase_201chars_enablesThinking")
    void parse_thresholdEdgeCase_201chars_enablesThinking() throws Exception {
        List<LlmChatRequest> captured = stubProviderAndCapture();

        // Length 201 — exactly one over the strict ">" threshold.
        String edgeQuery = greekOfLength(201);
        assertThat(edgeQuery.length()).isEqualTo(201);

        IntentParser.IntentResult result = intentParser.parse(edgeQuery);

        assertThat(result).isNotNull();
        assertThat(result.getMatchType()).isEqualTo("llm");

        assertThat(captured).hasSize(1);
        LlmChatRequest req = captured.get(0);
        assertThat(req.getThinking())
                .as("201-char query must cross the >200 threshold and enable thinking")
                .isNotNull();
        assertThat(req.getThinking().isEnabled()).isTrue();
        assertThat(req.getThinking().getBudgetTokens()).isEqualTo(8000);
    }

    /**
     * Sanity / non-regression: the threshold boundary is strict {@code >},
     * not {@code >=}. A 200-char query MUST stay on the no-thinking branch.
     */
    @Test
    @DisplayName("parse_thresholdBoundary_200chars_doesNotEnableThinking")
    void parse_thresholdBoundary_200chars_doesNotEnableThinking() throws Exception {
        List<LlmChatRequest> captured = stubProviderAndCapture();

        String edgeQuery = greekOfLength(200);
        assertThat(edgeQuery.length()).isEqualTo(200);

        intentParser.parse(edgeQuery);

        verify(providerFactory, never()).resolveConfig(any(), any());
        assertThat(captured)
                .as("200-char query is at the strict-> threshold and must NOT enable thinking")
                .isEmpty();
    }

    /**
     * Sanity: BaseIntegrationTest sets the tenant context, so the provider
     * path's {@link MetaContext} lookup is always non-null.
     */
    @Test
    @DisplayName("metaContext_isPopulated_forProviderPath")
    void metaContext_isPopulated_forProviderPath() {
        assertThat(MetaContext.exists()).isTrue();
        assertThat(MetaContext.getCurrentTenantId()).isNotNull();
    }
}
