package com.auraboot.framework.chatbi.v2.provider;

import com.auraboot.framework.chatbi.v2.dto.SearchToken;
import com.auraboot.framework.chatbi.v2.dto.TokenType;
import com.auraboot.framework.semantic.dto.SemanticMetaResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmProviderRouterTest {

    private AnthropicLlmProvider anthropic;
    private OpenAiLlmProvider openai;
    private LlmProviderRouter router;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        anthropic = mock(AnthropicLlmProvider.class);
        openai = mock(OpenAiLlmProvider.class);

        ObjectProvider<AnthropicLlmProvider> pri = mock(ObjectProvider.class);
        ObjectProvider<OpenAiLlmProvider> sec = mock(ObjectProvider.class);
        when(pri.getIfAvailable()).thenReturn(anthropic);
        when(sec.getIfAvailable()).thenReturn(openai);

        router = new LlmProviderRouter(pri, sec);
    }

    private static IntentResult ok(double confidence) {
        return new IntentResult(
                List.of(new SearchToken(TokenType.METRIC, "x", "s.t", null, null, 0, null, null)),
                confidence, false, null, List.of(),
                new LlmUsage("test-model", 10, 5, 0.1, 50L));
    }

    private static IntentResult empty() {
        return IntentResult.empty();
    }

    private static IntentResult disambig() {
        return new IntentResult(
                List.of(), 0.4, true,
                new Disambiguation("销量", List.of(
                        new Disambiguation.Candidate("METRIC", "x", "X", 0.8))),
                List.of(),
                new LlmUsage("test-model", 5, 2, 0.05, 30L));
    }

    @Test
    void primarySucceedsSkipsSecondary() {
        when(anthropic.translate(any(), any(), any())).thenReturn(ok(0.9));

        LlmProviderRouter.RouteOutcome out = router.translate(
                "q", new SemanticMetaResponse(), ConversationContext.empty());

        assertThat(out.winner()).isEqualTo("anthropic");
        assertThat(out.result().confidence()).isEqualTo(0.9);
        verify(openai, never()).translate(any(), any(), any());
        assertThat(out.attempts()).hasSize(1);
        assertThat(out.attempts().get(0).outcome())
                .isEqualTo(LlmProviderRouter.Outcome.SUCCESS);
    }

    @Test
    void primaryEmptyFallsBackToSecondary() {
        when(anthropic.translate(any(), any(), any())).thenReturn(empty());
        when(openai.translate(any(), any(), any())).thenReturn(ok(0.85));

        LlmProviderRouter.RouteOutcome out = router.translate(
                "q", new SemanticMetaResponse(), ConversationContext.empty());

        assertThat(out.winner()).isEqualTo("openai");
        assertThat(out.result().confidence()).isEqualTo(0.85);
        verify(anthropic).translate(any(), any(), any());
        verify(openai).translate(any(), any(), any());
        assertThat(out.attempts()).hasSize(2);
        assertThat(out.attempts().get(0).outcome())
                .isEqualTo(LlmProviderRouter.Outcome.EMPTY);
    }

    @Test
    void bothEmptyDowngradesToKeyword() {
        when(anthropic.translate(any(), any(), any())).thenReturn(empty());
        when(openai.translate(any(), any(), any())).thenReturn(empty());

        LlmProviderRouter.RouteOutcome out = router.translate(
                "q", new SemanticMetaResponse(), ConversationContext.empty());

        assertThat(out.winner()).isEqualTo("keyword-v1");
        assertThat(out.result().confidence()).isZero();
        assertThat(out.attempts()).hasSize(3);
        assertThat(out.attempts().get(2).outcome())
                .isEqualTo(LlmProviderRouter.Outcome.DOWNGRADED);
    }

    @Test
    void disambiguationFromPrimaryIsAcceptable() {
        when(anthropic.translate(any(), any(), any())).thenReturn(disambig());

        LlmProviderRouter.RouteOutcome out = router.translate(
                "q", new SemanticMetaResponse(), ConversationContext.empty());

        assertThat(out.winner()).isEqualTo("anthropic");
        assertThat(out.result().needsClarification()).isTrue();
        verify(openai, never()).translate(any(), any(), any());
    }

    @Test
    void primaryThrowingFallsBackAndIncrementsBreaker() {
        when(anthropic.translate(any(), any(), any()))
                .thenThrow(new RuntimeException("wire fault"));
        when(openai.translate(any(), any(), any())).thenReturn(ok(0.8));

        LlmProviderRouter.RouteOutcome out = router.translate(
                "q", new SemanticMetaResponse(), ConversationContext.empty());

        assertThat(out.winner()).isEqualTo("openai");
        assertThat(out.attempts().get(0).outcome())
                .isEqualTo(LlmProviderRouter.Outcome.FAILED);
    }

    @Test
    void breakerOpensAfterFiveFailuresAndSkipsPrimary() {
        when(anthropic.translate(any(), any(), any()))
                .thenThrow(new RuntimeException("wire fault"));
        when(openai.translate(any(), any(), any())).thenReturn(ok(0.8));

        // 5 calls — primary fails each time, but breaker opens after the 5th
        // failure. Subsequent calls should skip primary entirely.
        for (int i = 0; i < 5; i++) {
            router.translate("q" + i, new SemanticMetaResponse(), ConversationContext.empty());
        }
        verify(anthropic, times(5)).translate(any(), any(), any());

        LlmProviderRouter.RouteOutcome blocked = router.translate(
                "q6", new SemanticMetaResponse(), ConversationContext.empty());
        verify(anthropic, times(5)).translate(any(), any(), any()); // no more
        assertThat(blocked.attempts().get(0).outcome())
                .isEqualTo(LlmProviderRouter.Outcome.CIRCUIT_OPEN);
        assertThat(blocked.winner()).isEqualTo("openai");
    }

    @Test
    void successResetsBreaker() {
        when(anthropic.translate(any(), any(), any()))
                .thenThrow(new RuntimeException("flap 1"))
                .thenThrow(new RuntimeException("flap 2"))
                .thenReturn(ok(0.9));
        when(openai.translate(any(), any(), any())).thenReturn(ok(0.5));

        router.translate("q1", new SemanticMetaResponse(), ConversationContext.empty());
        router.translate("q2", new SemanticMetaResponse(), ConversationContext.empty());
        LlmProviderRouter.RouteOutcome good = router.translate(
                "q3", new SemanticMetaResponse(), ConversationContext.empty());

        assertThat(good.winner()).isEqualTo("anthropic");
        // Inject 5 fresh failures — breaker should reopen normally.
        when(anthropic.translate(any(), any(), any()))
                .thenThrow(new RuntimeException("re-flap"));
        for (int i = 0; i < 5; i++) {
            router.translate("q" + i, new SemanticMetaResponse(), ConversationContext.empty());
        }
        LlmProviderRouter.RouteOutcome blocked = router.translate(
                "after", new SemanticMetaResponse(), ConversationContext.empty());
        assertThat(blocked.attempts().get(0).outcome())
                .isEqualTo(LlmProviderRouter.Outcome.CIRCUIT_OPEN);
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingPrimaryBeanWalksToSecondary() {
        ObjectProvider<AnthropicLlmProvider> empty = mock(ObjectProvider.class);
        ObjectProvider<OpenAiLlmProvider> sec = mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);
        when(sec.getIfAvailable()).thenReturn(openai);
        when(openai.translate(any(), any(), any())).thenReturn(ok(0.8));
        LlmProviderRouter custom = new LlmProviderRouter(empty, sec);

        LlmProviderRouter.RouteOutcome out = custom.translate(
                "q", new SemanticMetaResponse(), ConversationContext.empty());

        assertThat(out.winner()).isEqualTo("openai");
        assertThat(out.attempts().get(0).outcome())
                .isEqualTo(LlmProviderRouter.Outcome.UNAVAILABLE);
    }

    @Test
    @SuppressWarnings("unchecked")
    void allProvidersMissingDowngrades() {
        ObjectProvider<AnthropicLlmProvider> e1 = mock(ObjectProvider.class);
        ObjectProvider<OpenAiLlmProvider> e2 = mock(ObjectProvider.class);
        when(e1.getIfAvailable()).thenReturn(null);
        when(e2.getIfAvailable()).thenReturn(null);
        LlmProviderRouter custom = new LlmProviderRouter(e1, e2);

        LlmProviderRouter.RouteOutcome out = custom.translate(
                "q", new SemanticMetaResponse(), ConversationContext.empty());

        assertThat(out.winner()).isEqualTo("keyword-v1");
        assertThat(out.result().confidence()).isZero();
    }
}
