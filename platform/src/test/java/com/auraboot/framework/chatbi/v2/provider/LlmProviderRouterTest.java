package com.auraboot.framework.chatbi.v2.provider;

import com.auraboot.framework.chatbi.v2.dto.SearchToken;
import com.auraboot.framework.chatbi.v2.dto.TokenType;
import com.auraboot.framework.semantic.dto.SemanticMetaResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmProviderRouterTest {

    private LlmProvider primary;
    private LlmProvider secondary;
    private LlmProviderRouter router;

    @BeforeEach
    void setup() {
        primary = mock(LlmProvider.class);
        secondary = mock(LlmProvider.class);
        when(primary.routingKey()).thenReturn("provider-primary");
        when(secondary.routingKey()).thenReturn("provider-secondary");
        router = new LlmProviderRouter(List.of(primary, secondary));
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
        when(primary.translate(any(), any(), any())).thenReturn(ok(0.9));

        LlmProviderRouter.RouteOutcome out = router.translate(
                "q", new SemanticMetaResponse(), ConversationContext.empty());

        assertThat(out.winner()).isEqualTo("provider-primary");
        assertThat(out.result().confidence()).isEqualTo(0.9);
        verify(secondary, never()).translate(any(), any(), any());
        assertThat(out.attempts()).hasSize(1);
        assertThat(out.attempts().get(0).outcome())
                .isEqualTo(LlmProviderRouter.Outcome.SUCCESS);
    }

    @Test
    void primaryEmptyFallsBackToSecondary() {
        when(primary.translate(any(), any(), any())).thenReturn(empty());
        when(secondary.translate(any(), any(), any())).thenReturn(ok(0.85));

        LlmProviderRouter.RouteOutcome out = router.translate(
                "q", new SemanticMetaResponse(), ConversationContext.empty());

        assertThat(out.winner()).isEqualTo("provider-secondary");
        assertThat(out.result().confidence()).isEqualTo(0.85);
        verify(primary).translate(any(), any(), any());
        verify(secondary).translate(any(), any(), any());
        assertThat(out.attempts()).hasSize(2);
        assertThat(out.attempts().get(0).outcome())
                .isEqualTo(LlmProviderRouter.Outcome.EMPTY);
    }

    @Test
    void bothEmptyDowngradesToKeyword() {
        when(primary.translate(any(), any(), any())).thenReturn(empty());
        when(secondary.translate(any(), any(), any())).thenReturn(empty());

        LlmProviderRouter.RouteOutcome out = router.translate(
                "q", new SemanticMetaResponse(), ConversationContext.empty());

        assertThat(out.winner()).isEqualTo("keyword-catalog");
        assertThat(out.result().confidence()).isZero();
        assertThat(out.attempts()).hasSize(3);
        assertThat(out.attempts().get(2).outcome())
                .isEqualTo(LlmProviderRouter.Outcome.DOWNGRADED);
    }

    @Test
    void disambiguationFromPrimaryIsAcceptable() {
        when(primary.translate(any(), any(), any())).thenReturn(disambig());

        LlmProviderRouter.RouteOutcome out = router.translate(
                "q", new SemanticMetaResponse(), ConversationContext.empty());

        assertThat(out.winner()).isEqualTo("provider-primary");
        assertThat(out.result().needsClarification()).isTrue();
        verify(secondary, never()).translate(any(), any(), any());
    }

    @Test
    void primaryThrowingFallsBackAndIncrementsBreaker() {
        when(primary.translate(any(), any(), any()))
                .thenThrow(new RuntimeException("wire fault"));
        when(secondary.translate(any(), any(), any())).thenReturn(ok(0.8));

        LlmProviderRouter.RouteOutcome out = router.translate(
                "q", new SemanticMetaResponse(), ConversationContext.empty());

        assertThat(out.winner()).isEqualTo("provider-secondary");
        assertThat(out.attempts().get(0).outcome())
                .isEqualTo(LlmProviderRouter.Outcome.FAILED);
    }

    @Test
    void breakerOpensAfterFiveFailuresAndSkipsPrimary() {
        when(primary.translate(any(), any(), any()))
                .thenThrow(new RuntimeException("wire fault"));
        when(secondary.translate(any(), any(), any())).thenReturn(ok(0.8));

        // 5 calls — primary fails each time, but breaker opens after the 5th
        // failure. Subsequent calls should skip primary entirely.
        for (int i = 0; i < 5; i++) {
            router.translate("q" + i, new SemanticMetaResponse(), ConversationContext.empty());
        }
        verify(primary, times(5)).translate(any(), any(), any());

        LlmProviderRouter.RouteOutcome blocked = router.translate(
                "q6", new SemanticMetaResponse(), ConversationContext.empty());
        verify(primary, times(5)).translate(any(), any(), any()); // no more
        assertThat(blocked.attempts().get(0).outcome())
                .isEqualTo(LlmProviderRouter.Outcome.CIRCUIT_OPEN);
        assertThat(blocked.winner()).isEqualTo("provider-secondary");
    }

    @Test
    void successResetsBreaker() {
        when(primary.translate(any(), any(), any()))
                .thenThrow(new RuntimeException("flap 1"))
                .thenThrow(new RuntimeException("flap 2"))
                .thenReturn(ok(0.9));
        when(secondary.translate(any(), any(), any())).thenReturn(ok(0.5));

        router.translate("q1", new SemanticMetaResponse(), ConversationContext.empty());
        router.translate("q2", new SemanticMetaResponse(), ConversationContext.empty());
        LlmProviderRouter.RouteOutcome good = router.translate(
                "q3", new SemanticMetaResponse(), ConversationContext.empty());

        assertThat(good.winner()).isEqualTo("provider-primary");
        // Inject 5 fresh failures — breaker should reopen normally.
        when(primary.translate(any(), any(), any()))
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
    void oneConfiguredProviderCanSucceed() {
        when(secondary.translate(any(), any(), any())).thenReturn(ok(0.8));
        LlmProviderRouter custom = new LlmProviderRouter(List.of(secondary));

        LlmProviderRouter.RouteOutcome out = custom.translate(
                "q", new SemanticMetaResponse(), ConversationContext.empty());

        assertThat(out.winner()).isEqualTo("provider-secondary");
        assertThat(out.attempts().get(0).outcome())
                .isEqualTo(LlmProviderRouter.Outcome.SUCCESS);
    }

    @Test
    void allProvidersMissingDowngrades() {
        LlmProviderRouter custom = new LlmProviderRouter(List.of());

        LlmProviderRouter.RouteOutcome out = custom.translate(
                "q", new SemanticMetaResponse(), ConversationContext.empty());

        assertThat(out.winner()).isEqualTo("keyword-catalog");
        assertThat(out.result().confidence()).isZero();
    }
}
