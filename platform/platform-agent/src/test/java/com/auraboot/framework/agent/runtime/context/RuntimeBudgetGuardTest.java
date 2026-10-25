package com.auraboot.framework.agent.runtime.context;

import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.identity.DelegationGrant;
import com.auraboot.framework.agent.identity.ExecutionPrincipal;
import com.auraboot.framework.agent.identity.Initiator;
import com.auraboot.framework.agent.provider.LlmProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("RuntimeBudgetGuard")
class RuntimeBudgetGuardTest {

    private static final Instant NOW = Instant.parse("2026-07-29T12:00:00Z");

    @Test
    @DisplayName("rejects execution at the pinned deadline")
    void rejectsExpiredDeadline() {
        RuntimeBudgetGuard guard = guard(envelope(NOW, null, null, null), null);

        assertThatThrownBy(() -> guard.beforeStep(0))
                .isInstanceOf(RuntimeBudgetGuard.RuntimeBudgetExceededException.class)
                .extracting(error -> ((RuntimeBudgetGuard.RuntimeBudgetExceededException) error).reasonCode())
                .isEqualTo("runtime_deadline_exceeded");
    }

    @Test
    @DisplayName("rejects steps beyond the immutable maxSteps limit")
    void rejectsStepOverrun() {
        RuntimeBudgetGuard guard = guard(envelope(NOW.plusSeconds(60), null, null, 2), null);

        guard.beforeStep(0);
        guard.beforeStep(1);
        assertThatThrownBy(() -> guard.beforeStep(2))
                .isInstanceOf(RuntimeBudgetGuard.RuntimeBudgetExceededException.class)
                .extracting(error -> ((RuntimeBudgetGuard.RuntimeBudgetExceededException) error).reasonCode())
                .isEqualTo("runtime_max_steps_exceeded");
    }

    @Test
    @DisplayName("accounts cumulative provider usage and fails closed above token budget")
    void rejectsTokenOverrun() {
        RuntimeBudgetGuard guard = guard(envelope(NOW.plusSeconds(60), 10L, null, null), null);

        guard.record(response(4, 3));
        assertThat(guard.consumedTokens()).isEqualTo(7);
        assertThatThrownBy(() -> guard.record(response(2, 2)))
                .isInstanceOf(RuntimeBudgetGuard.RuntimeBudgetExceededException.class)
                .extracting(error -> ((RuntimeBudgetGuard.RuntimeBudgetExceededException) error).reasonCode())
                .isEqualTo("runtime_token_budget_exceeded");
    }

    @Test
    @DisplayName("uses provider-neutral cost estimation and fails closed above micro-budget")
    void rejectsCostOverrun() {
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.estimateCost("model", 5, 5, 0, 0)).thenReturn(0.000_011d);
        RuntimeBudgetGuard guard = guard(envelope(NOW.plusSeconds(60), null, 10L, null), provider);

        assertThatThrownBy(() -> guard.record(response(5, 5)))
                .isInstanceOf(RuntimeBudgetGuard.RuntimeBudgetExceededException.class)
                .extracting(error -> ((RuntimeBudgetGuard.RuntimeBudgetExceededException) error).reasonCode())
                .isEqualTo("runtime_cost_budget_exceeded");
        assertThat(guard.consumedCostMicros()).isEqualTo(11L);
    }

    private RuntimeBudgetGuard guard(ContextEnvelope envelope, LlmProvider provider) {
        return new RuntimeBudgetGuard(
                envelope,
                provider,
                "model",
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private LlmChatResponse response(int input, int output) {
        return LlmChatResponse.builder()
                .content(List.of())
                .inputTokens(input)
                .outputTokens(output)
                .build();
    }

    private ContextEnvelope envelope(
            Instant deadline,
            Long tokens,
            Long costMicros,
            Integer maxSteps) {
        return new ContextEnvelope(
                "context-envelope/v2",
                "TURN",
                1L,
                principal(),
                "agent",
                "RELEASE",
                "DEPLOYMENT",
                "release-hash",
                "web",
                null,
                null,
                null,
                null,
                Set.of(),
                List.of(),
                Set.of(),
                List.of(),
                Map.of("working", "turn:TURN"),
                Map.of("riskScale", "risk-scale/v1"),
                Map.of(),
                Map.of(),
                "zh-CN",
                "Asia/Shanghai",
                "trace",
                deadline,
                tokens,
                costMicros,
                maxSteps,
                null,
                NOW,
                "hash");
    }

    private ExecutionPrincipal principal() {
        return new ExecutionPrincipal(
                1L,
                2L,
                3L,
                "USR_AGENT",
                "agent",
                4L,
                "EMP_AGENT",
                Initiator.human(5L, 6L, "web"),
                DelegationGrant.employeeAutonomous(),
                "agent",
                "RELEASE",
                "DEPLOYMENT",
                "release-hash",
                "web",
                ExecutionPrincipal.Type.DIGITAL_EMPLOYEE,
                Set.of());
    }
}
