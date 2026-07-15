package com.auraboot.framework.agent.eval;

import com.auraboot.framework.agent.eval.AgentOnlineEvalService.OnlineEvalSummary;
import com.auraboot.framework.agent.service.AgentObservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the L4 online-eval scheduled loop wiring: the enabled/tenant guards are
 * honored, a healthy sample emits nothing, and a degraded sample emits exactly one
 * {@code online_eval.degraded} observation. Mockito only — no DB / Spring / LLM.
 */
class ScheduledOnlineEvalJobTest {

    private final AgentOnlineEvalService onlineEvalService = mock(AgentOnlineEvalService.class);
    private final AgentObservationService observationService = mock(AgentObservationService.class);
    private final OnlineEvalCasePromoter casePromoter = mock(OnlineEvalCasePromoter.class);

    private ScheduledOnlineEvalJob job;

    @BeforeEach
    void setup() {
        job = new ScheduledOnlineEvalJob(onlineEvalService, observationService, casePromoter);
        ReflectionTestUtils.setField(job, "tenantId", 1L);
        ReflectionTestUtils.setField(job, "sinceHours", 24);
        ReflectionTestUtils.setField(job, "maxRuns", 200);
        ReflectionTestUtils.setField(job, "minHealthyRate", 0.80);
        ReflectionTestUtils.setField(job, "maxFailRate", 0.20);
        ReflectionTestUtils.setField(job, "maxCostFlaggedRate", 0.20);
        ReflectionTestUtils.setField(job, "minAvgScore", 0.50);
    }

    private static OnlineEvalSummary summary(int n, double healthy, double fail,
                                             double cost, double avg) {
        return new OnlineEvalSummary("heuristic", n, healthy, fail, cost, avg, List.of());
    }

    @Test
    void disabledTickIsNoOp() {
        ReflectionTestUtils.setField(job, "enabled", false);
        job.runScheduled();
        verifyNoInteractions(onlineEvalService, observationService);
    }

    @Test
    void enabledButTenantZeroIsNoOp() {
        ReflectionTestUtils.setField(job, "enabled", true);
        ReflectionTestUtils.setField(job, "tenantId", 0L);
        job.runScheduled();
        verifyNoInteractions(onlineEvalService, observationService);
    }

    @Test
    void healthySampleEmitsNoObservation() {
        when(onlineEvalService.sampleAndJudge(anyLong(), anyInt(), anyInt()))
                .thenReturn(summary(20, 0.95, 0.05, 0.05, 0.85));

        Map<String, Object> result = job.runOnce(1L);

        verify(observationService, never())
                .publish(anyLong(), anyString(), anyString(), any(), any(), any());
        assertTrue((Boolean) result.get("qualityOk"));
    }

    @Test
    void degradedSampleEmitsExactlyOneObservation() {
        when(onlineEvalService.sampleAndJudge(eq(1L), eq(24), eq(200)))
                .thenReturn(summary(20, 0.40, 0.50, 0.05, 0.30));

        Map<String, Object> result = job.runOnce(1L);

        verify(observationService, times(1)).publish(
                eq(1L), eq("online_eval.degraded"), eq("online-eval-scheduler"),
                anyString(), isNull(), any());
        assertFalse((Boolean) result.get("qualityOk"));
        assertEquals(20, result.get("sampledTurns"));
    }

    @Test
    void emptySampleEmitsNothing() {
        when(onlineEvalService.sampleAndJudge(anyLong(), anyInt(), anyInt()))
                .thenReturn(summary(0, 0, 0, 0, 0));

        Map<String, Object> result = job.runOnce(1L);

        verify(observationService, never())
                .publish(anyLong(), anyString(), anyString(), any(), any(), any());
        assertTrue((Boolean) result.get("qualityOk"));
    }

    @Test
    void promotesHardFailuresWhenEnabled() {
        ReflectionTestUtils.setField(job, "promoteFailures", true);
        ReflectionTestUtils.setField(job, "maxPromotions", 20);
        AgentTurnQualityJudge.TurnVerdict hardFail =
                new AgentTurnQualityJudge.TurnVerdict("run-x", 0.0, false, "fail");
        when(onlineEvalService.sampleAndJudge(anyLong(), anyInt(), anyInt()))
                .thenReturn(new OnlineEvalSummary("heuristic", 1, 0.0, 1.0, 0.0, 0.0, List.of(hardFail)));
        when(casePromoter.promoteHardFailures(eq(1L), any(), eq(20))).thenReturn(1);

        Map<String, Object> result = job.runOnce(1L);

        verify(casePromoter).promoteHardFailures(eq(1L), any(), eq(20));
        assertEquals(1, result.get("promotedCandidates"));
    }

    @Test
    void doesNotPromoteWhenDisabled() {
        // promoteFailures defaults to false
        when(onlineEvalService.sampleAndJudge(anyLong(), anyInt(), anyInt()))
                .thenReturn(summary(1, 1.0, 0.0, 0.0, 1.0));
        job.runOnce(1L);
        verify(casePromoter, never()).promoteHardFailures(anyLong(), any(), anyInt());
    }
}
