package com.auraboot.framework.conversation;

import com.auraboot.framework.agent.service.AgentObservationService;
import com.auraboot.framework.agent.triage.TriageBucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for {@link TurnCompletionObservationListener} — the G1
 * observation seam. The load-bearing assertions are the ANTI-filters:
 * unlike the memory listener, this seam must NOT skip LIGHT_CHAT and must
 * NOT skip non-Success outcomes, otherwise the CAP-02 eval distribution
 * bias this listener exists to close comes straight back.
 */
@DisplayName("TurnCompletionObservationListener — G1 all-terminal-turn observation seam")
class TurnCompletionObservationListenerTest {

    private AgentObservationService observationService;
    private TurnCompletionObservationListener listener;

    @BeforeEach
    void setUp() {
        observationService = mock(AgentObservationService.class);
        listener = new TurnCompletionObservationListener(observationService);
    }

    private TurnContext newCtx(TriageBucket bucket, Long userId, Long tenantId, String agentCode) {
        return new TurnContext(
                "01HW3KTEST",
                tenantId == null ? 0L : tenantId,
                userId == null ? 0L : userId,
                200L,
                null,                                // agentId
                agentCode,                           // agentCode (DC.3c)
                null,                                // channelSessionId
                999L,                                // conversationId
                null,
                bucket,
                java.util.Set.of(),
                null,
                null,                                // taskPid (DC.3c)
                Instant.now());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturePublishedDetail(String expectedType, String expectedAgentId) {
        ArgumentCaptor<Map<String, Object>> detailCaptor = ArgumentCaptor.forClass(Map.class);
        verify(observationService, times(1)).publish(
                eq(42L), eq(expectedType), eq(expectedAgentId), isNull(), eq("01HW3KTEST"),
                detailCaptor.capture());
        return detailCaptor.getValue();
    }

    // =========================================================================
    // Anti-filter assertions (the point of this seam)
    // =========================================================================

    @Test
    @DisplayName("LIGHT_CHAT Success IS recorded — no bucket filter (anti-bias, review G1/G3)")
    void lightChatSuccess_isRecorded() {
        TurnCompletedEvent ev = new TurnCompletedEvent(
                newCtx(TriageBucket.LIGHT_CHAT, 100L, 42L, null),
                new TurnOutcome.Success("Created the customer.", Map.of()));

        listener.onTurnCompleted(ev);

        Map<String, Object> detail = capturePublishedDetail(
                TurnCompletionObservationListener.EVENT_COMPLETED,
                TurnCompletionObservationListener.DEFAULT_AGENT_ID);
        assertThat(detail).containsEntry("triageBucket", "LIGHT_CHAT");
        assertThat(detail).containsEntry("responseChars", "Created the customer.".length());
        assertThat(detail).containsKey("latencyMs");
    }

    @Test
    @DisplayName("Failed outcome IS recorded as turn.failed with truncated error")
    void failedOutcome_isRecorded() {
        String longError = "x".repeat(500);
        TurnCompletedEvent ev = new TurnCompletedEvent(
                newCtx(TriageBucket.CONTEXTUAL_ANSWER, 100L, 42L, null),
                new TurnOutcome.Failed(longError, null));

        listener.onTurnCompleted(ev);

        Map<String, Object> detail = capturePublishedDetail(
                TurnCompletionObservationListener.EVENT_FAILED,
                TurnCompletionObservationListener.DEFAULT_AGENT_ID);
        assertThat((String) detail.get("error"))
                .hasSize(300 + 3)
                .endsWith("...");
    }

    @Test
    @DisplayName("Interrupted outcome IS recorded as turn.interrupted with reason")
    void interruptedOutcome_isRecorded() {
        TurnCompletedEvent ev = new TurnCompletedEvent(
                newCtx(TriageBucket.ACP_RUN, 100L, 42L, null),
                new TurnOutcome.Interrupted("partial", "user_cancelled"));

        listener.onTurnCompleted(ev);

        Map<String, Object> detail = capturePublishedDetail(
                TurnCompletionObservationListener.EVENT_INTERRUPTED,
                TurnCompletionObservationListener.DEFAULT_AGENT_ID);
        assertThat(detail).containsEntry("interruptReason", "user_cancelled");
    }

    @Test
    @DisplayName("System turn (userId=0) IS recorded — no user-scope filter")
    void systemTurn_isRecorded() {
        TurnCompletedEvent ev = new TurnCompletedEvent(
                newCtx(TriageBucket.ACP_RUN, 0L, 42L, null),
                new TurnOutcome.Success("done", Map.of()));

        listener.onTurnCompleted(ev);

        verify(observationService, times(1)).publish(
                eq(42L), anyString(), anyString(), isNull(), anyString(), any());
    }

    // =========================================================================
    // Route snapshot
    // =========================================================================

    @Test
    @DisplayName("Route present -> initialMode/decisionReason/policySignals land in detail")
    void routePresent_isRecorded() {
        TurnRoute route = new TurnRoute("NAMED_AGENT_TURN", "NAMED_AGENT_PROFILE",
                List.of("EXPLICIT_NAMED_AGENT"));
        TurnCompletedEvent ev = new TurnCompletedEvent(
                newCtx(TriageBucket.ACP_RUN, 100L, 42L, "sales_agent"),
                new TurnOutcome.Success("ok", Map.of()),
                route);

        listener.onTurnCompleted(ev);

        Map<String, Object> detail = capturePublishedDetail(
                TurnCompletionObservationListener.EVENT_COMPLETED, "sales_agent");
        assertThat(detail).containsEntry("initialMode", "NAMED_AGENT_TURN");
        assertThat(detail).containsEntry("decisionReason", "NAMED_AGENT_PROFILE");
        assertThat(detail).containsEntry("policySignals", List.of("EXPLICIT_NAMED_AGENT"));
        // Bucket recorded NEXT TO engine mode: ACP_RUN bucket + NAMED_AGENT engine
        // is exactly the G8 shadowing signature this seam makes visible.
        assertThat(detail).containsEntry("triageBucket", "ACP_RUN");
    }

    @Test
    @DisplayName("Route absent (resume path, two-arg event) -> no mode keys, still recorded")
    void routeAbsent_stillRecorded() {
        TurnCompletedEvent ev = new TurnCompletedEvent(
                newCtx(TriageBucket.CONTEXTUAL_ANSWER, 100L, 42L, null),
                new TurnOutcome.Success("ok", Map.of()));

        listener.onTurnCompleted(ev);

        Map<String, Object> detail = capturePublishedDetail(
                TurnCompletionObservationListener.EVENT_COMPLETED,
                TurnCompletionObservationListener.DEFAULT_AGENT_ID);
        assertThat(detail).doesNotContainKey("initialMode");
        assertThat(detail).containsEntry("triageBucket", "CONTEXTUAL_ANSWER");
    }

    // =========================================================================
    // Skips and defensiveness
    // =========================================================================

    @Test
    @DisplayName("Missing tenant scope (tenantId=0) -> skipped (only legitimate filter)")
    void missingTenant_isSkipped() {
        TurnCompletedEvent ev = new TurnCompletedEvent(
                newCtx(TriageBucket.LIGHT_CHAT, 100L, 0L, null),
                new TurnOutcome.Success("ok", Map.of()));

        listener.onTurnCompleted(ev);

        verifyNoInteractions(observationService);
    }

    @Test
    @DisplayName("Observation service throwing never propagates out of the listener")
    void serviceThrow_isSwallowed() {
        doThrow(new RuntimeException("db down")).when(observationService)
                .publish(anyLong(), anyString(), anyString(), any(), anyString(), any());
        TurnCompletedEvent ev = new TurnCompletedEvent(
                newCtx(TriageBucket.LIGHT_CHAT, 100L, 42L, null),
                new TurnOutcome.Success("ok", Map.of()));

        assertThatCode(() -> listener.onTurnCompleted(ev)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Null event / null outcome -> no publish, no exception")
    void nullSafety() {
        assertThatCode(() -> listener.onTurnCompleted(null)).doesNotThrowAnyException();
        assertThatCode(() -> listener.onTurnCompleted(
                new TurnCompletedEvent(newCtx(TriageBucket.LIGHT_CHAT, 100L, 42L, null), null)))
                .doesNotThrowAnyException();
        verifyNoInteractions(observationService);
    }
}
